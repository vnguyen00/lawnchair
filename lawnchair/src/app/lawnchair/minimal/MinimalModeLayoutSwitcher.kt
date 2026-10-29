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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.ItemInstallQueue
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.pm.UserCache
import com.android.launcher3.provider.LauncherDbUtils
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.MODEL_EXECUTOR

/**
 * Keeps two home screen layouts: the normal one and the Minimal Mode one.
 *
 * Both are copies of the favorites table inside the launcher database, so a switch is a single
 * transaction and every grid size keeps its own pair. While Minimal Mode is on, the normal
 * layout is set aside in [NORMAL_TABLE]; while it's off, the Minimal Mode layout is set aside in
 * [MINIMAL_TABLE]. Whether [NORMAL_TABLE] exists tells which layout is live.
 */
class MinimalModeLayoutSwitcher(
    private val context: Context,
    private val policy: MinimalModePolicy,
) {

    /** Makes the live home screen match [enabled], if it doesn't already. */
    fun apply(enabled: Boolean) {
        MODEL_EXECUTOR.execute {
            val model = LauncherAppState.getInstance(context).model
            val result = runCatching { switchLayout(model.modelDbController, enabled) }
                .onFailure { Log.e(TAG, "Failed to switch home screen layout", it) }
                .getOrNull() ?: return@execute
            MAIN_EXECUTOR.execute {
                model.forceReload()
                // A new Minimal Mode home starts with the allowed apps that are still missing.
                // The queue skips apps that are already on the home screen.
                if (result == Result.CREATED) {
                    val queue = ItemInstallQueue.INSTANCE.get(context)
                    policy.allowedApps.forEach { (packageName, user) -> queue.queueItem(packageName, user) }
                }
            }
        }
    }

    private enum class Result { SWITCHED, CREATED }

    private fun switchLayout(controller: ModelDbController, enabled: Boolean): Result? {
        val db = controller.db
        val isMinimalLayoutLive = LauncherDbUtils.tableExists(db, NORMAL_TABLE)
        if (enabled == isMinimalLayoutLive) return null

        var result = Result.SWITCHED
        controller.newTransaction().use { transaction ->
            if (enabled) {
                copy(db, Favorites.TABLE_NAME, NORMAL_TABLE)
                if (LauncherDbUtils.tableExists(db, MINIMAL_TABLE)) {
                    copy(db, MINIMAL_TABLE, Favorites.TABLE_NAME)
                } else {
                    // First time: start from the normal layout without the blocked apps.
                    removeBlockedItems(db)
                    result = Result.CREATED
                }
            } else {
                copy(db, Favorites.TABLE_NAME, MINIMAL_TABLE)
                copy(db, NORMAL_TABLE, Favorites.TABLE_NAME)
                LauncherDbUtils.dropTable(db, NORMAL_TABLE)
            }
            transaction.commit()
        }
        return result
    }

    private fun copy(db: SQLiteDatabase, fromTable: String, toTable: String) {
        LauncherDbUtils.copyTable(db, fromTable, db, toTable, context)
    }

    private fun removeBlockedItems(db: SQLiteDatabase) {
        val userCache = UserCache.INSTANCE.get(context)
        val blockedIds = mutableListOf<Int>()
        db.query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites._ID, Favorites.ITEM_TYPE, Favorites.INTENT, Favorites.APPWIDGET_PROVIDER, Favorites.PROFILE_ID),
            null,
            null,
            null,
            null,
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val packageName = when (c.getInt(1)) {
                    Favorites.ITEM_TYPE_APPLICATION,
                    Favorites.ITEM_TYPE_SHORTCUT,
                    Favorites.ITEM_TYPE_DEEP_SHORTCUT,
                    -> c.getString(2)?.let(::intentPackage)

                    Favorites.ITEM_TYPE_APPWIDGET -> c.getString(3)?.let { ComponentName.unflattenFromString(it)?.packageName }

                    else -> null
                } ?: continue
                val user = userCache.getUserForSerialNumber(c.getLong(4)) ?: continue
                if (policy.isBlocked(packageName, user)) blockedIds += c.getInt(0)
            }
        }
        if (blockedIds.isNotEmpty()) {
            db.delete(Favorites.TABLE_NAME, "${Favorites._ID} IN (${blockedIds.joinToString()})", null)
        }

        val table = Favorites.TABLE_NAME
        val id = Favorites._ID
        val container = Favorites.CONTAINER
        val itemType = Favorites.ITEM_TYPE
        // App pairs that lost one of their apps, then anything left inside them.
        db.execSQL(
            "DELETE FROM $table WHERE $itemType = ${Favorites.ITEM_TYPE_APP_PAIR} " +
                "AND (SELECT COUNT(*) FROM $table AS child WHERE child.$container = $table.$id) < 2",
        )
        db.execSQL("DELETE FROM $table WHERE $container >= 0 AND $container NOT IN (SELECT $id FROM $table)")
        // Folders with no apps left.
        db.execSQL(
            "DELETE FROM $table WHERE $itemType = ${Favorites.ITEM_TYPE_FOLDER} " +
                "AND $id NOT IN (SELECT $container FROM $table)",
        )
    }

    private fun intentPackage(uri: String): String? {
        val intent = runCatching { Intent.parseUri(uri, 0) }.getOrNull() ?: return null
        return intent.component?.packageName ?: intent.`package`
    }

    companion object {
        private const val TAG = "MinimalModeLayout"

        /** The normal layout, set aside while Minimal Mode is on. */
        const val NORMAL_TABLE = "minimal_mode_normal_favorites"

        /** The Minimal Mode layout, set aside while Minimal Mode is off. */
        const val MINIMAL_TABLE = "minimal_mode_favorites"

        /** Layouts that aren't live but whose items (and widgets) must be kept. */
        @JvmField
        val SNAPSHOT_TABLES = arrayOf(NORMAL_TABLE, MINIMAL_TABLE)
    }
}
