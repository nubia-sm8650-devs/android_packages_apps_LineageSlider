/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.content.ComponentName
import android.os.Handler
import android.os.IBinder
import android.os.IServiceCallback
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import vendor.lineage.slider.ISlider
import vendor.lineage.slider.ISliderCallback
import vendor.lineage.slider.SliderInfo

class SliderInstance(
    private val app: SliderApp,
    val name: String,
    private val actions: Actions?,
    private val dialog: SliderDialog?,
    private val handler: Handler,
) {
    private val service = ISlider.DESCRIPTOR + "/" + name
    private val lock = Any()

    @Volatile private var lastPosition = -1
    @Volatile private var config: Config? = null
    @Volatile private var info: SliderInfo? = null

    val currentPosition
        get() = lastPosition

    private val callback =
        object : ISliderCallback.Stub() {
            override fun onPositionChanged(position: Int) {
                // Registering delivers the current position, so a report that
                // leaves it alone re-asserts state rather than announcing a move.
                val restore =
                    synchronized(lock) {
                        val same = position == lastPosition
                        lastPosition = position
                        same
                    }
                app.onPositionChanged(name, position)
                applyPosition(position, restore)
                if (!restore) {
                    showDialog(position)
                }
            }

            override fun getInterfaceVersion() = ISliderCallback.VERSION

            override fun getInterfaceHash() = ISliderCallback.HASH
        }

    // Nothing here may run on the binder thread the notification arrives on.
    private val deathRecipient =
        IBinder.DeathRecipient {
            Log.w(TAG, "$name HAL died")
            handler.post { connect() }
        }

    // servicemanager reports an instance already registered at once, and
    // every registration after, so nothing waits on a HAL that starts late.
    private val registration =
        object : IServiceCallback.Stub() {
            override fun onRegistration(instance: String, binder: IBinder) {
                handler.post { connect() }
            }
        }

    fun watch() {
        try {
            ServiceManager.registerForNotifications(service, registration)
        } catch (e: RemoteException) {
            Log.e(TAG, "cannot watch for $name", e)
        }
    }

    private fun connect() {
        val binder = ServiceManager.checkService(service)
        if (binder == null) {
            Log.i(TAG, "$name is declared but not registered")
            return
        }

        try {
            binder.linkToDeath(deathRecipient, 0)
            val service = ISlider.Stub.asInterface(binder)
            val description = service.sliderInfo
            val position = service.position
            config = Config(app, name, description.positionCount)
            info = description
            lastPosition = position
            service.registerCallback(callback)
            Log.i(TAG, "connected $name, positions=${description.positionCount}, at $position")
        } catch (e: Exception) {
            Log.e(TAG, "connect $name failed, retrying", e)
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            handler.postDelayed({ connect() }, RETRY_DELAY_MS)
        }
    }

    private fun applyPosition(position: Int, restore: Boolean) {
        val actions = actions ?: return
        val config = config ?: return
        if (!config.enabled) {
            return
        }
        val action = config.actionForPosition(position)
        if (restore && !Action.isStateful(action)) {
            Log.i(TAG, "$name position=$position holds one-shot action $action, not applying")
            return
        }
        Log.i(TAG, "$name position=$position action=$action")
        actions.apply(action, config.targetForAction(position, action))
    }

    private fun showDialog(position: Int) {
        val dialog = dialog ?: return
        val config = config ?: return
        if (!config.enabled || !config.showDialog) {
            return
        }
        val action = config.actionForPosition(position)
        val label = Action.label(app, action) ?: return
        val target = config.targetForAction(position, action)
        if (target.isNullOrEmpty()) {
            dialog.show(label, info)
        } else {
            dialog.show(
                app.getString(R.string.slider_indicator_target, label, targetName(action, target)),
                info,
            )
        }
    }

    private fun targetName(action: Int, target: String): String {
        if (action == Action.LAUNCH_DEFAULT_APP) {
            return categoryName(target)
        }
        val packageManager = app.packageManager
        val component = ComponentName.unflattenFromString(target) ?: return target
        return runCatching {
                packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString()
            }
            .getOrDefault(target)
    }

    private fun categoryName(category: String): String {
        val values = app.resources.getStringArray(R.array.app_category_values)
        val index = values.indexOf(category)
        return if (index < 0) {
            category
        } else {
            app.resources.getStringArray(R.array.app_category_entries)[index]
        }
    }

    companion object {
        private const val TAG = "LineageSlider"
        private const val RETRY_DELAY_MS = 5000L
    }
}
