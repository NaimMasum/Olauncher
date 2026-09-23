package app.olauncher.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class TerminalSessionManager(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onOpenSettings()
        fun onOpenAppsDrawer()
        fun onSwitchToGui()
        fun getInstalledApps(): List<AppModel.App>
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var currentSession: TerminalSession? = null
        private set
    private var terminalView: TerminalView? = null

    val homeDir: File by lazy {
        File(context.filesDir, "home").apply { if (!exists()) mkdirs() }
    }

    val binDir: File by lazy {
        File(context.filesDir, "bin").apply { if (!exists()) mkdirs() }
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}

        override fun onSessionFinished(finishedSession: TerminalSession) {
            Log.d("TerminalSessionManager", "Shell session finished. Restarting new session...")
            mainHandler.postDelayed({
                startNewSession()
                terminalView?.let { tv ->
                    currentSession?.let { s -> tv.attachSession(s) }
                }
            }, 300)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "TerminalSession Exception", e) }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f

        override fun onSingleTapUp(e: MotionEvent) {
            // Keep focus on terminal
            terminalView?.requestFocus()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            return false
        }

        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "TerminalView Exception", e) }
    }

    init {
        setupShellEnvironment()
    }

    private fun setupShellEnvironment() {
        try {
            if (!homeDir.exists()) homeDir.mkdirs()
            if (!binDir.exists()) binDir.mkdirs()

            // Setup profile script sourced by mksh via ENV
            val profile = File(homeDir, ".profile")
            val profileContent = """
export PATH="${binDir.absolutePath}:/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin"
export HOME="${homeDir.absolutePath}"
export TERM="xterm-256color"
export COLORTERM="truecolor"
export SHELL="/system/bin/sh"
export PS1='naim@android:\w\$ '
alias ls='toybox ls -F --color=auto'
alias ll='toybox ls -laF --color=auto'
alias clear='toybox clear'
alias vi='toybox vi'
alias nano='toybox vi'

echo "\033[1;32m==========================================\033[0m"
echo "\033[1;36m Welcome Naim to term_lunch Linux Terminal\033[0m"
echo "\033[1;33m Live PTY Session (isatty=1)\033[0m"
echo " Standard output IS a real terminal device."
echo " Interactive TUI (vi, nano, top) & ANSI colors enabled."
echo " Type an app name (e.g. 'chrome') or 'apps' to launch."
echo "\033[1;32m==========================================\033[0m"
""".trimIndent()
            profile.writeText(profileContent)

            // Setup common utility scripts in bin
            setupBinCommands()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupBinCommands() {
        try {
            // Helper: settings
            val settingsScript = File(binDir, "settings")
            settingsScript.writeText("#!/system/bin/sh\nam start -a android.settings.SETTINGS >/dev/null 2>&1\necho 'Opening settings...'\n")
            settingsScript.setExecutable(true, false)

            // Helper: vi
            val viScript = File(binDir, "vi")
            viScript.writeText("#!/system/bin/sh\nexec toybox vi \"$@\"\n")
            viScript.setExecutable(true, false)

            // Helper: nano (aliased to toybox vi on Android)
            val nanoScript = File(binDir, "nano")
            nanoScript.writeText("#!/system/bin/sh\nexec toybox vi \"$@\"\n")
            nanoScript.setExecutable(true, false)

            // Helper: apps list
            updateAppsListScript(callbacks.getInstalledApps())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateAppsListScript(apps: List<AppModel.App>) {
        try {
            if (apps.isEmpty()) return
            if (!binDir.exists()) binDir.mkdirs()

            // 1. apps command
            val appsScript = File(binDir, "apps")
            val lines = StringBuilder("#!/system/bin/sh\necho 'Installed Applications (${apps.size}):'\n")
            for (app in apps.sortedBy { it.appLabel.lowercase() }) {
                lines.append("echo '  > ${app.appLabel.replace("'", "\\'")}'\n")
            }
            appsScript.writeText(lines.toString())
            appsScript.setExecutable(true, false)

            // 2. Individual app launcher scripts
            for (app in apps) {
                val safeName = app.appLabel.lowercase()
                    .trim()
                    .replace("\\s+".toRegex(), "_")
                    .replace("[^a-z0-9_-]".toRegex(), "")

                val reserved = setOf("sh", "su", "ls", "cd", "rm", "mv", "cp", "echo", "cat", "ps", "top", "vi", "nano", "apps", "settings", "help", "clear", "open")
                if (safeName.isNotEmpty() && !reserved.contains(safeName)) {
                    val appScript = File(binDir, safeName)
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.appPackage)
                    val comp = launchIntent?.component?.flattenToShortString()
                    val cmd = if (comp != null) {
                        "am start -n \"$comp\" >/dev/null 2>&1"
                    } else {
                        "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p \"${app.appPackage}\" >/dev/null 2>&1"
                    }
                    appScript.writeText("#!/system/bin/sh\n$cmd\necho \"Launched ${app.appLabel}\"\n")
                    appScript.setExecutable(true, false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun attachToView(view: TerminalView) {
        this.terminalView = view
        view.setTerminalViewClient(viewClient)
        view.setTextSize(13)
        view.setTypeface(Typeface.MONOSPACE)
        view.setTerminalCursorBlinkerRate(600)
        view.setTerminalCursorBlinkerState(true, true)

        if (currentSession == null || !currentSession!!.isRunning) {
            startNewSession()
        }

        currentSession?.let { session ->
            view.attachSession(session)
            applyTheme(TerminalTheme.fromId(prefs.terminalTheme))
        }
    }

    fun startNewSession() {
        try {
            currentSession?.finishIfRunning()

            val env = arrayOf(
                "HOME=${homeDir.absolutePath}",
                "PATH=${binDir.absolutePath}:/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "SHELL=/system/bin/sh",
                "ENV=${homeDir.absolutePath}/.profile",
                "TMPDIR=${context.cacheDir.absolutePath}",
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system"
            )

            val session = TerminalSession(
                "/system/bin/sh",
                homeDir.absolutePath,
                arrayOf("-i"),
                env,
                1000,
                sessionClient
            )

            currentSession = session
            applyTheme(TerminalTheme.fromId(prefs.terminalTheme))
        } catch (e: Exception) {
            Log.e("TerminalSessionManager", "Failed to start TerminalSession", e)
        }
    }

    fun applyTheme(theme: TerminalTheme) {
        val session = currentSession ?: return
        try {
            val emulator = session.emulator ?: return
            val colors = emulator.mColors

            val bgHex = String.format("#%06X", 0xFFFFFF and theme.bgColor)
            val fgHex = String.format("#%06X", 0xFFFFFF and theme.textColor)
            val cursorHex = String.format("#%06X", 0xFFFFFF and theme.accentColor)

            colors.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, bgHex)
            colors.tryParseColor(TextStyle.COLOR_INDEX_FOREGROUND, fgHex)
            colors.tryParseColor(TextStyle.COLOR_INDEX_CURSOR, cursorHex)

            session.onColorsChanged()
            terminalView?.setBackgroundColor(theme.bgColor)
            terminalView?.onScreenUpdated()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun write(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        currentSession?.write(bytes, 0, bytes.size)
    }

    fun write(bytes: ByteArray) {
        currentSession?.write(bytes, 0, bytes.size)
    }

    fun sendBackspace() {
        // 0x7F is DEL / Backspace in Linux terminal
        currentSession?.write(byteArrayOf(0x7F), 0, 1)
    }

    fun sendEnter() {
        currentSession?.write(byteArrayOf(0x0D), 0, 1) // \r
    }

    fun sendTab() {
        currentSession?.write(byteArrayOf(0x09), 0, 1) // \t
    }

    fun sendEsc() {
        currentSession?.write(byteArrayOf(0x1B), 0, 1) // ESC
    }

    fun sendCtrlC() {
        currentSession?.write(byteArrayOf(0x03), 0, 1) // SIGINT (^C)
    }

    fun sendUp() {
        val bytes = "\u001B[A".toByteArray(Charsets.UTF_8)
        currentSession?.write(bytes, 0, bytes.size)
    }

    fun sendDown() {
        val bytes = "\u001B[B".toByteArray(Charsets.UTF_8)
        currentSession?.write(bytes, 0, bytes.size)
    }

    fun sendLeft() {
        val bytes = "\u001B[D".toByteArray(Charsets.UTF_8)
        currentSession?.write(bytes, 0, bytes.size)
    }

    fun sendRight() {
        val bytes = "\u001B[C".toByteArray(Charsets.UTF_8)
        currentSession?.write(bytes, 0, bytes.size)
    }

    fun destroy() {
        currentSession?.finishIfRunning()
        currentSession = null
        terminalView = null
    }
}
