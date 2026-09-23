package app.olauncher.ui.terminal.ssh

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.olauncher.data.Prefs
import app.olauncher.ui.terminal.TerminalItemType
import app.olauncher.ui.terminal.TerminalLogItem
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.Executors

class SshTerminalManager(
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onOutput(line: String, type: TerminalItemType = TerminalItemType.OUTPUT)
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
        fun onStatusChanged(statusText: String)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellInputStream: InputStream? = null
    private var shellOutputStream: OutputStream? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var isConnecting: Boolean = false

    fun connect(
        host: String = prefs.terminalSshHost.ifBlank { "127.0.0.1" },
        port: Int = if (prefs.terminalSshPort > 0) prefs.terminalSshPort else 8022,
        user: String = prefs.terminalSshUser.ifBlank { "u0_a0" },
        password: String = prefs.terminalSshPass
    ) {
        if (isConnected || isConnecting) {
            postOutput("SSH already connected or connecting...", TerminalItemType.OUTPUT)
            return
        }

        isConnecting = true
        postOutput("Connecting to SSH at $user@$host:$port...", TerminalItemType.OUTPUT)
        callbacks.onStatusChanged("[connecting]")

        executor.execute {
            try {
                val jsch = JSch()
                val session = jsch.getSession(user, host, port)
                if (password.isNotEmpty()) {
                    session.setPassword(password)
                }

                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                config["PreferredAuthentications"] = "password,keyboard-interactive,publickey"
                session.setConfig(config)
                session.timeout = 10000

                session.connect()
                jschSession = session

                val channel = session.openChannel("shell") as ChannelShell
                channel.setPtyType("xterm-256color")
                channel.setPtySize(80, 24, 640, 480)

                val inStream = channel.inputStream
                val outStream = channel.outputStream

                channel.connect(5000)

                shellChannel = channel
                shellInputStream = inStream
                shellOutputStream = outStream

                isConnected = true
                isConnecting = false

                mainHandler.post {
                    callbacks.onConnected()
                    callbacks.onStatusChanged("[ssh:$user@$host:$port]")
                    callbacks.onOutput("=== Connected to Termux SSH session ===", TerminalItemType.SUCCESS)
                    callbacks.onOutput("Interactive Linux environment ready. Type commands or 'ssh exit' to disconnect.", TerminalItemType.OUTPUT)
                }

                // Background reading loop
                val buffer = ByteArray(2048)
                val lineBuilder = StringBuilder()

                while (isConnected && channel.isConnected) {
                    val bytesRead = inStream.read(buffer)
                    if (bytesRead == -1) break

                    val textChunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    for (ch in textChunk) {
                        if (ch == '\n') {
                            val line = lineBuilder.toString()
                            lineBuilder.setLength(0)
                            // Clean ANSI escape codes for cleaner display in list view
                            val cleanLine = stripAnsi(line).replace("\r", "")
                            if (cleanLine.isNotBlank()) {
                                mainHandler.post {
                                    callbacks.onOutput(cleanLine, TerminalItemType.OUTPUT)
                                }
                            }
                        } else {
                            lineBuilder.append(ch)
                        }
                    }
                }

                if (lineBuilder.isNotEmpty()) {
                    val cleanLine = stripAnsi(lineBuilder.toString()).replace("\r", "").trim()
                    if (cleanLine.isNotBlank()) {
                        mainHandler.post {
                            callbacks.onOutput(cleanLine, TerminalItemType.OUTPUT)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("SshTerminalManager", "SSH Connection error", e)
                val errMsg = e.message ?: e.javaClass.simpleName
                mainHandler.post {
                    callbacks.onError("SSH connection failed: $errMsg")
                    callbacks.onOutput(
                        "Termux SSH Guide:\n" +
                        "1. In Termux, run: pkg install openssh\n" +
                        "2. Set password:   passwd\n" +
                        "3. Find username:  whoami\n" +
                        "4. Start daemon:   sshd\n" +
                        "5. In Olauncher:   ssh connect <user> <password> [host] [port]\n" +
                        "   (Example: ssh connect $(whoami) mypassword 127.0.0.1 8022)",
                        TerminalItemType.OUTPUT
                    )
                }
            } finally {
                disconnectInternal()
            }
        }
    }

    fun sendCommand(cmd: String) {
        if (!isConnected || shellOutputStream == null) {
            postOutput("Not connected to SSH. Run 'ssh connect' first.", TerminalItemType.ERROR)
            return
        }

        executor.execute {
            try {
                val fullCmd = if (cmd.endsWith("\n")) cmd else "$cmd\n"
                shellOutputStream?.write(fullCmd.toByteArray(Charsets.UTF_8))
                shellOutputStream?.flush()
            } catch (e: Exception) {
                Log.e("SshTerminalManager", "Failed to write to SSH stream", e)
                postOutput("SSH Send error: ${e.message}", TerminalItemType.ERROR)
            }
        }
    }

    fun sendCtrlC() {
        executor.execute {
            try {
                shellOutputStream?.write(byteArrayOf(0x03))
                shellOutputStream?.flush()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun disconnect() {
        executor.execute {
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        val wasConnected = isConnected
        isConnected = false
        isConnecting = false

        try {
            shellInputStream?.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            shellOutputStream?.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            shellChannel?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        try {
            jschSession?.disconnect()
        } catch (e: Exception) {
            // ignore
        }

        shellInputStream = null
        shellOutputStream = null
        shellChannel = null
        jschSession = null

        if (wasConnected) {
            mainHandler.post {
                callbacks.onDisconnected()
                callbacks.onStatusChanged("")
                callbacks.onOutput("SSH session disconnected.", TerminalItemType.OUTPUT)
            }
        }
    }

    private fun postOutput(msg: String, type: TerminalItemType) {
        mainHandler.post {
            callbacks.onOutput(msg, type)
        }
    }

    private fun stripAnsi(str: String): String {
        return str.replace("\u001B\\[[;?0-9]*[a-zA-Z]".toRegex(), "")
    }
}
