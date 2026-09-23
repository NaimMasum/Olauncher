package app.olauncher.ui.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TermuxResultReceiver : BroadcastReceiver() {

    interface Listener {
        fun onTermuxResult(
            stdout: String?,
            stderr: String?,
            exitCode: Int,
            errCode: Int,
            errMsg: String?
        )
    }

    companion object {
        const val ACTION_TERMUX_RESULT = "app.olauncher.TERMUX_RESULT"

        const val EXTRA_STDOUT = "com.termux.RUN_COMMAND.RESULT.STDOUT"
        const val EXTRA_STDERR = "com.termux.RUN_COMMAND.RESULT.STDERR"
        const val EXTRA_EXIT_CODE = "com.termux.RUN_COMMAND.RESULT.EXIT_CODE"
        const val EXTRA_ERRCODE = "com.termux.RUN_COMMAND.RESULT.ERRCODE"
        const val EXTRA_ERRMSG = "com.termux.RUN_COMMAND.RESULT.ERRLMSG"

        private var activeListener: Listener? = null

        fun setListener(listener: Listener?) {
            activeListener = listener
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TERMUX_RESULT) {
            val stdout = intent.getStringExtra(EXTRA_STDOUT)
            val stderr = intent.getStringExtra(EXTRA_STDERR)
            val exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, 0)
            val errCode = intent.getIntExtra(EXTRA_ERRCODE, 0)
            val errMsg = intent.getStringExtra(EXTRA_ERRMSG)

            activeListener?.onTermuxResult(stdout, stderr, exitCode, errCode, errMsg)
        }
    }
}
