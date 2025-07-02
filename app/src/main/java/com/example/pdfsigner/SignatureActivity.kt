package com.example.pdfsigner // IMPORTANT: Ensure this package name matches your project's root package

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.gcacace.signaturepad.views.SignaturePad

class SignatureActivity : AppCompatActivity() {

    private lateinit var signaturePad: SignaturePad
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        signaturePad = SignaturePad(this, null).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            ).apply {
                bottomMargin = 16
            }
            setMinWidth(5f) // Your desired min width for the pen stroke
            setMaxWidth(8f) // Your desired max width for the pen stroke
            // Experiment with velocityFilterWeight for more dynamic line changes.
            // A lower value (e.g., 0.6f) makes the line width respond more to drawing speed.
            // setVelocityFilterWeight(0.6f)
            setPenColor(android.graphics.Color.BLACK)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        layout.addView(signaturePad)

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        saveButton = Button(this).apply {
            text = "Save Signature"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                marginEnd = 8
            }
            setOnClickListener {
                if (!signaturePad.isEmpty) {
                    val originalBitmap = signaturePad.signatureBitmap
                    Log.d("SignatureActivity", "Original Bitmap size: ${originalBitmap.byteCount / 1024} KB (Width: ${originalBitmap.width}, Height: ${originalBitmap.height})")

                    // Aggressively scale down the bitmap before sending it back
                    // This is crucial for Android 14+ to avoid TransactionTooLargeException
                    val scaledBitmap = scaleBitmap(originalBitmap, 400) // Max side length 400px
                    Log.d("SignatureActivity", "Scaled Bitmap size (for Intent): ${scaledBitmap.byteCount / 1024} KB (Width: ${scaledBitmap.width}, Height: ${scaledBitmap.height})")

                    // Recycle the original bitmap if it's different from the scaled one, to free up memory
                    if (originalBitmap != scaledBitmap) {
                        originalBitmap.recycle()
                        Log.d("SignatureActivity", "Original bitmap recycled.")
                    }

                    val resultIntent = Intent().apply {
                        putExtra("signature", scaledBitmap)
                    }
                    setResult(Activity.RESULT_OK, resultIntent) // Explicitly set result to OK
                    Log.d("SignatureActivity", "Signature saved successfully. Finishing with RESULT_OK.")
                    finish() // Close SignatureActivity
                } else {
                    Toast.makeText(this@SignatureActivity, "Please sign to save", Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_CANCELED) // Explicitly set result to CANCELED
                    Log.d("SignatureActivity", "Attempted to save empty signature. Finishing with RESULT_CANCELED.")
                    finish()
                }
            }
        }
        buttonLayout.addView(saveButton)

        clearButton = Button(this).apply {
            text = "Clear"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                marginStart = 8
            }
            setOnClickListener {
                signaturePad.clear()
            }
        }
        buttonLayout.addView(clearButton)

        layout.addView(buttonLayout)
        setContentView(layout)
    }

    // Override onBackPressed to explicitly set RESULT_CANCELED when user navigates back
    override fun onBackPressed() {
        setResult(Activity.RESULT_CANCELED)
        Log.d("SignatureActivity", "Back button pressed. Finishing with RESULT_CANCELED.")
        super.onBackPressed()
    }

    /**
     * Scales down a Bitmap to a maximum specified side length while maintaining its aspect ratio.
     * This is crucial to prevent TransactionTooLargeException when passing Bitmaps via Intents.
     * @param bitmap The original Bitmap to scale.
     * @param maxSideLength The maximum desired width or height for the scaled bitmap.
     * @return The scaled Bitmap.
     */
    private fun scaleBitmap(bitmap: Bitmap, maxSideLength: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSideLength && height <= maxSideLength) {
            return bitmap
        }

        val ratio: Float = Math.min(
            maxSideLength.toFloat() / width.toFloat(),
            maxSideLength.toFloat() / height.toFloat()
        )

        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
