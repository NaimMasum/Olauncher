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

import app.olauncher.helper.showKeyboard

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
            if (!prefs.terminalKeyboardVisible) {
                terminalView?.showKeyboard()
            }
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
            // Clean up old binDir if exists to eliminate permission denied binaries
            if (binDir.exists()) {
                binDir.deleteRecursively()
            }

            // Setup profile script sourced by mksh via ENV
            val profile = File(homeDir, ".profile")
            val profileContent = """
export PATH="/system/bin:/system/xbin"
export HOME="${homeDir.absolutePath}"
export TERM="xterm-256color"
export COLORTERM="truecolor"
export SHELL="/system/bin/sh"
export PS1='naim@android:${'$'} '

# System & Launcher Functions
apps() {
    if [ -f "${'$'}HOME/.apps_list" ]; then
        toybox cat "${'$'}HOME/.apps_list"
    else
        echo "Installed applications list is updating..."
    fi
}

battery() {
    dumpsys battery
}

call() {
    if [ -z "${'$'}1" ]; then
        am start -a android.intent.action.DIAL >/dev/null 2>&1
    else
        am start -a android.intent.action.DIAL -d "tel:${'$'}1" >/dev/null 2>&1
    fi
}

wa() {
    if [ -z "${'$'}1" ]; then
        am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.whatsapp >/dev/null 2>&1
    else
        am start -a android.intent.action.VIEW -d "https://api.whatsapp.com/send?phone=${'$'}1" >/dev/null 2>&1
    fi
}

whatsapp() {
    wa "${'$'}@"
}

settings() {
    am start -a android.settings.SETTINGS >/dev/null 2>&1
    echo "Opening Settings..."
}

termux() {
    am start -n com.termux/.app.TermuxActivity >/dev/null 2>&1
    echo "Opening Termux application..."
}

notif() {
    echo "── Active Notifications ──"
    dumpsys notification --noredact | grep -E 'extras=\{android.title|android.text' | head -n 30
}

pkg() {
    if [ -z "${'$'}1" ]; then
        echo "Termux package manager bridge"
        echo "Usage: pkg install <package> | pkg update | pkg list-all"
        echo "Opening Termux app..."
        am start -n com.termux/.app.TermuxActivity >/dev/null 2>&1
        return
    fi
    echo "Forwarding to Termux: pkg ${'$'}*"
    am broadcast -a com.termux.RUN_COMMAND -n com.termux/.app.RunCommandReceiver \
        --es com.termux.RUN_COMMAND_PATH "/data/data/com.termux/files/usr/bin/pkg" \
        --esa com.termux.RUN_COMMAND_ARGUMENTS "${'$'}*" \
        --ez com.termux.RUN_COMMAND_BACKGROUND "true" >/dev/null 2>&1
    echo "Dispatched to Termux background service. Switch to [CLI] top bar mode for full command outputs."
}

apt() {
    pkg "${'$'}@"
}

help() {
    echo "\033[1;36m── Available term_lunch Commands ──\033[0m"
    echo "  \033[1;32mapps\033[0m           : List all installed apps & shortcuts"
    echo "  \033[1;32m<appname>\033[0m      : Launch app directly (e.g. chrome, whatsapp, phone)"
    echo "  \033[1;32mbattery\033[0m        : Show detailed battery status"
    echo "  \033[1;32mcall <number>\033[0m  : Place phone call"
    echo "  \033[1;32mwa <number>\033[0m    : Open WhatsApp chat"
    echo "  \033[1;32mnotif\033[0m          : View active notification drawer"
    echo "  \033[1;32msettings\033[0m       : Open system settings"
    echo "  \033[1;32mtermux\033[0m         : Open Termux application"
    echo "  \033[1;32mpkg / apt\033[0m      : Termux package manager bridge"
    echo "  \033[1;32mvi / nano\033[0m      : Fullscreen text editor"
    echo "  \033[1;32mls / dir\033[0m       : List files in current directory"
    echo "  \033[1;32mtop / ps\033[0m       : View system processes in real-time"
    echo "  \033[1;32mclear\033[0m          : Clear terminal screen"
    echo "  \033[1;33m[TERMUX]/[CLI]\033[0m : Toggle at top bar to switch to Assistant CLI"
}

# Standard Unix Aliases
alias ls='toybox ls -F'
alias ll='toybox ls -laF'
alias dir='toybox ls -laF'
alias clear='toybox clear'
alias cls='toybox clear'
alias vi='toybox vi'
alias nano='toybox vi'
alias top='toybox top'
alias ps='toybox ps'
alias df='toybox df -h'
alias free='toybox free -m'
alias uptime='toybox uptime'
alias uname='toybox uname -a'
alias whoami='toybox whoami'
alias id='toybox id'
alias cat='toybox cat'
alias grep='toybox grep'
alias find='toybox find'

# Load app launch aliases
if [ -f "${'$'}HOME/.app_aliases" ]; then
    . "${'$'}HOME/.app_aliases"
fi

echo "\033[1;32m==========================================\033[0m"
echo "\033[1;36m Welcome Naim to term_lunch Linux Terminal\033[0m"
echo "\033[1;33m Live PTY Session (isatty=1)\033[0m"
echo " Standard Unix environment & tools ready."
echo " Type 'help' for commands, 'apps' for app list."
echo "\033[1;32m==========================================\033[0m"
""".trimIndent()
            profile.writeText(profileContent)
            updateAppsListScript(callbacks.getInstalledApps())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateAppsListScript(apps: List<AppModel.App>) {
        try {
            if (apps.isEmpty()) return
            if (!homeDir.exists()) homeDir.mkdirs()

            // 1. Generate .apps_list text file
            val appsListFile = File(homeDir, ".apps_list")
            val listBuilder = StringBuilder("── Installed Applications (${apps.size}) ──\n")
            val aliasesBuilder = StringBuilder("# Auto-generated application aliases\n")

            val reserved = setOf(
                "sh", "su", "ls", "ll", "dir", "cd", "rm", "mv", "cp", "echo", "cat", "ps", "top",
                "vi", "nano", "apps", "settings", "help", "clear", "cls", "open", "battery",
                "call", "wa", "whatsapp", "notif", "termux", "pkg", "apt", "df", "free",
                "uptime", "uname", "whoami", "id", "grep", "find"
            )

            for (app in apps.sortedBy { it.appLabel.lowercase() }) {
                val safeName = app.appLabel.lowercase()
                    .trim()
                    .replace("\\s+".toRegex(), "_")
                    .replace("[^a-z0-9_-]".toRegex(), "")

                listBuilder.append("  > ${app.appLabel}")
                if (safeName.isNotEmpty() && !reserved.contains(safeName)) {
                    listBuilder.append(" (command: $safeName)")
                }
                listBuilder.append("\n")

                if (safeName.isNotEmpty() && !reserved.contains(safeName)) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.appPackage)
                    val comp = launchIntent?.component?.flattenToShortString()
                    val cmd = if (comp != null) {
                        "am start -n \"$comp\" >/dev/null 2>&1"
                    } else {
                        "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p \"${app.appPackage}\" >/dev/null 2>&1"
                    }
                    val labelSafe = app.appLabel.replace("'", "\\'")
                    aliasesBuilder.append("alias $safeName='$cmd && echo \"Launched $labelSafe\"'\n")
                }
            }

            appsListFile.writeText(listBuilder.toString())

            // 2. Generate .app_aliases shell file
            val aliasesFile = File(homeDir, ".app_aliases")
            aliasesFile.writeText(aliasesBuilder.toString())

            // If session is already running, dynamically source the new aliases
            currentSession?.let {
                write(". \"\$HOME/.app_aliases\" >/dev/null 2>&1\n")
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
                "PATH=/system/bin:/system/xbin",
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
