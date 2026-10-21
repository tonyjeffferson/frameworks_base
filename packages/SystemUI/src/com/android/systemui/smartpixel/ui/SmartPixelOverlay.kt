/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.smartpixel.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Surface
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.UdfpsController
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.smartpixel.domain.SmartPixelSettings
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn

@SysUISingleton
class SmartPixelOverlay @Inject constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    @Application private val applicationScope: CoroutineScope,
    private val settings: SmartPixelSettings,
    private val authController: AuthController,
    private val udfpsControllerProvider: Provider<UdfpsController>,
) {
    private var filterView: SmartPixelView? = null
    private var viewController: SmartPixelViewController? = null
    private var sensorCutoutCenter: Point? = null
    private var sensorCutoutRadius = 0f
    private var isFingerDown = false
    private var udfpsCallbackRegistered = false

    private val authControllerCallback = object : AuthController.Callback {
        override fun onAllAuthenticatorsRegistered(modality: Int) {
            registerUdfpsCallbackIfSupported()
        }

        override fun onUdfpsLocationChanged(udfpsOverlayParams: UdfpsOverlayParams) {
            updateSensorCutout(udfpsOverlayParams)
        }
    }

    private val udfpsControllerCallback = object : UdfpsController.Callback {
        override fun onFingerDown() {
            isFingerDown = true
            updateSensorCutoutVisibility()
        }

        override fun onFingerUp() {
            isFingerDown = false
            updateSensorCutoutVisibility()
        }
    }

    fun init() {
        settings.init()
        authController.addCallback(authControllerCallback)
        registerUdfpsCallbackIfSupported()

        authController.udfpsLocation?.let { location ->
            val radius = authController.udfpsRadius
            if (radius > 0f) {
                val display = context.display
                val rotation = display?.rotation ?: Surface.ROTATION_0
                sensorCutoutCenter = rotatePortraitPoint(location, rotation)
                sensorCutoutRadius = radius
            }
        }

        combine(
            settings.isEnabled,
            settings.percent,
        ) { enabled, percent ->
            if (enabled) {
                showOverlay()
                viewController?.updateConfig(percent)
            } else {
                hideOverlay()
            }
        }.launchIn(applicationScope)
    }

    private fun registerUdfpsCallbackIfSupported() {
        if (!udfpsCallbackRegistered && authController.isOpticalUdfpsSupported()) {
            udfpsControllerProvider.get().addCallback(udfpsControllerCallback)
            udfpsCallbackRegistered = true
        }
    }

    private fun updateSensorCutout(params: UdfpsOverlayParams) {
        val bounds = params.sensorBounds
        if (bounds.isEmpty) {
            sensorCutoutCenter = null
            sensorCutoutRadius = 0f
            updateSensorCutoutVisibility()
            return
        }

        sensorCutoutCenter = rotateNaturalPoint(
            Point(bounds.centerX(), bounds.centerY()),
            params.naturalDisplayWidth,
            params.naturalDisplayHeight,
            params.rotation,
        )
        sensorCutoutRadius = maxOf(bounds.width(), bounds.height()) / 2f
        updateSensorCutoutVisibility()
    }

    private fun updateSensorCutoutVisibility() {
        val view = filterView ?: return
        val center = sensorCutoutCenter
        if (isFingerDown && center != null && sensorCutoutRadius > 0f) {
            view.setSensorCutout(center, sensorCutoutRadius)
        } else {
            view.clearSensorCutout()
        }
    }

    private fun rotatePortraitPoint(point: Point, rotation: Int): Point {
        val metrics = context.resources.displayMetrics
        val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val naturalWidth = if (isLandscape) metrics.heightPixels else metrics.widthPixels
        val naturalHeight = if (isLandscape) metrics.widthPixels else metrics.heightPixels
        return rotateNaturalPoint(point, naturalWidth, naturalHeight, rotation)
    }

    private fun rotateNaturalPoint(
        point: Point,
        naturalWidth: Int,
        naturalHeight: Int,
        rotation: Int,
    ): Point = when (rotation) {
        Surface.ROTATION_90 -> Point(naturalHeight - point.y, point.x)
        Surface.ROTATION_180 -> Point(naturalWidth - point.x, naturalHeight - point.y)
        Surface.ROTATION_270 -> Point(point.y, naturalWidth - point.x)
        else -> point
    }

    private fun showOverlay() {
        if (filterView != null) return

        val view = SmartPixelView(context)
        val controller = SmartPixelViewController(view)

        val params = WindowManager.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                LayoutParams.FLAG_HARDWARE_ACCELERATED or
                LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        )
        params.title = "SmartPixelFilter"
        params.layoutInDisplayCutoutMode =
            LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.privateFlags = params.privateFlags or
            LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY

        windowManager.addView(view, params)
        controller.init()
        controller.updateConfig(settings.percent.value)

        filterView = view
        viewController = controller
        updateSensorCutoutVisibility()
    }

    private fun hideOverlay() {
        viewController?.destroy()
        filterView?.let { view ->
            windowManager.removeViewImmediate(view)
        }
        filterView = null
        viewController = null
    }
}
