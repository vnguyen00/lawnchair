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
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.telecom.TelecomManager
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey

/**
 * Decides which apps may be launched while Minimal Mode is on.
 *
 * Apps are allowed per package and user: if any activity of an allowed app is in the list,
 * every activity and shortcut of that package (for that user) may be launched.
 */
class MinimalModePolicy(private val context: Context) {

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    private var allowedApps: Set<Pair<String, UserHandle>> = emptySet()

    /** @param keys [ComponentKey] strings, as stored in `PreferenceManager2.minimalModeApps`. */
    fun setAllowedApps(keys: Set<String>) {
        allowedApps = keys.mapNotNullTo(mutableSetOf()) { key ->
            ComponentKey.fromString(key)?.let { it.componentName.packageName to it.user }
        }
    }

    fun isAllowed(intent: Intent, item: ItemInfo?): Boolean {
        if (!isEnabled) return true
        // Nothing resolves, so the launch would fail anyway.
        val packageName = resolvePackage(intent, item) ?: return true
        if (isAlwaysAllowed(packageName)) return true
        val user = item?.user ?: Process.myUserHandle()
        return (packageName to user) in allowedApps
    }

    private fun resolvePackage(intent: Intent, item: ItemInfo?): String? = intent.component?.packageName
        ?: intent.`package`
        ?: item?.targetPackage
        ?: context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName

    private fun isAlwaysAllowed(packageName: String): Boolean {
        // "android" hosts the app chooser/resolver.
        if (packageName == context.packageName || packageName == "android") return true
        val telecomManager = context.getSystemService(TelecomManager::class.java)
        return packageName == telecomManager?.defaultDialerPackage
    }
}
