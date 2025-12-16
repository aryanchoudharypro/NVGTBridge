package com.nvgt.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Region
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class NvgtBridgeService : AccessibilityService() {

	companion object {
		private const val NVGT_METADATA_KEY = "org.nvgt.capability.DIRECT_TOUCH"
		private const val TAG = "NVGT_BRIDGE"
	}

	private var currentNvgtPackage: String? = null

	override fun onServiceConnected() {
		super.onServiceConnected()
		Log.e(TAG, "!!! BRIDGE SERVICE STARTED !!!")
		
		val info = serviceInfo
		info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
		serviceInfo = info
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent) {
		if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
			handleWindowStateChanged(event)
		} else if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
			if (currentNvgtPackage != null) {
				updatePassthroughRegion()
			}
		}
	}

	override fun onInterrupt() {
		Log.w(TAG, "System Interrupted Service.")
		if (currentNvgtPackage != null) {
			updatePassthroughRegion()
		}
	}

	private fun handleWindowStateChanged(event: AccessibilityEvent) {
		val packageName = event.packageName?.toString() ?: return
		val className = event.className?.toString() ?: return
		
		if (currentNvgtPackage != null) {
			if (packageName == currentNvgtPackage) {
				updatePassthroughRegion()
				return
			}
			if (packageName.contains("android") || packageName.contains("systemui") || packageName.contains("launcher")) {
				return
			}
		}

		val componentName = ComponentName(packageName, className)

		try {
			val activityInfo = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
			val isNvgt = activityInfo.metaData?.getBoolean(NVGT_METADATA_KEY, false) == true

			if (isNvgt) {
				Log.e(TAG, ">>> NVGT GAME DETECTED: $packageName <<<")
				currentNvgtPackage = packageName
				enableDirectTouch()
			} else {
				if (currentNvgtPackage != null) {
					Log.i(TAG, "Switching to non-game app. Disabling Bridge.")
					disableDirectTouch()
				}
			}
		} catch (e: PackageManager.NameNotFoundException) {
		}
	}

	private fun enableDirectTouch() {
		Log.e(TAG, "ENABLING TOUCH PASSTHROUGH MODE")
		val info = serviceInfo
		info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
		info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
		serviceInfo = info
		updatePassthroughRegion()
	}

	private fun disableDirectTouch() {
		Log.i(TAG, "Disabling Touch Passthrough Mode")
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
		
		val safeHeight = (metrics.heightPixels * 0.15).toInt()
		val safeRect = Rect(0, metrics.heightPixels - safeHeight, metrics.widthPixels, metrics.heightPixels)
		finalRegion.op(safeRect, Region.Op.DIFFERENCE)

		val windows = windows
		val windowBounds = Rect()
		var keyboardFound = false

		if (windows.isNotEmpty()) {
			for (window in windows) {
				if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
					window.getBoundsInScreen(windowBounds)
					finalRegion.op(windowBounds, Region.Op.DIFFERENCE)
					keyboardFound = true
				}
			}
		}

		if (keyboardFound) {
			Log.d(TAG, "Keyboard detected. Adjusted passthrough region.")
		}

		if (finalRegion.isEmpty) return

		setTouchExplorationPassthroughRegion(0, finalRegion)
	}
}
