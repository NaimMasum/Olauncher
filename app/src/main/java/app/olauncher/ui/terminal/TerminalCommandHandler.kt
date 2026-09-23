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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
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
    private val termuxBridge = TermuxBridge(context)

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
        banner.add(TerminalLogItem("olunch_term Linux CLI (Termux Engine)", TerminalItemType.BANNER))
        banner.add(TerminalLogItem("Type 'help' for commands, type app name, or run shell tools.", TerminalItemType.OUTPUT))

        val batteryLevel = getBatteryPercentage()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val appsCount = callbacks.getInstalledApps().size
        val termuxConnected = termuxBridge.isTermuxInstalled()
        val engineStr = if (termuxConnected) {
            if (prefs.terminalTermuxMode) "Engine: Termux IPC (active)" else "Engine: Android sh (Termux ready)"
        } else {
            "Engine: Android sh"
        }
        banner.add(TerminalLogItem("Dir: ${getDisplayPath()} | Battery: $batteryLevel% | Apps: $appsCount", TerminalItemType.BANNER))
        banner.add(TerminalLogItem(engineStr, TerminalItemType.BANNER))
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
            "termux" -> handleTermuxCommand(args, resolvedInput)
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

                // Run via Termux IPC if Termux mode is enabled or if command is Termux-specific (like pkg, apt)
                if ((prefs.terminalTermuxMode || isTermuxSpecificCommand(command)) && termuxBridge.isTermuxInstalled()) {
                    executeTermuxCommand(resolvedInput)
                } else {
                    // Run as real Linux shell process!
                    executeShellCommand(resolvedInput)
                }
            }
        }
    }

    private fun isTermuxSpecificCommand(cmd: String): Boolean {
        val termuxCommands = setOf(
            "pkg", "apt", "apt-get", "apt-cache", "apt-config", "apt-mark",
            "dpkg", "dpkg-deb", "dpkg-query", "termux-info", "termux-open",
            "termux-reload-settings", "termux-setup-storage", "termux-wake-lock", "termux-wake-unlock"
        )
        return termuxCommands.contains(cmd)
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

    private fun handleTermuxCommand(args: List<String>, fullInput: String) {
        if (args.isEmpty()) {
            val installed = termuxBridge.isTermuxInstalled()
            val mode = if (prefs.terminalTermuxMode) "ENABLED (all shell commands use Termux)" else "DISABLED (default /system/bin/sh)"
            callbacks.onAddLog(
                TerminalLogItem(
                    "Termux Companion & IPC Integration:\n" +
                    "  Status       : ${if (installed) "Connected / Installed" else "Not Installed"}\n" +
                    "  Termux Mode  : $mode\n\n" +
                    "Commands:\n" +
                    "  termux <cmd>          : Run command inside Termux (e.g. termux pkg update)\n" +
                    "  termux mode [on|off]  : Enable/disable routing all commands to Termux\n" +
                    "  termux status         : Check detailed status\n" +
                    "  termux open           : Open Termux GUI terminal\n" +
                    "  termux setup          : Setup guide for external apps",
                    TerminalItemType.OUTPUT
                )
            )
            return
        }

        when (args[0].lowercase()) {
            "status" -> {
                val installed = termuxBridge.isTermuxInstalled()
                callbacks.onAddLog(TerminalLogItem("Termux Status Check:", TerminalItemType.SUCCESS))
                callbacks.onAddLog(TerminalLogItem("  Package (com.termux) : ${if (installed) "Installed (OK)" else "Not Found"}", TerminalItemType.OUTPUT))
                callbacks.onAddLog(TerminalLogItem("  Service Component    : com.termux/.app.RunCommandService", TerminalItemType.OUTPUT))
                callbacks.onAddLog(TerminalLogItem("  Termux Mode Routing  : ${if (prefs.terminalTermuxMode) "ON" else "OFF"}", TerminalItemType.OUTPUT))
                callbacks.onAddLog(TerminalLogItem("  Working Directory    : ${currentWorkingDir.absolutePath}", TerminalItemType.OUTPUT))
            }
            "mode" -> {
                if (args.size < 2) {
                    val current = if (prefs.terminalTermuxMode) "ON" else "OFF"
                    callbacks.onAddLog(TerminalLogItem("Termux mode is currently $current. Use 'termux mode on' or 'termux mode off'.", TerminalItemType.OUTPUT))
                    return
                }
                when (args[1].lowercase()) {
                    "on", "enable", "1", "true" -> {
                        if (!termuxBridge.isTermuxInstalled()) {
                            callbacks.onAddLog(TerminalLogItem("Warning: Termux is not currently installed. Commands will fall back to local shell until Termux is installed.", TerminalItemType.ERROR))
                        }
                        prefs.terminalTermuxMode = true
                        callbacks.onAddLog(TerminalLogItem("Termux mode ENABLED. Shell commands will now route through Termux engine.", TerminalItemType.SUCCESS))
                    }
                    "off", "disable", "0", "false" -> {
                        prefs.terminalTermuxMode = false
                        callbacks.onAddLog(TerminalLogItem("Termux mode DISABLED. Reverted to standard Android shell (/system/bin/sh).", TerminalItemType.SUCCESS))
                    }
                    else -> {
                        callbacks.onAddLog(TerminalLogItem("Usage: termux mode [on|off]", TerminalItemType.ERROR))
                    }
                }
            }
            "open" -> {
                if (termuxBridge.isTermuxInstalled()) {
                    callbacks.onAddLog(TerminalLogItem("Launching Termux application...", TerminalItemType.SUCCESS))
                    termuxBridge.openTermux()
                } else {
                    callbacks.onAddLog(TerminalLogItem("Termux is not installed on this device.", TerminalItemType.ERROR))
                }
            }
            "setup" -> {
                callbacks.onAddLog(
                    TerminalLogItem(
                        "Termux External App Execution Setup:\n" +
                        "1. Open Termux app.\n" +
                        "2. Run: echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                        "3. Run: termux-reload-settings\n" +
                        "4. Grant background execution permissions if prompted.\n" +
                        "5. Now commands run headless in Olauncher without opening Termux!",
                        TerminalItemType.OUTPUT
                    )
                )
            }
            "repo" -> handleTermuxRepo(args)
            else -> {
                val cmdToRun = fullInput.trim().substringAfter("termux").trim()
                if (cmdToRun.isEmpty()) {
                    callbacks.onAddLog(TerminalLogItem("Usage: termux <cmd>", TerminalItemType.ERROR))
                } else {
                    executeTermuxCommand(cmdToRun)
                }
            }
        }
    }

    private fun handleTermuxRepo(args: List<String>) {
        if (!termuxBridge.isTermuxInstalled()) {
            callbacks.onAddLog(TerminalLogItem("Termux is not installed.", TerminalItemType.ERROR))
            return
        }
        val sub = if (args.size > 1) args[1].lowercase() else "status"
        when (sub) {
            "status" -> {
                callbacks.onAddLog(TerminalLogItem("Checking Termux repository configuration...", TerminalItemType.OUTPUT))
                executeTermuxCommand("cat /data/data/com.termux/files/usr/etc/apt/sources.list")
            }
            "fix", "reset", "default" -> {
                callbacks.onAddLog(TerminalLogItem("Configuring verified Termux mirrors and updating repositories...", TerminalItemType.OUTPUT))
                val fixScript = "mkdir -p /data/data/com.termux/files/usr/etc/apt /data/data/com.termux/files/usr/etc/termux; " +
                        "echo 'deb https://packages.termux.dev/apt/termux-main stable main' > /data/data/com.termux/files/usr/etc/apt/sources.list; " +
                        "echo 'deb https://packages-cf.termux.dev/apt/termux-main stable main' >> /data/data/com.termux/files/usr/etc/apt/sources.list; " +
                        "echo 'deb https://grimler.se/termux/termux-main stable main' >> /data/data/com.termux/files/usr/etc/apt/sources.list; " +
                        "rm -f /data/data/com.termux/files/usr/etc/termux/chosen_mirrors; " +
                        "ln -s /data/data/com.termux/files/usr/etc/termux/mirrors/europe /data/data/com.termux/files/usr/etc/termux/chosen_mirrors 2>/dev/null; " +
                        "echo 'Mirrors configured. Running pkg update...'; pkg update -y"
                executeTermuxCommand(fixScript)
            }
            "cf", "cloudflare" -> {
                callbacks.onAddLog(TerminalLogItem("Setting Cloudflare mirror...", TerminalItemType.OUTPUT))
                val cfScript = "echo 'deb https://packages-cf.termux.dev/apt/termux-main stable main' > /data/data/com.termux/files/usr/etc/apt/sources.list; pkg update -y"
                executeTermuxCommand(cfScript)
            }
            "grimler" -> {
                callbacks.onAddLog(TerminalLogItem("Setting Grimler (EU) mirror...", TerminalItemType.OUTPUT))
                val gScript = "echo 'deb https://grimler.se/termux/termux-main stable main' > /data/data/com.termux/files/usr/etc/apt/sources.list; pkg update -y"
                executeTermuxCommand(gScript)
            }
            else -> {
                callbacks.onAddLog(TerminalLogItem("Usage: termux repo [status|fix|cf|grimler]", TerminalItemType.ERROR))
            }
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
                conn.setRequestProperty("User-Agent", "olunch_term/1.0 (Linux; Android CLI)")

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

    private fun executeTermuxCommand(cmd: String) {
        if (!termuxBridge.isTermuxInstalled()) {
            callbacks.onAddLog(TerminalLogItem("Termux is not installed. Falling back to local Android shell...", TerminalItemType.ERROR))
            executeShellCommand(cmd)
            return
        }

        callbacks.onRunningStateChanged(true)
        callbacks.onAddLog(TerminalLogItem("[termux] $cmd", TerminalItemType.OUTPUT))

        val dispatched = termuxBridge.execute(cmd, currentWorkingDir.absolutePath) { stdout, stderr, exitCode, errCode, errMsg ->
            callbacks.onRunningStateChanged(false)
            if (!stdout.isNullOrEmpty()) {
                val lines = stdout.trimEnd().lines()
                lines.forEach { line ->
                    callbacks.onAddLog(TerminalLogItem(line, TerminalItemType.OUTPUT))
                }
            }
            if (!stderr.isNullOrEmpty()) {
                val lines = stderr.trimEnd().lines()
                lines.forEach { line ->
                    callbacks.onAddLog(TerminalLogItem(line, TerminalItemType.ERROR))
                }
            }
            if (errCode != 0 && !errMsg.isNullOrEmpty()) {
                callbacks.onAddLog(TerminalLogItem("[Termux Error] $errMsg", TerminalItemType.ERROR))
                if (errMsg.contains("external", ignoreCase = true) || errMsg.contains("permission", ignoreCase = true)) {
                    callbacks.onAddLog(TerminalLogItem("Hint: Run 'termux setup' to enable external apps execution.", TerminalItemType.OUTPUT))
                }
            } else if (exitCode != 0 && stdout.isNullOrEmpty() && stderr.isNullOrEmpty()) {
                callbacks.onAddLog(TerminalLogItem("Termux exited with code $exitCode", TerminalItemType.ERROR))
            }
        }

        if (!dispatched) {
            callbacks.onRunningStateChanged(false)
            callbacks.onAddLog(TerminalLogItem("Termux service unreachable. Falling back to local shell...", TerminalItemType.ERROR))
            executeShellCommand(cmd)
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
            "Terminal Launcher & Shell (Termux CLI):",
            "  <app_name>      : Launch app directly (e.g. 'chrome', 'camera')",
            "  apps [query]    : List installed applications (tap to open)",
            "  cd <path>       : Change working directory (~, .., /sdcard)",
            "  pwd             : Print working directory",
            "  history         : View command history (or use ▲ / ▼ keys)",
            "  pin <app>       : Pin app to suggestion bar (rm | list | clear)",
            "  unpin <app>     : Remove app from suggestion bar",
            "  theme [name]    : Switch theme (dracula, synthwave, tokyo, nord,",
            "                    gruvbox, solarized, cyberpunk, green, amber, red...)",
            "  battery         : Battery level and status",
            "  device, uname   : Device hardware info & RAM",
            "  settings        : Open launcher settings",
            "  mode [gui|cli]  : Switch between Terminal and GUI mode",
            "  clear, cls      : Clear terminal screen",
            "Networking & Internet:",
            "  ping <host> [n] : Ping host with latency metrics (e.g. 'ping google.com')",
            "  net, ip         : Inspect network connection, Wi-Fi/Cellular, DNS & status",
            "  curl <url> [-I] : Fetch HTTP/HTTPS headers or web content",
            "",
            "Termux Companion & Package Management:",
            "  pkg <command>   : Package manager (install, update, search, list-installed)",
            "  termux repo fix : Fix/reset Termux mirrors to stable repositories",
            "  termux mode     : Toggle auto-routing all shell commands to Termux",
            "  termux status   : Check Termux connection & execution status",
            "  termux open     : Launch Termux app interface",
            "  termux setup    : Guide to configure external app execution",
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
            val available = TerminalTheme.ALL_THEMES.joinToString(", ") { it.id }
            callbacks.onAddLog(TerminalLogItem("Current theme: $current\nAvailable: $available", TerminalItemType.OUTPUT))
            return
        }
        val themeId = args[0].lowercase()
        val theme = TerminalTheme.fromId(themeId)
        prefs.terminalTheme = theme.id
        callbacks.onThemeChanged(theme)
        callbacks.onAddLog(TerminalLogItem("Theme set to ${theme.displayName}.", TerminalItemType.SUCCESS))
    }

    private fun showBattery() {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = (level * 100 / scale.toFloat()).toInt()

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC

        val source = when {
            usbCharge -> " (USB)"
            acCharge -> " (AC)"
            else -> ""
        }

        callbacks.onAddLog(
            TerminalLogItem(
                "Battery: $pct% | ${if (isCharging) "Charging$source" else "Discharging"}",
                TerminalItemType.OUTPUT
            )
        )
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
            "help", "apps", "cd", "pwd", "ls", "open", "info", "uninstall",
            "history", "pin", "unpin", "theme", "termux", "battery", "time", "date",
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
            list.add(TerminalSuggestion("termux", "termux", executeImmediately = true))
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

        if (q.startsWith("termux")) {
            val sub = q.removePrefix("termux").trim()
            val termuxSubCommands = listOf(
                "status", "mode on", "mode off", "open", "setup",
                "pkg update", "pkg install ", "ls -la", "python "
            )
            for (sc in termuxSubCommands) {
                if (sub.isEmpty() || sc.startsWith(sub)) {
                    val isAction = sc.endsWith(" ")
                    list.add(
                        TerminalSuggestion(
                            displayText = "termux $sc",
                            commandToFill = "termux $sc",
                            executeImmediately = !isAction
                        )
                    )
                }
            }
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
