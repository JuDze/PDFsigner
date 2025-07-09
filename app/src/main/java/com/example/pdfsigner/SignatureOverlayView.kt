package com.example.pdfsigner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.gson.Gson
import kotlin.math.hypot

data class PointSample(
    val x: Float,
    val y: Float,
    val timestamp: Long,
    val pressure: Float,
    val velocity: Float
)

class SignatureOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var drawingModeEnabled = false // NEW: toggle drawing mode

    private val paint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 5f
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private var currentPath: Path? = null
    private val paths = mutableListOf<Path>()
    private val pointsPerPath = mutableListOf<MutableList<PointSample>>()

    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingModeEnabled) {
            return false // Let PDFView handle scrolling
        }

        val x = event.x
        val y = event.y
        val t = System.currentTimeMillis()
        val pressure = event.pressure

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().apply { moveTo(x, y) }
                paths.add(currentPath!!)
                pointsPerPath.add(mutableListOf())
                lastX = x
                lastY = y
                lastTime = t
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(x, y)
                val dt = (t - lastTime).coerceAtLeast(1)
                val dx = x - lastX
                val dy = y - lastY
                val velocity = hypot(dx.toDouble(), dy.toDouble()).toFloat() / dt
                pointsPerPath.last().add(PointSample(x, y, t, pressure, velocity))
                lastX = x
                lastY = y
                lastTime = t
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (path in paths) {
            canvas.drawPath(path, paint)
        }
    }

    fun clear() {
        paths.clear()
        pointsPerPath.clear()
        invalidate()
    }

    fun hasSignature(): Boolean {
        return paths.isNotEmpty()
    }

    fun getSignatureBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        draw(canvas)
        return bitmap
    }
    fun getSignatureBoundingBox(): RectF? {
        if (paths.isEmpty()) return null
        val rect = RectF()
        val first = RectF()
        paths[0].computeBounds(first, true)
        rect.set(first)
        for (i in 1 until paths.size) {
            val tmp = RectF()
            paths[i].computeBounds(tmp, true)
            rect.union(tmp)
        }
        return rect
    }


    fun getBiometricJson(): String {
        return Gson().toJson(pointsPerPath)
    }
}
