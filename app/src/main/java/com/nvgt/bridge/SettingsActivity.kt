package com.nvgt.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

	private lateinit var appsAdapter: AppsAdapter
	private val appsList = mutableListOf<AppInfo>()
	private val enabledApps = mutableSetOf<String>()
	private var currentSearchQuery = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settings)

		val toolbar: Toolbar = findViewById(R.id.toolbar)
		setSupportActionBar(toolbar)

		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)

		val hapticSwitch: SwitchCompat = findViewById(R.id.master_haptic_switch)
		hapticSwitch.isChecked = prefs.getBoolean("haptics_enabled", true)
		hapticSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.edit().putBoolean("haptics_enabled", isChecked).apply()
		}

		loadEnabledApps()
		
		val recyclerView: RecyclerView = findViewById(R.id.apps_recycler_view)
		recyclerView.layoutManager = LinearLayoutManager(this)
		
		appsAdapter = AppsAdapter(emptyList(), 
			onSwitchChanged = { app, isEnabled ->
				val index = appsList.indexOfFirst { it.packageName == app.packageName }
				if (index != -1) {
					appsList[index].isEnabled = isEnabled
					
					if (isEnabled) {
						enabledApps.add(app.packageName)
					} else {
						enabledApps.remove(app.packageName)
					}
					saveEnabledApps()
					
					// Update list to reflect move between sections
					lifecycleScope.launch {
						updateAppList(currentSearchQuery)
					}
				}
			},
			onAppLongClicked = { app ->
				showAppConfigDialog(app)
			}
		)
		recyclerView.adapter = appsAdapter

		lifecycleScope.launch {
			loadInstalledApps()
			updateAppList(currentSearchQuery)
		}

		val searchEditText: EditText = findViewById(R.id.search_edit_text)
		searchEditText.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				currentSearchQuery = s.toString()
				lifecycleScope.launch {
					updateAppList(currentSearchQuery)
				}
			}

			override fun afterTextChanged(s: Editable?) {}
		})
	}

	private suspend fun updateAppList(query: String) {
		val newItems = withContext(Dispatchers.Default) {
			val filtered = if (query.isEmpty()) {
				appsList
			} else {
				appsList.filter { it.name.contains(query, ignoreCase = true) }
			}

			val enabled = filtered.filter { it.isEnabled }.sortedBy { it.name }
			val disabled = filtered.filter { !it.isEnabled }.sortedBy { it.name }

			val items = mutableListOf<AppListItem>()

			if (enabled.isNotEmpty()) {
				items.add(AppListItem.Header("Direct Touch Enabled Apps"))
				items.addAll(enabled.map { AppListItem.App(it) })
			}

			if (disabled.isNotEmpty()) {
				items.add(AppListItem.Header("All Apps"))
				items.addAll(disabled.map { AppListItem.App(it) })
			}
			items
		}

		appsAdapter.updateItems(newItems)
	}

	private fun showAppConfigDialog(app: AppInfo) {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		val keyDirectTyping = "direct_typing_${app.packageName}"
		
		var isDirectTyping = prefs.getBoolean(keyDirectTyping, false)

		val builder = AlertDialog.Builder(this)
		builder.setTitle("Configure settings for ${app.name}")
		
		val options = arrayOf("Direct Typing (Don't cut out keyboard)")
		val checkedItems = booleanArrayOf(isDirectTyping)

		builder.setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
			if (which == 0) {
				isDirectTyping = isChecked
			}
		}

		builder.setPositiveButton("Save") { _, _ ->
			prefs.edit().putBoolean(keyDirectTyping, isDirectTyping).apply()
			
			app.directTyping = isDirectTyping
			appsAdapter.notifyDataSetChanged()
		}

		builder.setNegativeButton("Cancel", null)
		builder.show()
	}

	private suspend fun loadInstalledApps() {
		withContext(Dispatchers.IO) {
			val pm = packageManager
			val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
			val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
			
			val tempAppList = mutableListOf<AppInfo>()
			var newNativeAppsFound = false
			
			for (packageInfo in packages) {
				if (pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
					val appName = packageInfo.loadLabel(pm).toString()
					val appIcon = packageInfo.loadIcon(pm)
					val packageName = packageInfo.packageName
					
					var isEnabled = enabledApps.contains(packageName)
					
					if (!isEnabled) {
						if (NvgtUtils.hasNvgtSupport(pm, packageName)) {
							isEnabled = true
							enabledApps.add(packageName)
							newNativeAppsFound = true
						}
					}
					
					val directTyping = prefs.getBoolean("direct_typing_$packageName", false)

					tempAppList.add(AppInfo(appName, packageName, appIcon, isEnabled, directTyping))
				}
			}
			
			if (newNativeAppsFound) {
				saveEnabledApps()
			}

			tempAppList.sortBy { it.name }
			
			appsList.clear()
			appsList.addAll(tempAppList)
		}
	}

	private fun saveEnabledApps() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", MODE_PRIVATE)
		prefs.edit().putStringSet("enabled_app_packages", enabledApps.toSet()).apply()
	}

	private fun loadEnabledApps() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", MODE_PRIVATE)
		val savedSet = prefs.getStringSet("enabled_app_packages", emptySet())
		enabledApps.clear()
		if (savedSet != null) {
			enabledApps.addAll(savedSet)
		}
	}
}