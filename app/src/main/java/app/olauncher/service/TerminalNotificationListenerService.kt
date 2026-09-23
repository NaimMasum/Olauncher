package app.olauncher.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.olauncher.data.Prefs

class TerminalNotificationListenerService : NotificationListenerService() {

    companion object {
        const val ACTION_TERMINAL_NOTIFICATION = "app.olauncher.TERMINAL_NOTIFICATION"
        const val EXTRA_APP_LABEL = "extra_app_label"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_POST_TIME = "extra_post_time"

        var liveNotificationCallback: ((appLabel: String, packageName: String, title: String, text: String, time: Long) -> Unit)? = null
    }

    private var lastKey: String = ""
    private var lastPostTime: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = Prefs(applicationContext)
        if (!prefs.terminalNotificationsEnabled) return

        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return

        // Filter ongoing system/foreground service notifications (e.g. music player progress, step counter)
        if (sbn.isOngoing) return

        // Filter by selected apps if user configured an app allowlist
        val allowedApps = prefs.terminalNotificationApps
        if (allowedApps.isNotEmpty() && !allowedApps.contains(packageName)) {
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim() ?: ""

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return

        // Deduplicate duplicate notifications within 2 seconds
        val key = "$packageName|$title|$text"
        val now = System.currentTimeMillis()
        if (key == lastKey && (now - lastPostTime) < 2000L) {
            return
        }
        lastKey = key
        lastPostTime = now

        val pm = packageManager
        val appLabel = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val postTime = if (sbn.postTime > 0) sbn.postTime else now

        // 1. Direct in-memory callback for zero-latency delivery if launcher is active
        liveNotificationCallback?.invoke(appLabel, packageName, title, text, postTime)

        // 2. Broadcast for receiver delivery
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

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
