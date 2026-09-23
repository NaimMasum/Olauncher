package app.olauncher.ui.terminal

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.app.NotificationManagerCompat
import app.olauncher.service.TerminalNotificationListenerService
import app.olauncher.BuildConfig
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.uninstall
import org.json.JSONArray
import org.json.JSONObject
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TerminalCommandHandler(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onAddLog(item: TerminalLogItem)
        fun onClearLogs()
        fun onLaunchApp(app: AppModel.App)
        fun onThemeChanged(theme: TerminalTheme)
        fun onSwitchLauncherMode(terminalMode: Boolean)
        fun onOpenSettings()
        fun getInstalledApps(): List<AppModel.App>
        fun onPinnedAppsChanged()
        fun onPathChanged(displayPath: String)
        fun onRunningStateChanged(isRunning: Boolean)
    }

    private var currentWorkingDir: File = getDefaultDirectory()
    private var previousDirectory: File? = null

    private val commandHistory = ArrayList<String>()
    private var historyIndex = -1

    private val shellExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentProcess: Process? = null

    private fun getDefaultDirectory(): File {
        val home = File(context.filesDir, "home")
        if (!home.exists()) {
            home.mkdirs()
        }
        return home
    }

    fun getDisplayPath(): String {
        val path = currentWorkingDir.absolutePath
        val homePath = getDefaultDirectory().absolutePath
        val filesPath = context.filesDir.absolutePath
        return when {
            path == homePath -> "~"
            path.startsWith("$homePath/") -> "~" + path.removePrefix(homePath)
            path == filesPath -> "~"
            path.startsWith("$filesPath/") -> "~" + path.removePrefix(filesPath)
            path == "/sdcard" || path == "/storage/emulated/0" -> "/sdcard"
            path.startsWith("/storage/emulated/0/") -> path.replaceFirst("/storage/emulated/0", "/sdcard")
            else -> path
        }
    }

    fun getPromptText(): CharSequence {
        val theme = TerminalTheme.fromId(prefs.terminalTheme)
        val ssb = SpannableStringBuilder()

        val userPart = "naim@android:"
        val pathPart = getDisplayPath()
        val symbolPart = "$ "

        val startUser = ssb.length
        ssb.append(userPart)
        ssb.setSpan(
            ForegroundColorSpan(theme.promptColor),
            startUser,
            ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val startPath = ssb.length
        ssb.append(pathPart)
        ssb.setSpan(
            ForegroundColorSpan(theme.pathColor),
            startPath,
            ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val startSymbol = ssb.length
        ssb.append(symbolPart)
        ssb.setSpan(
            ForegroundColorSpan(theme.promptColor),
            startSymbol,
            ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        return ssb
    }

    fun getInitialBanner(): List<TerminalLogItem> {
        val banner = mutableListOf<TerminalLogItem>()
        banner.add(TerminalLogItem("==========================================", TerminalItemType.BANNER))
        banner.add(TerminalLogItem("Welcome Naim", TerminalItemType.SUCCESS))
        banner.add(TerminalLogItem("term_lunch Linux Terminal", TerminalItemType.BANNER))
        banner.add(TerminalLogItem("Type 'help' for commands, type app name, or run shell tools.", TerminalItemType.OUTPUT))

        val batteryLevel = getBatteryPercentage()
        val appsCount = callbacks.getInstalledApps().size
        banner.add(TerminalLogItem("Dir: ${getDisplayPath()} | Battery: $batteryLevel% | Apps: $appsCount", TerminalItemType.BANNER))
        banner.add(TerminalLogItem("Engine: Android Native Shell (/system/bin/sh)", TerminalItemType.BANNER))
        banner.add(TerminalLogItem("------------------------------------------", TerminalItemType.BANNER))
        return banner
    }

    fun getPreviousCommand(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex > 0) {
            historyIndex--
        } else if (historyIndex == -1) {
            historyIndex = commandHistory.size - 1
        }
        return commandHistory.getOrNull(historyIndex)
    }

    fun getNextCommand(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex < commandHistory.size - 1) {
            historyIndex++
            return commandHistory[historyIndex]
        } else {
            historyIndex = commandHistory.size
            return ""
        }
    }

    fun sendCtrlC() {
        if (currentProcess != null) {
            try {
                currentProcess?.destroyForcibly()
                callbacks.onAddLog(TerminalLogItem("^C", TerminalItemType.ERROR))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            currentProcess = null
            callbacks.onRunningStateChanged(false)
        } else {
            callbacks.onAddLog(TerminalLogItem("^C", TerminalItemType.OUTPUT))
        }
    }

    fun addNotification(
        appLabel: String,
        packageName: String,
        title: String,
        text: String,
        time: Long
    ) {
        val item = formatNotificationItem(time, appLabel, packageName, title, text)
        callbacks.onAddLog(item)
    }

    fun addNotificationLog(message: String) {
        callbacks.onAddLog(TerminalLogItem(message, TerminalItemType.NOTIFICATION))
    }

    fun formatNotificationItem(
        time: Long,
        appLabel: String,
        packageName: String,
        title: String,
        text: String
    ): TerminalLogItem {
        val theme = TerminalTheme.fromId(prefs.terminalTheme)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(time))
        val ssb = SpannableStringBuilder()

        // 1. Time bracket: [10:45]
        val tOpen = "["
        ssb.append(tOpen)
        ssb.setSpan(ForegroundColorSpan(theme.secondaryColor), ssb.length - tOpen.length, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        ssb.append(timeStr)
        ssb.setSpan(ForegroundColorSpan(Color.parseColor("#8BE9FD")), ssb.length - timeStr.length, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val tClose = "]"
        ssb.append(tClose)
        ssb.setSpan(ForegroundColorSpan(theme.secondaryColor), ssb.length - tClose.length, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // 2. Bell icon
        val bell = " 🔔 "
        ssb.append(bell)
        ssb.setSpan(ForegroundColorSpan(Color.parseColor("#FFD54F")), ssb.length - bell.length, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // 3. App Badge [AppName]
        val appColor = getAppNotificationColor(packageName, appLabel, theme)
        val bOpen = "["
        ssb.append(bOpen)
        ssb.setSpan(ForegroundColorSpan(theme.secondaryColor), ssb.length - bOpen.length, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val appStart = ssb.length
        ssb.append(appLabel)
        ssb.setSpan(ForegroundColorSpan(appColor), appStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(StyleSpan(Typeface.BOLD), appStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val bClose = "]"
        ssb.append(bClose)
        ssb.setSpan(ForegroundColorSpan(theme.secondaryColor), ssb.length - bClose.length, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // 4. Title (e.g. Sender or Subject)
        if (title.isNotBlank()) {
            ssb.append(" ")
            val titleStart = ssb.length
            ssb.append(title)
            ssb.setSpan(ForegroundColorSpan(theme.promptColor), titleStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(StyleSpan(Typeface.BOLD), titleStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            if (text.isNotBlank()) {
                val sep = ": "
                ssb.append(sep)
                ssb.setSpan(ForegroundColorSpan(theme.secondaryColor), ssb.length - sep.length, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (text.isNotBlank()) {
            ssb.append(" ")
        }

        // 5. Message Body
        if (text.isNotBlank()) {
            val bodyStart = ssb.length
            ssb.append(text)
            ssb.setSpan(ForegroundColorSpan(theme.textColor), bodyStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Link appModel so tapping the log item opens the corresponding application
        val appModel = callbacks.getInstalledApps().firstOrNull {
            it.appPackage.equals(packageName, ignoreCase = true) || it.appLabel.equals(appLabel, ignoreCase = true)
        }

        return TerminalLogItem(ssb, TerminalItemType.NOTIFICATION, appModel)
    }

    private fun getAppNotificationColor(packageName: String, appLabel: String, theme: TerminalTheme): Int {
        val pkg = packageName.lowercase()
        val label = appLabel.lowercase()
        return when {
            pkg.contains("whatsapp") || label.contains("whatsapp") -> Color.parseColor("#25D366") // WhatsApp Green
            pkg.contains("youtube") || label.contains("youtube") -> Color.parseColor("#FF5252") // YouTube Red
            pkg.contains("telegram") || label.contains("telegram") -> Color.parseColor("#29B6F6") // Telegram Sky Blue
            pkg.contains("messaging") || pkg.contains("mms") || label.contains("message") || label.contains("sms") -> Color.parseColor("#00E5FF") // Messages Cyan
            pkg.contains("gmail") || pkg.contains("email") || label.contains("gmail") || label.contains("mail") -> Color.parseColor("#FF5252") // Mail Red
            pkg.contains("instagram") || label.contains("instagram") -> Color.parseColor("#FF4081") // Instagram Pink
            pkg.contains("discord") || label.contains("discord") -> Color.parseColor("#7C4DFF") // Discord Purple
            pkg.contains("twitter") || label.contains("twitter") || label == "x" -> Color.parseColor("#1DA1F2") // Twitter Blue
            pkg.contains("facebook") || pkg.contains("orca") || label.contains("messenger") -> Color.parseColor("#0084FF") // Messenger Blue
            pkg.contains("phone") || pkg.contains("dialer") || label.contains("phone") || label.contains("call") -> Color.parseColor("#00E676") // Phone Green
            pkg.contains("spotify") || label.contains("spotify") -> Color.parseColor("#1ED760") // Spotify Green
            pkg.contains("reddit") || label.contains("reddit") -> Color.parseColor("#FF4500") // Reddit Orange
            pkg.contains("github") || label.contains("github") -> Color.parseColor("#E0E0E0") // GitHub White
            pkg.contains("chrome") || label.contains("chrome") -> Color.parseColor("#FFCA28") // Chrome Yellow
            else -> {
                val palette = intArrayOf(
                    Color.parseColor("#FF79C6"), // Neon Pink
                    Color.parseColor("#BD93F9"), // Neon Purple
                    Color.parseColor("#50FA7B"), // Mint Green
                    Color.parseColor("#FFB86C"), // Amber Orange
                    Color.parseColor("#8BE9FD"), // Electric Cyan
                    Color.parseColor("#F1FA8C"), // Pastel Yellow
                    Color.parseColor("#69F0AE"), // Spring Green
                    Color.parseColor("#40C4FF"), // Light Blue
                    Color.parseColor("#FF6E40"), // Deep Orange
                    Color.parseColor("#B388FF"), // Lavender
                    Color.parseColor("#EEFF41"), // Electric Lime
                    Color.parseColor("#EA80FC")  // Orchid
                )
                val key = if (packageName.isNotBlank()) packageName else appLabel
                val index = (key.hashCode().and(0x7FFFFFFF)) % palette.size
                palette[index]
            }
        }
    }

    fun destroy() {
        currentProcess?.destroyForcibly()
        currentProcess = null
    }

    fun execute(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        // Record command in history
        if (commandHistory.isEmpty() || commandHistory.last() != input) {
            commandHistory.add(input)
        }
        historyIndex = commandHistory.size

        val promptStr = "${getDisplayPath()} $ "
        callbacks.onAddLog(TerminalLogItem("$promptStr$input", TerminalItemType.COMMAND_ECHO))

        // Check custom aliases
        val aliases = loadAliases()
        val resolvedInput = aliases[input.lowercase()] ?: input

        val tokens = resolvedInput.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return

        val command = tokens[0].lowercase()
        val args = if (tokens.size > 1) tokens.subList(1, tokens.size) else emptyList()

        when (command) {
            "help", "?" -> showHelp()
            "clear", "cls" -> {
                callbacks.onClearLogs()
            }
            "apps" -> showApps(args.joinToString(" "))
            "cd" -> handleCd(args)
            "pwd" -> callbacks.onAddLog(TerminalLogItem(currentWorkingDir.absolutePath, TerminalItemType.OUTPUT))
            "history" -> showHistory()
            "open", "launch" -> {
                if (args.isEmpty()) {
                    callbacks.onAddLog(TerminalLogItem("Usage: open <app_name>", TerminalItemType.ERROR))
                } else {
                    launchByName(args.joinToString(" "))
                }
            }
            "info" -> {
                if (args.isEmpty()) {
                    callbacks.onAddLog(TerminalLogItem("Usage: info <app_name>", TerminalItemType.ERROR))
                } else {
                    showAppInfoByName(args.joinToString(" "))
                }
            }
            "uninstall", "remove" -> {
                if (args.isEmpty()) {
                    callbacks.onAddLog(TerminalLogItem("Usage: uninstall <app_name>", TerminalItemType.ERROR))
                } else {
                    uninstallByName(args.joinToString(" "))
                }
            }
            "theme", "color" -> handleTheme(args)
            "pin" -> handlePin(args)
            "unpin" -> handleUnpin(args)
            "battery" -> showBattery()
            "notif", "notifications" -> handleNotificationCommand(args)
            "time", "date" -> showTimeAndDate()
            "device", "uname", "neofetch" -> showDeviceInfo()
            "alias" -> handleAlias(args)
            "search" -> {
                val query = args.joinToString(" ")
                if (query.isBlank()) {
                    callbacks.onAddLog(TerminalLogItem("Usage: search <query>", TerminalItemType.ERROR))
                } else {
                    callbacks.onAddLog(TerminalLogItem("Searching: $query", TerminalItemType.OUTPUT))
                    context.openSearch(query)
                }
            }
            "settings" -> {
                callbacks.onAddLog(TerminalLogItem("Opening settings...", TerminalItemType.OUTPUT))
                callbacks.onOpenSettings()
            }
            "mode" -> {
                if (args.isEmpty()) {
                    callbacks.onAddLog(TerminalLogItem("Current mode: Terminal. Usage: mode [gui|cli]", TerminalItemType.OUTPUT))
                } else {
                    when (args[0].lowercase()) {
                        "gui", "minimal", "classic" -> {
                            callbacks.onAddLog(TerminalLogItem("Switching to GUI mode...", TerminalItemType.SUCCESS))
                            callbacks.onSwitchLauncherMode(false)
                        }
                        "cli", "terminal" -> {
                            callbacks.onAddLog(TerminalLogItem("Already in Terminal mode.", TerminalItemType.OUTPUT))
                        }
                        else -> {
                            callbacks.onAddLog(TerminalLogItem("Unknown mode '${args[0]}'. Use: mode [gui|cli]", TerminalItemType.ERROR))
                        }
                    }
                }
            }
            "echo" -> {
                callbacks.onAddLog(TerminalLogItem(args.joinToString(" "), TerminalItemType.OUTPUT))
            }
            "ping" -> handlePing(args)
            "net", "internet", "ip" -> handleNetStatus()
            "curl", "fetch" -> handleCurl(args)
            "call", "dial" -> {
                val number = args.joinToString(" ").trim()
                handleCall(number)
            }
            "whatsapp", "wa" -> {
                handleWhatsApp(args)
            }
            "chrome" -> {
                handleChrome(args)
            }
            else -> {
                // If it starts with ! (e.g. !g query), treat as duckduckgo or web search
                if (rawInput.startsWith("!")) {
                    callbacks.onAddLog(TerminalLogItem("Web search: $rawInput", TerminalItemType.OUTPUT))
                    context.openUrl("https://duckduckgo.com/?q=" + rawInput.replace(" ", "%20"))
                    return
                }

                // Check if user typed an installed app name directly (e.g. chrome, youtube, camera)
                val matched = findApp(resolvedInput)
                if (matched != null && !isLikelyShellCommand(command)) {
                    callbacks.onAddLog(TerminalLogItem("Launching ${matched.appLabel}...", TerminalItemType.SUCCESS))
                    callbacks.onLaunchApp(matched)
                    return
                }

                // Run as real native Linux shell process!
                executeShellCommand(resolvedInput)
            }
        }
    }


    private fun isLikelyShellCommand(cmd: String): Boolean {
        val commonLinuxCommands = setOf(
            "ls", "cat", "grep", "df", "ps", "top", "ping", "mkdir",
            "rm", "cp", "mv", "touch", "chmod", "chown", "stat", "find",
            "wc", "head", "tail", "sort", "uniq", "tar", "gzip", "gunzip",
            "ip", "ifconfig", "netstat", "whoami", "id", "getprop", "setprop",
            "logcat", "dumpsys", "pm", "am", "sh", "su", "uptime", "free"
        )
        return commonLinuxCommands.contains(cmd)
    }

    private fun executeShellCommand(cmd: String) {
        callbacks.onRunningStateChanged(true)
        shellExecutor.execute {
            try {
                val launchDir = context.filesDir
                val shellCmd = if (currentWorkingDir.absolutePath != launchDir.absolutePath) {
                    "cd \"${currentWorkingDir.absolutePath}\" 2>/dev/null; $cmd"
                } else {
                    cmd
                }

                val pb = ProcessBuilder("/system/bin/sh", "-c", shellCmd)
                    .directory(launchDir)
                    .redirectErrorStream(true)

                val env = pb.environment()
                env["HOME"] = getDefaultDirectory().absolutePath
                env["PATH"] = "${getDefaultDirectory().absolutePath}/bin:/system/bin:/system/xbin"
                env["TERM"] = "xterm-256color"
                env["TMPDIR"] = context.cacheDir.absolutePath

                val proc = pb.start()
                currentProcess = proc

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                var lineCount = 0
                val maxLines = 500

                while (reader.readLine().also { line = it } != null) {
                    lineCount++
                    if (lineCount > maxLines) {
                        mainHandler.post {
                            callbacks.onAddLog(TerminalLogItem("[Output truncated: exceeded $maxLines lines]", TerminalItemType.ERROR))
                        }
                        proc.destroyForcibly()
                        break
                    }
                    val outputLine = line ?: ""
                    mainHandler.post {
                        callbacks.onAddLog(TerminalLogItem(outputLine, TerminalItemType.OUTPUT))
                    }
                }
                proc.waitFor()
                val exitCode = proc.exitValue()
                if (exitCode != 0 && lineCount == 0) {
                    mainHandler.post {
                        callbacks.onAddLog(TerminalLogItem("Process exited with code $exitCode", TerminalItemType.ERROR))
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callbacks.onAddLog(TerminalLogItem("Error: ${e.message}", TerminalItemType.ERROR))
                }
            } finally {
                currentProcess = null
                mainHandler.post {
                    callbacks.onRunningStateChanged(false)
                }
            }
        }
    }


    private fun handleCall(rawTarget: String) {
        val trimmed = rawTarget.trim()
        if (trimmed.isEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                callbacks.onAddLog(TerminalLogItem("Opening phone dialer...", TerminalItemType.SUCCESS))
            } catch (e: Exception) {
                callbacks.onAddLog(TerminalLogItem("Failed to open dialer: ${e.message}", TerminalItemType.ERROR))
            }
            return
        }

        val cleaned = trimmed.replace("[^0-9+*#,;]".toRegex(), "")
        val target = if (cleaned.isNotEmpty()) cleaned else trimmed
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(target)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            callbacks.onAddLog(TerminalLogItem("Dialing $target...", TerminalItemType.SUCCESS))
        } catch (e: Exception) {
            callbacks.onAddLog(TerminalLogItem("Failed to open dialer: ${e.message}", TerminalItemType.ERROR))
        }
    }

    private fun handleWhatsApp(args: List<String>) {
        if (args.isEmpty()) {
            val matched = findApp("whatsapp")
            if (matched != null) {
                callbacks.onAddLog(TerminalLogItem("Opening WhatsApp...", TerminalItemType.SUCCESS))
                callbacks.onLaunchApp(matched)
            } else {
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    if (intent != null) {
                        context.startActivity(intent)
                        callbacks.onAddLog(TerminalLogItem("Opening WhatsApp...", TerminalItemType.SUCCESS))
                    } else {
                        callbacks.onAddLog(TerminalLogItem("WhatsApp is not installed.", TerminalItemType.ERROR))
                    }
                } catch (e: Exception) {
                    callbacks.onAddLog(TerminalLogItem("Error: ${e.message}", TerminalItemType.ERROR))
                }
            }
            return
        }

        val subCmd = args[0].lowercase()
        val rest = if (args.size > 1) args.subList(1, args.size) else emptyList()

        val isCall = subCmd == "call"
        val isMsg = subCmd == "msg" || subCmd == "message" || subCmd == "text"

        val targetAndMsg = if (isCall || isMsg) rest else args
        if (targetAndMsg.isEmpty()) {
            val matched = findApp("whatsapp")
            if (matched != null) callbacks.onLaunchApp(matched)
            return
        }

        val firstToken = targetAndMsg[0]
        val messageText = if (targetAndMsg.size > 1) targetAndMsg.subList(1, targetAndMsg.size).joinToString(" ") else ""

        val cleanedNumber = firstToken.replace("[^0-9+]".toRegex(), "")

        if (cleanedNumber.length >= 7) {
            val formatted = cleanedNumber.removePrefix("+")
            try {
                val url = if (messageText.isNotBlank()) {
                    "https://api.whatsapp.com/send?phone=$formatted&text=${Uri.encode(messageText)}"
                } else {
                    "https://api.whatsapp.com/send?phone=$formatted"
                }
                val uri = Uri.parse(url)
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                val actionType = if (isCall) "calling" else if (messageText.isNotBlank()) "messaging" else "chat"
                callbacks.onAddLog(TerminalLogItem("Opening WhatsApp ($actionType) for $cleanedNumber...", TerminalItemType.SUCCESS))
            } catch (e: Exception) {
                try {
                    val waUrl = if (messageText.isNotBlank()) {
                        "https://wa.me/$formatted?text=${Uri.encode(messageText)}"
                    } else {
                        "https://wa.me/$formatted"
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    callbacks.onAddLog(TerminalLogItem("Opening WhatsApp for $cleanedNumber...", TerminalItemType.SUCCESS))
                } catch (e2: Exception) {
                    callbacks.onAddLog(TerminalLogItem("Failed to open WhatsApp: ${e2.message}", TerminalItemType.ERROR))
                }
            }
        } else {
            // Target is a name or query (e.g. whatsapp john or whatsapp call john)
            val fullTargetQuery = targetAndMsg.joinToString(" ")
            try {
                // If text is provided, use ACTION_SEND to WhatsApp
                if (messageText.isNotBlank() || isMsg) {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, fullTargetQuery)
                        setPackage("com.whatsapp")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(sendIntent)
                    callbacks.onAddLog(TerminalLogItem("Opening WhatsApp to share: '$fullTargetQuery'...", TerminalItemType.SUCCESS))
                } else {
                    // Try launching WhatsApp or opening search
                    val matched = findApp("whatsapp")
                    if (matched != null) {
                        callbacks.onLaunchApp(matched)
                    } else {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                        if (launchIntent != null) {
                            context.startActivity(launchIntent)
                        } else {
                            callbacks.onAddLog(TerminalLogItem("WhatsApp is not installed.", TerminalItemType.ERROR))
                            return
                        }
                    }
                    callbacks.onAddLog(TerminalLogItem("Opened WhatsApp. Search: '$fullTargetQuery'", TerminalItemType.SUCCESS))
                }
            } catch (e: Exception) {
                callbacks.onAddLog(TerminalLogItem("Failed to open WhatsApp: ${e.message}", TerminalItemType.ERROR))
            }
        }
    }

    private fun handleChrome(args: List<String>) {
        if (args.isEmpty()) {
            val matched = findApp("chrome")
            if (matched != null) {
                callbacks.onAddLog(TerminalLogItem("Opening Chrome...", TerminalItemType.SUCCESS))
                callbacks.onLaunchApp(matched)
            } else {
                context.openUrl("https://www.google.com")
            }
            return
        }

        var urlOrQuery = args.joinToString(" ").trim()
        if (urlOrQuery.startsWith("open ", ignoreCase = true)) {
            urlOrQuery = urlOrQuery.substring(5).trim()
        }

        val targetUrl = if (urlOrQuery.contains(".") && !urlOrQuery.contains(" ")) {
            if (urlOrQuery.startsWith("http://") || urlOrQuery.startsWith("https://")) {
                urlOrQuery
            } else {
                "https://$urlOrQuery"
            }
        } else {
            "https://www.google.com/search?q=" + Uri.encode(urlOrQuery)
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                setPackage("com.android.chrome")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            callbacks.onAddLog(TerminalLogItem("Opening Chrome: $targetUrl", TerminalItemType.SUCCESS))
        } catch (e: Exception) {
            context.openUrl(targetUrl)
            callbacks.onAddLog(TerminalLogItem("Opening browser: $targetUrl", TerminalItemType.SUCCESS))
        }
    }


    private fun handlePing(args: List<String>) {
        if (args.isEmpty()) {
            callbacks.onAddLog(TerminalLogItem("Usage: ping <host> [count]", TerminalItemType.ERROR))
            return
        }
        val rawHost = args[0]
        val host = rawHost.removePrefix("http://").removePrefix("https://").substringBefore("/")
        val count = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 20) ?: 4

        callbacks.onRunningStateChanged(true)
        callbacks.onAddLog(TerminalLogItem("PING $host: 56 data bytes", TerminalItemType.OUTPUT))

        shellExecutor.execute {
            var sent = 0
            var received = 0
            val times = mutableListOf<Long>()

            try {
                val address = InetAddress.getByName(host)
                val ipStr = address.hostAddress ?: host

                for (i in 1..count) {
                    sent++
                    val startTime = System.currentTimeMillis()
                    var success = false

                    val ports = listOf(443, 80, 53)
                    for (port in ports) {
                        try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(address, port), 2500)
                                success = true
                            }
                            if (success) break
                        } catch (e: Exception) {
                            // try next port
                        }
                    }

                    if (!success) {
                        try {
                            if (address.isReachable(2000)) {
                                success = true
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    if (success) {
                        received++
                        times.add(elapsed)
                        mainHandler.post {
                            callbacks.onAddLog(TerminalLogItem("64 bytes from $ipStr: seq=$i time=${elapsed} ms", TerminalItemType.OUTPUT))
                        }
                    } else {
                        mainHandler.post {
                            callbacks.onAddLog(TerminalLogItem("Request timeout for seq $i", TerminalItemType.ERROR))
                        }
                    }

                    if (i < count) {
                        Thread.sleep(600)
                    }
                }

                val loss = if (sent > 0) ((sent - received) * 100) / sent else 0
                val stats = buildString {
                    appendLine("--- $host ping statistics ---")
                    append("$sent packets transmitted, $received received, $loss% packet loss")
                    if (times.isNotEmpty()) {
                        val min = times.minOrNull() ?: 0
                        val max = times.maxOrNull() ?: 0
                        val avg = times.average().toLong()
                        append(", min/avg/max = $min/$avg/$max ms")
                    }
                }

                mainHandler.post {
                    callbacks.onAddLog(TerminalLogItem(stats, if (received > 0) TerminalItemType.SUCCESS else TerminalItemType.ERROR))
                    callbacks.onRunningStateChanged(false)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callbacks.onAddLog(TerminalLogItem("ping: cannot resolve $host: ${e.message}", TerminalItemType.ERROR))
                    callbacks.onRunningStateChanged(false)
                }
            }
        }
    }

    private fun handleNetStatus() {
        callbacks.onRunningStateChanged(true)
        callbacks.onAddLog(TerminalLogItem("Inspecting network connectivity...", TerminalItemType.OUTPUT))

        shellExecutor.execute {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(activeNetwork)

                val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val hasCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

                val transportStr = when {
                    hasWifi -> "Wi-Fi"
                    hasCell -> "Cellular Mobile Data"
                    else -> "Unknown / None"
                }

                var dnsOk = false
                var latency = -1L
                try {
                    val start = System.currentTimeMillis()
                    val addr = InetAddress.getByName("one.one.one.one")
                    Socket().use { s ->
                        s.connect(InetSocketAddress(addr, 53), 2000)
                    }
                    latency = System.currentTimeMillis() - start
                    dnsOk = true
                } catch (e: Exception) {
                    try {
                        val start = System.currentTimeMillis()
                        val addr = InetAddress.getByName("google.com")
                        Socket().use { s ->
                            s.connect(InetSocketAddress(addr, 443), 2000)
                        }
                        latency = System.currentTimeMillis() - start
                        dnsOk = true
                    } catch (e2: Exception) {
                        // ignore
                    }
                }

                val statusText = buildString {
                    appendLine("Network Status:")
                    appendLine("  Connection : $transportStr")
                    appendLine("  Internet   : ${if (hasInternet) "AVAILABLE" else "NO CONNECTION"}")
                    appendLine("  Validated  : ${if (validated) "YES (Full Web Access)" else "NO"}")
                    appendLine("  DNS Query  : ${if (dnsOk) "RESOLVED (OK)" else "FAILED"}")
                    if (latency >= 0) {
                        appendLine("  RTT Latency: ${latency} ms")
                    }
                }

                mainHandler.post {
                    callbacks.onAddLog(TerminalLogItem(statusText.trimEnd(), if (hasInternet && dnsOk) TerminalItemType.SUCCESS else TerminalItemType.ERROR))
                    callbacks.onRunningStateChanged(false)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callbacks.onAddLog(TerminalLogItem("Network check error: ${e.message}", TerminalItemType.ERROR))
                    callbacks.onRunningStateChanged(false)
                }
            }
        }
    }

    private fun handleCurl(args: List<String>) {
        if (args.isEmpty()) {
            callbacks.onAddLog(TerminalLogItem("Usage: curl <url> [-I]", TerminalItemType.ERROR))
            return
        }
        val headOnly = args.contains("-I") || args.contains("-i")
        val targetArg = args.firstOrNull { it != "-I" && it != "-i" }
        if (targetArg == null) {
            callbacks.onAddLog(TerminalLogItem("Usage: curl <url>", TerminalItemType.ERROR))
            return
        }
        val urlStr = if (!targetArg.startsWith("http://") && !targetArg.startsWith("https://")) {
            "https://$targetArg"
        } else {
            targetArg
        }

        callbacks.onRunningStateChanged(true)
        callbacks.onAddLog(TerminalLogItem("Connecting to $urlStr...", TerminalItemType.OUTPUT))

        shellExecutor.execute {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = if (headOnly) "HEAD" else "GET"
                conn.setRequestProperty("User-Agent", "term_lunch/1.0 (Linux; Android CLI)")

                val code = conn.responseCode
                val msg = conn.responseMessage

                mainHandler.post {
                    callbacks.onAddLog(TerminalLogItem("HTTP/1.1 $code $msg", if (code in 200..399) TerminalItemType.SUCCESS else TerminalItemType.ERROR))
                }

                if (headOnly) {
                    val headerFields = conn.headerFields
                    for ((k, v) in headerFields) {
                        if (k != null) {
                            mainHandler.post {
                                callbacks.onAddLog(TerminalLogItem("$k: ${v.joinToString(", ")}", TerminalItemType.OUTPUT))
                            }
                        }
                    }
                } else {
                    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader()?.use { it.readLines().take(20).joinToString("\n") } ?: ""
                    if (body.isNotBlank()) {
                        mainHandler.post {
                            callbacks.onAddLog(TerminalLogItem(body, TerminalItemType.OUTPUT))
                        }
                    }
                }
                conn.disconnect()
                mainHandler.post {
                    callbacks.onRunningStateChanged(false)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callbacks.onAddLog(TerminalLogItem("curl error: ${e.message}", TerminalItemType.ERROR))
                    callbacks.onRunningStateChanged(false)
                }
            }
        }
    }


    private fun handleCd(args: List<String>) {
        val targetPath = if (args.isEmpty() || args[0] == "~") {
            getDefaultDirectory()
        } else if (args[0] == "-") {
            previousDirectory ?: getDefaultDirectory()
        } else {
            val p = args[0]
            if (p.startsWith("/")) File(p) else File(currentWorkingDir, p)
        }

        try {
            val canonical = targetPath.canonicalFile
            if (!canonical.exists()) {
                callbacks.onAddLog(TerminalLogItem("cd: no such file or directory: ${args.getOrNull(0) ?: ""}", TerminalItemType.ERROR))
                return
            }
            if (!canonical.isDirectory) {
                callbacks.onAddLog(TerminalLogItem("cd: not a directory: ${args[0]}", TerminalItemType.ERROR))
                return
            }
            if (!canonical.canRead()) {
                callbacks.onAddLog(TerminalLogItem("cd: permission denied: ${args[0]}", TerminalItemType.ERROR))
                return
            }
            previousDirectory = currentWorkingDir
            currentWorkingDir = canonical
            callbacks.onPathChanged(getDisplayPath())
        } catch (e: Exception) {
            callbacks.onAddLog(TerminalLogItem("cd: ${e.message}", TerminalItemType.ERROR))
        }
    }

    private fun showHistory() {
        if (commandHistory.isEmpty()) {
            callbacks.onAddLog(TerminalLogItem("No command history.", TerminalItemType.OUTPUT))
            return
        }
        callbacks.onAddLog(TerminalLogItem("Command History (${commandHistory.size}):", TerminalItemType.SUCCESS))
        commandHistory.takeLast(30).forEachIndexed { idx, cmd ->
            callbacks.onAddLog(TerminalLogItem("  ${idx + 1}  $cmd", TerminalItemType.OUTPUT))
        }
    }

    private fun showHelp() {
        val helpLines = listOf(
            "Terminal Launcher & Linux Shell (/system/bin/sh):",
            "  <app_name>      : Launch app directly (e.g. 'chrome', 'camera')",
            "  apps [query]    : List installed applications (tap to open)",
            "  cd <path>       : Change working directory (~, .., /sdcard)",
            "  pwd             : Print working directory",
            "  history         : View command history (or use ▲ / ▼ keys)",
            "  pin <app>       : Pin app to suggestion bar (rm | list | clear)",
            "  unpin <app>     : Remove app from suggestion bar",
            "  theme [name]    : Switch theme (dracula, synthwave, tokyo, nord,",
            "                    gruvbox, solarized, cyberpunk, green, amber, red...)",
            "  battery         : Battery level, charging rate, wattage, voltage & time remaining",
            "  notif [on|off]  : In-terminal notifications (apps, settings, test)",
            "  device, uname   : Device hardware info & RAM",
            "  settings        : Open launcher settings",
            "  mode [gui|cli]  : Switch between Terminal and GUI mode",
            "  clear, cls      : Clear terminal screen",
            "",
            "Actions & Shortcuts:",
            "  call [number]   : Open dialer or dial number directly (e.g. 'call', 'call 1234567890')",
            "  whatsapp <num>  : Open WhatsApp chat/call with number (e.g. 'wa call +123456')",
            "  whatsapp msg <num> <text> : Send message directly via WhatsApp",
            "  chrome [url]    : Open URL in Chrome (e.g. 'chrome open facebook.com')",
            "",
            "Networking & Internet:",
            "  ping <host> [n] : Ping host with latency metrics (e.g. 'ping google.com')",
            "  net, ip         : Inspect network connection, Wi-Fi/Cellular, DNS & status",
            "  curl <url> [-I] : Fetch HTTP/HTTPS headers or web content",
            "",
            "Linux Shell Builtins & Utilities (/system/bin/sh):",
            "  ls, cat, mkdir, rm, touch, df, ps, top, grep, echo, |",
            "  Accessories: ESC, ^C (SIGINT), ▲ (Prev), ▼ (Next), |, /, ~, ."
        )
        helpLines.forEach {
            callbacks.onAddLog(TerminalLogItem(it, TerminalItemType.OUTPUT))
        }
    }

    fun showApps(filterQuery: String) {
        val apps = callbacks.getInstalledApps()
        val filtered = if (filterQuery.isBlank()) {
            apps
        } else {
            apps.filter { it.appLabel.contains(filterQuery, ignoreCase = true) }
        }

        if (filtered.isEmpty()) {
            callbacks.onAddLog(TerminalLogItem("No apps found matching '$filterQuery'.", TerminalItemType.ERROR))
            return
        }

        callbacks.onAddLog(TerminalLogItem("Installed Apps (${filtered.size}): [tap to open]", TerminalItemType.SUCCESS))

        for (app in filtered.sortedBy { it.appLabel.lowercase() }) {
            callbacks.onAddLog(TerminalLogItem("  > ${app.appLabel}", TerminalItemType.APP_ENTRY, app))
        }
    }

    private fun launchByName(appName: String) {
        val app = findApp(appName)
        if (app != null) {
            callbacks.onAddLog(TerminalLogItem("Launching ${app.appLabel}...", TerminalItemType.SUCCESS))
            callbacks.onLaunchApp(app)
        } else {
            callbacks.onAddLog(TerminalLogItem("App '$appName' not found. Type 'apps' to see all apps.", TerminalItemType.ERROR))
        }
    }

    private fun showAppInfoByName(appName: String) {
        val app = findApp(appName)
        if (app != null) {
            callbacks.onAddLog(TerminalLogItem("Opening info for ${app.appLabel}...", TerminalItemType.OUTPUT))
            openAppInfo(context, app.user, app.appPackage)
        } else {
            callbacks.onAddLog(TerminalLogItem("App '$appName' not found.", TerminalItemType.ERROR))
        }
    }

    private fun uninstallByName(appName: String) {
        val app = findApp(appName)
        if (app != null) {
            callbacks.onAddLog(TerminalLogItem("Uninstalling ${app.appLabel}...", TerminalItemType.OUTPUT))
            context.uninstall(app.appPackage)
        } else {
            callbacks.onAddLog(TerminalLogItem("App '$appName' not found.", TerminalItemType.ERROR))
        }
    }

    private fun handleTheme(args: List<String>) {
        if (args.isEmpty()) {
            val current = prefs.terminalTheme
            val currentTheme = TerminalTheme.fromId(current)
            val sb = StringBuilder("Current theme: ${currentTheme.displayName} (${currentTheme.id})\n\nAvailable themes (${TerminalTheme.ALL_THEMES.size}):\n")
            for (t in TerminalTheme.ALL_THEMES) {
                val mark = if (t.id == currentTheme.id) " [*]" else ""
                sb.append("  %-16s : %s%s\n".format(t.id, t.displayName, mark))
            }
            sb.append("\nUsage: theme <id> (e.g. 'theme onedark', 'theme catppuccin')")
            callbacks.onAddLog(TerminalLogItem(sb.toString().trim(), TerminalItemType.OUTPUT))
            return
        }
        val themeId = args[0].lowercase()
        val theme = TerminalTheme.fromId(themeId)
        prefs.terminalTheme = theme.id
        callbacks.onThemeChanged(theme)
        callbacks.onAddLog(TerminalLogItem("Theme set to ${theme.displayName} (${theme.id}).", TerminalItemType.SUCCESS))
    }

    private fun showBattery() {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val source = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Adapter"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Cable"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Qi"
            else -> if (isCharging) "Charging" else "Unplugged"
        }

        // 10-block visual battery gauge
        val filledBlocks = if (pct >= 0) (pct / 10).coerceIn(0, 10) else 0
        val emptyBlocks = 10 - filledBlocks
        val progressBar = "█".repeat(filledBlocks) + "░".repeat(emptyBlocks)

        // Voltage in millivolts and volts
        val voltageMv = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val voltageStr = if (voltageMv > 0) {
            val volts = voltageMv / 1000.0
            String.format(Locale.US, "%.2f V (%d mV)", volts, voltageMv)
        } else {
            "N/A"
        }

        // Temperature in tenths of Celsius
        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempStr = if (tempTenths > 0) {
            val tempC = tempTenths / 10.0
            val tempF = (tempC * 9.0 / 5.0) + 32.0
            String.format(Locale.US, "%.1f °C (%.1f °F)", tempC, tempF)
        } else {
            "N/A"
        }

        // Health
        val healthCode = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val healthStr = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat ⚠️"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead ⚠️"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage ⚠️"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold ❄️"
            else -> "Normal"
        }

        // Technology
        val tech = batteryStatus?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-poly"

        // Current in microamperes
        val currentNowMicro = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val chargeCounterMicro = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0

        // Remaining capacity in mAh
        val capacityMah = if (chargeCounterMicro > 0) chargeCounterMicro / 1000 else 0

        // Current in mA
        val currentMa = if (currentNowMicro != 0 && currentNowMicro != Int.MIN_VALUE && currentNowMicro != Int.MAX_VALUE) {
            kotlin.math.abs(currentNowMicro) / 1000
        } else {
            0
        }

        // Wattage: P = V * I
        val powerWatts = if (voltageMv > 0 && currentMa > 0) {
            (voltageMv / 1000.0) * (currentMa / 1000.0)
        } else {
            0.0
        }

        val rateStr = when {
            isFull -> "Full (Trickle)"
            isCharging && currentMa > 0 -> String.format(Locale.US, "+%d mA (%.2f W)", currentMa, powerWatts)
            !isCharging && currentMa > 0 -> String.format(Locale.US, "-%d mA (%.2f W)", currentMa, powerWatts)
            isCharging -> "Charging"
            else -> "Discharging"
        }

        // Remaining time calculation
        var timeRemainingStr: String
        if (isFull) {
            timeRemainingStr = "Fully Charged (100%)"
        } else if (isCharging) {
            var chargeTimeMs = -1L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    chargeTimeMs = bm?.computeChargeTimeRemaining() ?: -1L
                } catch (e: Exception) {
                    chargeTimeMs = -1L
                }
            }
            if (chargeTimeMs > 0) {
                val hours = chargeTimeMs / (1000 * 60 * 60)
                val mins = (chargeTimeMs / (1000 * 60)) % 60
                timeRemainingStr = if (hours > 0) "${hours}h ${mins}m until full" else "${mins}m until full"
            } else if (currentMa > 0 && scale > 0 && level >= 0) {
                val missingPct = (100 - pct).coerceAtLeast(0)
                val estimatedCapacityTotal = if (capacityMah > 0 && pct > 0) (capacityMah * 100) / pct else 4500
                val missingMah = (estimatedCapacityTotal * missingPct) / 100
                val estHours = missingMah.toDouble() / currentMa.toDouble()
                val totalMins = (estHours * 60).toInt().coerceIn(1, 600)
                val h = totalMins / 60
                val m = totalMins % 60
                timeRemainingStr = if (h > 0) "~${h}h ${m}m until full" else "~${m}m until full"
            } else {
                timeRemainingStr = "Charging..."
            }
        } else {
            // Discharging estimate
            if (capacityMah > 0 && currentMa > 0) {
                val estHours = capacityMah.toDouble() / currentMa.toDouble()
                val totalMins = (estHours * 60).toInt().coerceIn(1, 6000)
                val h = totalMins / 60
                val m = totalMins % 60
                timeRemainingStr = if (h > 0) "~${h}h ${m}m remaining" else "~${m}m remaining"
            } else {
                timeRemainingStr = "Normal discharge"
            }
        }

        callbacks.onAddLog(TerminalLogItem("── Battery Status ───────────────────", TerminalItemType.BANNER))
        callbacks.onAddLog(TerminalLogItem("Level:       [$progressBar] $pct%", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("State:       ${if (isFull) "Full" else if (isCharging) "Charging ($source)" else "Discharging"}", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("Rate:        $rateStr", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("Voltage:     $voltageStr", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("Remaining:   $timeRemainingStr", TerminalItemType.OUTPUT))
        if (capacityMah > 0) {
            callbacks.onAddLog(TerminalLogItem("Capacity:    ~$capacityMah mAh remaining", TerminalItemType.OUTPUT))
        }
        callbacks.onAddLog(TerminalLogItem("Temperature: $tempStr", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("Health:      $healthStr ($tech)", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("─────────────────────────────────────", TerminalItemType.BANNER))
    }

    private fun handleNotificationCommand(args: List<String>) {
        val history = synchronized(TerminalNotificationListenerService.recentHistory) {
            TerminalNotificationListenerService.recentHistory.toList()
        }

        if (args.isEmpty()) {
            val isEnabled = prefs.terminalNotificationsEnabled
            val isGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            val appsCount = prefs.terminalNotificationApps.size
            val appsStr = if (appsCount == 0) "All Apps" else "$appsCount selected apps"

            callbacks.onAddLog(TerminalLogItem("── Terminal Notifications ───────────", TerminalItemType.BANNER))
            callbacks.onAddLog(TerminalLogItem("Status:      ${if (isEnabled) "Enabled (Active)" else "Disabled"}", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("Permission:  ${if (isGranted) "Granted" else "Missing (tap Settings to enable)"}", if (isGranted) TerminalItemType.OUTPUT else TerminalItemType.ERROR))
            callbacks.onAddLog(TerminalLogItem("Filter:      $appsStr", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("Intercepted: ${history.size} recent notifications", TerminalItemType.OUTPUT))

            if (history.isNotEmpty()) {
                callbacks.onAddLog(TerminalLogItem("Recent Feed:", TerminalItemType.BANNER))
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val recentFive = history.takeLast(5)
                for (rec in recentFive) {
                    val tStr = timeFormat.format(Date(rec.time))
                    val body = if (rec.title.isNotBlank()) "${rec.title}: ${rec.text}" else rec.text
                    callbacks.onAddLog(TerminalLogItem("[$tStr 🔔 ${rec.appLabel}] $body", TerminalItemType.SUCCESS))
                }
            }

            callbacks.onAddLog(TerminalLogItem("Commands:", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("  notif on       : Enable terminal notifications", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("  notif off      : Disable terminal notifications", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("  notif history  : Show all captured notifications", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("  notif apps     : List allowed notification apps", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("  notif test     : Send a test notification", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("  notif settings : Open Android Notification Access", TerminalItemType.OUTPUT))
            callbacks.onAddLog(TerminalLogItem("─────────────────────────────────────", TerminalItemType.BANNER))
            return
        }

        when (args[0].lowercase()) {
            "on", "enable" -> {
                prefs.terminalNotificationsEnabled = true
                val isGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                if (!isGranted) {
                    callbacks.onAddLog(TerminalLogItem("Terminal notifications enabled, but Notification Access is not granted yet.", TerminalItemType.ERROR))
                    callbacks.onAddLog(TerminalLogItem("Opening Notification Access settings...", TerminalItemType.OUTPUT))
                    openNotificationListenerSettings()
                } else {
                    TerminalNotificationListenerService.ensureServiceBound(context)
                    callbacks.onAddLog(TerminalLogItem("Terminal notifications enabled! Incoming messages will appear in terminal.", TerminalItemType.SUCCESS))
                }
            }
            "off", "disable" -> {
                prefs.terminalNotificationsEnabled = false
                callbacks.onAddLog(TerminalLogItem("Terminal notifications disabled.", TerminalItemType.OUTPUT))
            }
            "history", "log", "recent" -> {
                if (history.isEmpty()) {
                    callbacks.onAddLog(TerminalLogItem("No notifications captured yet.", TerminalItemType.OUTPUT))
                } else {
                    callbacks.onAddLog(TerminalLogItem("── Notification History (${history.size}) ───────────", TerminalItemType.BANNER))
                    for (rec in history) {
                        callbacks.onAddLog(formatNotificationItem(rec.time, rec.appLabel, rec.packageName, rec.title, rec.text))
                    }
                    callbacks.onAddLog(TerminalLogItem("─────────────────────────────────────", TerminalItemType.BANNER))
                }
            }
            "clear" -> {
                synchronized(TerminalNotificationListenerService.recentHistory) {
                    TerminalNotificationListenerService.recentHistory.clear()
                }
                callbacks.onAddLog(TerminalLogItem("Notification history cleared.", TerminalItemType.SUCCESS))
            }
            "settings", "perm", "permission" -> {
                callbacks.onAddLog(TerminalLogItem("Opening Notification Access settings...", TerminalItemType.OUTPUT))
                openNotificationListenerSettings()
            }
            "apps" -> {
                val apps = prefs.terminalNotificationApps
                if (apps.isEmpty()) {
                    callbacks.onAddLog(TerminalLogItem("All apps are allowed. (Configure specific apps in Settings)", TerminalItemType.OUTPUT))
                } else {
                    callbacks.onAddLog(TerminalLogItem("Allowed Notification Apps (${apps.size}):", TerminalItemType.BANNER))
                    val pm = context.packageManager
                    for (pkg in apps) {
                        val label = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (e: Exception) {
                            pkg
                        }
                        callbacks.onAddLog(TerminalLogItem("  • $label ($pkg)", TerminalItemType.OUTPUT))
                    }
                }
            }
            "test" -> {
                val now = System.currentTimeMillis()
                callbacks.onAddLog(
                    formatNotificationItem(
                        now,
                        "WhatsApp",
                        "com.whatsapp",
                        "Naim",
                        "Hey baby! Terminal notifications now have vibrant color effects!"
                    )
                )
                callbacks.onAddLog(
                    formatNotificationItem(
                        now - 120_000,
                        "YouTube",
                        "com.google.android.youtube",
                        "Cyberpunk Studio",
                        "Retro Synthwave Beats - Live 24/7"
                    )
                )
                callbacks.onAddLog(
                    formatNotificationItem(
                        now - 300_000,
                        "Messages",
                        "com.google.android.apps.messaging",
                        "Google",
                        "Your verification code is 482910"
                    )
                )
            }
            else -> {
                callbacks.onAddLog(TerminalLogItem("Usage: notif [on|off|history|clear|apps|test|settings]", TerminalItemType.ERROR))
            }
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (err: Exception) {
                callbacks.onAddLog(TerminalLogItem("Could not open settings: ${err.message}", TerminalItemType.ERROR))
            }
        }
    }

    private fun showTimeAndDate() {
        val now = Date()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault())
        val tzFormat = SimpleDateFormat("z (ZZZZ)", Locale.getDefault())
        val uptimeHours = SystemClock.elapsedRealtime() / (1000 * 60 * 60)
        val uptimeMins = (SystemClock.elapsedRealtime() / (1000 * 60)) % 60

        callbacks.onAddLog(TerminalLogItem("Time:    ${timeFormat.format(now)}", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("Date:    ${dateFormat.format(now)}", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("Zone:    ${tzFormat.format(now)}", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("Uptime:  ${uptimeHours}h ${uptimeMins}m", TerminalItemType.OUTPUT))
    }

    private fun showDeviceInfo() {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVer = Build.VERSION.RELEASE
        val sdkInt = Build.VERSION.SDK_INT

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRam = memInfo.totalMem / (1024 * 1024)
        val availRam = memInfo.availMem / (1024 * 1024)

        callbacks.onAddLog(TerminalLogItem("--- System Information ---", TerminalItemType.SUCCESS))
        callbacks.onAddLog(TerminalLogItem("Host:      $manufacturer $model", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("OS:        Android $androidVer (API $sdkInt)", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("RAM:       ${totalRam - availRam}MB / ${totalRam}MB used", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("Shell:     /system/bin/sh", TerminalItemType.OUTPUT))
        callbacks.onAddLog(TerminalLogItem("CWD:       ${currentWorkingDir.absolutePath}", TerminalItemType.OUTPUT))
    }

    private fun handleAlias(args: List<String>) {
        val currentAliases = loadAliases().toMutableMap()
        if (args.isEmpty() || args[0].equals("list", ignoreCase = true)) {
            if (currentAliases.isEmpty()) {
                callbacks.onAddLog(TerminalLogItem("No aliases defined. Use: alias add <key> <app_or_command>", TerminalItemType.OUTPUT))
            } else {
                callbacks.onAddLog(TerminalLogItem("Aliases (${currentAliases.size}):", TerminalItemType.SUCCESS))
                for ((k, v) in currentAliases) {
                    callbacks.onAddLog(TerminalLogItem("  $k -> $v", TerminalItemType.OUTPUT))
                }
            }
            return
        }

        when (args[0].lowercase()) {
            "add" -> {
                if (args.size < 3) {
                    callbacks.onAddLog(TerminalLogItem("Usage: alias add <key> <command_or_app>", TerminalItemType.ERROR))
                    return
                }
                val key = args[1].lowercase()
                val target = args.subList(2, args.size).joinToString(" ")
                currentAliases[key] = target
                saveAliases(currentAliases)
                callbacks.onAddLog(TerminalLogItem("Alias added: $key -> $target", TerminalItemType.SUCCESS))
            }
            "rm", "remove", "delete" -> {
                if (args.size < 2) {
                    callbacks.onAddLog(TerminalLogItem("Usage: alias rm <key>", TerminalItemType.ERROR))
                    return
                }
                val key = args[1].lowercase()
                if (currentAliases.remove(key) != null) {
                    saveAliases(currentAliases)
                    callbacks.onAddLog(TerminalLogItem("Alias removed: $key", TerminalItemType.SUCCESS))
                } else {
                    callbacks.onAddLog(TerminalLogItem("Alias '$key' not found.", TerminalItemType.ERROR))
                }
            }
            else -> {
                callbacks.onAddLog(TerminalLogItem("Usage: alias [add <k> <v> | rm <k> | list]", TerminalItemType.ERROR))
            }
        }
    }

    private fun loadAliases(): Map<String, String> {
        val raw = prefs.terminalAliases
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.getString(k)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveAliases(aliases: Map<String, String>) {
        val json = JSONObject(aliases)
        prefs.terminalAliases = json.toString()
    }

    private fun handlePin(args: List<String>) {
        if (args.isEmpty()) {
            val pinned = getPinnedApps()
            val listStr = if (pinned.isEmpty()) "None" else pinned.joinToString(", ")
            callbacks.onAddLog(
                TerminalLogItem(
                    "Suggestion Bar Apps:\n" +
                    "  Current: $listStr\n\n" +
                    "Usage:\n" +
                    "  pin <app>        : Pin an app\n" +
                    "  pin rm <app>     : Unpin an app\n" +
                    "  pin list         : Show pinned apps\n" +
                    "  pin clear        : Clear all pinned apps\n" +
                    "  pin reset        : Reset to default apps\n" +
                    "Tip: Tap [+ Add] in suggestion bar to pick an app!",
                    TerminalItemType.OUTPUT
                )
            )
            return
        }

        when (args[0].lowercase()) {
            "list" -> {
                val pinned = getPinnedApps()
                if (pinned.isEmpty()) {
                    callbacks.onAddLog(TerminalLogItem("No apps pinned in suggestion bar.", TerminalItemType.OUTPUT))
                } else {
                    callbacks.onAddLog(TerminalLogItem("Pinned apps (${pinned.size}):\n" + pinned.joinToString(", "), TerminalItemType.OUTPUT))
                }
            }
            "clear" -> {
                savePinnedApps(emptyList())
                callbacks.onAddLog(TerminalLogItem("Cleared all pinned apps from suggestion bar.", TerminalItemType.SUCCESS))
            }
            "reset" -> {
                prefs.terminalPinnedApps = ""
                callbacks.onPinnedAppsChanged()
                callbacks.onAddLog(TerminalLogItem("Reset suggestion bar apps to default.", TerminalItemType.SUCCESS))
            }
            "rm", "remove", "delete" -> {
                val appName = args.drop(1).joinToString(" ")
                if (appName.isBlank()) {
                    callbacks.onAddLog(TerminalLogItem("Usage: pin rm <app_name>", TerminalItemType.ERROR))
                } else if (unpinApp(appName)) {
                    callbacks.onAddLog(TerminalLogItem("Unpinned '$appName' from suggestion bar.", TerminalItemType.SUCCESS))
                } else {
                    callbacks.onAddLog(TerminalLogItem("App '$appName' not found in suggestion bar.", TerminalItemType.ERROR))
                }
            }
            else -> {
                val appName = args.joinToString(" ")
                val app = findApp(appName)
                if (app != null) {
                    if (pinApp(app.appLabel)) {
                        callbacks.onAddLog(TerminalLogItem("Pinned '${app.appLabel}' to suggestion bar.", TerminalItemType.SUCCESS))
                    } else {
                        callbacks.onAddLog(TerminalLogItem("'${app.appLabel}' is already pinned.", TerminalItemType.OUTPUT))
                    }
                } else {
                    callbacks.onAddLog(TerminalLogItem("App not found: '$appName'", TerminalItemType.ERROR))
                }
            }
        }
    }

    private fun handleUnpin(args: List<String>) {
        if (args.isEmpty()) {
            callbacks.onAddLog(TerminalLogItem("Usage: unpin <app_name>", TerminalItemType.ERROR))
            return
        }
        val appName = args.joinToString(" ")
        if (unpinApp(appName)) {
            callbacks.onAddLog(TerminalLogItem("Unpinned '$appName' from suggestion bar.", TerminalItemType.SUCCESS))
        } else {
            callbacks.onAddLog(TerminalLogItem("App '$appName' not found in suggestion bar.", TerminalItemType.ERROR))
        }
    }

    fun getPinnedApps(): List<String> {
        val raw = prefs.terminalPinnedApps.trim()
        if (raw.isNotEmpty()) {
            return try {
                val array = JSONArray(raw)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                list
            } catch (e: Exception) {
                raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        val defaults = mutableListOf<String>()
        for (i in 1..8) {
            val name = prefs.getAppName(i)
            if (name.isNotBlank() && !defaults.contains(name)) {
                defaults.add(name)
            }
        }

        if (defaults.isNotEmpty()) return defaults

        val installed = callbacks.getInstalledApps()
        val popular = listOf("Phone", "Messages", "Chrome", "Camera", "Settings", "Gallery", "Files", "YouTube")
        for (pop in popular) {
            val found = installed.firstOrNull { it.appLabel.equals(pop, ignoreCase = true) }
            if (found != null && !defaults.contains(found.appLabel)) {
                defaults.add(found.appLabel)
            }
            if (defaults.size >= 5) break
        }

        if (defaults.isEmpty()) {
            defaults.addAll(installed.take(4).map { it.appLabel })
        }

        return defaults
    }

    fun savePinnedApps(apps: List<String>) {
        val array = JSONArray(apps.distinct())
        prefs.terminalPinnedApps = array.toString()
        callbacks.onPinnedAppsChanged()
    }

    fun pinApp(appName: String): Boolean {
        val app = findApp(appName)
        val nameToPin = app?.appLabel ?: appName
        val current = getPinnedApps().toMutableList()
        val exists = current.any { it.equals(nameToPin, ignoreCase = true) }
        if (!exists) {
            current.add(nameToPin)
            savePinnedApps(current)
            return true
        }
        return false
    }

    fun unpinApp(appName: String): Boolean {
        val current = getPinnedApps().toMutableList()
        val match = current.firstOrNull { it.equals(appName, ignoreCase = true) }
        return if (match != null) {
            current.remove(match)
            savePinnedApps(current)
            true
        } else {
            false
        }
    }

    fun findApp(query: String): AppModel.App? {
        val q = query.trim().lowercase()
        val apps = callbacks.getInstalledApps()

        val exact = apps.firstOrNull { it.appLabel.equals(q, ignoreCase = true) }
        if (exact != null) return exact

        val startsWith = apps.firstOrNull { it.appLabel.lowercase().startsWith(q) }
        if (startsWith != null) return startsWith

        val contains = apps.firstOrNull { it.appLabel.lowercase().contains(q) }
        if (contains != null) return contains

        val packageMatch = apps.firstOrNull { it.appPackage.lowercase().contains(q) }
        return packageMatch
    }

    fun getSuggestions(query: String): List<TerminalSuggestion> {
        val q = query.trim().lowercase()
        val list = mutableListOf<TerminalSuggestion>()

        val commands = listOf(
            "help", "apps", "call", "whatsapp", "chrome",
            "cd", "pwd", "ls", "open", "info", "uninstall",
            "history", "pin", "unpin", "theme", "battery", "notif", "time", "date",
            "device", "alias", "search", "settings", "mode", "clear"
        )

        if (q.isEmpty()) {
            val pinned = getPinnedApps()
            for (appName in pinned) {
                list.add(
                    TerminalSuggestion(
                        displayText = appName,
                        commandToFill = appName,
                        executeImmediately = true,
                        isPinnedApp = true
                    )
                )
            }

            list.add(
                TerminalSuggestion(
                    displayText = "+ Add",
                    commandToFill = "pin ",
                    executeImmediately = false,
                    isAction = true
                )
            )

            list.add(TerminalSuggestion("help", "help", executeImmediately = true))
            list.add(TerminalSuggestion("apps", "apps", executeImmediately = true))
            list.add(TerminalSuggestion("ls", "ls", executeImmediately = true))
            list.add(TerminalSuggestion("pwd", "pwd", executeImmediately = true))
            list.add(TerminalSuggestion("battery", "battery", executeImmediately = true))
            list.add(TerminalSuggestion("clear", "clear", executeImmediately = true))

            return list
        }

        if (q.startsWith("pin ") || q.startsWith("unpin ")) {
            val isUnpin = q.startsWith("unpin ")
            val prefix = if (isUnpin) "unpin " else "pin "
            val sub = q.removePrefix(prefix).trim()

            if (isUnpin) {
                val pinned = getPinnedApps()
                val matched = if (sub.isEmpty()) pinned else pinned.filter { it.lowercase().contains(sub) }
                for (app in matched) {
                    list.add(TerminalSuggestion("unpin $app", "unpin $app", executeImmediately = true))
                }
            } else {
                if (sub.isEmpty() || "rm".startsWith(sub)) list.add(TerminalSuggestion("pin rm ", "pin rm ", executeImmediately = false))
                if (sub.isEmpty() || "list".startsWith(sub)) list.add(TerminalSuggestion("pin list", "pin list", executeImmediately = true))
                if (sub.isEmpty() || "clear".startsWith(sub)) list.add(TerminalSuggestion("pin clear", "pin clear", executeImmediately = true))
                if (sub.isEmpty() || "reset".startsWith(sub)) list.add(TerminalSuggestion("pin reset", "pin reset", executeImmediately = true))

                val apps = callbacks.getInstalledApps()
                val matchedApps = if (sub.isEmpty()) apps.take(8) else apps.filter { it.appLabel.lowercase().contains(sub) }.take(8)
                for (app in matchedApps) {
                    list.add(TerminalSuggestion("pin ${app.appLabel}", "pin ${app.appLabel}", executeImmediately = true))
                }
            }
            return list
        }

        if (q.startsWith("theme ")) {
            val sub = q.removePrefix("theme ").trim()
            val themeIds = TerminalTheme.ALL_THEMES.map { it.id }
            for (t in themeIds) {
                if (t.startsWith(sub)) {
                    list.add(TerminalSuggestion("theme $t", "theme $t", executeImmediately = true))
                }
            }
            return list
        }

        if (q.startsWith("whatsapp") || q.startsWith("wa ")) {
            val isWa = q.startsWith("wa ")
            val sub = if (isWa) q.removePrefix("wa ").trim() else q.removePrefix("whatsapp").trim()
            val waPrefix = if (isWa) "wa" else "whatsapp"
            val waSubCommands = listOf(
                "call ", "message ", "msg "
            )
            for (sc in waSubCommands) {
                if (sub.isEmpty() || sc.startsWith(sub)) {
                    list.add(
                        TerminalSuggestion(
                            displayText = "$waPrefix $sc",
                            commandToFill = "$waPrefix $sc",
                            executeImmediately = false
                        )
                    )
                }
            }
            // Also suggest contacts or pinned if available
            return list
        }

        if (q.startsWith("call ") || q.startsWith("dial ")) {
            // Suggest phone dialer or popular numbers
            return list
        }

        for (cmd in commands) {
            if (cmd.startsWith(q)) {
                list.add(TerminalSuggestion(cmd, cmd, executeImmediately = false))
            }
        }

        val appQuery = if (q.startsWith("open ")) q.removePrefix("open ") else q
        val apps = callbacks.getInstalledApps()
        val matchedApps = apps.filter { it.appLabel.lowercase().contains(appQuery) }.take(6)

        for (app in matchedApps) {
            list.add(
                TerminalSuggestion(
                    displayText = app.appLabel,
                    commandToFill = app.appLabel,
                    executeImmediately = true
                )
            )
        }

        return list
    }

    private fun getBatteryPercentage(): Int {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 100
    }
}
