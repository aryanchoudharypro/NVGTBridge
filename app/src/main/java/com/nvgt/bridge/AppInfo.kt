package com.nvgt.bridge

import android.graphics.drawable.Drawable

data class AppInfo(
	val name: String,
	val packageName: String,
	val icon: Drawable,
	var isEnabled: Boolean
)
