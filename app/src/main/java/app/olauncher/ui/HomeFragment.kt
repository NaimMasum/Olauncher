package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.setPlainWallpaperByTheme
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.LinearLayoutManager
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showKeyboard
import app.olauncher.service.TerminalNotificationListenerService
import app.olauncher.ui.terminal.TerminalCommandHandler
import app.olauncher.ui.terminal.TerminalKeyboardView
import app.olauncher.ui.terminal.TerminalLogAdapter
import app.olauncher.ui.terminal.TerminalLogItem
import app.olauncher.ui.terminal.TerminalSessionManager
import app.olauncher.ui.terminal.TerminalSuggestion
import app.olauncher.ui.terminal.TerminalSuggestionAdapter
import app.olauncher.ui.terminal.TerminalTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var terminalLogAdapter: TerminalLogAdapter? = null
    private var terminalSuggestionAdapter: TerminalSuggestionAdapter? = null
    private var terminalCommandHandler: TerminalCommandHandler? = null
    private var terminalSessionManager: TerminalSessionManager? = null
    private var terminalKeyboardView: TerminalKeyboardView? = null
    private var installedAppsList: List<AppModel.App> = emptyList()
    private var isTerminalInitialized = false
    private var isReceiverRegistered = false
    private var lastLoggedNotificationMsg: String? = null
    private var lastLoggedNotificationTime: Long = 0L

    private val terminalBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "app.olauncher.RUN_TERMINAL_COMMAND" -> {
                    val command = intent.getStringExtra("command")
                    if (!command.isNullOrBlank()) {
                        terminalCommandHandler?.execute(command)
                    }
                }
                TerminalNotificationListenerService.ACTION_TERMINAL_NOTIFICATION -> {
                    val appLabel = intent.getStringExtra(TerminalNotificationListenerService.EXTRA_APP_LABEL) ?: ""
                    val packageName = intent.getStringExtra(TerminalNotificationListenerService.EXTRA_PACKAGE_NAME) ?: ""
                    val title = intent.getStringExtra(TerminalNotificationListenerService.EXTRA_TITLE) ?: ""
                    val text = intent.getStringExtra(TerminalNotificationListenerService.EXTRA_TEXT) ?: ""
                    val time = intent.getLongExtra(TerminalNotificationListenerService.EXTRA_POST_TIME, System.currentTimeMillis())
                    handleIncomingTerminalNotification(appLabel, packageName, title, text, time)
                }
            }
        }
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        TerminalNotificationListenerService.liveNotificationCallback = { appLabel, packageName, title, text, time ->
            activity?.runOnUiThread {
                handleIncomingTerminalNotification(appLabel, packageName, title, text, time)
            }
        }

        if (prefs.terminalNotificationsEnabled) {
            TerminalNotificationListenerService.ensureServiceBound(requireContext())
        }

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
    }

    override fun onResume() {
        super.onResume()
        viewModel.getAppList()
        populateHomeScreen(false)
        viewModel.isOlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
        if (prefs.terminalMode) {
            binding.etTerminalInput.requestFocus()
            if (prefs.terminalKeyboardVisible) {
                binding.etTerminalInput.showSoftInputOnFocus = false
                binding.etTerminalInput.hideKeyboard()
            } else if (prefs.autoShowKeyboard) {
                binding.etTerminalInput.showSoftInputOnFocus = true
                binding.etTerminalInput.showKeyboard()
            }
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()
            R.id.btnGuiToggleCli -> {
                prefs.terminalMode = true
                populateHomeScreen(true)
            }

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        viewModel.appList.observe(viewLifecycleOwner) { list ->
            installedAppsList = list?.filterIsInstance<AppModel.App>() ?: emptyList()
            terminalSessionManager?.updateAppsListScript(installedAppsList)
            if (prefs.terminalMode && isTerminalInitialized) {
                refreshSuggestions()
            }
        }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)
        binding.btnGuiToggleCli.setOnClickListener(this)

        // These fire only on d-pad/keyboard events; touch is consumed by ViewSwipeTouchListener
        binding.homeApp1.setOnClickListener(this)
        binding.homeApp2.setOnClickListener(this)
        binding.homeApp3.setOnClickListener(this)
        binding.homeApp4.setOnClickListener(this)
        binding.homeApp5.setOnClickListener(this)
        binding.homeApp6.setOnClickListener(this)
        binding.homeApp7.setOnClickListener(this)
        binding.homeApp8.setOnClickListener(this)
        binding.homeApp1.setOnLongClickListener(this)
        binding.homeApp2.setOnLongClickListener(this)
        binding.homeApp3.setOnLongClickListener(this)
        binding.homeApp4.setOnLongClickListener(this)
        binding.homeApp5.setOnLongClickListener(this)
        binding.homeApp6.setOnLongClickListener(this)
        binding.homeApp7.setOnLongClickListener(this)
        binding.homeApp8.setOnLongClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

//        var dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (prefs.terminalMode) {
            binding.terminalLayout.visibility = View.VISIBLE
            binding.btnGuiToggleCli.visibility = View.GONE
            binding.dateTimeLayout.visibility = View.GONE
            binding.tvScreenTime.visibility = View.GONE
            binding.homeAppsLayout.visibility = View.GONE
            binding.firstRunTips.visibility = View.GONE
            binding.setDefaultLauncher.visibility = View.GONE
            initTerminal()
            applyTerminalTheme(TerminalTheme.fromId(prefs.terminalTheme))
            updatePtyModeUi(prefs.terminalUsePty)
            return
        } else {
            binding.terminalLayout.visibility = View.GONE
            binding.btnGuiToggleCli.visibility = View.VISIBLE
            binding.homeAppsLayout.visibility = View.VISIBLE
        }

        if (appCountUpdated) hideHomeApps()
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1, prefs.isShortcut1, prefs.shortcutId1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2, prefs.isShortcut2, prefs.shortcutId2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3, prefs.isShortcut3, prefs.shortcutId3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4, prefs.isShortcut4, prefs.shortcutId4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5, prefs.isShortcut5, prefs.shortcutId5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) return

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp6, prefs.appName6, prefs.appPackage6, prefs.appUser6, prefs.isShortcut6, prefs.shortcutId6)) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) return

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp7, prefs.appName7, prefs.appPackage7, prefs.appUser7, prefs.isShortcut7, prefs.shortcutId7)) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) return

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp8, prefs.appName8, prefs.appPackage8, prefs.appUser8, prefs.isShortcut8, prefs.shortcutId8)) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
        }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName
                    return true
                }
                textView.text = ""
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            return true
        }
        textView.text = ""
        return false
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    private fun initTerminal() {
        if (isTerminalInitialized) return
        isTerminalInitialized = true

        val currentTheme = TerminalTheme.fromId(prefs.terminalTheme)

        terminalLogAdapter = TerminalLogAdapter(
            theme = currentTheme,
            onAppClicked = { app ->
                viewModel.selectedApp(app, Constants.FLAG_LAUNCH_APP)
            },
            onItemClicked = { text ->
                binding.etTerminalInput.append(text)
            }
        )
        binding.rvTerminalLog.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvTerminalLog.adapter = terminalLogAdapter

        terminalSuggestionAdapter = TerminalSuggestionAdapter(
            theme = currentTheme,
            onSuggestionClicked = { suggestion ->
                if (suggestion.isAction) {
                    showPinAppDialog()
                } else if (suggestion.executeImmediately) {
                    terminalCommandHandler?.execute(suggestion.commandToFill)
                    binding.etTerminalInput.setText("")
                } else {
                    binding.etTerminalInput.setText(suggestion.commandToFill)
                    binding.etTerminalInput.setSelection(suggestion.commandToFill.length)
                }
            }
        )
        binding.rvTerminalSuggestions.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvTerminalSuggestions.adapter = terminalSuggestionAdapter

        terminalCommandHandler = TerminalCommandHandler(
            context = requireContext(),
            prefs = prefs,
            callbacks = object : TerminalCommandHandler.Callbacks {
                override fun onAddLog(item: TerminalLogItem) {
                    requireActivity().runOnUiThread {
                        terminalLogAdapter?.addItem(item)
                        binding.rvTerminalLog.scrollToPosition(terminalLogAdapter?.itemCount?.minus(1) ?: 0)
                    }
                }

                override fun onClearLogs() {
                    requireActivity().runOnUiThread {
                        terminalLogAdapter?.clear()
                    }
                }

                override fun onLaunchApp(app: AppModel.App) {
                    requireActivity().runOnUiThread {
                        viewModel.selectedApp(app, Constants.FLAG_LAUNCH_APP)
                    }
                }

                override fun onThemeChanged(theme: TerminalTheme) {
                    requireActivity().runOnUiThread {
                        applyTerminalTheme(theme)
                    }
                }

                override fun onSwitchLauncherMode(terminalMode: Boolean) {
                    requireActivity().runOnUiThread {
                        prefs.terminalMode = terminalMode
                        populateHomeScreen(true)
                    }
                }

                override fun onOpenSettings() {
                    requireActivity().runOnUiThread {
                        try {
                            findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun getInstalledApps(): List<AppModel.App> {
                    return installedAppsList
                }

                override fun onPinnedAppsChanged() {
                    requireActivity().runOnUiThread {
                        refreshSuggestions()
                    }
                }

                override fun onPathChanged(displayPath: String) {
                    requireActivity().runOnUiThread {
                        binding.tvTerminalPrompt.text = terminalCommandHandler?.getPromptText() ?: "naim@android:$ "
                    }
                }

                override fun onRunningStateChanged(isRunning: Boolean) {
                    requireActivity().runOnUiThread {
                        if (isRunning) {
                            binding.tvTerminalPrompt.text = "[busy] "
                        } else {
                            binding.tvTerminalPrompt.text = terminalCommandHandler?.getPromptText() ?: "naim@android:$ "
                        }
                    }
                }
            }
        )

        terminalSessionManager = TerminalSessionManager(
            context = requireContext(),
            prefs = prefs,
            callbacks = object : TerminalSessionManager.Callbacks {
                override fun onOpenSettings() {
                    requireActivity().runOnUiThread {
                        try {
                            findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onOpenAppsDrawer() {
                    requireActivity().runOnUiThread {
                        showAppList(Constants.FLAG_LAUNCH_APP)
                    }
                }

                override fun onSwitchToGui() {
                    requireActivity().runOnUiThread {
                        prefs.terminalMode = false
                        populateHomeScreen(true)
                    }
                }

                override fun getInstalledApps(): List<AppModel.App> {
                    return installedAppsList
                }
            }
        )
        terminalSessionManager?.attachToView(binding.terminalView)
        terminalSessionManager?.updateAppsListScript(installedAppsList)

        binding.etTerminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                val command = binding.etTerminalInput.text.toString()
                if (command.isNotBlank()) {
                    terminalCommandHandler?.execute(command)
                    binding.etTerminalInput.setText("")
                }
                true
            } else {
                false
            }
        }

        binding.etTerminalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshSuggestions()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        terminalKeyboardView = TerminalKeyboardView(
            rootView = binding.includedKeyboard.llKeyboardRoot,
            onSendInput = { text ->
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.write(text)
                } else {
                    val start = binding.etTerminalInput.selectionStart.coerceAtLeast(0)
                    val end = binding.etTerminalInput.selectionEnd.coerceAtLeast(0)
                    binding.etTerminalInput.text.replace(kotlin.math.min(start, end), kotlin.math.max(start, end), text)
                }
            },
            onSendBackspace = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendBackspace()
                } else {
                    val start = binding.etTerminalInput.selectionStart
                    val end = binding.etTerminalInput.selectionEnd
                    if (start != end) {
                        binding.etTerminalInput.text.delete(kotlin.math.min(start, end), kotlin.math.max(start, end))
                    } else if (start > 0) {
                        binding.etTerminalInput.text.delete(start - 1, start)
                    }
                }
            },
            onEnterPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendEnter()
                } else {
                    val command = binding.etTerminalInput.text.toString()
                    if (command.isNotBlank()) {
                        terminalCommandHandler?.execute(command)
                        binding.etTerminalInput.setText("")
                    }
                }
            },
            onTabPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendTab()
                } else {
                    val query = binding.etTerminalInput.text.toString()
                    val suggestions = terminalCommandHandler?.getSuggestions(query) ?: emptyList()
                    if (suggestions.isNotEmpty()) {
                        val first = suggestions[0]
                        if (first.executeImmediately) {
                            terminalCommandHandler?.execute(first.commandToFill)
                            binding.etTerminalInput.setText("")
                        } else {
                            binding.etTerminalInput.setText(first.commandToFill)
                            binding.etTerminalInput.setSelection(first.commandToFill.length)
                        }
                    }
                }
            },
            onAppsPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.write("apps\n")
                } else {
                    terminalCommandHandler?.execute("apps")
                }
            },
            onCtrlCPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendCtrlC()
                } else {
                    terminalCommandHandler?.sendCtrlC()
                }
            },
            onCtrlKeyPressed = { char ->
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendCtrlKey(char)
                } else {
                    when (char.lowercaseChar()) {
                        'c' -> terminalCommandHandler?.sendCtrlC()
                        'l' -> terminalLogAdapter?.clear()
                        'u' -> binding.etTerminalInput.setText("")
                        'd' -> {
                            if (binding.etTerminalInput.text.isNullOrEmpty()) {
                                prefs.terminalMode = false
                                populateHomeScreen(true)
                            } else {
                                binding.etTerminalInput.setText("")
                            }
                        }
                    }
                }
            },
            onUpPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendUp()
                } else {
                    terminalCommandHandler?.getPreviousCommand()?.let {
                        binding.etTerminalInput.setText(it)
                        binding.etTerminalInput.setSelection(it.length)
                    }
                }
            },
            onDownPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendDown()
                } else {
                    terminalCommandHandler?.getNextCommand()?.let {
                        binding.etTerminalInput.setText(it)
                        binding.etTerminalInput.setSelection(it.length)
                    }
                }
            },
            onLeftPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendLeft()
                } else {
                    val pos = (binding.etTerminalInput.selectionStart - 1).coerceAtLeast(0)
                    binding.etTerminalInput.setSelection(pos)
                }
            },
            onRightPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendRight()
                } else {
                    val pos = (binding.etTerminalInput.selectionStart + 1).coerceAtMost(binding.etTerminalInput.text?.length ?: 0)
                    binding.etTerminalInput.setSelection(pos)
                }
            },
            onEscPressed = {
                if (prefs.terminalUsePty) {
                    terminalSessionManager?.sendEsc()
                } else {
                    binding.etTerminalInput.setText("")
                }
            }
        )

        // Top Bar: Clock, Date, Mode Toggle, PTY Mode Toggle
        binding.tcTerminalClock.setOnClickListener { openClockApp() }
        binding.tcTerminalDate.setOnClickListener { openCalendarApp() }
        binding.btnToggleMode.setOnClickListener {
            prefs.terminalMode = false
            populateHomeScreen(true)
        }
        binding.btnTogglePtyMode.setOnClickListener {
            prefs.terminalUsePty = !prefs.terminalUsePty
            updatePtyModeUi(prefs.terminalUsePty)
        }

        fun updateKeyboardVisibility(visible: Boolean) {
            binding.includedKeyboard.llKeyboardRoot.visibility = if (visible) View.VISIBLE else View.GONE
            binding.btnToggleKeyboard.text = if (visible) "[KB]" else "[kb]"
            binding.etTerminalInput.showSoftInputOnFocus = !visible
            if (visible) {
                binding.etTerminalInput.hideKeyboard()
                binding.terminalView.hideKeyboard()
            } else {
                if (prefs.terminalUsePty) {
                    binding.terminalView.requestFocus()
                    binding.terminalView.showKeyboard()
                } else {
                    binding.etTerminalInput.showKeyboard()
                }
            }
        }

        updateKeyboardVisibility(prefs.terminalKeyboardVisible)

        binding.btnToggleKeyboard.setOnClickListener {
            prefs.terminalKeyboardVisible = !prefs.terminalKeyboardVisible
            updateKeyboardVisibility(prefs.terminalKeyboardVisible)
        }

        binding.tvTerminalPrompt.setOnLongClickListener {
            try {
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            true
        }

        binding.terminalLayout.setOnClickListener {
            if (prefs.terminalUsePty) {
                binding.terminalView.requestFocus()
                if (!prefs.terminalKeyboardVisible) {
                    binding.terminalView.showKeyboard()
                }
            } else {
                binding.etTerminalInput.requestFocus()
                if (!prefs.terminalKeyboardVisible) {
                    binding.etTerminalInput.showKeyboard()
                }
            }
        }

        refreshSuggestions()

        ViewCompat.setOnApplyWindowInsetsListener(binding.terminalLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                bottom = kotlin.math.max(insets.bottom + 8.dpToPx(), 16.dpToPx()),
                top = kotlin.math.max(insets.top + 8.dpToPx(), 36.dpToPx())
            )
            windowInsets
        }

        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction("app.olauncher.RUN_TERMINAL_COMMAND")
                addAction(TerminalNotificationListenerService.ACTION_TERMINAL_NOTIFICATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(terminalBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                requireContext().registerReceiver(terminalBroadcastReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun handleIncomingTerminalNotification(
        appLabel: String,
        packageName: String,
        title: String,
        text: String,
        time: Long
    ) {
        if (!prefs.terminalNotificationsEnabled) return
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(time))
        val msg = buildString {
            append("[$timeStr 🔔 $appLabel] ")
            if (title.isNotBlank()) {
                append(title)
                if (text.isNotBlank()) append(": ")
            }
            if (text.isNotBlank()) {
                append(text)
            }
        }
        val now = System.currentTimeMillis()
        if (msg == lastLoggedNotificationMsg && (now - lastLoggedNotificationTime) < 5_000L) {
            return
        }
        lastLoggedNotificationMsg = msg
        lastLoggedNotificationTime = now

        terminalCommandHandler?.addNotificationLog(msg)
    }

    private fun refreshSuggestions() {
        if (!isTerminalInitialized) return
        val query = binding.etTerminalInput.text?.toString() ?: ""
        val suggestions = terminalCommandHandler?.getSuggestions(query) ?: emptyList()
        terminalSuggestionAdapter?.setSuggestions(suggestions)
        binding.rvTerminalSuggestions.visibility = if (suggestions.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showPinAppDialog() {
        val apps = installedAppsList.sortedBy { it.appLabel.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(requireContext(), "No apps available yet", Toast.LENGTH_SHORT).show()
            return
        }
        val appNames = apps.map { it.appLabel }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Add App to Suggestion Bar")
            .setItems(appNames) { _, which ->
                val selected = apps[which]
                terminalCommandHandler?.pinApp(selected.appLabel)
                refreshSuggestions()
                Toast.makeText(requireContext(), "Pinned ${selected.appLabel}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePtyModeUi(usePty: Boolean) {
        val theme = TerminalTheme.fromId(prefs.terminalTheme)
        if (usePty) {
            binding.terminalViewContainer.visibility = View.VISIBLE
            binding.rvTerminalLog.visibility = View.GONE
            binding.terminalInputLayout.visibility = View.GONE
            binding.rvTerminalSuggestions.visibility = View.GONE
            binding.btnTogglePtyMode.text = "[TERMUX]"
            binding.btnTogglePtyMode.setTextColor(theme.accentColor)
            binding.terminalView.requestFocus()
        } else {
            binding.terminalViewContainer.visibility = View.GONE
            binding.rvTerminalLog.visibility = View.VISIBLE
            binding.terminalInputLayout.visibility = View.VISIBLE
            refreshSuggestions()
            binding.btnTogglePtyMode.text = "[CLI]"
            binding.btnTogglePtyMode.setTextColor(theme.secondaryColor)
            binding.etTerminalInput.requestFocus()
        }
    }

    private fun applyTerminalTheme(theme: TerminalTheme) {
        terminalLogAdapter?.setTheme(theme)
        terminalSuggestionAdapter?.setTheme(theme)
        terminalKeyboardView?.applyTheme(theme)
        terminalSessionManager?.applyTheme(theme)
        binding.terminalLayout.setBackgroundColor(theme.bgColor)
        binding.tcTerminalClock.setTextColor(theme.accentColor)
        binding.tcTerminalDate.setTextColor(theme.secondaryColor)
        binding.btnTogglePtyMode.setTextColor(if (prefs.terminalUsePty) theme.accentColor else theme.secondaryColor)
        binding.btnToggleMode.setTextColor(theme.promptColor)
        binding.tvTerminalPrompt.text = terminalCommandHandler?.getPromptText() ?: "naim@android:$ "
        binding.etTerminalInput.setTextColor(theme.textColor)
        binding.etTerminalInput.setHintTextColor(theme.secondaryColor)
        binding.btnToggleKeyboard.setTextColor(theme.accentColor)
    }

    override fun onDestroyView() {
        TerminalNotificationListenerService.liveNotificationCallback = null
        if (isReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(terminalBroadcastReceiver)
            } catch (e: Exception) {
                // ignore
            }
            isReceiverRegistered = false
        }
        terminalSessionManager?.destroy()
        terminalCommandHandler?.destroy()
        super.onDestroyView()
        isTerminalInitialized = false
        _binding = null
    }
}