package com.vibe.assistant.access

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Screen capture the way the Gemini app shares the screen: Android's MediaProjection
 * ("Start now" once), available on every Android Vibe supports — not only 11+, where
 * the accessibility screenshot exists.
 *
 * The screen is mirrored into a small virtual display (long side ≤ 1280 px), and the
 * newest frame is always kept, so a still screen can still be "photographed"
 * (Lucy's capture code missed that case: no new frame arrives when nothing changes).
 */
object ScreenProjector {
    /** Set by the consent screen, used once by [start]. */
    @Volatile var resultCode = 0
    @Volatile var resultData: Intent? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile private var frame: Bitmap? = null
    @Volatile var frameAt = 0L
        private set
    private var realW = 0
    private var realH = 0

    val active get() = projection != null

    fun hasConsent() = resultData != null

    /** Starts mirroring. Call from the (foreground) service after consent. */
    fun start(service: Service): Boolean {
        if (projection != null) return true
        val data = resultData ?: return false
        val mpm = service.getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        } ?: run {
            // A consent token works once on Android 14+; ask again next time.
            resultData = null
            return false
        }
        val t = HandlerThread("vibe-projection").apply { start() }
        val h = Handler(t.looper)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }, h)

        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        service.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        realW = m.widthPixels
        realH = m.heightPixels
        val scale = minOf(1f, 1280f / maxOf(realW, realH))
        val w = (realW * scale).toInt().coerceAtLeast(2) and 1.inv()
        val hgt = (realH * scale).toInt().coerceAtLeast(2) and 1.inv()
        val r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, hgt, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                frame = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, hgt)
                frameAt = android.os.SystemClock.uptimeMillis()
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, h)
        val vd = try {
            p.createVirtualDisplay(
                "vibe-screen", w, hgt, (m.densityDpi * scale).toInt().coerceAtLeast(120),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
            )
        } catch (e: Exception) {
            r.close(); t.quitSafely(); p.stop()
            resultData = null
            return false
        }
        projection = p
        display = vd
        reader = r
        thread = t
        return true
    }

    fun stop() {
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        display = null; reader = null; projection = null; thread = null
        frame = null
        // Android 14+ tokens are single-use; older ones may be reused while valid.
        if (Build.VERSION.SDK_INT >= 34) resultData = null
    }

    /** The newest frame as JPEG, long side ≤ [maxSide], with the real screen size for coordinates. */
    fun shot(maxSide: Int, quality: Int): VibeAccessibility.Shot? {
        val f = frame ?: return null
        val scale = maxSide.toFloat() / maxOf(f.width, f.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(f, (f.width * scale).toInt(), (f.height * scale).toInt(), true) else f
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return VibeAccessibility.Shot(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), realW, realH)
    }

    fun consentIntent(ctx: Context): Intent =
        ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
}
