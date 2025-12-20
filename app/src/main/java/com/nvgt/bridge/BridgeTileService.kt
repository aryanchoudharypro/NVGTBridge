package com.nvgt.bridge

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class BridgeTileService : TileService() {
	
	override fun onStartListening() {
		super.onStartListening()
		updateTileState()
	}

	override fun onClick() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		val currentState = prefs.getBoolean("master_switch", true)
		val newState = !currentState
		
		prefs.edit().putBoolean("master_switch", newState).apply()
		updateTileState()
	}

	private fun updateTileState() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		val isActive = prefs.getBoolean("master_switch", true)
		val tile = qsTile ?: return
		tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
		tile.label = if (isActive) "Bridge ON" else "Bridge OFF"
		tile.contentDescription = if (isActive) "Bridge is Active. Double tap to disable." else "Bridge is Paused. Double tap to enable."
		
		tile.updateTile()
	}
}