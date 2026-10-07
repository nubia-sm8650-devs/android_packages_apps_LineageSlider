/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayUtils
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import vendor.lineage.slider.Edge
import vendor.lineage.slider.SliderInfo

class SliderDialog(private val context: Context) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var windows: WindowManager? = null
    private var shownDisplay = Display.DEFAULT_DISPLAY
    private var shownRotation = Surface.ROTATION_0
    private val hide = Runnable { dismiss() }

    // A placed window names a point in one rotation, and onDisplayChanged
    // also reports a refresh rate change, which is not a rotation.
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == shownDisplay) {
                    dismiss()
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == shownDisplay && rotation(displayId) != shownRotation) {
                    dismiss()
                }
            }
        }

    private val layoutParams =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                FLAGS,
                PixelFormat.TRANSLUCENT,
            )
            .apply { title = "LineageSlider" }

    fun show(label: String, info: SliderInfo?) {
        handler.post {
            val display = displayFor(info) ?: return@post
            if (view != null && display.displayId != shownDisplay) {
                dismiss()
            }

            // The window belongs to the display the offset was measured on, so
            // the inflater, the metrics and the margins come from there too.
            val displayContext = context.createDisplayContext(display)
            val windowManager =
                displayContext.getSystemService(WindowManager::class.java) ?: return@post
            place(info, displayContext, windowManager)

            val existing = view
            val shown =
                existing
                    ?: LayoutInflater.from(displayContext)
                        .inflate(R.layout.slider_dialog, null)
                        .let {
                            if (runCatching { windowManager.addView(it, layoutParams) }.isFailure) {
                                return@post
                            }
                            view = it
                            windows = windowManager
                            shownDisplay = display.displayId
                            displayManager?.registerDisplayListener(displayListener, handler)
                            it
                        }
            if (existing != null) {
                runCatching { windowManager.updateViewLayout(existing, layoutParams) }
            }
            (shown as TextView).text = label
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, DURATION)
        }
    }

    // The device may name the display its offset is measured on, and one that
    // is not attached leaves the default display to place the overlay.
    private fun displayFor(info: SliderInfo?): Display? {
        val named = info?.location?.display?.takeIf { it.isNotEmpty() }
        if (named != null) {
            displayManager
                ?.displays
                ?.firstOrNull { it.uniqueId == named }
                ?.let {
                    return it
                }
        }
        return displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun place(info: SliderInfo?, displayContext: Context, windowManager: WindowManager) {
        val display = displayContext.display
        val location =
            info?.location?.takeIf { it.display.isEmpty() || it.display == display.uniqueId }
        shownRotation = display.rotation

        // An offset measured on a display this overlay does not draw on cannot
        // place it, so it is treated as no offset at all.
        if (location == null) {
            layoutParams.flags = FLAGS
            layoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            layoutParams.fitInsetsTypes = WindowInsets.Type.systemBars()
            layoutParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            layoutParams.x = 0
            layoutParams.y =
                displayContext.resources.getDimensionPixelSize(R.dimen.slider_indicator_margin)
            return
        }

        // Centred gravity centres the view in its container, which the system
        // bars would otherwise inset to less than the display.
        layoutParams.flags = FLAGS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        layoutParams.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        layoutParams.fitInsetsTypes = 0

        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val rotation = shownRotation
        val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val naturalWidth = if (sideways) height else width
        val naturalHeight = if (sideways) width else height

        // The offset is measured at the display's native resolution, which is
        // not the one it is driven at while a smaller mode is selected.
        val native = DisplayUtils.getMaximumResolutionDisplayMode(display.supportedModes)
        val ratio =
            native?.let {
                DisplayUtils.getPhysicalPixelDisplaySizeRatio(
                    it.physicalWidth,
                    it.physicalHeight,
                    naturalWidth,
                    naturalHeight,
                )
            }
        val scale = ratio?.takeIf { it.isFinite() } ?: 1f
        val offset = (location.offsetPixels * scale).toInt()

        val naturalX =
            when (location.edge) {
                Edge.LEFT -> 0
                Edge.RIGHT -> naturalWidth
                else -> offset
            }
        val naturalY =
            when (location.edge) {
                Edge.TOP -> 0
                Edge.BOTTOM -> naturalHeight
                else -> offset
            }

        // The edge the window hugs is the named edge carried through the
        // rotation, which an offset at either end of it does not change.
        val screenEdge =
            when (rotation) {
                Surface.ROTATION_90 -> ROTATED_90
                Surface.ROTATION_180 -> ROTATED_180
                Surface.ROTATION_270 -> ROTATED_270
                else -> null
            }?.get(location.edge) ?: location.edge

        val x: Int
        val y: Int
        when (rotation) {
            Surface.ROTATION_90 -> {
                x = naturalY
                y = naturalWidth - naturalX
            }
            Surface.ROTATION_180 -> {
                x = naturalWidth - naturalX
                y = naturalHeight - naturalY
            }
            Surface.ROTATION_270 -> {
                x = naturalHeight - naturalY
                y = naturalX
            }
            else -> {
                x = naturalX
                y = naturalY
            }
        }

        // Flush against the edge would put the rounded corners and the shadow
        // under a rounded display's own corner radius.
        val margin =
            displayContext.resources.getDimensionPixelSize(R.dimen.slider_indicator_edge_margin)

        layoutParams.apply {
            when (screenEdge) {
                Edge.LEFT -> {
                    gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                    this.x = margin
                    this.y = y - height / 2
                }
                Edge.RIGHT -> {
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    this.x = margin
                    this.y = y - height / 2
                }
                Edge.TOP -> {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    this.y = margin
                    this.x = x - width / 2
                }
                else -> {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    this.y = margin
                    this.x = x - width / 2
                }
            }
        }
    }

    private fun dismiss() {
        view?.let {
            displayManager?.unregisterDisplayListener(displayListener)
            runCatching { windows?.removeView(it) }
        }
        view = null
        windows = null
        handler.removeCallbacks(hide)
    }

    private fun rotation(displayId: Int) =
        displayManager?.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0

    companion object {
        private const val DURATION = 1500L

        private val ROTATED_90 =
            mapOf(
                Edge.LEFT to Edge.BOTTOM,
                Edge.TOP to Edge.LEFT,
                Edge.RIGHT to Edge.TOP,
                Edge.BOTTOM to Edge.RIGHT,
            )
        private val ROTATED_180 =
            mapOf(
                Edge.LEFT to Edge.RIGHT,
                Edge.TOP to Edge.BOTTOM,
                Edge.RIGHT to Edge.LEFT,
                Edge.BOTTOM to Edge.TOP,
            )
        private val ROTATED_270 =
            mapOf(
                Edge.LEFT to Edge.TOP,
                Edge.TOP to Edge.RIGHT,
                Edge.RIGHT to Edge.BOTTOM,
                Edge.BOTTOM to Edge.LEFT,
            )

        private const val FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
    }
}
