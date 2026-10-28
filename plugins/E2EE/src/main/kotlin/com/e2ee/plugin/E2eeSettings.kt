package com.e2ee.plugin

import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.views.CheckedSetting

class E2eeSettings(private val prefs: SettingsAPI) : SettingsPage() {
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
            ctx, CheckedSetting.ViewType.SWITCH, "Encrypt outgoing messages", "Text only. Use /e2eeimage for files.",
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

        val rememberSwitch = Utils.createCheckedSetting(
            ctx, CheckedSetting.ViewType.SWITCH, "Remember password",
            "Saved in plain text on this device and loaded at startup. Off = asked every session. Takes effect when you press Set key.",
        ).apply {
            isChecked = prefs.getBool(E2ee.REMEMBER, false)
            setOnCheckedListener { on ->
                prefs.setBool(E2ee.REMEMBER, on)
                if (!on) prefs.setString(E2ee.PASSWORD, "")
            }
        }

        val setBtn = Button(ctx).apply {
            text = "Set key"
            layoutParams = margins()
            setOnClickListener {
                val pw = input.editText.text.toString()
                if (pw.isEmpty() || pw.length > 128) return@setOnClickListener toast("Password must be 1-128 chars")
                toast("Deriving key...")
                Thread {
                    val k = Crypto.deriveKey(pw)
                    view.post {
                        if (k == null) {
                            toast("Key derivation failed")
                        } else {
                            E2ee.key = k
                            prefs.setString(E2ee.PASSWORD, if (rememberSwitch.isChecked) pw else "")
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
                prefs.setString(E2ee.PASSWORD, "")
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
            addView(rememberSwitch)
        }
    }
}
