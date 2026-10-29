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

import android.content.Context
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.util.LawnchairSingletonHolder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * App-wide Minimal Mode state, shared by the launcher, the app drawer and icons.
 */
class MinimalModeManager private constructor(context: Context) {

    private val prefs2 = PreferenceManager2.getInstance(context)
    private val scope = MainScope()

    val policy = MinimalModePolicy(context)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits whenever the mode is turned on/off or the allowed apps change. */
    val changes: SharedFlow<Unit> = _changes

    init {
        // The preference cache is loaded synchronously, so even the first launch is checked.
        policy.isEnabled = prefs2.minimalModeEnabled.firstCached(prefs2)
        var allowedApps = prefs2.minimalModeApps.firstCached(prefs2)
        policy.setAllowedApps(allowedApps)

        prefs2.minimalModeEnabled.get().onEach {
            if (it == policy.isEnabled) return@onEach
            policy.isEnabled = it
            _changes.tryEmit(Unit)
        }.launchIn(scope)
        prefs2.minimalModeApps.get().onEach {
            if (it == allowedApps) return@onEach
            allowedApps = it
            policy.setAllowedApps(it)
            if (policy.isEnabled) _changes.tryEmit(Unit)
        }.launchIn(scope)
    }

    companion object : LawnchairSingletonHolder<MinimalModeManager>(::MinimalModeManager)
}
