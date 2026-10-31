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

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * What it takes to turn Minimal Mode off or change its settings while it's on.
 */
object MinimalModeLock {

    enum class Type {
        NONE,

        /** A PIN set just for Minimal Mode, which someone else can hold. */
        PIN,

        /** The phone's own screen lock: fingerprint, face, PIN, pattern or password. */
        DEVICE,
        ;

        companion object {
            fun fromName(name: String): Type = entries.firstOrNull { it.name == name } ?: NONE
        }
    }

    // How long an unlock lasts, so several settings can be changed in one go.
    private const val UNLOCK_DURATION_MS = 5 * 60 * 1000L

    private const val PIN_ITERATIONS = 120_000
    private const val PIN_KEY_LENGTH = 256

    @Volatile
    private var unlockedUntil = 0L

    fun isUnlocked(): Boolean = SystemClock.elapsedRealtime() < unlockedUntil

    fun onUnlocked() {
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_DURATION_MS
    }

    fun lock() {
        unlockedUntil = 0L
    }

    /** Whether the phone has a screen lock that [Type.DEVICE] can use. */
    fun isDeviceLockAvailable(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Returns "salt:hash", both Base64. */
    fun hashPin(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "${encode(salt)}:${encode(derive(pin, salt))}"
    }

    fun verifyPin(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 2) return false
        val salt = runCatching { decode(parts[0]) }.getOrNull() ?: return false
        val expected = runCatching { decode(parts[1]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt), expected)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, PIN_KEY_LENGTH)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
