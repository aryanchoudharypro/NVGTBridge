package com.nvgt.bridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView

class AppsAdapter(
	private val apps: MutableList<AppInfo>,
	private val onSwitchChanged: (AppInfo, Boolean) -> Unit,
	private val onAppLongClicked: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

	private var filteredApps: MutableList<AppInfo> = apps.toMutableList()

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = LayoutInflater.from(parent.context)
			.inflate(R.layout.item_app, parent, false)
		return ViewHolder(view)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val app = filteredApps[position]
		holder.appName.text = app.name
		holder.appIcon.setImageDrawable(app.icon)
		
		holder.appSwitch.setOnCheckedChangeListener(null)
		holder.appSwitch.isChecked = app.isEnabled

		holder.itemView.setOnClickListener {
			val newState = !app.isEnabled
			app.isEnabled = newState
			holder.appSwitch.isChecked = newState
			onSwitchChanged(app, newState)
		}
		
		holder.itemView.setOnLongClickListener {
			onAppLongClicked(app)
			true
		}

		ViewCompat.addAccessibilityAction(
			holder.itemView,
			"Configure settings for ${app.name}"
		) { _, _ ->
			onAppLongClicked(app)
			true
		}
		
		holder.appSwitch.setOnClickListener {
			val newState = holder.appSwitch.isChecked
			app.isEnabled = newState
			onSwitchChanged(app, newState)
		}
	}

	override fun getItemCount(): Int {
		return filteredApps.size
	}

	fun filter(query: String) {
		filteredApps = if (query.isEmpty()) {
			apps.toMutableList()
		} else {
			apps.filter { it.name.contains(query, ignoreCase = true) }.toMutableList()
		}
		notifyDataSetChanged()
	}

	class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		val appName: TextView = view.findViewById(R.id.app_name)
		val appIcon: ImageView = view.findViewById(R.id.app_icon)
		val appSwitch: SwitchCompat = view.findViewById(R.id.app_switch)
	}
}
