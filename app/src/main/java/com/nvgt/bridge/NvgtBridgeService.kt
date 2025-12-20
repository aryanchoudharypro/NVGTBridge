package com.nvgt.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.Region
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class NvgtBridgeService : AccessibilityService() {

	companion object {
		private const val PREFS_NAME = "nvgt_bridge_prefs"
		private const val KEY_ENABLED_APPS = "enabled_app_packages"
		private const val KEY_MASTER_SWITCH = "master_switch"
		private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
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
	private val directTypingCache = mutableMapOf<String, Boolean>()
	
	private var vibrator: Vibrator? = null
	private var lastPassthroughState = false
	private val hapticAttributes = AudioAttributes.Builder()
		.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
		.setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
		.build()
	
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
		if (key != null && key.startsWith("direct_typing_")) {
			directTypingCache.clear()
		}
		if (key == KEY_MASTER_SWITCH) {
			performUpdate()
		}
	}

	override fun onServiceConnected() {
		super.onServiceConnected()
		prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
		
		accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
		accessibilityManager?.addAccessibilityServicesStateChangeListener(servicesStateChangeListener)
		
		vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

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
		directTypingCache.clear()
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
		val isMasterEnabled = prefs.getBoolean(KEY_MASTER_SWITCH, true)
		if (!isMasterEnabled) {
			if (currentNvgtPackage != null) {
				disableDirectTouch()
			}
			return
		}

		val allWindows = windows
		val rootWindow = allWindows.find { it.isFocused } ?: allWindows.firstOrNull()
		val currentAppPackage = rootWindow?.root?.packageName?.toString() ?: return

		val displayMetrics = resources.displayMetrics
		val screenHeight = displayMetrics.heightPixels

		val isSystemUIInFront = allWindows.any { window ->
			if (window.isActive && 
				window.type == AccessibilityWindowInfo.TYPE_SYSTEM && 
				window.root?.packageName?.toString() == "com.android.systemui") {
				
				val bounds = Rect()
				window.getBoundsInScreen(bounds)
				return@any bounds.height() > (screenHeight / 2)
			}
			false
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
		return windows.any { window ->
			if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION && 
				window.type != AccessibilityWindowInfo.TYPE_SYSTEM) {
				return@any false
			}

			val root = window.root ?: return@any false
			val pkg = root.packageName?.toString()

			if (IGNORED_SYSTEM_PACKAGES.contains(pkg)) {
				return@any false
			}
			
			scanNodeForDialogs(root, 0)
		}
	}

	private fun scanNodeForDialogs(node: AccessibilityNodeInfo, depth: Int): Boolean {
		if (depth > 10) return false

		val className = node.className?.toString() ?: ""
		
		if (className.contains("AlertDialog", ignoreCase = true) ||
			className.contains("android.app.Dialog", ignoreCase = true)) {
			return true
		}

		if (className.contains("EditText", ignoreCase = true)) {
			return true
		}

		if (node.isClickable && className.endsWith("Button")) {
			return true
		}
		
		for (i in 0 until node.childCount) {
			val child = node.getChild(i) ?: continue
			if (scanNodeForDialogs(child, depth + 1)) {
				child.recycle()
				return true
			}
			child.recycle()
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

		val isNvgt = NvgtUtils.hasNvgtSupport(packageManager, packageName)
		targetCache[packageName] = isNvgt
		return isNvgt
	}

	private fun isDirectTypingEnabled(packageName: String): Boolean {
		directTypingCache[packageName]?.let { return it }
		
		val isEnabled = prefs.getBoolean("direct_typing_$packageName", false)
		directTypingCache[packageName] = isEnabled
		return isEnabled
	}

	private fun playHapticFeedback(isEnabled: Boolean) {
		val hapticsOn = prefs.getBoolean(KEY_HAPTICS_ENABLED, true)
		if (!hapticsOn) return
		if (lastPassthroughState == isEnabled) return
		lastPassthroughState = isEnabled

		val vib = vibrator ?: return
		if (!vib.hasVibrator()) return

		try {
			if (isEnabled) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					val effect = VibrationEffect.createWaveform(
						longArrayOf(0, 30, 50, 30), 
						intArrayOf(0, 255, 0, 255), 
						-1
					)
					vib.vibrate(effect, hapticAttributes)
				} else {
					@Suppress("DEPRECATION")
					vib.vibrate(longArrayOf(0, 30, 50, 30), -1, hapticAttributes)
				}
			} else {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					val effect = VibrationEffect.createOneShot(100, 255)
					vib.vibrate(effect, hapticAttributes)
				} else {
					@Suppress("DEPRECATION")
					vib.vibrate(100, hapticAttributes)
				}
			}
		} catch (_: Exception) {
			@Suppress("DEPRECATION")
			vib.vibrate(100)
		}
	}

	private fun enableDirectTouch() {
		updatePassthroughRegion()
		playHapticFeedback(true)
	}

	private fun disableDirectTouch(keepPackage: Boolean = false) {
		if (!keepPackage) currentNvgtPackage = null
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			setTouchExplorationPassthroughRegion(0, Region())
		}
		playHapticFeedback(false)
	}

	private fun updatePassthroughRegion() {
		val currentPkg = currentNvgtPackage ?: return
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

		val metrics = resources.displayMetrics
		val finalRegion = Region(0, 0, metrics.widthPixels, metrics.heightPixels)
		val windowBounds = Rect()

		val directTyping = isDirectTypingEnabled(currentPkg)

		windows.forEach { window ->
			val shouldSubtract = if (directTyping) {
				window.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
				window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
			} else {
				window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || 
				window.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
				window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
			}
			
			if (shouldSubtract) {
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
