package com.novemberraining.psalyric

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock

data class CarLifeBridgeSnapshot(
    val foreground: Boolean = false,
    val connected: Boolean = false,
    val enabled: Boolean = true,
    val sentElapsedMs: Long = 0L,
    val receivedElapsedMs: Long = 0L
) {
    fun isFresh(nowElapsedMs: Long, maxAgeMs: Long): Boolean =
        receivedElapsedMs > 0L &&
            nowElapsedMs >= receivedElapsedMs &&
            nowElapsedMs - receivedElapsedMs <= maxAgeMs
}

object CarLifeBridgeStore {
    @Volatile
    var snapshot: CarLifeBridgeSnapshot = CarLifeBridgeSnapshot()
}

class CarLifeStateBridge(
    context: Context,
    private val onState: (CarLifeBridgeSnapshot) -> Unit
) {
    companion object {
        const val CARLIFE_PACKAGE = "com.baidu.carlifevehicle"
        const val ACTION_REQUEST =
            "com.novemberraining.psalyric.action.REQUEST_CARLIFE_STATE"
        const val ACTION_STATE =
            "com.novemberraining.psalyric.action.CARLIFE_STATE"
        const val EXTRA_FOREGROUND = "foreground"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_ENABLED = "bridge_enabled"
        const val EXTRA_SENT_ELAPSED = "sent_elapsed"
    }

    private val app = context.applicationContext
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STATE) return
            val snapshot = CarLifeBridgeSnapshot(
                foreground = intent.getBooleanExtra(EXTRA_FOREGROUND, false),
                connected = intent.getBooleanExtra(EXTRA_CONNECTED, false),
                // Older bridge builds did not send this extra; they were
                // effectively always enabled, so default true is compatible.
                enabled = intent.getBooleanExtra(EXTRA_ENABLED, true),
                sentElapsedMs = intent.getLongExtra(EXTRA_SENT_ELAPSED, 0L),
                receivedElapsedMs = SystemClock.elapsedRealtime()
            )
            CarLifeBridgeStore.snapshot = snapshot
            onState(snapshot)
        }
    }

    fun start() {
        if (started) return
        started = true
        app.registerReceiver(receiver, IntentFilter(ACTION_STATE))
        requestState()
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            app.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
    }

    fun requestState() {
        try {
            app.sendBroadcast(
                Intent(ACTION_REQUEST)
                    .setPackage(CARLIFE_PACKAGE)
            )
        } catch (_: Throwable) {
        }
    }

    fun snapshot(): CarLifeBridgeSnapshot = CarLifeBridgeStore.snapshot
}
