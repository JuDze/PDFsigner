package com.example.pdfsigner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.barteksc.pdfviewer.PDFView
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle as Box
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var pdfView: PDFView
    private lateinit var signatureOverlay: SignatureOverlayView
    private lateinit var placementView: SignaturePlacementView

    private lateinit var loadPdfButton: Button
    private lateinit var signButton: Button
    private lateinit var placeButton: Button
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button
    private lateinit var progressBar: ProgressBar

    private var currentPdfUri: Uri? = null
    private var currentPageIndex = 0
    private var capturedBitmap: Bitmap? = null
    private var capturedJson: String? = null

    private lateinit var keyStoreManager: KeyStoreManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            currentPdfUri = it
            signatureOverlay.visibility = View.GONE
            placementView.visibility = View.GONE
            signButton.isEnabled = true
            clearButton.isEnabled = false
            saveButton.isEnabled  = false
            placeButton.isEnabled = false
            displayPdf(it)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_main)

        pdfView          = findViewById(R.id.pdfView)
        signatureOverlay = findViewById(R.id.signatureOverlay)
        placementView    = findViewById(R.id.signaturePlacementView)
        loadPdfButton    = findViewById(R.id.loadPdfButton)
        signButton       = findViewById(R.id.signButton)
        placeButton      = findViewById(R.id.placeButton)
        saveButton       = findViewById(R.id.saveButton)
        clearButton      = findViewById(R.id.clearButton)
        progressBar      = findViewById(R.id.progressBar)

        keyStoreManager = KeyStoreManager(this).apply {
            // ensure a fresh key with SHA-512 support
            deleteKeyIfExists()
            generateKeyPairAndCertificate()
        }
        signButton.isEnabled = false

        loadPdfButton.setOnClickListener {
            requestPdf()

        }
        signButton.setOnClickListener {
            signatureOverlay.visibility = View.VISIBLE
            signatureOverlay.drawingModeEnabled = true
            Toast.makeText(this, "Draw your signature", Toast.LENGTH_SHORT).show()
            placeButton.isEnabled = true
            clearButton.isEnabled = true
        }
        clearButton.setOnClickListener {
            signatureOverlay.clear()
            placementView.visibility = View.GONE
            placeButton.isEnabled = false
            saveButton.isEnabled = false
        }
        placeButton.setOnClickListener {
            capturedBitmap = signatureOverlay.getSignatureBitmap()
            capturedJson   = signatureOverlay.getBiometricJson()
            signatureOverlay.visibility = View.GONE
            placementView.setSignatureBitmap(capturedBitmap!!)
            placementView.visibility = View.VISIBLE
            placeButton.isEnabled = false
            saveButton.isEnabled = true
        }
        saveButton.setOnClickListener {
            signatureOverlay.drawingModeEnabled = false
            saveButton.isEnabled = false
            clearButton.isEnabled = false
            signButton.isEnabled = false
            doEmbedAndSign()
        }

        clearButton.isEnabled = false
        placeButton.isEnabled = false
        saveButton.isEnabled  = false
    }

    private fun requestPdf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                pickPdfLauncher.launch("application/pdf")
            } else {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                pickPdfLauncher.launch("application/pdf")
            } else {
                ActivityCompat.requestPermissions(this, perms, 0)
            }
        }
    }

    private fun displayPdf(uri: Uri) {
        pdfView.fromUri(uri)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .enableAntialiasing(true)
            .defaultPage(0)
            .spacing(8)
            .onPageChange { page, _ -> currentPageIndex = page }
            .load()
    }

    private fun doEmbedAndSign() {
        val inUri = currentPdfUri ?: return
        val bmp   = capturedBitmap ?: return
        val bio   = capturedJson   ?: "{}"
        val viewRect = placementView.getPlacementRect()  // android.graphics.RectF

        progressBar.visibility = View.VISIBLE

        scope.launch(Dispatchers.IO) {
            try {
                // --- compute PDF coords from viewRect ---
                val (pdfWidth, pdfHeight) = contentResolver.openInputStream(inUri)!!.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        val page = doc.getPage(currentPageIndex)
                        page.mediaBox.width to page.mediaBox.height
                    }
                }

                val sx = pdfWidth  / placementView.width
                val sy = pdfHeight / placementView.height

                val pdfRect = PDRectangle(
                    viewRect.left   * sx,
                    pdfHeight - viewRect.bottom * sy,
                    viewRect.width()  * sx,
                    viewRect.height() * sy
                )

                // --- Phase 1: embed visual signature ---
                val tmp = File(cacheDir, "tmp.pdf")
                contentResolver.openInputStream(inUri)!!.use { inp ->
                    FileOutputStream(tmp).use { out ->
                        PdfSigner().embedVisualSignature(
                            pdfInput              = inp,
                            pdfOutput             = out,
                            visualSignatureBitmap = bmp,
                            pageNumber            = currentPageIndex + 1,
                            position              = pdfRect,
                            biometricJson         = bio
                        )
                    }
                }

                // --- Phase 2: digital sign ---
                val signed = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    "signed_${System.currentTimeMillis()}.pdf"
                )
                // regenerate key to ensure correct digests
                keyStoreManager.deleteKeyIfExists()
                keyStoreManager.generateKeyPairAndCertificate()

                PdfSigner().addDigitalSignature(
                    inputFile        = tmp,
                    outputFile       = signed,
                    privateKey       = keyStoreManager.getPrivateKey()!!,
                    certificateChain = keyStoreManager.getCertificateChain()!!,
                    biometricJson    = bio,
                    reason           = "Approved by user",
                    location         = "Riga, Latvia"
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Signed: ${signed.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                    displayPdf(Uri.fromFile(signed))
                    signatureOverlay.clear()
                    placementView.visibility = View.GONE
                }
            } catch (ex: Exception) {
                Log.e("PdfSigner", "Error embedding/signing PDF", ex)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${ex.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    clearButton.isEnabled = true
                    signButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
