/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ServiceManager
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import vendor.lineage.slider.Direction
import vendor.lineage.slider.Edge
import vendor.lineage.slider.ISlider
import vendor.lineage.slider.SliderInfo

class SliderSettingsFragment : SettingsBasePreferenceFragment() {
    private var pendingTargetKey: String? = null
    private val rows = mutableMapOf<String, List<ActionPreference>>()
    private val handler = Handler(Looper.getMainLooper())

    // Reported from one of the app's binder threads, so nothing here reads
    // fragment state before the hop to the thread that owns it.
    private val positionListener = { instance: String, position: Int ->
        handler.post { mark(instance, position) }
        Unit
    }

    private val pickActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val key = pendingTargetKey ?: return@registerForActivityResult
            pendingTargetKey = null

            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }

            val component = result.data?.component ?: return@registerForActivityResult
            val target = component.flattenToShortString()
            preferenceManager.preferenceDataStore?.putString(key, target)
            findPreference<Preference>(key)?.summary = targetLabel(target)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_TARGET, pendingTargetKey)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        pendingTargetKey = savedInstanceState?.getString(KEY_PENDING_TARGET)
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
        val allCategories = resources.getStringArray(R.array.app_category_values)
        val handled = allCategories.indices.filter { handlesCategory(allCategories[it]) }
        val categoryEntries =
            handled
                .map { resources.getStringArray(R.array.app_category_entries)[it] }
                .toTypedArray()
        val categoryValues = handled.map { allCategories[it] }.toTypedArray()
        val positionPrefs = mutableListOf<ActionPreference>()
        val positionGroups = mutableListOf<PreferenceCategory>()
        val perPosition = mutableListOf<PositionPrefs>()

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

            val appPref =
                Preference(context).apply {
                    key = "$name/position_$position/app"
                    title = getString(R.string.position_app_title)
                    summary = targetLabel(config.target(position))
                    setOnPreferenceClickListener {
                        pendingTargetKey = it.key
                        pickActivity.launch(pickIntent(getString(R.string.position_app_pick)))
                        true
                    }
                }
            group.addPreference(appPref)

            val categoryPref =
                ListPreference(context).apply {
                    key = "$name/position_$position/category"
                    title = getString(R.string.position_category_title)
                    entries = categoryEntries
                    entryValues = categoryValues
                    setDefaultValue(categoryValues.first())
                    summaryProvider =
                        Preference.SummaryProvider<ListPreference> { pref ->
                            val label = pref.entry ?: pref.value
                            categoryHandler(pref.value)?.let {
                                getString(R.string.position_category_summary, label, it)
                            } ?: label
                        }
                }
            group.addPreference(categoryPref)

            perPosition.add(PositionPrefs(appPref, categoryPref))
        }

        val showDialogPref =
            SwitchPreferenceCompat(context).apply {
                key = "$name/show_dialog"
                title = getString(R.string.slider_show_dialog)
                setDefaultValue(true)
            }
        category.addPreference(showDialogPref)

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
            showDialogPref.isEnabled = enabled
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
            perPosition.forEachIndexed { position, prefs ->
                val action = actionAt(preset, position)
                prefs.app.isVisible = action == Action.LAUNCH_APP
                prefs.app.isEnabled = enabled
                prefs.category.isVisible = action == Action.LAUNCH_DEFAULT_APP
                prefs.category.isEnabled = enabled
            }
        }

        positionPrefs.forEach { pref ->
            pref.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { preference, newValue ->
                    val chosen = newValue as String
                    (preference as ListPreference).value = chosen
                    requestGrants(chosen.toIntOrNull() ?: Action.NONE)
                    update(enabledPref.isChecked, presetPref.value)
                    true
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
                Presets.table(newValue as String, count)?.let { requestGrants(*it) }
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

    // A permission another app must hold is asked for while the user is here
    // choosing the action that needs it, rather than once the slider moves.
    private fun requestGrants(vararg actions: Int) {
        for (action in actions) {
            val intent = Action.grantIntent(preferenceManager.context, action) ?: continue
            startActivity(intent)
            return
        }
    }

    // The app a category opens right now, which is nothing while several
    // handle it and the user has picked no default.
    private fun categoryHandler(category: String?): String? {
        if (category.isNullOrEmpty()) {
            return null
        }
        val packageManager = preferenceManager.context.packageManager
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        val resolved = packageManager.resolveActivity(intent, 0)?.activityInfo ?: return null
        if (resolved.packageName == PLATFORM_PACKAGE) {
            return null
        }
        return resolved.loadLabel(packageManager).toString()
    }

    // A category nothing on the device handles is a position that would do
    // nothing, so it is not offered as one.
    private fun handlesCategory(category: String) =
        preferenceManager.context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .isNotEmpty()

    private fun pickIntent(title: String) =
        Intent(Intent.ACTION_PICK_ACTIVITY)
            .putExtra(Intent.EXTRA_TITLE, title)
            .putExtra(
                Intent.EXTRA_INTENT,
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            )

    private fun targetLabel(target: String?): String {
        if (target.isNullOrEmpty()) {
            return getString(R.string.position_app_none)
        }

        val component = ComponentName.unflattenFromString(target) ?: return target
        val packageManager = preferenceManager.context.packageManager
        return runCatching {
                packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString()
            }
            .getOrDefault(target)
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

    private class PositionPrefs(val app: Preference, val category: ListPreference)

    companion object {
        private const val KEY_PENDING_TARGET = "pending_target"
        private const val PLATFORM_PACKAGE = "android"
    }
}
