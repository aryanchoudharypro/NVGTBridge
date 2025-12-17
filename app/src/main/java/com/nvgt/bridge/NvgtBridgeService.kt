package com.nvgt.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class NvgtBridgeService : AccessibilityService() {

	companion object {
		private const val NVGT_METADATA_KEY = "org.nvgt.capability.DIRECT_TOUCH"
		private const val TAG = "NVGT_BRIDGE"
		private const val PREFS_NAME = "nvgt_bridge_prefs"
		private const val KEY_ENABLED_APPS = "enabled_app_packages"
		private const val DEBOUNCE_DELAY = 150L
		private val IGNORED_SYSTEM_PACKAGES = setOf(
			"com.android.systemui",
			"com.android.inputmethod",
			"com.google.android.inputmethod",
			"android",
			"com.google.android.gms"
		)
	}

	private var currentNvgtPackage: String? = null
	private lateinit var prefs: SharedPreferences
	private var accessibilityManager: AccessibilityManager? = null
	private val targetCache = mutableMapOf<String, Boolean>()
	
	private val handler = Handler(Looper.getMainLooper())
	private val updateRunnable = Runnable { performUpdate() }

	private val screenReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			if (intent?.action == Intent.ACTION_SCREEN_OFF) {
				disableDirectTouch()
			}
		}
	}

	private val servicesStateChangeListener = AccessibilityManager.AccessibilityServicesStateChangeListener {
		if (!isOtherTouchExplorationEnabled()) {
			disableDirectTouch()
		}
	}

	private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if (key == KEY_ENABLED_APPS) {
			targetCache.clear()
		}
	}

	override fun onServiceConnected() {
		super.onServiceConnected()
		prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
		
		accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
		accessibilityManager?.addAccessibilityServicesStateChangeListener(servicesStateChangeListener)
		
		val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
		registerReceiver(screenReceiver, filter)
		
		val info = serviceInfo
		info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
		serviceInfo = info
	}

	override fun onUnbind(intent: Intent?): Boolean {
		disableDirectTouch()
		return super.onUnbind(intent)
	}

	override fun onDestroy() {
		super.onDestroy()
		unregisterReceiver(screenReceiver)
		prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
		accessibilityManager?.removeAccessibilityServicesStateChangeListener(servicesStateChangeListener)
		handler.removeCallbacks(updateRunnable)
		targetCache.clear()
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent) {
		when (event.eventType) {
			AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
			AccessibilityEvent.TYPE_WINDOWS_CHANGED,
			AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
				debounceUpdate()
			}
		}
	}

	override fun onInterrupt() {}

	private fun debounceUpdate() {
		handler.removeCallbacks(updateRunnable)
		handler.postDelayed(updateRunnable, DEBOUNCE_DELAY)
	}

	private fun performUpdate() {
		val allWindows = windows
		val rootWindow = allWindows.find { it.isFocused } ?: allWindows.firstOrNull()
		val currentAppPackage = rootWindow?.root?.packageName?.toString() ?: return

		val isSystemUIInFront = allWindows.any { window ->
			window.isActive && 
			window.type == AccessibilityWindowInfo.TYPE_SYSTEM && 
			window.root?.packageName?.toString() == "com.android.systemui"
		}

		if (isSystemUIInFront || IGNORED_SYSTEM_PACKAGES.contains(currentAppPackage)) {
			disableDirectTouch(keepPackage = (currentNvgtPackage != null))
			return
		}

		if (shouldEnableBridgeForPackage(currentAppPackage)) {
			currentNvgtPackage = currentAppPackage
			
			if (checkForNativeUI()) {
				disableDirectTouch(keepPackage = true)
			} else {
				enableDirectTouch()
			}
		} else {
			disableDirectTouch()
		}
	}

	private fun checkForNativeUI(): Boolean {
		windows.forEach { window ->
			if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION || 
				window.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
				val root = window.root ?: return@forEach
				if (scanNodeForDialogs(root)) return true
			}
		}
		return false
	}

	private fun scanNodeForDialogs(node: AccessibilityNodeInfo): Boolean {
		val className = node.className?.toString() ?: ""
		
		if (className.contains("EditText", ignoreCase = true) || 
			className.contains("AlertDialog", ignoreCase = true) ||
			className.contains("android.app.Dialog", ignoreCase = true)) {
			return true
		}

		val text = node.text?.toString()?.lowercase() ?: ""
		val commonDialogButtons = setOf("ok", "cancel", "yes", "no", "dismiss", "close")
		if (node.isClickable && commonDialogButtons.contains(text)) {
			return true
		}
		
		for (i in 0 until node.childCount) {
			val child = node.getChild(i) ?: continue
			if (scanNodeForDialogs(child)) return true
		}
		return false
	}

	private fun shouldEnableBridgeForPackage(packageName: String): Boolean {
		targetCache[packageName]?.let { return it }

		val enabledPackages = prefs.getStringSet(KEY_ENABLED_APPS, emptySet())
		if (enabledPackages?.contains(packageName) == true) {
			targetCache[packageName] = true
			return true
		}

		val pm = packageManager
		var isNvgt = false
		try {
			val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
			isNvgt = appInfo.metaData?.getBoolean(NVGT_METADATA_KEY, false) == true
			
			if (!isNvgt) {
				val launchIntent = pm.getLaunchIntentForPackage(packageName)
				launchIntent?.component?.let { component ->
					val activityInfo = pm.getActivityInfo(component, PackageManager.GET_META_DATA)
					isNvgt = activityInfo.metaData?.getBoolean(NVGT_METADATA_KEY, false) == true
				}
			}
		} catch (_: Exception) {}

		targetCache[packageName] = isNvgt
		return isNvgt
	}

	private fun enableDirectTouch() {
		val info = serviceInfo
		var changed = false

		if ((info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) == 0) {
			info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
			changed = true
		}

		if (changed) serviceInfo = info
		updatePassthroughRegion()
	}

	private fun disableDirectTouch(keepPackage: Boolean = false) {
		if (!keepPackage) currentNvgtPackage = null
		
		val info = serviceInfo
		if ((info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0) {
			info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
			serviceInfo = info
		}
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			setTouchExplorationPassthroughRegion(0, Region())
		}
	}

	private fun updatePassthroughRegion() {
		val currentPkg = currentNvgtPackage ?: return
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

		val metrics = resources.displayMetrics
		val finalRegion = Region(0, 0, metrics.widthPixels, metrics.heightPixels)
		val windowBounds = Rect()

		windows.forEach { window ->
			if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || 
				window.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
				window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
				
				window.getBoundsInScreen(windowBounds)
				finalRegion.op(windowBounds, Region.Op.DIFFERENCE)
			}
		}

		if (!finalRegion.isEmpty) {
			setTouchExplorationPassthroughRegion(0, finalRegion)
		}
	}

	private fun isOtherTouchExplorationEnabled(): Boolean {
		val am = accessibilityManager ?: return false
		val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
		val myPackageName = this.packageName
		return services.any { 
			it.resolveInfo.serviceInfo.packageName != myPackageName && 
			(it.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0 
		}
	}
}
