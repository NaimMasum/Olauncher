package app.olauncher.ui.terminal

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

class TermuxBridge(private val context: Context) {

    companion object {
        const val TERMUX_PACKAGE_NAME = "com.termux"
        const val RUN_COMMAND_RECEIVER = "com.termux.app.RunCommandReceiver"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_RUN_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        const val DEFAULT_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        const val DEFAULT_SH_PATH = "/data/data/com.termux/files/usr/bin/sh"
        const val DEFAULT_WORKDIR = "/data/data/com.termux/files/home"

        private val requestCounter = AtomicInteger(1000)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTimeoutRunnable: Runnable? = null

    fun isTermuxInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    TERMUX_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openTermux(): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun execute(
        command: String,
        workDir: String? = null,
        timeoutMs: Long = 30000L,
        onResult: (stdout: String?, stderr: String?, exitCode: Int, errCode: Int, errMsg: String?) -> Unit
    ): Boolean {
        if (!isTermuxInstalled()) {
            return false
        }

        // Cancel previous pending timeout if any
        pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }

        val requestCode = requestCounter.incrementAndGet()

        val resultIntent = Intent(TermuxResultReceiver.ACTION_TERMUX_RESULT).apply {
            setPackage(context.packageName)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            resultIntent,
            flags
        )

        // Set listener for the result
        TermuxResultReceiver.setListener(object : TermuxResultReceiver.Listener {
            override fun onTermuxResult(
                stdout: String?,
                stderr: String?,
                exitCode: Int,
                errCode: Int,
                errMsg: String?
            ) {
                pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                TermuxResultReceiver.setListener(null)
                mainHandler.post {
                    onResult(stdout, stderr, exitCode, errCode, errMsg)
                }
            }
        })

        val trimmedCmd = command.trim()
        val isPkgOrApt = trimmedCmd.startsWith("pkg") || trimmedCmd.startsWith("apt") || trimmedCmd.startsWith("dpkg")

        val effectiveTimeout = if (isPkgOrApt && timeoutMs < 180000L) 180000L else timeoutMs

        // Set timeout
        val timeoutRunnable = Runnable {
            TermuxResultReceiver.setListener(null)
            onResult(null, "Termux command timed out or service did not respond.", -1, -1, "Timeout")
        }
        pendingTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, effectiveTimeout)

        // For package managers or when pointing to launcher storage, route to Termux's home
        val effectiveWorkDir = if (!isPkgOrApt && workDir != null && !workDir.contains(context.packageName)) {
            workDir
        } else {
            DEFAULT_WORKDIR
        }

        // 1. Try BroadcastReceiver dispatch (avoids Android background service start restrictions)
        try {
            val receiverIntent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE_NAME, RUN_COMMAND_RECEIVER)
                putExtra(EXTRA_RUN_COMMAND_PATH, DEFAULT_BASH_PATH)
                putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_RUN_COMMAND_WORKDIR, effectiveWorkDir)
                putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
                putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, "0")
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            val receivers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryBroadcastReceivers(
                    receiverIntent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryBroadcastReceivers(receiverIntent, 0)
            }

            if (receivers.isNotEmpty()) {
                context.sendBroadcast(receiverIntent)
                return true
            }
        } catch (e: Exception) {
            // Receiver dispatch failed, fallback to service
        }

        // 2. Fallback to startService (for standard Termux app)
        return try {
            val serviceIntent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE_NAME, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_RUN_COMMAND_PATH, DEFAULT_BASH_PATH)
                putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_RUN_COMMAND_WORKDIR, effectiveWorkDir)
                putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
                putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, "0")
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            context.startService(serviceIntent)
            true
        } catch (e: Exception) {
            pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            TermuxResultReceiver.setListener(null)
            onResult(null, "Failed to start Termux service: ${e.message}", -1, -1, e.message)
            false
        }
    }
}
