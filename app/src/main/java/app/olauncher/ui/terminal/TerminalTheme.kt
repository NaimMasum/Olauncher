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
            displayName = "Cyberpunk Neon",
            bgColor = Color.parseColor("#0D0221"),
            textColor = Color.parseColor("#FFE600"),
            promptColor = Color.parseColor("#FF007F"),
            secondaryColor = Color.parseColor("#382457"),
            errorColor = Color.parseColor("#FF3344"),
            accentColor = Color.parseColor("#7122FA"),
            pathColor = Color.parseColor("#00F0FF"),
            commandColor = Color.parseColor("#05FFA1")
        )

        val ONEDARK = TerminalTheme(
            id = "onedark",
            displayName = "One Dark Pro",
            bgColor = Color.parseColor("#1E1E24"),
            textColor = Color.parseColor("#ABB2BF"),
            promptColor = Color.parseColor("#98C379"),
            secondaryColor = Color.parseColor("#5C6370"),
            errorColor = Color.parseColor("#E06C75"),
            accentColor = Color.parseColor("#61AFEF"),
            pathColor = Color.parseColor("#E5C07B"),
            commandColor = Color.parseColor("#C678DD")
        )

        val CATPPUCCIN = TerminalTheme(
            id = "catppuccin",
            displayName = "Catppuccin Mocha",
            bgColor = Color.parseColor("#1E1E2E"),
            textColor = Color.parseColor("#CDD6F4"),
            promptColor = Color.parseColor("#A6E3A1"),
            secondaryColor = Color.parseColor("#6C7086"),
            errorColor = Color.parseColor("#F38BA8"),
            accentColor = Color.parseColor("#CBA6F7"),
            pathColor = Color.parseColor("#89B4FA"),
            commandColor = Color.parseColor("#F9E2AF")
        )

        val LATTE = TerminalTheme(
            id = "latte",
            displayName = "Catppuccin Latte (Light)",
            bgColor = Color.parseColor("#EFF1F5"),
            textColor = Color.parseColor("#4C4F69"),
            promptColor = Color.parseColor("#40A02B"),
            secondaryColor = Color.parseColor("#9CA0B0"),
            errorColor = Color.parseColor("#D20F39"),
            accentColor = Color.parseColor("#1E66F5"),
            pathColor = Color.parseColor("#8839EF"),
            commandColor = Color.parseColor("#FE640B")
        )

        val GITHUB = TerminalTheme(
            id = "github",
            displayName = "GitHub Dark",
            bgColor = Color.parseColor("#0D1117"),
            textColor = Color.parseColor("#C9D1D9"),
            promptColor = Color.parseColor("#58A6FF"),
            secondaryColor = Color.parseColor("#484F58"),
            errorColor = Color.parseColor("#F85149"),
            accentColor = Color.parseColor("#3FB950"),
            pathColor = Color.parseColor("#F0883E"),
            commandColor = Color.parseColor("#A371F7")
        )

        val POWERSHELL = TerminalTheme(
            id = "powershell",
            displayName = "PowerShell Deep Blue",
            bgColor = Color.parseColor("#012456"),
            textColor = Color.parseColor("#EEEDF0"),
            promptColor = Color.parseColor("#FFFF00"),
            secondaryColor = Color.parseColor("#4169E1"),
            errorColor = Color.parseColor("#FF5555"),
            accentColor = Color.parseColor("#00FFFF"),
            pathColor = Color.parseColor("#00FF7F"),
            commandColor = Color.parseColor("#FFFFFF")
        )

        val UBUNTU = TerminalTheme(
            id = "ubuntu",
            displayName = "Ubuntu Aubergine",
            bgColor = Color.parseColor("#300A24"),
            textColor = Color.parseColor("#FFFFFF"),
            promptColor = Color.parseColor("#4E9A06"),
            secondaryColor = Color.parseColor("#77216F"),
            errorColor = Color.parseColor("#CC0000"),
            accentColor = Color.parseColor("#E95420"),
            pathColor = Color.parseColor("#729FCF"),
            commandColor = Color.parseColor("#FCE94F")
        )

        val SOLARIZED_LIGHT = TerminalTheme(
            id = "solarizedlight",
            displayName = "Solarized Light",
            bgColor = Color.parseColor("#FDF6E3"),
            textColor = Color.parseColor("#657B83"),
            promptColor = Color.parseColor("#859900"),
            secondaryColor = Color.parseColor("#93A1A1"),
            errorColor = Color.parseColor("#DC322F"),
            accentColor = Color.parseColor("#268BD2"),
            pathColor = Color.parseColor("#B58900"),
            commandColor = Color.parseColor("#CB4B16")
        )

        val OUTRUN = TerminalTheme(
            id = "outrun",
            displayName = "Outrun Sunset",
            bgColor = Color.parseColor("#1F0E1E"),
            textColor = Color.parseColor("#FFD285"),
            promptColor = Color.parseColor("#FF5E8A"),
            secondaryColor = Color.parseColor("#6B3B60"),
            errorColor = Color.parseColor("#FF3366"),
            accentColor = Color.parseColor("#FF9900"),
            pathColor = Color.parseColor("#FF007F"),
            commandColor = Color.parseColor("#00F0FF")
        )

        val EMERALD = TerminalTheme(
            id = "emerald",
            displayName = "Hacker Emerald",
            bgColor = Color.parseColor("#021414"),
            textColor = Color.parseColor("#2EE59D"),
            promptColor = Color.parseColor("#00F5B4"),
            secondaryColor = Color.parseColor("#0F5245"),
            errorColor = Color.parseColor("#FF4D6D"),
            accentColor = Color.parseColor("#00D2FF"),
            pathColor = Color.parseColor("#70FFD4"),
            commandColor = Color.parseColor("#2EE59D")
        )

        val AYU = TerminalTheme(
            id = "ayu",
            displayName = "Ayu Mirage",
            bgColor = Color.parseColor("#1F2430"),
            textColor = Color.parseColor("#CBCCC6"),
            promptColor = Color.parseColor("#FFCC66"),
            secondaryColor = Color.parseColor("#707A8C"),
            errorColor = Color.parseColor("#F28779"),
            accentColor = Color.parseColor("#73D0FF"),
            pathColor = Color.parseColor("#FFA759"),
            commandColor = Color.parseColor("#BAE67E")
        )

        val KANAGAWA = TerminalTheme(
            id = "kanagawa",
            displayName = "Kanagawa Wave",
            bgColor = Color.parseColor("#1F1F28"),
            textColor = Color.parseColor("#DCD7BA"),
            promptColor = Color.parseColor("#98BB6C"),
            secondaryColor = Color.parseColor("#727169"),
            errorColor = Color.parseColor("#C34043"),
            accentColor = Color.parseColor("#7E9CD8"),
            pathColor = Color.parseColor("#FFA066"),
            commandColor = Color.parseColor("#957FB8")
        )

        val ROSE_PINE = TerminalTheme(
            id = "rosepine",
            displayName = "Rosé Pine",
            bgColor = Color.parseColor("#191724"),
            textColor = Color.parseColor("#E0DEF4"),
            promptColor = Color.parseColor("#EBBCBA"),
            secondaryColor = Color.parseColor("#6E6A86"),
            errorColor = Color.parseColor("#EB6F92"),
            accentColor = Color.parseColor("#31748F"),
            pathColor = Color.parseColor("#F6C177"),
            commandColor = Color.parseColor("#9CCFD8")
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
                "onedark", "atom" -> ONEDARK
                "catppuccin", "mocha" -> CATPPUCCIN
                "latte", "light" -> LATTE
                "github", "ghdark" -> GITHUB
                "powershell", "ps", "blue" -> POWERSHELL
                "ubuntu" -> UBUNTU
                "solarizedlight", "solarlight" -> SOLARIZED_LIGHT
                "outrun", "sunset" -> OUTRUN
                "emerald", "ghost" -> EMERALD
                "ayu", "mirage" -> AYU
                "kanagawa", "wave" -> KANAGAWA
                "rosepine", "rose" -> ROSE_PINE
                else -> GREEN
            }
        }

        val ALL_THEMES = listOf(
            GREEN, AMBER, CYAN, WHITE, RED,
            DRACULA, SYNTHWAVE, TOKYO, NORD,
            GRUVBOX, SOLARIZED, CYBERPUNK,
            ONEDARK, CATPPUCCIN, LATTE, GITHUB,
            POWERSHELL, UBUNTU, SOLARIZED_LIGHT,
            OUTRUN, EMERALD, AYU, KANAGAWA, ROSE_PINE
        )
    }
}
