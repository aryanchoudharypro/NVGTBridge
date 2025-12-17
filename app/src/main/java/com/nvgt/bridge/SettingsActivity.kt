package com.nvgt.bridge

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SettingsActivity : AppCompatActivity() {

	private lateinit var appsAdapter: AppsAdapter
	private val appsList = mutableListOf<AppInfo>()
	private val enabledApps = mutableSetOf<String>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settings)

		val toolbar: Toolbar = findViewById(R.id.toolbar)
		setSupportActionBar(toolbar)

		loadEnabledApps()
		
		Thread {
			loadInstalledApps()
			runOnUiThread {
				if (::appsAdapter.isInitialized) {
					appsAdapter.filter("")
				}
			}
		}.start()

		val recyclerView: RecyclerView = findViewById(R.id.apps_recycler_view)
		recyclerView.layoutManager = LinearLayoutManager(this)
		
		appsAdapter = AppsAdapter(appsList) { app, isEnabled ->
			val index = appsList.indexOfFirst { it.packageName == app.packageName }
			if (index != -1) {
				appsList[index].isEnabled = isEnabled
				
				if (isEnabled) {
					enabledApps.add(app.packageName)
				} else {
					enabledApps.remove(app.packageName)
				}
				saveEnabledApps()
			}
			Log.d("SettingsActivity", "App ${app.name} isEnabled: $isEnabled")
		}
		recyclerView.adapter = appsAdapter

		val searchEditText: EditText = findViewById(R.id.search_edit_text)
		searchEditText.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				appsAdapter.filter(s.toString())
			}

			override fun afterTextChanged(s: Editable?) {}
		})
	}

	private fun loadInstalledApps() {
		val pm = packageManager
		val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
		
		val tempAppList = mutableListOf<AppInfo>()
		
		for (packageInfo in packages) {
			if (pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
				val appName = packageInfo.loadLabel(pm).toString()
				val appIcon = packageInfo.loadIcon(pm)
				val packageName = packageInfo.packageName
				val isEnabled = enabledApps.contains(packageName)
				tempAppList.add(AppInfo(appName, packageName, appIcon, isEnabled))
			}
		}
		tempAppList.sortBy { it.name }
		
		appsList.clear()
		appsList.addAll(tempAppList)
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
