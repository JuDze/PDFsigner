package com.example.pdfsigner

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * A container that lets the user move & scale a child ImageView (the signature bitmap).
 */
class SignaturePlacementView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.MATRIX
    }
    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    // gesture detectors
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    // For drag
    private var lastTouch = PointF()
    private var mode = Mode.NONE

    private enum class Mode { NONE, DRAG, ZOOM }

    init {
        addView(imageView, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    /** Set the signature bitmap to be placed & resized */
    fun setSignatureBitmap(bitmap: android.graphics.Bitmap) {
        imageView.setImageBitmap(bitmap)
        // reset transform
        matrix.reset()
        imageView.imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                lastTouch.set(event.x, event.y)
                mode = Mode.DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                savedMatrix.set(matrix)
                mode = Mode.ZOOM
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.DRAG) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    matrix.set(savedMatrix)
                    matrix.postTranslate(dx, dy)
                }
                // pinch handled in ScaleListener
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = Mode.NONE
            }
        }

        imageView.imageMatrix = matrix
        return true
    }

    /** Give back the bounding box (in this view's coordinate space) of the signature */
    fun getPlacementRect(): android.graphics.RectF {
        // get image bounds & map through matrix
        val drawable = imageView.drawable ?: throw IllegalStateException("No signature set")
        val w = drawable.intrinsicWidth.toFloat()
        val h = drawable.intrinsicHeight.toFloat()
        val pts = floatArrayOf(0f,0f, w,0f, w,h, 0f,h)
        matrix.mapPoints(pts)
        val xs = pts.filterIndexed { i, _ -> i%2==0 }
        val ys = pts.filterIndexed { i, _ -> i%2==1 }
        return android.graphics.RectF(
            xs.minOrNull()!!, ys.minOrNull()!!,
            xs.maxOrNull()!!, ys.maxOrNull()!!
        )
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            matrix.set(savedMatrix)
            matrix.postScale(
                detector.scaleFactor,
                detector.scaleFactor,
                detector.focusX,
                detector.focusY
            )
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // reset on double-tap
            matrix.reset()
            imageView.imageMatrix = matrix
            return true
        }
    }
}
