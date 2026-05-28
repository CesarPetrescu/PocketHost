package dev.pockethost.supervisor

import android.content.Context
import android.content.Intent
import android.os.Build

object ServerCommands {
    fun startAll(context: Context) = send(context, ServerForegroundService.ACTION_START_ALL)
    fun stopAll(context: Context) = send(context, ServerForegroundService.ACTION_STOP_ALL)
    fun start(context: Context, serviceId: String) =
        send(context, ServerForegroundService.ACTION_START_ONE, serviceId)
    fun stop(context: Context, serviceId: String) =
        send(context, ServerForegroundService.ACTION_STOP_ONE, serviceId)
    fun restart(context: Context, serviceId: String) =
        send(context, ServerForegroundService.ACTION_RESTART_ONE, serviceId)

    private fun send(context: Context, action: String, serviceId: String? = null) {
        val intent = Intent(context, ServerForegroundService::class.java).apply {
            this.action = action
            if (serviceId != null) putExtra(ServerForegroundService.EXTRA_SERVICE_ID, serviceId)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
