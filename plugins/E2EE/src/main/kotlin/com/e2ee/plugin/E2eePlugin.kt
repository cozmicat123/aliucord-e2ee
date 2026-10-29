package com.e2ee.plugin

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.utils.ReflectUtils
import com.aliucord.utils.ViewUtils.findViewById
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.filename
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.url
import com.lytefast.flexinput.model.Attachment
import de.robv.android.xposed.XC_MethodHook
import com.discord.api.message.attachment.MessageAttachment
import com.discord.restapi.RestAPIParams
import com.discord.utilities.messagesend.`MessageQueue$doSend$2`
import com.discord.utilities.rest.SendUtils
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.widgets.chat.MessageContent
import com.discord.widgets.chat.MessageManager
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import java.util.regex.Pattern
import javax.crypto.SecretKey
import com.discord.api.message.Message as ApiMessage
import com.discord.models.message.Message as ModelMessage

object E2ee {
    const val REMEMBER = "remember"
    const val PASSWORD = "password"
    const val ENCRYPT = "encrypt"
    const val INLINE = "inline"

    @Volatile var key: SecretKey? = null
    @Volatile var enabled = false
    @Volatile var canSend = false
    @Volatile var canSendFiles = false
    @Volatile var onError: (Throwable) -> Unit = {}
}

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class E2eePlugin : Plugin() {
    private companion object {
        // Image messages carry a hidden "[sz:W,H]" prefix inside the encrypted text
        val SIZE_TAG: Pattern = Pattern.compile("^\\[sz:\\d+,\\d+]")
    }

    init {
        settingsTab = Plugin.SettingsTab(E2eeSettings::class.java).withArgs(settings)
    }

    private fun decrypt(m: ModelMessage) {
        val key = E2ee.key ?: return
        val content = m.content ?: return
        if (!Crypto.looksEncrypted(content)) return
        val plain = Crypto.decrypt(content, key) ?: return
        try {
            ReflectUtils.setField(m, "content", SIZE_TAG.matcher(plain).replaceFirst("").trimEnd())
        } catch (e: Throwable) {
            logger.error("Failed to set decrypted content", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun encryptOutgoingFiles(param: XC_MethodHook.MethodHookParam, context: Context) {
        val key = E2ee.key
        if (!E2ee.enabled || key == null) return
        val files = (param.args[3] as? List<Attachment<*>>).orEmpty()
        if (files.isEmpty()) return
        val message = param.args[2] as MessageContent
        try {
            val out = Media.encryptForSend(context, key, files, message.textContent.trim())
            param.args[3] = out.attachments
            param.args[2] = MessageContent(out.content, message.mentionedUsers)
        } catch (e: Throwable) {
            logger.error("File encryption failed", e)
            Utils.showToast("Encryption failed, nothing was sent.")
            param.result = null
        }
    }

    override fun start(context: Context) {
        E2ee.onError = { logger.error("E2EE error", it) }

        commands.registerCommand("e2ee", "Toggle encryption") {
            when {
                E2ee.key == null -> CommandsAPI.CommandResult("Set the password in plugin settings first.", null, false)
                !E2ee.canSend -> CommandsAPI.CommandResult("Send hook unavailable, cannot encrypt.", null, false)
                else -> {
                    E2ee.enabled = !E2ee.enabled
                    settings.setBool(E2ee.ENCRYPT, E2ee.enabled)
                    CommandsAPI.CommandResult("Encryption ${if (E2ee.enabled) "ON" else "OFF"}${if (E2ee.enabled && !E2ee.canSendFiles) " (files are NOT encrypted)" else ""}", null, false)
                }
            }
        }

        // Incoming: decrypt when the store builds or merges its message model
        patcher.after<ModelMessage>(ApiMessage::class.java) { decrypt(this) }
        patcher.after<ModelMessage>("merge", ApiMessage::class.java) { (param, _: ApiMessage) ->
            (param.result as? ModelMessage)?.let { decrypt(it) }
        }

        // Already-cached messages: decrypt when the chat list (re)binds a message
        patcher.before<WidgetChatListAdapterItemMessage>("onConfigure", Int::class.java, ChatListEntry::class.java) { param ->
            (param.args[1] as? MessageEntry)?.message?.let { decrypt(it) }
        }

        // Long press menu: view an encrypted image or video attachment
        patcher.after<WidgetChatListActions>("configureUI", WidgetChatListActions.Model::class.java) { (_, model: WidgetChatListActions.Model) ->
            val target = model.message.attachments.orEmpty().firstOrNull { Media.encryptedKind(it.filename) != null }
                ?: return@after
            val kind = Media.encryptedKind(target.filename) ?: return@after
            val layout = (requireView() as ViewGroup).findViewById<LinearLayout>("dialog_chat_actions_container")
            if (layout.findViewById<View>(Media.menuViewId) != null) return@after
            val anchor = layout.findViewById<View?>("dialog_chat_actions_edit") ?: return@after

            val sheet = this
            val ctx = requireContext()
            val label = if (kind == Media.Kind.VIDEO) "View decrypted video" else "View decrypted image"
            val entry = Media.menuEntry(ctx, label) {
                sheet.dismiss()
                Media.showDecrypted(ctx, target.url, kind) { logger.error("Media decrypt failed", it) }
            }
            layout.addView(entry, layout.indexOfChild(anchor))
        }

        // Inline view: show encrypted images where the generic file card would be
        patcher.after<WidgetChatListAdapterItemAttachment>(
            "configureFileData",
            MessageAttachment::class.java,
            MessageRenderContext::class.java,
        ) { param ->
            try {
                val root = WidgetChatListAdapterItemAttachment.`access$getBinding$p`(this).root as ConstraintLayout
                Inline.bind(root, param.args[0] as MessageAttachment, settings.getBool(E2ee.INLINE, true))
            } catch (e: Throwable) {
                logger.error("Inline media failed", e)
            }
        }

        // Outgoing files: encrypt files and caption before the message is queued. Fails closed.
        E2ee.canSendFiles = runCatching {
            patcher.before<ChatInputViewModel>(
                "sendMessage",
                Context::class.java,
                MessageManager::class.java,
                MessageContent::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType!!,
                Function1::class.java,
            ) { param -> encryptOutgoingFiles(param, context) }
        }.onFailure { logger.error("sendMessage hook failed, files will not be encrypted", it) }.isSuccess

        // Outgoing: encrypt right before the request is sent. Fails closed (empty content -> send rejected).
        E2ee.canSend = runCatching { RestAPIParams.Message::class.java.getDeclaredField("content") }.isSuccess
        if (E2ee.canSend) {
            patcher.before<`MessageQueue$doSend$2`<*, *>>("call", SendUtils.SendPayload.ReadyToSend::class.java) { (_, payload: SendUtils.SendPayload.ReadyToSend) ->
                val key = E2ee.key
                if (!E2ee.enabled || key == null) return@before
                val msg = payload.message
                val plain = msg.content?.trim()
                if (plain.isNullOrBlank() || Crypto.looksEncrypted(plain)) return@before
                val out = if (plain.length <= Crypto.MAX_PLAINTEXT_LEN) Crypto.encrypt(plain, key) else null
                ReflectUtils.setField(msg, "content", out ?: "")
            }
        } else {
            logger.error("RestAPIParams.Message.content not found, outgoing encryption disabled", null)
        }

        // Restore the remembered key off the main thread (PBKDF2 is slow)
        if (settings.getBool(E2ee.REMEMBER, false)) {
            val saved = settings.getString(E2ee.PASSWORD, "")
            if (saved.isNotEmpty()) {
                Thread {
                    E2ee.key = Crypto.deriveKey(saved)
                    E2ee.enabled = E2ee.key != null && E2ee.canSend && settings.getBool(E2ee.ENCRYPT, false)
                }.start()
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        E2ee.key = null
        E2ee.enabled = false
        Media.onKeyChanged()
    }
}
