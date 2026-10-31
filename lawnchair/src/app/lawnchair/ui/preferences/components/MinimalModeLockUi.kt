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

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lawnchair.minimal.MinimalModeLock
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PIN_LENGTH = 4

class MinimalModeLockState(
    val isLocked: Boolean,
    val onUnlocked: () -> Unit,
)

/**
 * Whether Minimal Mode settings are locked right now: the mode is on, a lock is set and it
 * hasn't been unlocked in the last few minutes.
 */
@Composable
fun rememberMinimalModeLockState(): MinimalModeLockState {
    val context = LocalContext.current
    val prefs2 = preferenceManager2()
    val isEnabled = prefs2.minimalModeEnabled.getAdapter().state.value
    val lockType = MinimalModeLock.Type.fromName(prefs2.minimalModeLock.getAdapter().state.value)
    var isUnlocked by remember { mutableStateOf(MinimalModeLock.isUnlocked()) }
    LifecycleResumeEffect(Unit) {
        isUnlocked = MinimalModeLock.isUnlocked()
        onPauseOrDispose { }
    }
    // If the screen lock was removed, a device lock can't be checked, so it doesn't lock anything.
    val hasLock = when (lockType) {
        MinimalModeLock.Type.NONE -> false
        MinimalModeLock.Type.PIN -> true
        MinimalModeLock.Type.DEVICE -> MinimalModeLock.isDeviceLockAvailable(context)
    }
    return MinimalModeLockState(
        isLocked = isEnabled && hasLock && !isUnlocked,
        onUnlocked = {
            MinimalModeLock.onUnlocked()
            isUnlocked = true
        },
    )
}

/**
 * Shown instead of Minimal Mode settings while they're locked.
 */
@Composable
fun MinimalModeLockedContent(
    lockState: MinimalModeLockState,
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val lockType = MinimalModeLock.Type.fromName(prefs2.minimalModeLock.getAdapter().state.value)
    val pinHash = prefs2.minimalModePinHash.getAdapter().state.value
    var showPinDialog by remember { mutableStateOf(false) }
    val unlockWithDevice = rememberDeviceUnlock(onSuccess = lockState.onUnlocked)

    PreferenceGroup(
        modifier = modifier,
        description = stringResource(R.string.minimal_mode_locked_description),
    ) {
        ClickablePreference(
            label = stringResource(R.string.minimal_mode_unlock),
            onClick = {
                when (lockType) {
                    MinimalModeLock.Type.PIN -> showPinDialog = true
                    else -> unlockWithDevice()
                }
            },
        )
    }

    if (showPinDialog) {
        PinEntryDialog(
            pinHash = pinHash,
            onDismiss = { showPinDialog = false },
            onCorrectPin = {
                showPinDialog = false
                lockState.onUnlocked()
            },
        )
    }
}

/**
 * Picks the lock. Choosing a PIN asks for the new PIN first; the phone's screen lock is only
 * offered when one is set.
 */
@Composable
fun MinimalModeLockPreference(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs2 = preferenceManager2()
    val lockAdapter = prefs2.minimalModeLock.getAdapter()
    val pinAdapter = prefs2.minimalModePinHash.getAdapter()
    var showPinSetup by remember { mutableStateOf(false) }

    ListPreference(
        entries = listOf(
            ListPreferenceEntry(MinimalModeLock.Type.NONE) { stringResource(R.string.minimal_mode_lock_none) },
            ListPreferenceEntry(MinimalModeLock.Type.PIN) { stringResource(R.string.minimal_mode_lock_pin) },
            ListPreferenceEntry(MinimalModeLock.Type.DEVICE) { stringResource(R.string.minimal_mode_lock_device) },
        ),
        value = MinimalModeLock.Type.fromName(lockAdapter.state.value),
        onValueChange = { type ->
            when (type) {
                MinimalModeLock.Type.NONE -> {
                    lockAdapter.onChange(type.name)
                    pinAdapter.onChange("")
                }

                MinimalModeLock.Type.PIN -> showPinSetup = true

                MinimalModeLock.Type.DEVICE -> {
                    if (MinimalModeLock.isDeviceLockAvailable(context)) {
                        lockAdapter.onChange(type.name)
                        pinAdapter.onChange("")
                    } else {
                        Toast.makeText(context, R.string.minimal_mode_lock_device_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            }
        },
        label = stringResource(R.string.minimal_mode_lock_label),
        modifier = modifier,
    )

    if (showPinSetup) {
        PinSetupDialog(
            onDismiss = { showPinSetup = false },
            onPinSet = { hash ->
                showPinSetup = false
                pinAdapter.onChange(hash)
                lockAdapter.onChange(MinimalModeLock.Type.PIN.name)
            },
        )
    }
}

@Composable
private fun PinEntryDialog(
    pinHash: String,
    onDismiss: () -> Unit,
    onCorrectPin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var isWrong by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.minimal_mode_enter_pin)) },
        text = {
            PinField(
                value = pin,
                onValueChange = {
                    pin = it
                    isWrong = false
                },
                label = stringResource(R.string.minimal_mode_pin),
                error = if (isWrong) stringResource(R.string.minimal_mode_pin_wrong) else null,
            )
        },
        confirmButton = {
            TextButton(
                enabled = pin.isNotEmpty() && !isChecking,
                onClick = {
                    isChecking = true
                    scope.launch {
                        // Hashing is deliberately slow, so keep it off the main thread.
                        val isCorrect = withContext(Dispatchers.Default) { MinimalModeLock.verifyPin(pin, pinHash) }
                        isChecking = false
                        if (isCorrect) {
                            onCorrectPin()
                        } else {
                            isWrong = true
                            pin = ""
                        }
                    }
                },
            ) { Text(stringResource(R.string.minimal_mode_unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PinSetupDialog(
    onDismiss: () -> Unit,
    onPinSet: (hash: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    val isMismatch = confirmation.isNotEmpty() && confirmation != pin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.minimal_mode_set_pin)) },
        text = {
            Column {
                Text(stringResource(R.string.minimal_mode_set_pin_description))
                Spacer(Modifier.height(12.dp))
                PinField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = stringResource(R.string.minimal_mode_pin),
                )
                Spacer(Modifier.height(8.dp))
                PinField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = stringResource(R.string.minimal_mode_confirm_pin),
                    error = if (isMismatch) stringResource(R.string.minimal_mode_pin_mismatch) else null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length >= MIN_PIN_LENGTH && confirmation == pin && !isSaving,
                onClick = {
                    isSaving = true
                    scope.launch {
                        val hash = withContext(Dispatchers.Default) { MinimalModeLock.hashPin(pin) }
                        onPinSet(hash)
                    }
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

/**
 * Returns an action that asks for the phone's screen lock (or fingerprint/face) and calls
 * [onSuccess] if it's confirmed.
 */
@Composable
private fun rememberDeviceUnlock(onSuccess: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val title = stringResource(R.string.minimal_mode_unlock_title)
    // Android 9 and older can only confirm the screen lock through this activity.
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) onSuccess()
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            showBiometricPrompt(context, title, onSuccess)
        } else {
            @Suppress("DEPRECATION")
            val intent = context.getSystemService(KeyguardManager::class.java)
                ?.createConfirmDeviceCredentialIntent(title, null)
            if (intent != null) legacyLauncher.launch(intent) else onSuccess()
        }
    }
}

private fun showBiometricPrompt(context: Context, title: String, onSuccess: () -> Unit) {
    val builder = BiometricPrompt.Builder(context).setTitle(title)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    builder.build().authenticate(
        CancellationSignal(),
        context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        },
    )
}
