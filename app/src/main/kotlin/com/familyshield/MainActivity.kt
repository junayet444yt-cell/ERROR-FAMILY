package com.familyshield

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest

class MainActivity : Activity() {

    private val reqVpn = 100
    private lateinit var status: TextView
    private lateinit var main: Button
    private lateinit var always: Button
    private val prefs by lazy { FamilyVpnService.prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#081622"))
            setPadding(56, 120, 56, 56)
        }
        root.addView(text("🛡️", 64f, Color.WHITE))
        root.addView(text("Family Mode", 30f, Color.WHITE, true))
        root.addView(text("ফ্যামিলি মোড", 18f, Color.parseColor("#9fb8c8")))
        status = text("", 18f, Color.parseColor("#9fb8c8")).apply { setPadding(0, 48, 0, 48) }
        root.addView(status)

        main = Button(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener { if (isOn()) askStop() else askStart() }
        }
        root.addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 190))

        always = Button(this).apply {
            text = "Always-on VPN settings (recommended)"
            isAllCaps = false
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } catch (e: Exception) { }
            }
        }
        root.addView(always, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 })

        root.addView(text(
            "Press Start once. Adult websites are then blocked on this whole phone (Chrome, other browsers and apps) and Google / Bing " +
            "SafeSearch is forced on. It starts again by itself after the phone restarts. Stopping needs your PIN.\n\n" +
            "একবার Start চাপুন। এরপর এই ফোনের সব ব্রাউজারে ও অ্যাপে ১৮+ ওয়েবসাইট বন্ধ থাকবে। ফোন চালু হলে নিজে থেকেই আবার চালু হবে। বন্ধ করতে আপনার PIN লাগবে।\n\n" +
            "Not perfect: it blocks websites by name. A child can still switch the VPN off in Android Settings, and adult posts on normal sites " +
            "(for example inside social media apps) cannot be seen by this filter. For stronger protection also use Google Family Link.",
            13f, Color.parseColor("#7e97a8")).apply { setPadding(0, 48, 0, 0); gravity = Gravity.START })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.parseColor("#081622")) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun isOn() = prefs.getBoolean("enabled", false)

    private fun refresh() {
        val on = isOn()
        val live = on && FamilyVpnService.running
        status.text = when {
            live -> "● Family Mode is ON\nফ্যামিলি মোড চালু আছে"
            on -> "Family Mode is switching on…\nOpen the app again if it stays like this."
            else -> "Family Mode is OFF\nফ্যামিলি মোড বন্ধ"
        }
        status.setTextColor(if (live) Color.parseColor("#3cd89a") else Color.parseColor("#9fb8c8"))
        main.text = if (on) "Stop (PIN needed)" else "Start"
        main.background = GradientDrawable().apply {
            cornerRadius = 40f
            setColor(Color.parseColor(if (on) "#b23a48" else "#1fa97a"))
        }
    }

    // ---------- PIN ----------
    private fun hash(pin: String) = MessageDigest.getInstance("SHA-256").digest(("fs1:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun askPin(title: String, onOk: (String) -> Unit) {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askStart() {
        if (prefs.getString("pin", "").isNullOrEmpty()) {
            askPin("Create a PIN (4 to 8 digits). You need it to stop Family Mode.") { p1 ->
                if (p1.length < 4 || p1.length > 8) {
                    Toast.makeText(this, "The PIN must have 4 to 8 digits", Toast.LENGTH_LONG).show()
                } else {
                    askPin("Type the PIN again") { p2 ->
                        if (p1 == p2) {
                            prefs.edit().putString("pin", hash(p1)).apply()
                            startFamily()
                        } else Toast.makeText(this, "The two PINs are not the same", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else startFamily()
    }

    private fun askStop() {
        askPin("Enter the PIN to stop Family Mode") { p ->
            if (hash(p) == prefs.getString("pin", "")) {
                prefs.edit().putBoolean("enabled", false).apply()
                startService(Intent(this, FamilyVpnService::class.java).setAction(FamilyVpnService.ACTION_STOP))
                refresh()
            } else Toast.makeText(this, "Wrong PIN", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- start ----------
    private fun startFamily() {
        val i = VpnService.prepare(this)        // Android asks once: "Allow this app to set up a VPN connection?"
        if (i != null) startActivityForResult(i, reqVpn) else vpnAllowed()
    }

    private fun vpnAllowed() {
        prefs.edit().putBoolean("enabled", true).apply()
        startService(Intent(this, FamilyVpnService::class.java).setAction(FamilyVpnService.ACTION_START))
        status.postDelayed({ refresh() }, 700)
        refresh()
    }

    @Deprecated("old API, still works")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqVpn) {
            if (resultCode == RESULT_OK) vpnAllowed()
            else Toast.makeText(this, "Family Mode needs the VPN permission to work.", Toast.LENGTH_LONG).show()
        }
    }
}
