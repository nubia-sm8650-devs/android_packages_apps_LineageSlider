/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.content.Context
import android.icu.text.ListFormatter

object Presets {
    const val RING_SILENT = "ring_silent"
    const val RING_VIBRATE = "ring_vibrate"
    const val RING_VIBRATE_SILENT = "ring_vibrate_silent"
    const val VIBRATE_SILENT = "vibrate_silent"
    const val DND = "dnd"
    const val TOTAL_SILENCE = "total_silence"
    const val ALARMS_ONLY = "alarms_only"
    const val FLASHLIGHT = "flashlight"
    const val CUSTOM = "custom"

    private val LABELS =
        mapOf(FLASHLIGHT to R.string.preset_flashlight, CUSTOM to R.string.preset_custom)

    fun table(preset: String, count: Int): IntArray? =
        if (preset == CUSTOM) null else tables(count)[preset]

    fun has(preset: String, count: Int) = preset == CUSTOM || tables(count).containsKey(preset)

    // A sound preset is named by the modes it selects, since one id stands
    // for different modes at different position counts.
    fun label(context: Context, id: String, count: Int): String {
        val actions = soundPresets(count)[id]
        if (actions == null) {
            val label = LABELS[id] ?: return id
            return context.getString(label)
        }
        return ListFormatter.getInstance(context.resources.configuration.locales[0])
            .format(actions.map { Action.label(context, it).orEmpty() })
    }

    fun ids(context: Context, count: Int): List<String> =
        tables(count)
            .filterValues { actions -> actions.all { Action.isAvailable(context, it) } }
            .keys
            .toList() + CUSTOM

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

    private fun tables(count: Int): Map<String, IntArray> =
        soundPresets(count) + featurePresets(count)

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

    private fun featurePresets(count: Int): Map<String, IntArray> =
        mapOf(FLASHLIGHT to toggle(count, Action.TORCH_OFF, Action.TORCH_ON))

    private fun toggle(count: Int, off: Int, on: Int): IntArray =
        IntArray(count) { if (it == count - 1) on else off }
}
