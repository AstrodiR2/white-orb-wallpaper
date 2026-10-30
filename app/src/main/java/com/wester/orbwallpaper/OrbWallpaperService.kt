package com.wester.orbwallpaper

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.animation.DecelerateInterpolator

// config
private const val kOrbRadiusDp = 42f          // orb radius, density-independent px
private const val kEdgeMarginDp = 56f         // distance from right/top edge when docked
private const val kAnimDurationMs = 650L      // lock -> corner travel time
private const val kTickMs = 16L               // ~60fps redraw
private const val kGlowSoftness = 2.6f        // radial gradient falloff multiplier

class OrbWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = OrbEngine()

    inner class OrbEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var surfaceW = 0
        private var surfaceH = 0

        private val density = resources.displayMetrics.density
        private val orbRadius = kOrbRadiusDp * density
        private val edgeMargin = kEdgeMarginDp * density

        // 0f = centered (locked), 1f = docked top-right (unlocked)
        private var progress = 0f
        private var animator: ValueAnimator? = null

        private val bgPaint = Paint().apply { color = Color.BLACK; isAntiAlias = true }
        private val orbPaint = Paint().apply { isAntiAlias = true }

        private val drawRunner = Runnable { drawFrame() }

        private val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> animateTo(1f)   // unlocked -> corner
                    Intent.ACTION_SCREEN_OFF -> {
                        animator?.cancel()
                        progress = 0f                              // reset for next lock screen
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(unlockReceiver, filter)
        }

        override fun onDestroy() {
            super.onDestroy()
            runCatching { unregisterReceiver(unlockReceiver) }
            animator?.cancel()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width
            surfaceH = height
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) handler.post(drawRunner) else handler.removeCallbacks(drawRunner)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunner)
            animator?.cancel()
        }

        private fun animateTo(target: Float) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = kAnimDurationMs
                interpolator = DecelerateInterpolator()
                addUpdateListener { progress = it.animatedValue as Float }
                start()
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null && surfaceW > 0 && surfaceH > 0) {
                    canvas.drawRect(0f, 0f, surfaceW.toFloat(), surfaceH.toFloat(), bgPaint)

                    // center position (locked) -> top-right corner (unlocked)
                    val centerX = surfaceW / 2f
                    val centerY = surfaceH / 2f
                    val cornerX = surfaceW - edgeMargin
                    val cornerY = edgeMargin

                    val x = centerX + (cornerX - centerX) * progress
                    val y = centerY + (cornerY - centerY) * progress

                    // slight shrink as it docks, like it's settling into place
                    val radius = orbRadius * (1f - 0.25f * progress)

                    orbPaint.shader = RadialGradient(
                        x, y, radius * kGlowSoftness,
                        Color.WHITE, Color.argb(0, 255, 255, 255),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(x, y, radius * kGlowSoftness, orbPaint)

                    orbPaint.shader = null
                    orbPaint.color = Color.WHITE
                    canvas.drawCircle(x, y, radius, orbPaint)
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }

            handler.removeCallbacks(drawRunner)
            if (visible) handler.postDelayed(drawRunner, kTickMs)
        }
    }
}
