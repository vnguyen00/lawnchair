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

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.android.launcher3.R

/**
 * Blocks apps that aren't allowed in Minimal Mode however they're opened (notifications, links
 * from other apps, recents), by sending the user back to the home screen.
 *
 * Accessibility events only carry a package name, not a user, so an app allowed in one profile
 * is allowed in all of them here.
 */
class MinimalModeEnforcer(private val service: AccessibilityService) {

    private val policy = MinimalModeManager.getInstance(service).policy
    private val packageManager = service.packageManager

    // System packages that must keep working, looked up when first needed.
    private var systemPackages: Set<String>? = null

    private var lastToastPackage: String? = null
    private var lastToastTime = 0L

    val isActive: Boolean get() = policy.isEnabled

    /** Call when Minimal Mode or the allowed apps change. */
    fun refresh() {
        systemPackages = null
    }

    fun onWindowStateChanged(event: AccessibilityEvent) {
        if (!policy.isEnabled) return
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        // Dialogs, toasts and the keyboard also send this event; only full apps are blocked.
        if (!isActivity(packageName, className)) return
        if (policy.isPackageAllowed(packageName) || packageName in getSystemPackages()) return

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        // An app that keeps reopening itself shouldn't flood the screen with toasts.
        val now = SystemClock.uptimeMillis()
        if (packageName == lastToastPackage && now - lastToastTime < TOAST_INTERVAL_MS) return
        lastToastPackage = packageName
        lastToastTime = now
        Toast.makeText(
            service,
            service.getString(R.string.minimal_mode_app_blocked_named, appLabel(packageName)),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun isActivity(packageName: String, className: String): Boolean = runCatching {
        packageManager.getActivityInfo(ComponentName(packageName, className), 0)
    }.isSuccess

    private fun appLabel(packageName: String): CharSequence = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
    }.getOrDefault(packageName)

    private fun getSystemPackages(): Set<String> = systemPackages ?: buildSet {
        add("com.android.systemui")
        // Emergency calls.
        add("com.android.phone")
        // Keyboards, so allowed apps can still be typed into.
        service.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.forEach { add(it.packageName) }
        // Permission prompts and app installs started by allowed apps.
        listOf(
            Intent("android.content.pm.action.REQUEST_PERMISSIONS"),
            @Suppress("DEPRECATION") Intent(Intent.ACTION_INSTALL_PACKAGE).setType("application/vnd.android.package-archive"),
        ).forEach { intent ->
            packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName?.let(::add)
        }
    }.also { systemPackages = it }

    companion object {
        private const val TOAST_INTERVAL_MS = 2000L
    }
}
