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
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * App-wide Minimal Mode state, shared by the launcher, the app drawer and icons. Also switches
 * the home screen to its Minimal Mode layout and back, and runs the schedule.
 */
class MinimalModeManager private constructor(context: Context) {

    private val prefs2 = PreferenceManager2.getInstance(context)
    private val scope = MainScope()

    val policy = MinimalModePolicy(context)
    private val layoutSwitcher = MinimalModeLayoutSwitcher(context, policy)
    private val schedule = MinimalModeSchedule(context)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits whenever the mode is turned on/off or the allowed apps change. */
    val changes: SharedFlow<Unit> = _changes

    init {
        // The preference cache is loaded synchronously, so even the first launch is checked.
        policy.isEnabled = prefs2.minimalModeEnabled.firstCached(prefs2)
        var allowedApps = prefs2.minimalModeApps.firstCached(prefs2)
        policy.setAllowedApps(allowedApps)
        // Catches up if the app stopped before the home screen layout was switched.
        layoutSwitcher.apply(policy.isEnabled)

        prefs2.minimalModeEnabled.get().onEach {
            if (it == policy.isEnabled) return@onEach
            policy.isEnabled = it
            // An unlock from before shouldn't carry over to the next time the mode is on.
            if (it) MinimalModeLock.lock()
            layoutSwitcher.apply(it)
            _changes.tryEmit(Unit)
        }.launchIn(scope)
        prefs2.minimalModeApps.get().onEach {
            if (it == allowedApps) return@onEach
            allowedApps = it
            policy.setAllowedApps(it)
            if (policy.isEnabled) _changes.tryEmit(Unit)
        }.launchIn(scope)

        var isFirstSchedule = true
        combine(
            prefs2.minimalModeScheduleEnabled.get(),
            prefs2.minimalModeScheduleStart.get(),
            prefs2.minimalModeScheduleEnd.get(),
            prefs2.minimalModeScheduleDays.get(),
        ) { isEnabled, start, end, days -> ScheduleSettings(isEnabled, start, end, days.toDaysOfWeek()) }
            .onEach { settings ->
                if (isFirstSchedule) {
                    isFirstSchedule = false
                    // A start or end that passed while the phone was off or Lawnchair wasn't running.
                    schedule.takeDueBoundary()?.let(::setEnabled)
                } else if (settings.isEnabled && settings.isInPeriodNow()) {
                    // Just set up during a scheduled period: start now instead of waiting a day.
                    setEnabled(true)
                }
                schedule.reschedule(settings.isEnabled, settings.start, settings.end, settings.days)
            }.launchIn(scope)
    }

    /**
     * Called by the schedule's alarm and after a reboot or clock change. Calls [onDone] once any
     * change is saved.
     */
    fun onScheduleEvent(onDone: () -> Unit = {}) {
        scope.launch {
            schedule.takeDueBoundary()?.let { prefs2.minimalModeEnabled.set(it) }
            rescheduleFromPrefs()
            onDone()
        }
    }

    /**
     * Applies a start or end that's due but whose alarm hasn't run yet. Called when the home
     * screen is shown, since Android can delay the alarm.
     */
    fun applyDueSchedule() {
        val turnOn = schedule.takeDueBoundary() ?: return
        scope.launch {
            prefs2.minimalModeEnabled.set(turnOn)
            rescheduleFromPrefs()
        }
    }

    private fun rescheduleFromPrefs() {
        schedule.reschedule(
            prefs2.minimalModeScheduleEnabled.firstCached(prefs2),
            prefs2.minimalModeScheduleStart.firstCached(prefs2),
            prefs2.minimalModeScheduleEnd.firstCached(prefs2),
            prefs2.minimalModeScheduleDays.firstCached(prefs2).toDaysOfWeek(),
        )
    }

    private fun setEnabled(isEnabled: Boolean) {
        scope.launch { prefs2.minimalModeEnabled.set(isEnabled) }
    }

    private data class ScheduleSettings(
        val isEnabled: Boolean,
        val start: Int,
        val end: Int,
        val days: Set<DayOfWeek>,
    ) {
        fun isInPeriodNow() = MinimalModeSchedule.isInPeriod(ZonedDateTime.now(), start, end, days)
    }

    companion object : LawnchairSingletonHolder<MinimalModeManager>(::MinimalModeManager)
}

/** ISO day numbers (1 = Monday) as stored in preferences. */
fun Set<String>.toDaysOfWeek(): Set<DayOfWeek> = mapNotNullTo(mutableSetOf()) { it.toIntOrNull()?.takeIf { day -> day in 1..7 }?.let(DayOfWeek::of) }
