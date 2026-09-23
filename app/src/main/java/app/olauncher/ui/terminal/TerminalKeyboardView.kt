package app.olauncher.ui.terminal

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
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

    private data class LetterKeyHolder(
        val view: TextView,
        val letter: String,
        val upper: String,
        val sym: String
    )

    private val allKeys = mutableListOf<TextView>()
    private val accessoryKeys = mutableListOf<TextView>()
    private val cachedLetterKeys = mutableListOf<LetterKeyHolder>()

    private var currentTheme: TerminalTheme? = null
    private var isShifted = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var lastShiftPressTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    // Fast drawables for Shift and Sym active/normal states
    private var shiftNormalDrawable: Drawable? = null
    private var shiftActiveDrawable: Drawable? = null
    private var symNormalDrawable: Drawable? = null
    private var symActiveDrawable: Drawable? = null

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

    private fun performFastHaptic(view: View, isLong: Boolean = false) {
        val flags = HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        val constant = if (isLong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP
        view.performHapticFeedback(constant, flags)
    }

    private fun isInsideView(view: View, event: MotionEvent): Boolean {
        return event.x >= 0 && event.x <= view.width && event.y >= 0 && event.y <= view.height
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindInstantKey(view: TextView, action: () -> Unit) {
        allKeys.add(view)
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    performFastHaptic(v, false)
                    action()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isInsideView(v, event)) {
                        v.isPressed = false
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindRepeatingKey(
        view: TextView,
        initialDelayMs: Long = 200L,
        repeatIntervalMs: Long = 35L,
        action: () -> Unit
    ) {
        allKeys.add(view)
        var isHeld = false
        val repeatRunnable = object : Runnable {
            override fun run() {
                if (isHeld) {
                    performFastHaptic(view, false)
                    action()
                    mainHandler.postDelayed(this, repeatIntervalMs)
                }
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    isHeld = true
                    performFastHaptic(v, false)
                    action()
                    mainHandler.removeCallbacks(repeatRunnable)
                    mainHandler.postDelayed(repeatRunnable, initialDelayMs)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    isHeld = false
                    mainHandler.removeCallbacks(repeatRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isInsideView(v, event)) {
                        v.isPressed = false
                        isHeld = false
                        mainHandler.removeCallbacks(repeatRunnable)
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTapAndHoldKey(
        view: TextView,
        tapChar: String,
        holdChar: String,
        holdThresholdMs: Long = 260L
    ) {
        allKeys.add(view)
        var longPressed = false
        val longPressRunnable = Runnable {
            longPressed = true
            performFastHaptic(view, true)
            // Replace the instantly-typed tapChar with holdChar
            deleteLastChar()
            appendChar(holdChar)
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    longPressed = false
                    performFastHaptic(v, false)
                    appendChar(tapChar)
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, holdThresholdMs)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isInsideView(v, event)) {
                        v.isPressed = false
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupAccessoryKeys() {
        val instantAccessories = listOf(
            R.id.key_esc to { onEscPressed() },
            R.id.key_ctrl_c to { onCtrlCPressed() },
            R.id.key_tab to { onTabPressed() },
            R.id.key_pipe to { appendChar("| ") },
            R.id.key_tilde to { appendChar("~") }
        )

        for ((id, action) in instantAccessories) {
            rootView.findViewById<TextView>(id)?.let { tv ->
                accessoryKeys.add(tv)
                bindInstantKey(tv, action)
            }
        }

        // Repeating arrow navigation keys (hold down to smoothly move/scroll)
        val repeatingArrows = listOf(
            R.id.key_up to { onUpPressed() },
            R.id.key_down to { onDownPressed() },
            R.id.key_left to { onLeftPressed() },
            R.id.key_right to { onRightPressed() }
        )

        for ((id, action) in repeatingArrows) {
            rootView.findViewById<TextView>(id)?.let { tv ->
                accessoryKeys.add(tv)
                bindRepeatingKey(tv, initialDelayMs = 220L, repeatIntervalMs = 45L, action = action)
            }
        }
    }

    private fun setupNumberKeys() {
        val numberSymbols = listOf(
            Triple(R.id.key_1, "1", "!"),
            Triple(R.id.key_2, "2", "@"),
            Triple(R.id.key_3, "3", "#"),
            Triple(R.id.key_4, "4", "$"),
            Triple(R.id.key_5, "5", "%"),
            Triple(R.id.key_6, "6", "^"),
            Triple(R.id.key_7, "7", "&"),
            Triple(R.id.key_8, "8", "*"),
            Triple(R.id.key_9, "9", "("),
            Triple(R.id.key_0, "0", ")")
        )

        for ((id, num, sym) in numberSymbols) {
            rootView.findViewById<TextView>(id)?.let { tv ->
                // Instant tap response on ACTION_DOWN, hold converts to symbol
                bindTapAndHoldKey(tv, tapChar = num, holdChar = sym, holdThresholdMs = 260L)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLetterKeys() {
        val letterKeyData = listOf(
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

        for ((id, letter, sym) in letterKeyData) {
            rootView.findViewById<TextView>(id)?.let { tv ->
                allKeys.add(tv)
                val holder = LetterKeyHolder(
                    view = tv,
                    letter = letter,
                    upper = letter.uppercase(),
                    sym = sym
                )
                cachedLetterKeys.add(holder)

                // Instant firing on ACTION_DOWN
                tv.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            performFastHaptic(v, false)
                            if (isSymbolsMode) {
                                appendChar(sym)
                            } else {
                                val charToAppend = if (isShifted) holder.upper else holder.letter
                                appendChar(charToAppend)
                                if (isShifted && !isCapsLock) {
                                    isShifted = false
                                    updateKeyboardDisplay()
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isInsideView(v, event)) {
                                v.isPressed = false
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
        }
    }

    private fun setupBottomKeys() {
        // Space
        rootView.findViewById<TextView>(R.id.key_space)?.let { space ->
            bindInstantKey(space) { appendChar(" ") }
        }

        // Dash (-) with instant tap and hold for --
        rootView.findViewById<TextView>(R.id.key_dash)?.let { dash ->
            bindTapAndHoldKey(dash, tapChar = "-", holdChar = "--", holdThresholdMs = 260L)
        }

        // Dot (.) with instant tap and hold for ..
        rootView.findViewById<TextView>(R.id.key_dot)?.let { dot ->
            bindTapAndHoldKey(dot, tapChar = ".", holdChar = "..", holdThresholdMs = 260L)
        }

        // Slash (/)
        rootView.findViewById<TextView>(R.id.key_slash)?.let { slash ->
            bindInstantKey(slash) { appendChar("/") }
        }

        // Apps
        rootView.findViewById<TextView>(R.id.key_apps)?.let { apps ->
            accessoryKeys.add(apps)
            bindInstantKey(apps) { onAppsPressed() }
        }

        // Enter
        rootView.findViewById<TextView>(R.id.key_enter)?.let { enter ->
            accessoryKeys.add(enter)
            bindInstantKey(enter) { onEnterPressed() }
        }
    }

    private fun setupControlKeys() {
        // Shift key
        shiftKeyView?.let { shift ->
            bindInstantKey(shift) {
                if (isSymbolsMode) {
                    appendChar("_")
                } else {
                    val now = SystemClock.uptimeMillis()
                    if (isCapsLock) {
                        isCapsLock = false
                        isShifted = false
                    } else if (isShifted) {
                        if (now - lastShiftPressTime < 320) {
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
            bindInstantKey(sym) {
                isSymbolsMode = !isSymbolsMode
                if (isSymbolsMode) {
                    isShifted = false
                    isCapsLock = false
                }
                updateKeyboardDisplay()
            }
        }

        // Backspace with snappy hold-to-repeat (200ms initial, 35ms repeat)
        rootView.findViewById<TextView>(R.id.key_backspace)?.let { backspace ->
            bindRepeatingKey(
                backspace,
                initialDelayMs = 200L,
                repeatIntervalMs = 35L
            ) {
                deleteLastChar()
            }
        }
    }

    private fun updateKeyboardDisplay() {
        // 1. Update letter/symbol keys using cached holders - no layout inflation or findViewById
        for (holder in cachedLetterKeys) {
            holder.view.text = if (isSymbolsMode) {
                holder.sym
            } else if (isShifted) {
                holder.upper
            } else {
                holder.letter
            }
        }

        // 2. Update Shift key label and state
        shiftKeyView?.let { shift ->
            shift.text = if (isSymbolsMode) {
                "_"
            } else if (isCapsLock) {
                "⇪"
            } else {
                "⇧"
            }

            val theme = currentTheme
            if (theme != null) {
                val isActive = isShifted && !isSymbolsMode
                shift.background = if (isActive) shiftActiveDrawable else shiftNormalDrawable
                shift.setTextColor(if (isActive) theme.bgColor else theme.textColor)
            }
        }

        // 3. Update Symbols key label and state
        symKeyView?.let { sym ->
            sym.text = if (isSymbolsMode) "ABC" else "?123"

            val theme = currentTheme
            if (theme != null) {
                val isActive = isSymbolsMode
                sym.background = if (isActive) symActiveDrawable else symNormalDrawable
                sym.setTextColor(if (isActive) theme.bgColor else theme.textColor)
            }
        }
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

        fun createRippleDrawable(bgColor: Int): Drawable {
            val normalDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgColor)
                this.cornerRadius = cornerRadius
            }
            val rippleColor = ColorStateList.valueOf(promptColor and 0x33FFFFFF)
            return RippleDrawable(rippleColor, normalDrawable, null)
        }

        // Pre-create Shift and Sym active/normal drawables so toggling is instant
        shiftNormalDrawable = createRippleDrawable(defaultBgColor)
        shiftActiveDrawable = createRippleDrawable(promptColor)
        symNormalDrawable = createRippleDrawable(defaultBgColor)
        symActiveDrawable = createRippleDrawable(promptColor)

        for (tv in allKeys) {
            val isAccessory = accessoryKeys.contains(tv)
            val isShiftKey = tv.id == R.id.key_shift
            val isSymKey = tv.id == R.id.key_sym

            val textCol = when {
                isShiftKey && isShifted -> theme.bgColor
                isSymKey && isSymbolsMode -> theme.bgColor
                tv.id == R.id.key_enter || tv.id == R.id.key_apps -> promptColor
                tv.id == R.id.key_ctrl_c -> theme.errorColor
                isAccessory -> accentColor
                else -> textColor
            }
            tv.setTextColor(textCol)

            tv.background = when {
                isShiftKey -> if (isShifted) shiftActiveDrawable else shiftNormalDrawable
                isSymKey -> if (isSymbolsMode) symActiveDrawable else symNormalDrawable
                isAccessory -> createRippleDrawable(accessoryBgColor)
                else -> createRippleDrawable(defaultBgColor)
            }
        }

        rootView.setBackgroundColor(theme.bgColor)
    }
}
