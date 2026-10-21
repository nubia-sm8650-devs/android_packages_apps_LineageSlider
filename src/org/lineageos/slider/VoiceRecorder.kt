/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.Manifest.permission
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle

class VoiceRecorder(private val context: Context) {
    fun start() {
        if (!hasMicrophone(context)) {
            error("${SERVICE.packageName} has not been granted the microphone")
        }

        // Recorder names the recording itself when no name is given.
        send(ACTION_START, foreground = true)
    }

    fun stop() {
        send(ACTION_STOP, foreground = false)
    }

    // The recording belongs to the user in front of the screen, who is also
    // the one that granted Recorder the microphone.
    private fun send(action: String, foreground: Boolean) {
        val intent = Intent(action).setComponent(SERVICE)
        if (foreground) {
            context.startForegroundServiceAsUser(intent, UserHandle.CURRENT)
        } else {
            context.startServiceAsUser(intent, UserHandle.CURRENT)
        }
    }

    companion object {
        private val SERVICE =
            ComponentName(
                "org.lineageos.recorder",
                "org.lineageos.recorder.service.SoundRecorderService",
            )

        private const val ACTION_START = "org.lineageos.recorder.action.START"
        private const val ACTION_STOP = "org.lineageos.recorder.action.STOP"

        // Recorder is installed, and holds the microphone, per user, so
        // every question is asked of the user the recording starts for.
        private fun packagesOf(context: Context) =
            context
                .createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
                .packageManager

        fun isAvailable(context: Context): Boolean =
            packagesOf(context).resolveService(Intent().setComponent(SERVICE), 0) != null

        private fun hasMicrophone(context: Context) =
            packagesOf(context).checkPermission(permission.RECORD_AUDIO, SERVICE.packageName) ==
                PackageManager.PERMISSION_GRANTED

        // A runtime permission is the holder's to ask for and the user's to
        // give, so this opens the page rather than granting anything.
        fun grantIntent(context: Context): Intent? {
            if (hasMicrophone(context)) {
                return null
            }

            val user = UserHandle.of(ActivityManager.getCurrentUser())
            return Intent(Intent.ACTION_MANAGE_APP_PERMISSION)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, SERVICE.packageName)
                .putExtra(Intent.EXTRA_PERMISSION_NAME, permission.RECORD_AUDIO)
                .putExtra(Intent.EXTRA_USER, user)
        }
    }
}
