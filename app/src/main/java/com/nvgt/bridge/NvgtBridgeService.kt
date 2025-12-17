package com.nvgt.bridge

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Region
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityWindowInfo

class NvgtBridgeService : AccessibilityService() {

	companion object {
		private const val NVGT_METADATA_KEY = "org.nvgt.capability.DIRECT_TOUCH"
		private const val TAG = "NVGT_BRIDGE"
		private const val PREFS_NAME = "nvgt_bridge_prefs"
		private const val KEY_ENABLED_APPS = "enabled_app_packages"
		private val IGNORED_SYSTEM_PACKAGES = setOf(
			"com.android.systemui",
			"com.android.inputmethod",
			"com.google.android.inputmethod",
			"android"
		)
	}

	private var currentNvgtPackage: String? = null
	private lateinit var prefs: SharedPreferences
	private var accessibilityManager: AccessibilityManager? = null

	private val servicesStateChangeListener = AccessibilityManager.AccessibilityServicesStateChangeListener {
		if (!isOtherTouchExplorationEnabled()) {
			Log.w(TAG, "Touch exploration disabled. Killing Bridge.")
			disableDirectTouch()
		}
	}

	private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if (key == KEY_ENABLED_APPS) {
			Log.i(TAG, "Enabled apps updated")
		}
	}

	@SuppressLint("NewApi")
	override fun onServiceConnected() {
		super.onServiceConnected()
		Log.i(TAG, "Bridge Service Connected")
		prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
		
		accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
		accessibilityManager?.addAccessibilityServicesStateChangeListener(servicesStateChangeListener)
		
		val info = serviceInfo
		info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
		serviceInfo = info
	}

	@SuppressLint("NewApi")
	override fun onDestroy() {
		super.onDestroy()
		prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
		accessibilityManager?.removeAccessibilityServicesStateChangeListener(servicesStateChangeListener)
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent) {
		when (event.eventType) {
			AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
			AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
				if (currentNvgtPackage != null) {
					updatePassthroughRegion()
				}
			}
			else -> {}
		}
	}

	override fun onInterrupt() {
		Log.w(TAG, "Service Interrupted")
	}

	private fun handleWindowStateChanged(event: AccessibilityEvent) {
		val packageName = event.packageName?.toString() ?: return
		if (packageName == currentNvgtPackage) {
			enableDirectTouch() 
			return
		}
		if (isSystemPackage(packageName)) {
			if (currentNvgtPackage != null) {
				updatePassthroughRegion()
			}
			return
		}
		if (shouldEnableBridgeForPackage(packageName)) {
			Log.i(TAG, "Target detected: $packageName")
			currentNvgtPackage = packageName
			enableDirectTouch()
			return
		}
		if (currentNvgtPackage != null) {
			Log.i(TAG, "Exited target to '$packageName'. Disabling Bridge.")
			disableDirectTouch()
		}
	}

	private fun isSystemPackage(packageName: String): Boolean {
		return IGNORED_SYSTEM_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
	}

	private fun isOtherTouchExplorationEnabled(): Boolean {
		val am = accessibilityManager ?: return false
		val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
		
		return services.any { service ->
			service.resolveInfo.serviceInfo.packageName != packageName &&
			(service.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0
		}
	}

	private fun shouldEnableBridgeForPackage(packageName: String): Boolean {
		val enabledPackages = prefs.getStringSet(KEY_ENABLED_APPS, emptySet())
		if (enabledPackages?.contains(packageName) == true) return true

		val pm = packageManager
		
		try {
			val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
			if (appInfo.metaData?.getBoolean(NVGT_METADATA_KEY, false) == true) return true
		} catch (_: Exception) {}

		try {
			val launchIntent = pm.getLaunchIntentForPackage(packageName)
			launchIntent?.component?.let { component ->
				val activityInfo = pm.getActivityInfo(component, PackageManager.GET_META_DATA)
				if (activityInfo.metaData?.getBoolean(NVGT_METADATA_KEY, false) == true) return true
			}
		} catch (_: Exception) {}
		
		return false
	}

	private fun enableDirectTouch() {
		val info = serviceInfo
		if ((info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) == 0) {
			Log.i(TAG, "Enabling Direct Touch Mode")
			info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
			info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
			serviceInfo = info
		}
		updatePassthroughRegion()
	}

	private fun disableDirectTouch() {
		Log.i(TAG, "Disabling Direct Touch Mode")
		currentNvgtPackage = null
		val info = serviceInfo
		info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
		serviceInfo = info
		setTouchExplorationPassthroughRegion(0, Region())
	}

	private fun updatePassthroughRegion() {
		if (currentNvgtPackage == null) return

		val metrics = resources.displayMetrics
		val finalRegion = Region(0, 0, metrics.widthPixels, metrics.heightPixels)
		val windowBounds = Rect()

		windows.forEach { window ->
			if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || window.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
				window.getBoundsInScreen(windowBounds)
				finalRegion.op(windowBounds, Region.Op.DIFFERENCE)
			}
		}

		if (!finalRegion.isEmpty) {
			setTouchExplorationPassthroughRegion(0, finalRegion)
		}
	}
}
