/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.ServiceManager
import android.os.UserHandle
import java.util.concurrent.CopyOnWriteArrayList
import vendor.lineage.slider.ISlider

class SliderApp : Application() {
    private val instances = CopyOnWriteArrayList<SliderInstance>()
    private lateinit var handler: Handler

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread(TAG)
        thread.start()
        handler = Handler(thread.looper)
        handler.post { init() }
    }

    private fun init() {
        // The slider acts once for the device, from the process the platform
        // keeps for the system user. Another user's process only follows it.
        val acting = UserHandle.myUserId() == UserHandle.USER_SYSTEM
        val actions = if (acting) Actions(this) else null
        for (name in ServiceManager.getDeclaredInstances(ISlider.DESCRIPTOR)) {
            val instance = SliderInstance(this, name, actions, handler)
            instances.add(instance)
            instance.watch()
        }
    }

    companion object {
        private const val TAG = "LineageSlider"
    }
}
