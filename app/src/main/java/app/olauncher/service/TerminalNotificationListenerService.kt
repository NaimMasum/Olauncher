package app.olauncher.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.olauncher.data.Prefs

import android.util.Log

class TerminalNotificationListenerService : NotificationListenerService() {

    data class NotificationRecord(
        val appLabel: String,
        val packageName: String,
        val title: String,
        val text: String,
        val time: Long
    )

    companion object {
        const val TAG = "TerminalNotif"
        const val ACTION_TERMINAL_NOTIFICATION = "app.olauncher.TERMINAL_NOTIFICATION"
        const val EXTRA_APP_LABEL = "extra_app_label"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_POST_TIME = "extra_post_time"

        var liveNotificationCallback: ((appLabel: String, packageName: String, title: String, text: String, time: Long) -> Unit)? = null
        val recentHistory = mutableListOf<NotificationRecord>()

        fun ensureServiceBound(context: Context) {
            val component = ComponentName(context, TerminalNotificationListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(component)
                    Log.d(TAG, "requestRebind triggered for $component")
                } catch (e: Exception) {
                    Log.e(TAG, "requestRebind failed: ${e.message}")
                }
            }
            try {
                val pm = context.packageManager
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                Log.d(TAG, "Component enabled state cycled to force system bind")
            } catch (e: Exception) {
                Log.e(TAG, "Component cycle failed: ${e.message}")
            }
        }
    }

    // Cache of recent notification signatures -> timestamp to prevent duplicates across updates
    private val recentNotifications = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = Prefs(applicationContext)
        if (!prefs.terminalNotificationsEnabled) {
            Log.d(TAG, "Ignoring notification: terminalNotificationsEnabled is false")
            return
        }

        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return

        val notification = sbn.notification ?: return

        // 1. Filter ongoing notifications (persistent background services, music, pedometer, etc.)
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            Log.d(TAG, "Filtered ongoing notification from $packageName (flags=${notification.flags})")
            return
        }

        // 2. Filter Android notification group summaries (e.g. "2 new messages" or account headers)
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "Filtered group summary from $packageName")
            return
        }

        // 3. Filter by selected apps if user configured an app allowlist
        val allowedApps = prefs.terminalNotificationApps
        if (allowedApps.isNotEmpty() && !allowedApps.contains(packageName)) {
            Log.d(TAG, "Filtered unallowed app: $packageName (allowed: $allowedApps)")
            return
        }

        val extras = notification.extras ?: return

        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim() ?: ""

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) {
            Log.d(TAG, "Filtered blank notification from $packageName")
            return
        }

        val now = System.currentTimeMillis()

        // 4. Robust content deduplication: clean up entries older than 3 minutes
        recentNotifications.entries.removeIf { now - it.value > 180_000L }

        // Deduplicate notifications with identical package, title, and body within 60 seconds
        val contentSignature = "$packageName|$title|$text"
        val lastSeen = recentNotifications[contentSignature]
        if (lastSeen != null && (now - lastSeen) < 60_000L) {
            Log.d(TAG, "Filtered duplicate notification within 60s: $contentSignature")
            return
        }
        recentNotifications[contentSignature] = now

        val pm = packageManager
        val appLabel = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val postTime = if (sbn.postTime > 0) sbn.postTime else now

        Log.i(TAG, "Intercepted notification: [$appLabel ($packageName)] '$title': '$text'")

        val record = NotificationRecord(appLabel, packageName, title, text, postTime)
        synchronized(recentHistory) {
            recentHistory.add(record)
            if (recentHistory.size > 50) {
                recentHistory.removeAt(0)
            }
        }

        // 5. Send to live callback if launcher is active, OR send broadcast if in background (never both)
        val callback = liveNotificationCallback
        if (callback != null) {
            Log.d(TAG, "Dispatching to live callback in HomeFragment")
            callback.invoke(appLabel, packageName, title, text, postTime)
        } else {
            Log.d(TAG, "Launcher not foregrounded; dispatching ACTION_TERMINAL_NOTIFICATION broadcast")
            val intent = Intent(ACTION_TERMINAL_NOTIFICATION).apply {
                setPackage(applicationContext.packageName)
                putExtra(EXTRA_APP_LABEL, appLabel)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_POST_TIME, postTime)
            }
            sendBroadcast(intent)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected: TerminalNotificationListenerService is successfully BOUND and LISTENING")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected: TerminalNotificationListenerService was disconnected. Attempting rebind...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, TerminalNotificationListenerService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "requestRebind on disconnected failed: ${e.message}")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
