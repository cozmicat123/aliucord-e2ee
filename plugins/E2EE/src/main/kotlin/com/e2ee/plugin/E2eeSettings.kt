package com.e2ee.plugin

import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.lytefast.flexinput.R

class E2eeSettings(private val prefs: SettingsAPI) : SettingsPage() {
    // Same text as Aegires GuildSessionMenu.presetBlurb
    private fun presetBlurb(p: Crypto.Preset): String {
        val tier = if (p == Crypto.Preset.AES128) "Compact" else if (p == Crypto.Preset.AES192) "Balanced" else "Maximum"
        return tier + " \u00B7 " + p.tagBits() + "-bit tag \u00B7 " + p.padBlock + "-byte padding\nup to " +
            Crypto.maxPlaintextBytes(p) + " bytes per message"
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("E2EE")
        val ctx = view.context
        val pad = DimenUtils.defaultPadding

        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
        fun margins() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(pad, 0, pad, pad) }

        val input = TextInput(ctx, "Password").apply {
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = margins()
        }

        val encryptSwitch = Utils.createCheckedSetting(
            ctx, CheckedSetting.ViewType.SWITCH, "Encrypt outgoing messages", "Attached images and videos are encrypted too.",
        ).apply {
            isChecked = E2ee.enabled
            setOnCheckedListener { on ->
                if (on && (E2ee.key == null || !E2ee.canSend)) {
                    toast("Set a password first")
                    isChecked = false
                } else {
                    E2ee.enabled = on
                    prefs.setBool(E2ee.ENCRYPT, on)
                }
            }
        }

        val inlineSwitch = Utils.createCheckedSetting(
            ctx, CheckedSetting.ViewType.SWITCH, "Show encrypted images inline",
            "Decrypts images in the chat. Videos play when tapped. Turn off if chat feels slow.",
        ).apply {
            isChecked = prefs.getBool(E2ee.INLINE, true)
            setOnCheckedListener { on -> prefs.setBool(E2ee.INLINE, on) }
        }

        // Preset choice: radio list as in the MoreGifProviders settings page, texts from Aegires' preset dialog
        val presetTitle = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply { text = "Encryption preset" }
        val presetInfo = TextView(ctx).apply {
            text = "Applies to the messages and files you send. You can read everyone else's messages regardless of profile. " +
                "Preset channels always use AES-128-GCM."
            setTextColor(Color.parseColor("#B5BAC1"))
            textSize = 13f
            setPadding(pad, 0, pad, pad)
        }
        val presets = Crypto.Preset.values().toList()
        val radios = presets.map { Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, it.label, presetBlurb(it)) }
        val radioManager = RadioManager(radios)
        val selected = radios[presets.indexOf(E2ee.preset).coerceAtLeast(0)]
        selected.isChecked = true
        radioManager.a(selected)
        radios.forEachIndexed { i, radio ->
            radio.e {
                val p = presets[i]
                E2ee.preset = p
                prefs.setInt(E2ee.PRESET, p.index)
                radioManager.a(radio)
                toast("Using " + p.label + " (max " + Crypto.maxPlaintextBytes(p) + " bytes per message)")
            }
        }

        val setBtn = Button(ctx).apply {
            text = "Set key"
            layoutParams = margins()
            setOnClickListener {
                val pw = input.editText.text.toString()
                if (pw.length < 8) return@setOnClickListener toast("Use at least 8 characters")
                if (pw.length > 256) return@setOnClickListener toast("Password too long (max 256)")
                toast("Deriving key, this takes a few seconds\u2026")
                Thread {
                    val k = Crypto.deriveKey(pw)
                    view.post {
                        if (k == null) {
                            toast("Key derivation failed")
                        } else {
                            E2ee.key = k
                            Media.onKeyChanged()
                            input.editText.setText("")
                            toast("Key set")
                        }
                    }
                }.start()
            }
        }

        val clearBtn = Button(ctx).apply {
            text = "Clear key"
            layoutParams = margins()
            setOnClickListener {
                E2ee.key = null
                E2ee.enabled = false
                Media.onKeyChanged()
                prefs.setBool(E2ee.ENCRYPT, false)
                encryptSwitch.isChecked = false
                toast("Key cleared")
            }
        }

        linearLayout.run {
            addView(input)
            addView(setBtn)
            addView(clearBtn)
            addView(encryptSwitch)
            addView(inlineSwitch)
            addView(presetTitle)
            addView(presetInfo)
            radios.forEach { addView(it) }
        }
    }
}
