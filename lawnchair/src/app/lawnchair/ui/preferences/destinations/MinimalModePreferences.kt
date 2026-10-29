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

package app.lawnchair.ui.preferences.destinations

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.AppItemPlaceholder
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.navigation.MinimalModeApps
import app.lawnchair.util.appsState
import com.android.launcher3.R

@Composable
fun MinimalModePreferences(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val resources = LocalContext.current.resources
    val allowedApps = prefs2.minimalModeApps.getAdapter().state.value
    // Lawndeck swaps the whole home screen too, so the two can't be on together.
    val deckLayout = prefs2.deckLayout.getAdapter().state.value

    PreferenceLayout(
        label = stringResource(id = R.string.minimal_mode_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup {
            SwitchPreference(
                adapter = prefs2.minimalModeEnabled.getAdapter(),
                label = stringResource(id = R.string.minimal_mode_enable_label),
                description = stringResource(
                    id = if (deckLayout) R.string.minimal_mode_unavailable_with_deck else R.string.minimal_mode_enable_description,
                ),
                enabled = !deckLayout,
            )
            NavigationActionPreference(
                label = stringResource(id = R.string.minimal_mode_allowed_apps_label),
                destination = MinimalModeApps,
                subtitle = resources.getQuantityString(R.plurals.apps_count, allowedApps.size, allowedApps.size),
            )
        }
    }
}

@Composable
fun MinimalModeAppsPreferences(
    modifier: Modifier = Modifier,
) {
    val adapter = preferenceManager2().minimalModeApps.getAdapter()
    val allowedApps by adapter.state
    val pageTitle =
        if (allowedApps.isEmpty()) {
            stringResource(id = R.string.minimal_mode_allowed_apps_label)
        } else {
            stringResource(id = R.string.minimal_mode_allowed_apps_label_with_count, allowedApps.size)
        }
    // Allowed apps first, then alphabetical.
    val apps by appsState(comparator = hiddenAppsComparator(allowedApps))
    val state = rememberLazyListState()
    PreferenceScaffold(
        label = pageTitle,
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) {
        Crossfade(targetState = apps.isNotEmpty(), label = "") { present ->
            if (present) {
                PreferenceLazyColumn(it, state = state) {
                    preferenceGroupItems(
                        items = apps,
                        isFirstChild = true,
                    ) { _, app ->
                        val key = app.key.toString()
                        AppItem(
                            app = app,
                            onClick = {
                                adapter.onChange(
                                    if (key in allowedApps) allowedApps - key else allowedApps + key,
                                )
                            },
                        ) {
                            Checkbox(
                                checked = key in allowedApps,
                                onCheckedChange = null,
                            )
                        }
                    }
                }
            } else {
                PreferenceLazyColumn(it, enabled = false) {
                    preferenceGroupItems(
                        count = 20,
                        isFirstChild = true,
                    ) {
                        AppItemPlaceholder {
                            Spacer(Modifier.width(24.dp))
                        }
                    }
                }
            }
        }
    }
}
