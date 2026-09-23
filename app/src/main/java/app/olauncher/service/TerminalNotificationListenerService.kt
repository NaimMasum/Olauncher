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

    // Cache of recent notification signatures -> timestamp to prevent duplicates across updates
    private val recentNotifications = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = Prefs(applicationContext)
        if (!prefs.terminalNotificationsEnabled) return

        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return

        val notification = sbn.notification ?: return

        // 1. Filter ongoing notifications (persistent background services, music, pedometer, etc.)
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return
        }

        // 2. Filter Android notification group summaries (e.g. "2 new messages" or account headers)
        // Group summaries duplicate the actual message notifications
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        // 3. Filter by selected apps if user configured an app allowlist
        val allowedApps = prefs.terminalNotificationApps
        if (allowedApps.isNotEmpty() && !allowedApps.contains(packageName)) {
            return
        }

        val extras = notification.extras ?: return

        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim() ?: ""

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val now = System.currentTimeMillis()

        // 4. Robust content deduplication: clean up entries older than 3 minutes
        recentNotifications.entries.removeIf { now - it.value > 180_000L }

        // Deduplicate notifications with identical package, title, and body within 60 seconds
        val contentSignature = "$packageName|$title|$text"
        val lastSeen = recentNotifications[contentSignature]
        if (lastSeen != null && (now - lastSeen) < 60_000L) {
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

        // 5. Send to live callback if launcher is active, OR send broadcast if in background (never both)
        val callback = liveNotificationCallback
        if (callback != null) {
            callback.invoke(appLabel, packageName, title, text, postTime)
        } else {
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

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
