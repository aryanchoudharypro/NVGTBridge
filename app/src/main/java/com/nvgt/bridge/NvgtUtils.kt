package com.nvgt.bridge

import android.content.pm.PackageManager

object NvgtUtils {
	private const val NVGT_METADATA_KEY = "dev.nvgt.capability.DIRECT_TOUCH"

	fun hasNvgtSupport(pm: PackageManager, packageName: String): Boolean {
		try {
			val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
			if (appInfo.metaData?.getBoolean(NVGT_METADATA_KEY, false) == true) {
				return true
			}

			val launchIntent = pm.getLaunchIntentForPackage(packageName)
			launchIntent?.component?.let { component ->
				val activityInfo = pm.getActivityInfo(component, PackageManager.GET_META_DATA)
				return activityInfo.metaData?.getBoolean(NVGT_METADATA_KEY, false) == true
			}
		} catch (_: Exception) {}
		return false
	}
}
