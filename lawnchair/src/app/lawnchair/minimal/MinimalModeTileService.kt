/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.minimal

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for Minimal Mode. Turning it on is one tap; turning it off opens Minimal
 * Mode settings when a lock is set, so it can't be used to get around the lock.
 */
class MinimalModeTileService : TileService() {

    private val scope = MainScope()
    private val prefs2 by lazy { PreferenceManager2.getInstance(this) }
    private var listening: Job? = null

    override fun onStartListening() {
        listening = prefs2.minimalModeEnabled.get().onEach(::updateTile).launchIn(scope)
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        val isEnabled = prefs2.minimalModeEnabled.firstCached(prefs2)
        when {
            // Lawndeck can't be on at the same time; settings explain why.
            !isEnabled && prefs2.deckLayout.firstCached(prefs2) -> openSettings()
            !isEnabled -> scope.launch { prefs2.minimalModeEnabled.set(true) }
            isMinimalModeLocked() -> openSettings()
            else -> scope.launch { prefs2.minimalModeEnabled.set(false) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isMinimalModeLocked(): Boolean {
        if (MinimalModeLock.isUnlocked()) return false
        return when (MinimalModeLock.Type.fromName(prefs2.minimalModeLock.firstCached(prefs2))) {
            MinimalModeLock.Type.NONE -> false
            MinimalModeLock.Type.PIN -> true
            MinimalModeLock.Type.DEVICE -> MinimalModeLock.isDeviceLockAvailable(this)
        }
    }

    private fun updateTile(isEnabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.minimal_mode_label)
        tile.updateTile()
    }

    private fun openSettings() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SETTINGS_URI))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val SETTINGS_URI = "lawnchair://settings/minimal-mode"
    }
}
