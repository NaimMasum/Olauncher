package app.olauncher.ui.terminal

import android.graphics.Color

data class TerminalTheme(
    val id: String,
    val displayName: String,
    val bgColor: Int,
    val textColor: Int,
    val promptColor: Int,
    val secondaryColor: Int,
    val errorColor: Int,
    val accentColor: Int = promptColor,
    val pathColor: Int = secondaryColor,
    val commandColor: Int = promptColor
) {
    companion object {
        // Classic Monochromes
        val GREEN = TerminalTheme(
            id = "green",
            displayName = "Matrix Green",
            bgColor = Color.parseColor("#0A0F0A"),
            textColor = Color.parseColor("#00FF66"),
            promptColor = Color.parseColor("#33FF88"),
            secondaryColor = Color.parseColor("#009933"),
            errorColor = Color.parseColor("#FF4D4D"),
            accentColor = Color.parseColor("#33FF88"),
            pathColor = Color.parseColor("#66FFAA"),
            commandColor = Color.parseColor("#00FF66")
        )

        val AMBER = TerminalTheme(
            id = "amber",
            displayName = "Retro Amber",
            bgColor = Color.parseColor("#120E06"),
            textColor = Color.parseColor("#FFB000"),
            promptColor = Color.parseColor("#FFCC33"),
            secondaryColor = Color.parseColor("#B37D00"),
            errorColor = Color.parseColor("#FF4D4D"),
            accentColor = Color.parseColor("#FFCC33"),
            pathColor = Color.parseColor("#FFE066"),
            commandColor = Color.parseColor("#FFB000")
        )

        val CYAN = TerminalTheme(
            id = "cyan",
            displayName = "Cyberpunk Cyan",
            bgColor = Color.parseColor("#061016"),
            textColor = Color.parseColor("#00F0FF"),
            promptColor = Color.parseColor("#66F5FF"),
            secondaryColor = Color.parseColor("#0099A6"),
            errorColor = Color.parseColor("#FF4D4D"),
            accentColor = Color.parseColor("#66F5FF"),
            pathColor = Color.parseColor("#80FFFF"),
            commandColor = Color.parseColor("#00F0FF")
        )

        val WHITE = TerminalTheme(
            id = "white",
            displayName = "Classic Monokai",
            bgColor = Color.parseColor("#141414"),
            textColor = Color.parseColor("#ECEFF4"),
            promptColor = Color.parseColor("#FFFFFF"),
            secondaryColor = Color.parseColor("#888888"),
            errorColor = Color.parseColor("#FF5555"),
            accentColor = Color.parseColor("#A6E22E"),
            pathColor = Color.parseColor("#66D9EF"),
            commandColor = Color.parseColor("#FD971F")
        )

        val RED = TerminalTheme(
            id = "red",
            displayName = "Hacker Red",
            bgColor = Color.parseColor("#140808"),
            textColor = Color.parseColor("#FF3344"),
            promptColor = Color.parseColor("#FF6677"),
            secondaryColor = Color.parseColor("#B31B2A"),
            errorColor = Color.parseColor("#FFAAAA"),
            accentColor = Color.parseColor("#FF6677"),
            pathColor = Color.parseColor("#FF8899"),
            commandColor = Color.parseColor("#FF3344")
        )

        // Mixed Color Themes
        val DRACULA = TerminalTheme(
            id = "dracula",
            displayName = "Dracula (Mixed)",
            bgColor = Color.parseColor("#282A36"),
            textColor = Color.parseColor("#F8F8F2"),
            promptColor = Color.parseColor("#50FA7B"),
            secondaryColor = Color.parseColor("#6272A4"),
            errorColor = Color.parseColor("#FF5555"),
            accentColor = Color.parseColor("#FF79C6"),
            pathColor = Color.parseColor("#8BE9FD"),
            commandColor = Color.parseColor("#F1FA8C")
        )

        val SYNTHWAVE = TerminalTheme(
            id = "synthwave",
            displayName = "Synthwave '84 (Mixed)",
            bgColor = Color.parseColor("#1A102F"),
            textColor = Color.parseColor("#00F0FF"),
            promptColor = Color.parseColor("#FF2A85"),
            secondaryColor = Color.parseColor("#8C71B8"),
            errorColor = Color.parseColor("#FE4450"),
            accentColor = Color.parseColor("#B042FF"),
            pathColor = Color.parseColor("#FFE600"),
            commandColor = Color.parseColor("#FF7EDB")
        )

        val TOKYO = TerminalTheme(
            id = "tokyo",
            displayName = "Tokyo Night (Mixed)",
            bgColor = Color.parseColor("#1A1B26"),
            textColor = Color.parseColor("#A9B1D6"),
            promptColor = Color.parseColor("#7DCFFF"),
            secondaryColor = Color.parseColor("#565F89"),
            errorColor = Color.parseColor("#F7768E"),
            accentColor = Color.parseColor("#BB9AF7"),
            pathColor = Color.parseColor("#FF9E64"),
            commandColor = Color.parseColor("#9ECE6A")
        )

        val NORD = TerminalTheme(
            id = "nord",
            displayName = "Nord Frost (Mixed)",
            bgColor = Color.parseColor("#2E3440"),
            textColor = Color.parseColor("#ECEFF4"),
            promptColor = Color.parseColor("#88C0D0"),
            secondaryColor = Color.parseColor("#4C566A"),
            errorColor = Color.parseColor("#BF616A"),
            accentColor = Color.parseColor("#EBCB8B"),
            pathColor = Color.parseColor("#A3BE8C"),
            commandColor = Color.parseColor("#81A1C1")
        )

        val GRUVBOX = TerminalTheme(
            id = "gruvbox",
            displayName = "Gruvbox Retro (Mixed)",
            bgColor = Color.parseColor("#282828"),
            textColor = Color.parseColor("#EBDBB2"),
            promptColor = Color.parseColor("#8EC07C"),
            secondaryColor = Color.parseColor("#928374"),
            errorColor = Color.parseColor("#FB4934"),
            accentColor = Color.parseColor("#FE8019"),
            pathColor = Color.parseColor("#FABD2F"),
            commandColor = Color.parseColor("#B8BB26")
        )

        val SOLARIZED = TerminalTheme(
            id = "solarized",
            displayName = "Solarized Dark (Mixed)",
            bgColor = Color.parseColor("#002B36"),
            textColor = Color.parseColor("#839496"),
            promptColor = Color.parseColor("#B58900"),
            secondaryColor = Color.parseColor("#586E75"),
            errorColor = Color.parseColor("#DC322F"),
            accentColor = Color.parseColor("#6C71C4"),
            pathColor = Color.parseColor("#268BD2"),
            commandColor = Color.parseColor("#2AA198")
        )

        val CYBERPUNK = TerminalTheme(
            id = "cyberpunk",
            displayName = "Cyberpunk Neon (Mixed)",
            bgColor = Color.parseColor("#0D0221"),
            textColor = Color.parseColor("#FFE600"),
            promptColor = Color.parseColor("#FF007F"),
            secondaryColor = Color.parseColor("#382457"),
            errorColor = Color.parseColor("#FF3344"),
            accentColor = Color.parseColor("#7122FA"),
            pathColor = Color.parseColor("#00F0FF"),
            commandColor = Color.parseColor("#05FFA1")
        )

        fun fromId(id: String): TerminalTheme {
            return when (id.lowercase().trim()) {
                "amber" -> AMBER
                "cyan" -> CYAN
                "white", "monokai" -> WHITE
                "red" -> RED
                "dracula" -> DRACULA
                "synthwave", "80s", "neon" -> SYNTHWAVE
                "tokyo", "tokyonight" -> TOKYO
                "nord", "frost" -> NORD
                "gruvbox" -> GRUVBOX
                "solarized" -> SOLARIZED
                "cyberpunk" -> CYBERPUNK
                else -> GREEN
            }
        }

        val ALL_THEMES = listOf(
            GREEN, AMBER, CYAN, WHITE, RED,
            DRACULA, SYNTHWAVE, TOKYO, NORD,
            GRUVBOX, SOLARIZED, CYBERPUNK
        )
    }
}
