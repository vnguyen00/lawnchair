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

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.views.BaseDragLayer

/**
 * A small pill at the top of the home screen that shows Minimal Mode is on. Tapping it opens
 * Minimal Mode settings. The launcher is recreated when the mode changes, so this only needs to
 * be added once when it starts.
 */
object MinimalModeIndicator {

    fun attach(launcher: Launcher) {
        val density = launcher.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val view = TextView(launcher).apply {
            text = launcher.getString(R.string.minimal_mode_label)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(6)
            setPadding(dp(12), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(0x99, 0, 0, 0))
            }
            ContextCompat.getDrawable(launcher, R.drawable.hourglass_24px)?.mutate()?.let { icon ->
                icon.setTint(Color.WHITE)
                icon.setBounds(0, 0, dp(16), dp(16))
                setCompoundDrawablesRelative(icon, null, null, null)
            }
            setOnClickListener {
                launcher.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("lawnchair://settings/minimal-mode"))
                        .setPackage(launcher.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        // The drag layer's own params, since it replaces others and drops their gravity.
        val params = BaseDragLayer.LayoutParams(
            BaseDragLayer.LayoutParams.WRAP_CONTENT,
            BaseDragLayer.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // The drag layer adds the status bar height on top of this.
            topMargin = dp(4)
        }
        launcher.dragLayer.addView(view, params)

        // Only on the home screen itself, not over the app drawer, overview or while dragging.
        fun update(state: LauncherState) {
            view.visibility = if (state == LauncherState.NORMAL) View.VISIBLE else View.GONE
        }
        update(launcher.stateManager.state)
        launcher.stateManager.addStateListener(object : StateManager.StateListener<LauncherState> {
            override fun onStateTransitionStart(toState: LauncherState) {
                if (toState != LauncherState.NORMAL) update(toState)
            }

            override fun onStateTransitionComplete(finalState: LauncherState) = update(finalState)
        })
    }
}
