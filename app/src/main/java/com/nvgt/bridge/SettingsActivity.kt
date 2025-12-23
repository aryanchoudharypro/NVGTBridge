package com.nvgt.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity() {

	private lateinit var appsAdapter: AppsAdapter
	private val appsList = mutableListOf<AppInfo>()
	private val enabledApps = mutableSetOf<String>()
	private var currentSearchQuery = ""

	private val backupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			result.data?.data?.let { uri ->
				performBackup(uri)
			}
		}
	}

	private val restoreLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			result.data?.data?.let { uri ->
				performRestore(uri)
			}
		}
	}

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

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.settings_menu, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_backup -> {
				val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
					addCategory(Intent.CATEGORY_OPENABLE)
					type = "application/json"
					putExtra(Intent.EXTRA_TITLE, "nvgt_bridge_backup.json")
				}
				backupLauncher.launch(intent)
				true
			}
			R.id.action_restore -> {
				val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
					addCategory(Intent.CATEGORY_OPENABLE)
					type = "application/json"
				}
				restoreLauncher.launch(intent)
				true
			}
			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun performBackup(uri: Uri) {
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
				val root = JSONObject()
				
				val appsArray = JSONArray()
				enabledApps.forEach { appsArray.put(it) }
				root.put("enabled_apps", appsArray)
				
				root.put("haptics_enabled", prefs.getBoolean("haptics_enabled", true))

				// Backup direct typing settings
				val directTypingObj = JSONObject()
				prefs.all.keys.filter { it.startsWith("direct_typing_") }.forEach { key ->
					directTypingObj.put(key, prefs.getBoolean(key, false))
				}
				root.put("direct_typing", directTypingObj)

				contentResolver.openOutputStream(uri)?.use { outputStream ->
					outputStream.write(root.toString(4).toByteArray())
				}

				withContext(Dispatchers.Main) {
					Toast.makeText(this@SettingsActivity, R.string.backup_success, Toast.LENGTH_SHORT).show()
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					Toast.makeText(this@SettingsActivity, R.string.error_backup, Toast.LENGTH_SHORT).show()
				}
			}
		}
	}

	private fun performRestore(uri: Uri) {
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val stringBuilder = StringBuilder()
				contentResolver.openInputStream(uri)?.use { inputStream ->
					BufferedReader(InputStreamReader(inputStream)).use { reader ->
						var line: String? = reader.readLine()
						while (line != null) {
							stringBuilder.append(line)
							line = reader.readLine()
						}
					}
				}

				val root = JSONObject(stringBuilder.toString())
				val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
				val editor = prefs.edit()

				if (root.has("enabled_apps")) {
					val appsArray = root.getJSONArray("enabled_apps")
					val newEnabledApps = mutableSetOf<String>()
					for (i in 0 until appsArray.length()) {
						newEnabledApps.add(appsArray.getString(i))
					}
					editor.putStringSet("enabled_app_packages", newEnabledApps)
					enabledApps.clear()
					enabledApps.addAll(newEnabledApps)
				}

				if (root.has("haptics_enabled")) {
					editor.putBoolean("haptics_enabled", root.getBoolean("haptics_enabled"))
				}

				if (root.has("direct_typing")) {
					val directTypingObj = root.getJSONObject("direct_typing")
					val keys = directTypingObj.keys()
					while (keys.hasNext()) {
						val key = keys.next()
						editor.putBoolean(key, directTypingObj.getBoolean(key))
					}
				}

				editor.apply()

				withContext(Dispatchers.Main) {
					// Refresh UI
					val hapticSwitch: SwitchCompat = findViewById(R.id.master_haptic_switch)
					hapticSwitch.isChecked = prefs.getBoolean("haptics_enabled", true)
					
					loadInstalledApps() // Reloads list and refreshes adapter
					updateAppList(currentSearchQuery)
					
					Toast.makeText(this@SettingsActivity, R.string.restore_success, Toast.LENGTH_SHORT).show()
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					Toast.makeText(this@SettingsActivity, R.string.error_restore, Toast.LENGTH_SHORT).show()
				}
			}
		}
	}
}