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

package app.lawnchair.ui.preferences.components

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.minimal.toDaysOfWeek
import app.lawnchair.preferences.PreferenceAdapter
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import com.android.launcher3.R
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns Minimal Mode on and off at set times on chosen days.
 */
@Composable
fun MinimalModeSchedulePreferences(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val enabledAdapter = prefs2.minimalModeScheduleEnabled.getAdapter()
    val daysAdapter = prefs2.minimalModeScheduleDays.getAdapter()
    var showDaysDialog by remember { mutableStateOf(false) }

    PreferenceGroup(
        modifier = modifier,
        heading = stringResource(R.string.minimal_mode_schedule_label),
    ) {
        SwitchPreference(
            adapter = enabledAdapter,
            label = stringResource(R.string.minimal_mode_schedule_enable_label),
            description = stringResource(R.string.minimal_mode_schedule_enable_description),
        )
        if (enabledAdapter.state.value) {
            TimePreference(
                label = stringResource(R.string.minimal_mode_schedule_start),
                adapter = prefs2.minimalModeScheduleStart.getAdapter(),
            )
            TimePreference(
                label = stringResource(R.string.minimal_mode_schedule_end),
                adapter = prefs2.minimalModeScheduleEnd.getAdapter(),
            )
            val days = daysAdapter.state.value.toDaysOfWeek()
            ClickablePreference(
                label = stringResource(R.string.minimal_mode_schedule_days),
                subtitle = if (days.isEmpty()) {
                    stringResource(R.string.minimal_mode_schedule_no_days)
                } else {
                    DayOfWeek.entries.filter { it in days }.joinToString { it.displayName(TextStyle.SHORT) }
                },
                onClick = { showDaysDialog = true },
            )
        }
    }

    if (showDaysDialog) {
        DaysDialog(
            initialDays = daysAdapter.state.value.toDaysOfWeek(),
            onDismiss = { showDaysDialog = false },
            onConfirm = { days ->
                showDaysDialog = false
                daysAdapter.onChange(days.map { it.value.toString() }.toSet())
            },
        )
    }
}

/** A time of day, stored as minutes after midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePreference(
    label: String,
    adapter: PreferenceAdapter<Int>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val minutes = adapter.state.value
    var showPicker by remember { mutableStateOf(false) }

    ClickablePreference(
        label = label,
        subtitle = LocalTime.of(minutes / 60, minutes % 60)
            .format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a")),
        modifier = modifier,
        onClick = { showPicker = true },
    )

    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            is24Hour = is24Hour,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPicker = false
                        adapter.onChange(state.hour * 60 + state.minute)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DaysDialog(
    initialDays: Set<DayOfWeek>,
    onDismiss: () -> Unit,
    onConfirm: (Set<DayOfWeek>) -> Unit,
) {
    var days by remember { mutableStateOf(initialDays) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.minimal_mode_schedule_days)) },
        text = {
            Column {
                DayOfWeek.entries.forEach { day ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { days = if (day in days) days - day else days + day }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = day in days, onCheckedChange = null)
                        Text(day.displayName(TextStyle.FULL), modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(days) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun DayOfWeek.displayName(style: TextStyle) = getDisplayName(style, Locale.getDefault())
