package com.familyshield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** After the phone restarts, Family Mode switches itself on again (if it was on before). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!FamilyVpnService.prefs(context).getBoolean("enabled", false)) return
        if (VpnService.prepare(context) != null) return          // permission was taken away: the person has to open the app
        try {
            context.startService(Intent(context, FamilyVpnService::class.java).setAction(FamilyVpnService.ACTION_START))
        } catch (e: Exception) { }
    }
}
