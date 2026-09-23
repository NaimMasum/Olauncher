package app.olauncher.ui.terminal

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import app.olauncher.R

class TerminalKeyboardView(
    private val rootView: View,
    private val onSendInput: (String) -> Unit,
    private val onSendBackspace: () -> Unit,
    private val onEnterPressed: () -> Unit,
    private val onTabPressed: () -> Unit,
    private val onAppsPressed: () -> Unit,
    private val onCtrlCPressed: () -> Unit = {},
    private val onUpPressed: () -> Unit = {},
    private val onDownPressed: () -> Unit = {},
    private val onEscPressed: () -> Unit = {},
    private val onLeftPressed: () -> Unit = {},
    private val onRightPressed: () -> Unit = {}
) {

    private val allKeys = mutableListOf<TextView>()
    private val accessoryKeys = mutableListOf<TextView>()

    private var currentTheme: TerminalTheme? = null
    private var isShifted = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var lastShiftPressTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isBackspaceHeld = false
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (isBackspaceHeld) {
                deleteLastChar()
                mainHandler.postDelayed(this, 50)
            }
        }
    }

    // Mapping from view ID to (Lowercase letter, Symbol when in ?123 mode)
    private val letterKeyData = listOf(
        // Row 2
        Triple(R.id.key_q, "q", "!"),
        Triple(R.id.key_w, "w", "@"),
        Triple(R.id.key_e, "e", "#"),
        Triple(R.id.key_r, "r", "$"),
        Triple(R.id.key_t, "t", "%"),
        Triple(R.id.key_y, "y", "^"),
        Triple(R.id.key_u, "u", "&"),
        Triple(R.id.key_i, "i", "("),
        Triple(R.id.key_o, "o", ")"),
        Triple(R.id.key_p, "p", "*"),
        // Row 3
        Triple(R.id.key_a, "a", "+"),
        Triple(R.id.key_s, "s", "="),
        Triple(R.id.key_d, "d", "\\"),
        Triple(R.id.key_f, "f", ":"),
        Triple(R.id.key_g, "g", ";"),
        Triple(R.id.key_h, "h", "\""),
        Triple(R.id.key_j, "j", "'"),
        Triple(R.id.key_k, "k", "`"),
        Triple(R.id.key_l, "l", "~"),
        // Row 4
        Triple(R.id.key_z, "z", "["),
        Triple(R.id.key_x, "x", "]"),
        Triple(R.id.key_c, "c", "{"),
        Triple(R.id.key_v, "v", "}"),
        Triple(R.id.key_b, "b", "<"),
        Triple(R.id.key_n, "n", ">"),
        Triple(R.id.key_m, "m", "?")
    )

    private val numberSymbols = mapOf(
        R.id.key_1 to Pair("1", "!"),
        R.id.key_2 to Pair("2", "@"),
        R.id.key_3 to Pair("3", "#"),
        R.id.key_4 to Pair("4", "$"),
        R.id.key_5 to Pair("5", "%"),
        R.id.key_6 to Pair("6", "^"),
        R.id.key_7 to Pair("7", "&"),
        R.id.key_8 to Pair("8", "*"),
        R.id.key_9 to Pair("9", "("),
        R.id.key_0 to Pair("0", ")")
    )

    private val shiftKeyView: TextView? by lazy { rootView.findViewById(R.id.key_shift) }
    private val symKeyView: TextView? by lazy { rootView.findViewById(R.id.key_sym) }

    init {
        setupAccessoryKeys()
        setupNumberKeys()
        setupLetterKeys()
        setupBottomKeys()
        setupControlKeys()
        updateKeyboardDisplay()
    }

    private fun setupAccessoryKeys() {
        val accessoryMapping = listOf(
            R.id.key_esc to { onEscPressed() },
            R.id.key_ctrl_c to { onCtrlCPressed() },
            R.id.key_tab to { onTabPressed() },
            R.id.key_up to { onUpPressed() },
            R.id.key_down to { onDownPressed() },
            R.id.key_left to { onLeftPressed() },
            R.id.key_right to { onRightPressed() },
            R.id.key_pipe to { appendChar("| ") },
            R.id.key_tilde to { appendChar("~") }
        )

        for ((id, action) in accessoryMapping) {
            rootView.findViewById<TextView>(id)?.let { tv ->
                allKeys.add(tv)
                accessoryKeys.add(tv)
                tv.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                }
            }
        }
    }

    private fun setupNumberKeys() {
        for ((id, pair) in numberSymbols) {
            val (num, sym) = pair
            rootView.findViewById<TextView>(id)?.let { tv ->
                allKeys.add(tv)
                tv.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    appendChar(num)
                }
                tv.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    appendChar(sym)
                    true
                }
            }
        }
    }

    private fun setupLetterKeys() {
        for (item in letterKeyData) {
            val (id, letter, sym) = item
            rootView.findViewById<TextView>(id)?.let { tv ->
                allKeys.add(tv)
                tv.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (isSymbolsMode) {
                        appendChar(sym)
                    } else {
                        val charToAppend = if (isShifted) letter.uppercase() else letter
                        appendChar(charToAppend)
                        if (isShifted && !isCapsLock) {
                            isShifted = false
                            updateKeyboardDisplay()
                        }
                    }
                }
            }
        }
    }

    private fun setupBottomKeys() {
        // Space
        rootView.findViewById<TextView>(R.id.key_space)?.let { space ->
            allKeys.add(space)
            space.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                appendChar(" ")
            }
        }

        // Dash (-)
        rootView.findViewById<TextView>(R.id.key_dash)?.let { dash ->
            allKeys.add(dash)
            dash.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                appendChar("-")
            }
            dash.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                appendChar("--")
                true
            }
        }

        // Dot (.)
        rootView.findViewById<TextView>(R.id.key_dot)?.let { dot ->
            allKeys.add(dot)
            dot.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                appendChar(".")
            }
            dot.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                appendChar("..")
                true
            }
        }

        // Slash (/)
        rootView.findViewById<TextView>(R.id.key_slash)?.let { slash ->
            allKeys.add(slash)
            slash.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                appendChar("/")
            }
        }

        // Apps
        rootView.findViewById<TextView>(R.id.key_apps)?.let { apps ->
            allKeys.add(apps)
            accessoryKeys.add(apps)
            apps.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onAppsPressed()
            }
        }

        // Enter
        rootView.findViewById<TextView>(R.id.key_enter)?.let { enter ->
            allKeys.add(enter)
            accessoryKeys.add(enter)
            enter.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onEnterPressed()
            }
        }
    }

    private fun setupControlKeys() {
        // Shift key
        shiftKeyView?.let { shift ->
            allKeys.add(shift)
            shift.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (isSymbolsMode) {
                    appendChar("_")
                } else {
                    val now = SystemClock.uptimeMillis()
                    if (isCapsLock) {
                        isCapsLock = false
                        isShifted = false
                    } else if (isShifted) {
                        if (now - lastShiftPressTime < 350) {
                            isCapsLock = true
                            isShifted = true
                        } else {
                            isShifted = false
                        }
                    } else {
                        isShifted = true
                        lastShiftPressTime = now
                    }
                    updateKeyboardDisplay()
                }
            }
        }

        // Symbols toggle key (?123 / ABC)
        symKeyView?.let { sym ->
            allKeys.add(sym)
            sym.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                isSymbolsMode = !isSymbolsMode
                if (isSymbolsMode) {
                    isShifted = false
                    isCapsLock = false
                }
                updateKeyboardDisplay()
            }
        }

        // Backspace with hold-to-repeat
        rootView.findViewById<TextView>(R.id.key_backspace)?.let { backspace ->
            allKeys.add(backspace)
            backspace.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        deleteLastChar()
                        isBackspaceHeld = true
                        mainHandler.postDelayed(backspaceRepeatRunnable, 350)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isBackspaceHeld = false
                        mainHandler.removeCallbacks(backspaceRepeatRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun updateKeyboardDisplay() {
        // Update letter/symbol keys
        for (item in letterKeyData) {
            val (id, letter, sym) = item
            rootView.findViewById<TextView>(id)?.let { tv ->
                tv.text = if (isSymbolsMode) {
                    sym
                } else if (isShifted) {
                    letter.uppercase()
                } else {
                    letter
                }
            }
        }

        // Update Shift key label
        shiftKeyView?.let { shift ->
            shift.text = if (isSymbolsMode) {
                "_"
            } else if (isCapsLock) {
                "⇪"
            } else {
                "⇧"
            }
        }

        // Update Symbols key label
        symKeyView?.let { sym ->
            sym.text = if (isSymbolsMode) "ABC" else "?123"
        }

        // Re-apply theme highlight to reflect active mode
        currentTheme?.let { applyTheme(it) }
    }

    private fun appendChar(char: String) {
        onSendInput(char)
    }

    private fun deleteLastChar() {
        onSendBackspace()
    }

    fun applyTheme(theme: TerminalTheme) {
        currentTheme = theme
        val defaultBgColor = android.graphics.Color.argb(35, 255, 255, 255)
        val accessoryBgColor = android.graphics.Color.argb(65, 255, 255, 255)
        val textColor = theme.textColor
        val promptColor = theme.promptColor
        val accentColor = theme.accentColor
        val cornerRadius = 8f

        for (tv in allKeys) {
            val isAccessory = accessoryKeys.contains(tv)
            val bg = when {
                tv.id == R.id.key_shift && isShifted -> promptColor
                tv.id == R.id.key_sym && isSymbolsMode -> promptColor
                isAccessory -> accessoryBgColor
                else -> defaultBgColor
            }

            val textCol = when {
                tv.id == R.id.key_shift && isShifted -> theme.bgColor
                tv.id == R.id.key_sym && isSymbolsMode -> theme.bgColor
                tv.id == R.id.key_enter || tv.id == R.id.key_apps -> promptColor
                tv.id == R.id.key_ctrl_c -> theme.errorColor
                isAccessory -> accentColor
                else -> textColor
            }

            tv.setTextColor(textCol)
            val normalDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bg)
                this.cornerRadius = cornerRadius
            }

            val rippleColor = ColorStateList.valueOf(promptColor and 0x33FFFFFF)
            tv.background = RippleDrawable(rippleColor, normalDrawable, null)
        }

        rootView.setBackgroundColor(theme.bgColor)
    }
}
