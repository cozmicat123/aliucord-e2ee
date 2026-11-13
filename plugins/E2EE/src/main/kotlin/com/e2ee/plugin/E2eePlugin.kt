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
import com.discord.api.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.chat.MessageContent
import com.discord.widgets.chat.MessageManager
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import com.discord.api.message.Message as ApiMessage
import com.discord.models.message.Message as ModelMessage

object E2ee {
    const val ENCRYPT = "encrypt"
    const val INLINE = "inline"
    const val PRESET = "preset"

    @Volatile var key: SecretKey? = null
    @Volatile var enabled = false
    @Volatile var preset: Crypto.Preset = Crypto.Preset.AES256
    @Volatile var canSend = false
    @Volatile var canSendFiles = false
    @Volatile var onError: (Throwable) -> Unit = {}
    @Volatile var log: (String) -> Unit = {}

    // Forced-channel keys are derived once per channel; never start two 64 MiB derivations for one channel
    private val preparing = ConcurrentHashMap.newKeySet<String>()

    fun isForced(channelId: Long) = ForcedKeys.isForced(channelId.toString())

    // Same rules as Aegires MessageListScreen.key(), preset() and encOn()
    fun keyFor(channelId: Long): SecretKey? {
        val id = channelId.toString()
        if (!ForcedKeys.isForced(id)) return key
        ForcedKeys.keyFor(id)?.let { return it }
        if (preparing.add(id)) ForcedKeys.prepare(id) { preparing.remove(id) }
        return null
    }

    fun presetFor(channelId: Long): Crypto.Preset = if (isForced(channelId)) Crypto.Preset.AES128 else preset

    fun encryptOn(channelId: Long): Boolean = isForced(channelId) || enabled

    fun selfId(): String? = StoreStream.getUsers().me?.id?.toString()

    // Same checks and messages as Aegires MessageCryptoHelper.prepareOutgoingText. Null = do not send.
    fun encryptText(channelId: Long, plain: String): String? {
        val key = keyFor(channelId)
        if (key == null) {
            Utils.showToast(if (isForced(channelId)) "Encryption key not ready yet" else "Encryption key missing")
            return null
        }
        val sender = selfId()
        if (sender == null) {
            Utils.showToast("Not ready yet, try again in a moment")
            return null
        }
        val p = presetFor(channelId)
        val max = Crypto.maxPlaintextBytes(p)
        if (Crypto.utf8Length(plain) > max) {
            Utils.showToast("Too long for ${p.label} (max $max bytes)")
            return null
        }
        val out = Crypto.encrypt(plain, key, p, channelId.toString(), sender)
        if (out == null) Utils.showToast("Encryption failed")
        return out
    }
}

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class E2eePlugin : Plugin() {
    init {
        settingsTab = Plugin.SettingsTab(E2eeSettings::class.java).withArgs(settings)
    }

    private fun setContent(target: Any, text: String) {
        try {
            ReflectUtils.setField(target, "content", text)
        } catch (e: Throwable) {
            logger.error("Failed to set decrypted content", e)
        }
    }

    private fun decrypt(m: ModelMessage) {
        val channelId = m.channelId
        val key = E2ee.keyFor(channelId) ?: return
        val content = m.content
        if (content != null && Crypto.looksEncrypted(content)) {
            val plain = Crypto.decrypt(content, key, channelId.toString(), m.author?.id?.toString())
            if (plain != null) setContent(m, Media.stripSizeTag(plain, m.id))
        }
        m.referencedMessage?.let { decryptReply(it, key, channelId) }
    }

    // Reply preview, same as Aegires MessageCryptoHelper: the replied-to message carries its own channel and author
    private fun decryptReply(ref: ApiMessage, key: SecretKey, channelId: Long) {
        val content = runCatching { ReflectUtils.getField(ref, "content") as? String }.getOrNull() ?: return
        if (!Crypto.looksEncrypted(content)) return
        // The API Message getters are obfuscated, but its fields keep their names
        val refChannel = runCatching { ReflectUtils.getField(ref, "channelId") as? Long }.getOrNull() ?: 0L
        val refAuthor = runCatching { ReflectUtils.getField(ref, "author") as? User }.getOrNull()
        val replyChannel = if (refChannel != 0L) refChannel else channelId
        val plain = Crypto.decrypt(content, key, replyChannel.toString(), refAuthor?.id?.toString()) ?: return
        setContent(ref, Media.stripSizeTag(plain))
    }

    private fun encryptOutgoing(channelId: Long, msg: RestAPIParams.Message) {
        if (!E2ee.encryptOn(channelId)) return
        val plain = msg.content?.trim()
        if (plain.isNullOrBlank() || Crypto.looksEncrypted(plain)) return
        // Fails closed: an empty content is rejected by Discord, so nothing readable is ever sent
        ReflectUtils.setField(msg, "content", E2ee.encryptText(channelId, plain) ?: "")
    }

    @Suppress("UNCHECKED_CAST")
    private fun encryptOutgoingFiles(param: XC_MethodHook.MethodHookParam, context: Context) {
        val files = (param.args[3] as? List<Attachment<*>>).orEmpty()
        if (files.isEmpty()) return
        // The chat input has no channel id of its own; the send goes to the selected channel (same source as Aliucord's CommandsAPI)
        val channelId = StoreStream.getChannelsSelected().id
        if (!E2ee.encryptOn(channelId)) return
        val message = param.args[2] as MessageContent
        try {
            val out = Media.encryptForSend(context, channelId, files, message.textContent.trim())
            if (out == null) {
                param.result = null // the user was already told why
                return
            }
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
        E2ee.log = { logger.info(it) }
        Media.init(context)
        E2ee.preset = Crypto.Preset.fromIndex(settings.getInt(E2ee.PRESET, Crypto.Preset.AES256.index)) ?: Crypto.Preset.AES256

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
                Media.showDecrypted(ctx, target.url, kind, model.message) { logger.error("Media decrypt failed", it) }
            }
            layout.addView(entry, layout.indexOfChild(anchor))
        }

        // Inline view: show encrypted images where the generic file card would be. Hooked on onConfigure like the
        // AudioPlayer plugin, because the attachment entry also carries the message (channel and author ids).
        patcher.after<WidgetChatListAdapterItemAttachment>("onConfigure", Int::class.java, ChatListEntry::class.java) { param ->
            try {
                val entry = param.args[1] as? AttachmentEntry ?: return@after
                val root = WidgetChatListAdapterItemAttachment.`access$getBinding$p`(this).root as ConstraintLayout
                Inline.bind(root, entry.attachment, entry.message, settings.getBool(E2ee.INLINE, true))
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

        // Outgoing text and edits: same RestAPI hooks as mantikafasi/EncryptDMs (channel id is args[0]). Fails closed.
        E2ee.canSend = runCatching {
            RestAPIParams.Message::class.java.getDeclaredField("content")
            patcher.before<RestAPI>(
                "sendMessage",
                Long::class.javaPrimitiveType!!,
                RestAPIParams.Message::class.java,
            ) { param -> encryptOutgoing(param.args[0] as Long, param.args[1] as RestAPIParams.Message) }
            patcher.before<RestAPI>(
                "editMessage",
                Long::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
                RestAPIParams.Message::class.java,
            ) { param -> encryptOutgoing(param.args[0] as Long, param.args[2] as RestAPIParams.Message) }
        }.onFailure { logger.error("RestAPI send hooks failed, outgoing encryption disabled", it) }.isSuccess
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        E2ee.key = null
        E2ee.enabled = false
        Media.onKeyChanged()
    }
}
