package com.bontecou.syncmd.automation

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bontecou.syncmd.MainActivity
import com.bontecou.syncmd.R
import com.bontecou.syncmd.background.BackgroundSyncEntryPoint
import com.bontecou.syncmd.background.BackgroundSyncJobs
import com.bontecou.syncmd.background.BackgroundSyncStore
import dagger.hilt.android.EntryPointAccessors

/**
 * Quick Settings "Background Sync": active while pull or push runs for at least one included vault,
 * subtitle "Pull on · Push off". A tap turns both off, remembering which were on, or turns the
 * remembered modes back on (pull only the first time). With no vault included yet it opens the app.
 */
class BackgroundSyncTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); render() }

    private fun render() {
        val configuration = runCatching { BackgroundSyncStore(this).read() }.getOrNull()
        qsTile?.apply {
            label = getString(R.string.tile_m3_background_sync)
            icon = Icon.createWithResource(this@BackgroundSyncTileService, R.drawable.ic_m3_sync)
            state = when {
                configuration == null -> Tile.STATE_UNAVAILABLE
                configuration.active -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            if (Build.VERSION.SDK_INT >= 29) subtitle = configuration?.let { modes(this@BackgroundSyncTileService, it.pull && it.active, it.push && it.active) }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val configuration = runCatching { BackgroundSyncStore(this).read() }.getOrNull() ?: return
        if (configuration.policies.values.none { it.included }) { openApp(); return }
        val memory = getSharedPreferences(MEMORY, Context.MODE_PRIVATE)
        val (pull, push) = if (configuration.active) {
            memory.edit().putBoolean("pull", configuration.pull).putBoolean("push", configuration.push).apply()
            false to false
        } else {
            val pull = memory.getBoolean("pull", true); val push = memory.getBoolean("push", false)
            if (!pull && !push) true to false else pull to push
        }
        runCatching {
            EntryPointAccessors.fromApplication(applicationContext, BackgroundSyncEntryPoint::class.java).background()
                .modes(pull, push, configuration.generation)
            BackgroundSyncJobs.preferencesChanged(this)
        }
        render()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 430, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val MEMORY = "background_sync_tile"
        fun modes(context: Context, pull: Boolean, push: Boolean): String = context.getString(R.string.tile_m3_modes,
            context.getString(if (pull) R.string.tile_m3_pull_on else R.string.tile_m3_pull_off),
            context.getString(if (push) R.string.tile_m3_push_on else R.string.tile_m3_push_off))
        /** Ask Quick Settings to re-read the tile after Background Sync settings change elsewhere. */
        fun changed(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, BackgroundSyncTileService::class.java)) }
        }
    }
}
