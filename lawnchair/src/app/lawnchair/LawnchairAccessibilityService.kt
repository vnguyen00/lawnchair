/*
 * Copyright 2021, Lawnchair
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

package app.lawnchair

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import app.lawnchair.minimal.MinimalModeEnforcer
import app.lawnchair.minimal.MinimalModeManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class LawnchairAccessibilityService : AccessibilityService() {

    private val scope = MainScope()
    private lateinit var minimalModeEnforcer: MinimalModeEnforcer

    override fun onServiceConnected() {
        minimalModeEnforcer = MinimalModeEnforcer(this)
        updateServiceInfo()
        MinimalModeManager.getInstance(this).changes.onEach {
            minimalModeEnforcer.refresh()
            updateServiceInfo()
        }.launchIn(scope)
        lawnchairApp.accessibilityService = this
    }

    private fun updateServiceInfo() {
        serviceInfo = AccessibilityServiceInfo().apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // Gestures only need performGlobalAction, which takes no events. Minimal Mode needs to
            // know which app opens, so it listens to every app while it's on.
            if (minimalModeEnforcer.isActive) {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                packageNames = null
            } else {
                eventTypes = 0
                packageNames = emptyArray()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        lawnchairApp.accessibilityService = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            minimalModeEnforcer.onWindowStateChanged(event)
        }
    }
}
