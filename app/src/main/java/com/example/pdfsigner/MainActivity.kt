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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.Security
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : AppCompatActivity() {

    private val TAG = "PdfVisualSignerApp"
    private val REQUEST_STORAGE_PERMISSIONS = 1
    private val REQUEST_MANAGE_ALL_FILES_ACCESS = 2

    private lateinit var pdfView: PDFView
    private lateinit var loadPdfButton: Button
    private lateinit var drawSignatureButton: Button
    private lateinit var signPdfButton: Button
    private lateinit var progressBar: ProgressBar

    private var currentPdfUri: Uri? = null
    private var capturedSignatureBitmap: Bitmap? = null
    private lateinit var keyStoreManager: KeyStoreManager

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

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
            Log.d(TAG, "Key pair and certificate initialized/generated.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing KeyStoreManager or generating key pair: ${e.message}", e)
            Toast.makeText(this, "Error initializing security: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermissionsAndPickPdf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                pickPdfLauncher.launch("application/pdf")
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivityForResult(intent, REQUEST_MANAGE_ALL_FILES_ACCESS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                pickPdfLauncher.launch("application/pdf")
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_STORAGE_PERMISSIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permissions granted.", Toast.LENGTH_SHORT).show()
                pickPdfLauncher.launch("application/pdf")
            } else {
                Toast.makeText(this, "Storage permissions denied. Cannot load or save PDF.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_ALL_FILES_ACCESS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Manage external storage permission granted.", Toast.LENGTH_SHORT).show()
                    pickPdfLauncher.launch("application/pdf")
                } else {
                    Toast.makeText(this, "Manage external storage permission denied. Cannot save signed PDF to public storage.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayPdf(pdfUri: Uri) {
        pdfView.fromUri(pdfUri)
            .onLoad { nbPages ->
                Log.d(TAG, "PDF loaded with $nbPages pages.")
            }
            .onError { t ->
                Log.e(TAG, "Error loading PDF: ${t.message}", t)
                Toast.makeText(this, "Error loading PDF: ${t.message}", Toast.LENGTH_LONG).show()
            }
            .load()
    }

    private fun signPdfDocument() {
        if (currentPdfUri == null) {
            Toast.makeText(this, "No PDF loaded.", Toast.LENGTH_SHORT).show()
            return
        }
        if (capturedSignatureBitmap == null) {
            Toast.makeText(this, "No signature drawn.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!keyStoreManager.containsKey()) {
            Toast.makeText(this, "Digital signing certificate not available. Please restart app.", Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        loadPdfButton.isEnabled = false
        drawSignatureButton.isEnabled = false
        signPdfButton.isEnabled = false

        activityScope.launch {
            try {
                val privateKey: PrivateKey? = keyStoreManager.getPrivateKey()
                val certificateChain: Array<Certificate>? = keyStoreManager.getCertificateChain()

                if (privateKey == null || certificateChain == null || certificateChain.isEmpty()) {
                    throw IllegalStateException("Private key or certificate chain not found in KeyStore.")
                }

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val outputFileName = "digitally_signed_secure_${System.currentTimeMillis()}.pdf"
                val outputFile = File(downloadsDir, outputFileName)

                val inputStream: InputStream = contentResolver.openInputStream(currentPdfUri!!)!!
                val outputStream: OutputStream = FileOutputStream(outputFile)

                // Define signature position (example: bottom-left corner of the first page)
                // The values are in PDF points (1/72 inch). Adjust as needed for your desired placement.
                // PDRectangle(x, y, width, height) where y is the bottom coordinate.
                //val signaturePosition = PDRectangle(50f, 50f, 150f, 100f)
                //val pageNumber = 1 // Add signature to the first page
// Load the PDF first to get page count
                val pageCount: Int = PDDocument.load(contentResolver.openInputStream(currentPdfUri!!)!!).use { it.numberOfPages }

// Put the signature on the last page
                val pageNumber = pageCount
                val signaturePosition = PDRectangle(50f, 50f, 150f, 100f)
                // --- Define Security Parameters ---
                val userPassword = "user123"
                val ownerPassword = "owner456"

                val allowPrinting = true
                val allowCopy = false
                val allowModifyContents = false

                val reason = "I approve this document"
                val location = "Riga, Latvia"
                // ----------------------------------

                val pdfSigner = PdfSigner()
                awaitAddVisualAndDigitalSignatureAndEncrypt(pdfSigner, inputStream, outputStream,
                    capturedSignatureBitmap!!, signaturePosition, pageNumber,
                    privateKey, certificateChain, reason, location,
                    userPassword, ownerPassword, allowPrinting, allowCopy, allowModifyContents)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "PDF digitally signed and secured. Saved to: ${outputFile.absolutePath}", Toast.LENGTH_LONG).show()
                    displayPdf(Uri.fromFile(outputFile))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error signing PDF: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error signing PDF: ${e.message}", Toast.LENGTH_LONG).show()
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

    private suspend fun awaitAddVisualAndDigitalSignatureAndEncrypt(
        pdfSigner: PdfSigner,
        inputStream: InputStream,
        outputStream: OutputStream,
        visualSignatureBitmap: Bitmap,
        position: PDRectangle,
        pageNumber: Int,
        privateKey: PrivateKey,
        certificateChain: Array<Certificate>,
        reason: String,
        location: String,
        userPassword: String?,
        ownerPassword: String?,
        allowPrinting: Boolean,
        allowCopy: Boolean,
        allowModifyContents: Boolean
    ): String = withContext(Dispatchers.IO) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pdfSigner.addVisualAndDigitalSignatureAndEncrypt(
                inputStream,
                outputStream,
                visualSignatureBitmap,
                position,
                pageNumber,
                privateKey,
                certificateChain,
                reason,
                location,
                userPassword,
                ownerPassword,
                allowPrinting,
                allowCopy,
                allowModifyContents,
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
        capturedSignatureBitmap = null
    }
}
