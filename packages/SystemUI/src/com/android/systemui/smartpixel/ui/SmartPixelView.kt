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
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Shader
import android.view.View

class SmartPixelView(context: Context) : View(context) {

    private val patternPaint = Paint()
    private var patternBitmap: Bitmap? = null
    private val sensorCutout = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updatePattern(bitmap: Bitmap) {
        patternBitmap?.recycle()
        patternBitmap = bitmap
        patternPaint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        invalidate()
    }

    fun setSensorCutout(center: Point, radius: Float) {
        val safeRadius = radius.coerceAtLeast(0f) + 4f * resources.displayMetrics.density
        sensorCutout.reset()
        sensorCutout.addCircle(
            center.x.toFloat(), center.y.toFloat(), safeRadius, Path.Direction.CW,
        )
        invalidate()
    }

    fun clearSensorCutout() {
        sensorCutout.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (patternBitmap == null) return
        val saveCount = canvas.save()
        if (!sensorCutout.isEmpty) {
            canvas.clipOutPath(sensorCutout)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), patternPaint)
        canvas.restoreToCount(saveCount)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        patternBitmap?.recycle()
        patternBitmap = null
    }
}
