/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ServiceManager
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import vendor.lineage.slider.Direction
import vendor.lineage.slider.Edge
import vendor.lineage.slider.ISlider
import vendor.lineage.slider.SliderInfo

class SliderSettingsFragment : SettingsBasePreferenceFragment() {
    private val rows = mutableMapOf<String, List<ActionPreference>>()
    private val handler = Handler(Looper.getMainLooper())

    // Reported from one of the app's binder threads, so nothing here reads
    // fragment state before the hop to the thread that owns it.
    private val positionListener = { instance: String, position: Int ->
        handler.post { mark(instance, position) }
        Unit
    }

    override fun onStart() {
        super.onStart()
        sliderApp()?.addPositionListener(positionListener)
    }

    override fun onStop() {
        super.onStop()
        sliderApp()?.removePositionListener(positionListener)
    }

    private fun sliderApp() = context?.applicationContext as? SliderApp

    private fun mark(instance: String, position: Int) {
        rows[instance]?.forEachIndexed { index, pref -> pref.active = index == position }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = Config.Store(preferenceManager.context)
        val screen = preferenceManager.createPreferenceScreen(preferenceManager.context)

        val names = ServiceManager.getDeclaredInstances(ISlider.DESCRIPTOR)
        names.forEachIndexed { index, name ->
            addInstance(screen, name, index, showName = names.size > 1)
        }
        preferenceScreen = screen
    }

    private fun addInstance(screen: PreferenceScreen, name: String, index: Int, showName: Boolean) {
        val context = preferenceManager.context
        val info = sliderInfo(name)
        val count = info?.positionCount ?: 0
        if (count < 2) {
            return
        }
        val config = Config(context, name, count)

        val enabledPref =
            MainSwitchPreference(context).apply {
                key = "$name/enabled"
                title =
                    if (showName) {
                        instanceTitle(info, index)
                    } else {
                        getString(R.string.slider_enabled)
                    }
                setDefaultValue(true)
            }
        screen.addPreference(enabledPref)

        val category = PreferenceCategory(context).apply { key = "cat_$name" }
        screen.addPreference(category)

        // A preset whose action the device has stopped offering still has to
        // name itself, or its row goes blank.
        val presetIds = (Presets.ids(context, count) + config.preset).distinct()
        val presetPref =
            ListPreference(context).apply {
                key = "$name/preset"
                title = getString(R.string.preset_title)
                entries = presetIds.map { Presets.label(context, it, count) }.toTypedArray()
                entryValues = presetIds.toTypedArray()
                setDefaultValue(Presets.defaultId(context, count))
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        category.addPreference(presetPref)

        val allEntries = resources.getStringArray(R.array.action_entries)
        val allValues = resources.getStringArray(R.array.action_values)
        val offered =
            allValues.indices.filter {
                Action.isAvailable(context, allValues[it].toIntOrNull() ?: Action.NONE)
            }
        val actionEntries = offered.map { allEntries[it] }.toTypedArray()
        val actionValues = offered.map { allValues[it] }.toTypedArray()
        val positionPrefs = mutableListOf<ActionPreference>()
        val positionGroups = mutableListOf<PreferenceCategory>()

        // A row reads its stored value when it is added, so the group it goes
        // in has to be on the screen first.
        val groups = mutableMapOf<Int, PreferenceCategory>()
        displayOrder(info, count).forEachIndexed { rank, position ->
            val group =
                PreferenceCategory(context).apply {
                    key = "cat_$name/position_$position"
                    title = positionTitle(position, rank, count)
                }
            screen.addPreference(group)
            groups[position] = group
        }

        for (position in 0 until count) {
            val group = groups.getValue(position)
            positionGroups.add(group)

            val actionPref =
                ActionPreference(context).apply {
                    key = "$name/position_$position"
                    title = getString(R.string.position_action_title)
                    entries = actionEntries
                    entryValues = actionValues
                    setDefaultValue(Action.NONE.toString())
                }
            group.addPreference(actionPref)
            positionPrefs.add(actionPref)
        }

        // A preset's actions come from its table rather than from what a row
        // holds, since only a custom preset lets a row be opened.
        fun actionAt(preset: String?, position: Int): Int {
            val table = Presets.table(preset ?: Presets.defaultId(context, count), count)
            return table?.getOrElse(position) { Action.NONE }
                ?: positionPrefs[position].value?.toIntOrNull()
                ?: Action.NONE
        }

        fun update(enabled: Boolean, preset: String?) {
            val custom = preset == Presets.CUSTOM
            presetPref.isEnabled = enabled
            positionGroups.forEach { it.isEnabled = enabled }
            positionPrefs.forEachIndexed { position, pref ->
                pref.isEnabled = enabled && custom
                if (custom) {
                    pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                } else {
                    pref.summaryProvider = null
                    pref.summary = Action.label(context, actionAt(preset, position))
                }
            }
        }

        rows[name] = positionPrefs
        sliderApp()?.currentPosition(name)?.let { mark(name, it) }

        update(enabledPref.isChecked, presetPref.value)
        enabledPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                update(newValue as Boolean, presetPref.value)
                true
            }
        presetPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                update(enabledPref.isChecked, newValue as String)
                true
            }
    }

    // checkService() is the lookup that answers at once, where getService()
    // retries for five seconds.
    private fun sliderInfo(instance: String): SliderInfo? {
        val binder = ServiceManager.checkService(ISlider.DESCRIPTOR + "/" + instance) ?: return null
        return runCatching { ISlider.Stub.asInterface(binder).sliderInfo }.getOrNull()
    }

    // A HAL instance name is a wire identifier, so a screen holding more than
    // one slider names each by the edge it sits on, or by its order.
    private fun instanceTitle(info: SliderInfo?, index: Int): String {
        val edge =
            info?.location?.edge ?: return getString(R.string.slider_enabled_numbered, index + 1)
        return getString(
            when (edge) {
                Edge.LEFT -> R.string.slider_enabled_left
                Edge.TOP -> R.string.slider_enabled_top
                Edge.RIGHT -> R.string.slider_enabled_right
                else -> R.string.slider_enabled_bottom
            }
        )
    }

    // Index 0 is the off end of the slider's travel. Anything longer than
    // three positions stays numbered.
    // The rows are listed from the top of the device down, which reverses
    // the index order on a slider whose positions rise towards the top.
    private fun displayOrder(info: SliderInfo?, count: Int): List<Int> {
        val direction = info?.location?.direction
        val reversed = direction == Direction.UP || direction == Direction.LEFT
        return if (reversed) (count - 1 downTo 0).toList() else (0 until count).toList()
    }

    private fun positionTitle(position: Int, rank: Int, count: Int): String =
        when {
            count > 3 -> getString(R.string.position_title, rank + 1)
            position == 0 -> getString(R.string.position_off)
            position == count - 1 -> getString(R.string.position_on)
            else -> getString(R.string.position_middle)
        }

    // Marking a position toggles the badge's visibility rather than swapping
    // the widget layout, so a row keeps its view type.
    private class ActionPreference(context: Context) : ListPreference(context) {
        var active = false
            set(value) {
                if (field == value) {
                    return
                }
                field = value
                notifyChanged()
            }

        init {
            widgetLayoutResource = R.layout.slider_position_active
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.findViewById(R.id.active)?.visibility = if (active) View.VISIBLE else View.GONE
        }
    }
}
