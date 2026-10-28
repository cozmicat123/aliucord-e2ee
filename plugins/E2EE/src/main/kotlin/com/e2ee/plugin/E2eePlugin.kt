package com.e2ee.plugin

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.utils.ReflectUtils
import com.discord.restapi.RestAPIParams
import com.discord.utilities.messagesend.`MessageQueue$doSend$2`
import com.discord.utilities.rest.SendUtils
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

    @Volatile var key: SecretKey? = null
    @Volatile var enabled = false
    @Volatile var canSend = false
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
            ReflectUtils.setField(m, "content", SIZE_TAG.matcher(plain).replaceFirst(""))
        } catch (e: Throwable) {
            logger.error("Failed to set decrypted content", e)
        }
    }

    override fun start(context: Context) {
        commands.registerCommand("e2ee", "Toggle encryption") {
            when {
                E2ee.key == null -> CommandsAPI.CommandResult("Set the password in plugin settings first.", null, false)
                !E2ee.canSend -> CommandsAPI.CommandResult("Send hook unavailable, cannot encrypt.", null, false)
                else -> {
                    E2ee.enabled = !E2ee.enabled
                    settings.setBool(E2ee.ENCRYPT, E2ee.enabled)
                    CommandsAPI.CommandResult("Encryption ${if (E2ee.enabled) "ON" else "OFF"}", null, false)
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

        // Outgoing: encrypt right before the request is sent. Fails closed (empty content -> send rejected).
        E2ee.canSend = runCatching { RestAPIParams.Message::class.java.getDeclaredField("content") }.isSuccess
        if (E2ee.canSend) {
            patcher.before<`MessageQueue$doSend$2`<*, *>>("call", SendUtils.SendPayload.ReadyToSend::class.java) { (_, payload: SendUtils.SendPayload.ReadyToSend) ->
                val key = E2ee.key
                if (!E2ee.enabled || key == null) return@before
                val msg = payload.message
                val plain = msg.content
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
    }
}
