/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.os.Handler
import android.os.IBinder
import android.os.IServiceCallback
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import vendor.lineage.slider.ISlider
import vendor.lineage.slider.ISliderCallback

class SliderInstance(
    private val app: SliderApp,
    val name: String,
    private val actions: Actions?,
    private val handler: Handler,
) {
    private val service = ISlider.DESCRIPTOR + "/" + name

    @Volatile private var lastPosition = -1
    @Volatile private var config: Config? = null

    val currentPosition
        get() = lastPosition

    private val callback =
        object : ISliderCallback.Stub() {
            override fun onPositionChanged(position: Int) {
                lastPosition = position
                app.onPositionChanged(name, position)
                applyPosition(position)
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
            config = Config(app, name, description.positionCount)
            service.registerCallback(callback)
            Log.i(TAG, "connected $name, positions=${description.positionCount}")
        } catch (e: Exception) {
            Log.e(TAG, "connect $name failed, retrying", e)
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            handler.postDelayed({ connect() }, RETRY_DELAY_MS)
        }
    }

    private fun applyPosition(position: Int) {
        val actions = actions ?: return
        val config = config ?: return
        if (!config.enabled) {
            return
        }
        val action = config.actionForPosition(position)
        Log.i(TAG, "$name position=$position action=$action")
        actions.apply(action)
    }

    companion object {
        private const val TAG = "LineageSlider"
        private const val RETRY_DELAY_MS = 5000L
    }
}
