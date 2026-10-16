package com.novemberraining.psalyric

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock

data class ForegroundAppInfo(
    val packageName: String = "",
    val usageAccess: Boolean = false,
    val source: String = "none",
    val ageMs: Long = -1L
)

object ForegroundAppDetector {
    @Volatile private var cachedPackage = ""
    @Volatile private var cachedAtElapsed = 0L

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = try {
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } catch (_: Throwable) {
            AppOpsManager.MODE_DEFAULT
        }
        if (mode == AppOpsManager.MODE_ALLOWED) return true
        if (mode != AppOpsManager.MODE_DEFAULT) return false

        // Some automotive ROMs leave AppOps in MODE_DEFAULT even after exposing usage data.
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            !usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 60_000L,
                now
            ).isNullOrEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    fun info(context: Context): ForegroundAppInfo {
        if (!hasUsageAccess(context)) {
            return ForegroundAppInfo(usageAccess = false, source = "unauthorized")
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        // UsageEvents is the most precise source, but the old 8-second window was too short:
        // if no activity transition occurred in that window the detector returned blank forever.
        try {
            val events = usm.queryEvents(nowWall - 120_000L, nowWall)
            val event = UsageEvents.Event()
            var latestTime = -1L
            var latestPackage = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.timeStamp >= latestTime
                ) {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName ?: ""
                }
            }
            if (latestPackage.isNotBlank()) {
                remember(latestPackage, nowElapsed)
                return ForegroundAppInfo(
                    packageName = latestPackage,
                    usageAccess = true,
                    source = "events",
                    ageMs = (nowWall - latestTime).coerceAtLeast(0L)
                )
            }
        } catch (_: Throwable) {
        }

        // Fallback for ROMs that do not emit MOVE_TO_FOREGROUND reliably.
        try {
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                nowWall - 10 * 60_000L,
                nowWall
            )
            val latest = stats
                ?.filter { it.packageName?.isNotBlank() == true && it.lastTimeUsed > 0L }
                ?.maxByOrNull { it.lastTimeUsed }
            if (latest != null) {
                val pkg = latest.packageName.orEmpty()
                remember(pkg, nowElapsed)
                return ForegroundAppInfo(
                    packageName = pkg,
                    usageAccess = true,
                    source = "usage-stats",
                    ageMs = (nowWall - latest.lastTimeUsed).coerceAtLeast(0L)
                )
            }
        } catch (_: Throwable) {
        }

        val cached = cachedPackage
        if (cached.isNotBlank() && nowElapsed - cachedAtElapsed <= 10 * 60_000L) {
            return ForegroundAppInfo(
                packageName = cached,
                usageAccess = true,
                source = "cache",
                ageMs = (nowElapsed - cachedAtElapsed).coerceAtLeast(0L)
            )
        }

        return ForegroundAppInfo(usageAccess = true, source = "no-data")
    }

    fun foregroundPackage(context: Context): String = info(context).packageName

    fun homePackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun remember(packageName: String, nowElapsed: Long) {
        if (packageName.isBlank()) return
        cachedPackage = packageName
        cachedAtElapsed = nowElapsed
    }
}
