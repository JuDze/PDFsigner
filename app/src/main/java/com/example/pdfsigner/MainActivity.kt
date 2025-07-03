package com.example.pdfsigner

import android.Manifest
import android.app.Activity
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
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.cert.Certificate
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

class MainActivity : AppCompatActivity() {

    private val TAG = "PdfVisualSignerApp"
    private lateinit var pdfView: PDFView
    private lateinit var loadPdfButton: Button
    private lateinit var drawSignatureButton: Button
    private lateinit var signPdfButton: Button
    private lateinit var progressBar: ProgressBar

    private var currentPdfUri: Uri? = null
    private var capturedSignatureBitmap: Bitmap? = null
    private lateinit var keyStoreManager: KeyStoreManager

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            currentPdfUri = uri
            displayPdf(uri)
            drawSignatureButton.isEnabled = true
            signPdfButton.isEnabled = false
        } else {
            Toast.makeText(this, "No PDF selected", Toast.LENGTH_SHORT).show()
        }
    }

    private val signaturePadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val signatureBitmap: Bitmap? = data?.getParcelableExtra("signature")
            if (signatureBitmap != null) {
                capturedSignatureBitmap = signatureBitmap
                Toast.makeText(this, "Signature captured!", Toast.LENGTH_SHORT).show()
                signPdfButton.isEnabled = true
            } else {
                Toast.makeText(this, "Signature capture failed.", Toast.LENGTH_SHORT).show()
                signPdfButton.isEnabled = false
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(this, "Signature capture cancelled.", Toast.LENGTH_SHORT).show()
            signPdfButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pdfView = findViewById(R.id.pdfView)
        loadPdfButton = findViewById(R.id.loadPdfButton)
        drawSignatureButton = findViewById(R.id.drawSignatureButton)
        signPdfButton = findViewById(R.id.applySignatureButton)
        progressBar = findViewById(R.id.progressBar)

        loadPdfButton.setOnClickListener { requestPermissionsAndPickPdf() }
        drawSignatureButton.setOnClickListener {
            if (currentPdfUri != null) {
                val intent = Intent(this, SignatureActivity::class.java)
                signaturePadLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Please load a PDF first.", Toast.LENGTH_SHORT).show()
            }
        }
        signPdfButton.setOnClickListener {
            if (currentPdfUri != null && capturedSignatureBitmap != null) {
                signPdfDocument()
            } else {
                Toast.makeText(this, "Load PDF and draw signature first.", Toast.LENGTH_SHORT).show()
            }
        }

        try {
            keyStoreManager = KeyStoreManager(this)
            keyStoreManager.generateKeyPairAndCertificate()
        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing KeyStoreManager: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermissionsAndPickPdf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                pickPdfLauncher.launch("application/pdf")
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                pickPdfLauncher.launch("application/pdf")
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    1
                )
            }
        }
    }

    private fun displayPdf(pdfUri: Uri) {
        pdfView.fromUri(pdfUri)
            .onLoad {}
            .onError { t ->
                Toast.makeText(this, "Error loading PDF: ${t.message}", Toast.LENGTH_LONG).show()
            }
            .load()
    }

    private fun signPdfDocument() {
        if (currentPdfUri == null || capturedSignatureBitmap == null) return

        progressBar.visibility = View.VISIBLE
        loadPdfButton.isEnabled = false
        drawSignatureButton.isEnabled = false
        signPdfButton.isEnabled = false

        activityScope.launch {
            try {
                val privateKey = keyStoreManager.getPrivateKey()
                val certificateChain = keyStoreManager.getCertificateChain()
                val singleCert = keyStoreManager.getCertificate()

                if (privateKey == null) throw IllegalStateException("Private key not found.")
                if (singleCert == null) throw IllegalStateException("Certificate not found.")

                val safeChain = if (certificateChain.isNullOrEmpty()) arrayOf(singleCert) else certificateChain

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outputFile = File(downloadsDir, "signed_${System.currentTimeMillis()}.pdf")

                val inputStream = contentResolver.openInputStream(currentPdfUri!!)!!
                val outputStream = FileOutputStream(outputFile)

                val pageCount = PDDocument.load(contentResolver.openInputStream(currentPdfUri!!)!!).use { it.numberOfPages }
                val pageNumber = pageCount
                val signaturePosition = PDRectangle(50f, 50f, 150f, 100f)

                val pdfSigner = PdfSigner()
                awaitAddVisualAndDigitalSignature(
                    pdfSigner,
                    inputStream,
                    outputStream,
                    capturedSignatureBitmap!!,
                    signaturePosition,
                    pageNumber,
                    privateKey,
                    safeChain,
                    "For RVPP purposes",
                    "Rīga, Latvia"
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "PDF signed:\n${outputFile.absolutePath}", Toast.LENGTH_LONG).show()
                    displayPdf(Uri.fromFile(outputFile))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    loadPdfButton.isEnabled = true
                    drawSignatureButton.isEnabled = true
                    signPdfButton.isEnabled = true
                }
                capturedSignatureBitmap?.recycle()
                capturedSignatureBitmap = null
            }
        }
    }

    private suspend fun awaitAddVisualAndDigitalSignature(
        pdfSigner: PdfSigner,
        inputStream: InputStream,
        outputStream: OutputStream,
        visualSignatureBitmap: Bitmap,
        position: PDRectangle,
        pageNumber: Int,
        privateKey: PrivateKey,
        certificateChain: Array<Certificate>,
        reason: String,
        location: String
    ): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            pdfSigner.addVisualAndDigitalSignature(
                inputStream,
                outputStream,
                visualSignatureBitmap,
                position,
                pageNumber,
                privateKey,
                certificateChain,
                reason,
                location,
                object : PdfSigner.PdfSignCallback {
                    override fun onSuccess(outputPath: String) {
                        continuation.resumeWith(Result.success(outputPath))
                    }
                    override fun onError(e: Exception) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        capturedSignatureBitmap?.recycle()
    }
}
