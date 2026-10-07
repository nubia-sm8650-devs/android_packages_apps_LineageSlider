/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.content.Context

object Presets {
    const val RING_SILENT = "ring_silent"
    const val RING_VIBRATE = "ring_vibrate"
    const val RING_VIBRATE_SILENT = "ring_vibrate_silent"
    const val VIBRATE_SILENT = "vibrate_silent"
    const val DND = "dnd"
    const val TOTAL_SILENCE = "total_silence"
    const val ALARMS_ONLY = "alarms_only"
    const val CUSTOM = "custom"

    fun table(preset: String, count: Int): IntArray? =
        if (preset == CUSTOM) null else tables(count)[preset]

    fun has(preset: String, count: Int) = preset == CUSTOM || tables(count).containsKey(preset)

    // A count with no sound arrangement of its own, or one the device cannot
    // perform, leaves every position for the user to fill in.
    fun defaultId(context: Context, count: Int): String {
        val id =
            when (count) {
                2 -> RING_SILENT
                3 -> RING_VIBRATE_SILENT
                else -> return CUSTOM
            }
        val actions = soundPresets(count)[id] ?: return CUSTOM
        return if (actions.all { Action.isAvailable(context, it) }) id else CUSTOM
    }

    private fun tables(count: Int): Map<String, IntArray> = soundPresets(count)

    private fun soundPresets(count: Int): Map<String, IntArray> =
        when (count) {
            2 ->
                mapOf(
                    RING_SILENT to intArrayOf(Action.SILENT, Action.NORMAL),
                    RING_VIBRATE to intArrayOf(Action.VIBRATE, Action.NORMAL),
                    VIBRATE_SILENT to intArrayOf(Action.SILENT, Action.VIBRATE),
                    DND to intArrayOf(Action.DND_PRIORITY, Action.NORMAL),
                )
            3 ->
                mapOf(
                    RING_VIBRATE_SILENT to intArrayOf(Action.SILENT, Action.VIBRATE, Action.NORMAL),
                    DND to intArrayOf(Action.DND_PRIORITY, Action.VIBRATE, Action.NORMAL),
                    TOTAL_SILENCE to intArrayOf(Action.DND_TOTAL, Action.VIBRATE, Action.NORMAL),
                    ALARMS_ONLY to intArrayOf(Action.DND_ALARMS, Action.VIBRATE, Action.NORMAL),
                )
            else -> emptyMap()
        }
}
