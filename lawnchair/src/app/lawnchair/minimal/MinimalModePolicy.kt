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
        set(value) {
            field = value
            dialerPackage = null
        }

    /** Allowed apps as (package name, user) pairs. */
    @Volatile
    var allowedApps: Set<Pair<String, UserHandle>> = emptySet()
        private set

    // Looked up lazily, since icons check it on every bind.
    @Volatile
    private var dialerPackage: String? = null

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
        return isAllowed(packageName, item?.user ?: Process.myUserHandle())
    }

    /** Whether [item] should be shown grayed out. */
    fun isBlocked(item: ItemInfo): Boolean {
        val packageName = item.targetPackage ?: return false
        return isBlocked(packageName, item.user ?: Process.myUserHandle())
    }

    fun isBlocked(packageName: String, user: UserHandle): Boolean = isEnabled && !isAllowed(packageName, user)

    private fun isAllowed(packageName: String, user: UserHandle): Boolean = isAlwaysAllowed(packageName) || (packageName to user) in allowedApps

    private fun resolvePackage(intent: Intent, item: ItemInfo?): String? = intent.component?.packageName
        ?: intent.`package`
        ?: item?.targetPackage
        ?: context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName

    private fun isAlwaysAllowed(packageName: String): Boolean {
        // "android" hosts the app chooser/resolver.
        if (packageName == context.packageName || packageName == "android") return true
        val dialer = dialerPackage
            ?: context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
                ?.also { dialerPackage = it }
        return packageName == dialer
    }
}
