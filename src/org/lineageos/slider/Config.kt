/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.content.Context
import android.os.UserHandle
import androidx.preference.PreferenceDataStore
import lineageos.providers.LineageSettings
import org.json.JSONObject

// Every choice is read for the current user, who is the user an action is
// carried out for. UserController exempts the system uid from that read.
class Config(
    private val context: Context,
    private val instance: String,
    private val positionCount: Int,
) {
    private val resolver = context.contentResolver

    val enabled: Boolean
        get() = boolean(KEY_ENABLED, true)

    // A preset stored for a different position count has no table at this
    // one, and reading it back as itself would leave every position idle.
    val preset: String
        get() {
            val stored = string(KEY_PRESET)
            if (stored != null && Presets.has(stored, positionCount)) {
                return stored
            }
            return Presets.defaultId(context, positionCount)
        }

    fun customAction(position: Int): Int =
        string(keyPosition(position))?.toIntOrNull() ?: Action.NONE

    fun actionForPosition(position: Int): Int {
        val preset = preset
        if (preset == Presets.CUSTOM) {
            return customAction(position)
        }
        val table = Presets.table(preset, positionCount) ?: return Action.NONE
        return table.getOrElse(position) { Action.NONE }
    }

    // Read on every access rather than held, so a choice made while the
    // slider sits in a position takes effect on the next move.
    private fun string(suffix: String): String? =
        read(LineageSettings.System.getStringForUser(resolver, SETTING, UserHandle.USER_CURRENT))[
            name(suffix)]

    private fun boolean(suffix: String, defaultValue: Boolean): Boolean =
        string(suffix)?.toBooleanStrictOrNull() ?: defaultValue

    private fun name(suffix: String) = "$instance/$suffix"

    // The process drawing the screen belongs to the user whose choices it
    // edits, so a write lands in that user's copy of the setting.
    class Store(context: Context) : PreferenceDataStore() {
        private val resolver = context.contentResolver

        override fun getString(key: String, defValue: String?): String? = values()[key] ?: defValue

        override fun putString(key: String, value: String?) {
            write(key, value)
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values()[key]?.toBooleanStrictOrNull() ?: defValue

        override fun putBoolean(key: String, value: Boolean) {
            write(key, value.toString())
        }

        private fun values() = read(LineageSettings.System.getString(resolver, SETTING))

        private fun write(key: String, value: String?) {
            val values = values().toMutableMap()
            if (value == null) {
                values.remove(key)
            } else {
                values[key] = value
            }
            LineageSettings.System.putString(resolver, SETTING, JSONObject(values).toString())
        }
    }

    companion object {
        private val SETTING = LineageSettings.System.SLIDER_CONFIGURATION

        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"

        private fun keyPosition(position: Int) = "position_$position"

        // A malformed value is one a device seeded by hand, so it reads as no
        // configuration at all rather than taking the screen down with it.
        private fun read(raw: String?): Map<String, String> {
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
            return json
                .keys()
                .asSequence()
                .filterNot { json.isNull(it) }
                .associateWith { json.optString(it) }
        }
    }
}
