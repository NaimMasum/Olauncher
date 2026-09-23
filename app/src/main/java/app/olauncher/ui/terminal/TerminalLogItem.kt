package app.olauncher.ui.terminal

import app.olauncher.data.AppModel

enum class TerminalItemType {
    BANNER,
    COMMAND_ECHO,
    OUTPUT,
    APP_ENTRY,
    ERROR,
    SUCCESS,
    NOTIFICATION
}

data class TerminalLogItem(
    val text: CharSequence,
    val type: TerminalItemType = TerminalItemType.OUTPUT,
    val appModel: AppModel.App? = null
)
