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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Turns Minimal Mode on at the start time and off at the end time on chosen days.
 *
 * The schedule only acts at those moments, so turning Minimal Mode off by hand during a
 * scheduled period keeps it off until the next start. The next moment is also saved, so one
 * missed while the phone was off is applied when it starts again.
 */
class MinimalModeSchedule(private val context: Context) {

    data class Boundary(val time: ZonedDateTime, val turnOn: Boolean)

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val savedState = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Sets an alarm for the next start or end, or cancels it when the schedule is off. */
    fun reschedule(isEnabled: Boolean, startMinute: Int, endMinute: Int, days: Set<DayOfWeek>) {
        val boundary = if (isEnabled) nextBoundary(ZonedDateTime.now(), startMinute, endMinute, days) else null
        if (boundary == null) {
            alarmManager?.cancel(alarmIntent())
            savedState.edit().clear().apply()
            return
        }
        val millis = boundary.time.toInstant().toEpochMilli()
        savedState.edit()
            .putLong(KEY_TIME, millis)
            .putBoolean(KEY_TURN_ON, boundary.turnOn)
            .apply()
        // Exact alarms need a permission Lawnchair doesn't have, so allow a short window instead.
        // A plain inexact alarm can run up to an hour late. While the phone is idle it can still
        // be later, but the launcher also applies a due start or end whenever it's shown.
        alarmManager?.setWindow(AlarmManager.RTC_WAKEUP, millis, ALARM_WINDOW_MS, alarmIntent())
    }

    /**
     * Returns whether Minimal Mode should be on, if a saved start or end has come, and forgets it.
     */
    fun takeDueBoundary(): Boolean? {
        val millis = savedState.getLong(KEY_TIME, -1L)
        if (millis < 0 || Instant.now().toEpochMilli() < millis) return null
        val turnOn = savedState.getBoolean(KEY_TURN_ON, false)
        savedState.edit().clear().apply()
        return turnOn
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, MinimalModeScheduleReceiver::class.java).setAction(ACTION_SCHEDULE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_SCHEDULE = "app.lawnchair.minimal.SCHEDULE"

        private const val PREFS_NAME = "minimal_mode_schedule"
        private const val KEY_TIME = "next_time"
        private const val KEY_TURN_ON = "next_turn_on"
        private const val ALARM_WINDOW_MS = 10 * 60 * 1000L

        /**
         * The first start or end of a scheduled period after [now], or null if nothing is
         * scheduled. Periods start on [days]; one that ends earlier than it starts runs past
         * midnight into the next day.
         */
        fun nextBoundary(now: ZonedDateTime, startMinute: Int, endMinute: Int, days: Set<DayOfWeek>): Boundary? {
            if (days.isEmpty() || startMinute == endMinute) return null
            val startTime = LocalTime.of(startMinute / 60, startMinute % 60)
            val endTime = LocalTime.of(endMinute / 60, endMinute % 60)
            val endsNextDay = endMinute < startMinute
            var next: Boundary? = null
            // From yesterday, whose period may still be running past midnight, to a week ahead.
            for (offset in -1L..7L) {
                val day = now.toLocalDate().plusDays(offset)
                if (day.dayOfWeek !in days) continue
                val start = Boundary(ZonedDateTime.of(day, startTime, now.zone), turnOn = true)
                val endDay = if (endsNextDay) day.plusDays(1) else day
                val end = Boundary(ZonedDateTime.of(endDay, endTime, now.zone), turnOn = false)
                for (boundary in listOf(start, end)) {
                    if (boundary.time.isAfter(now) && (next == null || boundary.time.isBefore(next.time))) {
                        next = boundary
                    }
                }
            }
            return next
        }

        /** Whether [now] is inside a scheduled period. */
        fun isInPeriod(now: ZonedDateTime, startMinute: Int, endMinute: Int, days: Set<DayOfWeek>): Boolean = nextBoundary(now, startMinute, endMinute, days)?.turnOn == false
    }
}

/**
 * Runs the schedule's alarm, and catches up after a reboot or a clock change.
 */
class MinimalModeScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The manager applies a due start or end and sets the next alarm. Keep the process alive
        // until the change is saved.
        val pendingResult = goAsync()
        MinimalModeManager.getInstance(context).onScheduleEvent { pendingResult.finish() }
    }
}
