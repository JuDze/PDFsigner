package com.example.pdfsigner

import android.graphics.Bitmap
//import org.apache.pdfbox.Loader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature.FILTER_ADOBE_PPKLITE
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.*
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.security.cert.Certificate
import java.util.*

class PdfSigner {

    interface PdfSignCallback {
        fun onSuccess(outputPath: String)
        fun onError(e: Exception)
    }

    companion object {
        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    fun addVisualAndDigitalSignatureAndEncrypt(
        pdfInputStream: InputStream,
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
        allowModifyContents: Boolean,
        callback: PdfSignCallback
    ) {

        var document: PDDocument? = null
        try {
            document = PDDocument.load(pdfInputStream)

            // --- Apply Encryption ---
            val permissions = AccessPermission().apply {
                setCanPrint(allowPrinting)
                setCanExtractContent(allowCopy)
                setCanModify(allowModifyContents)
            }

            val policy = StandardProtectionPolicy(
                ownerPassword.orEmpty(),
                userPassword.orEmpty(),
                permissions
            ).apply {
                encryptionKeyLength = 128
                this.permissions = permissions
            }

            document?.protect(policy)

            // --- Create digital signature ---
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                name = "RVPP APP"
                this.location = location
                this.reason = reason

                signDate = Calendar.getInstance()
            }

            val signatureOptions = SignatureOptions()
            signatureOptions.page = pageNumber - 1

            // Sign interface
            val signatureInterface = object : SignatureInterface {
                override fun sign(content: InputStream): ByteArray {
                    val data = content.readBytes()
                    val signer = Signature.getInstance("SHA256withRSA", BouncyCastleProvider.PROVIDER_NAME)
                    signer.initSign(privateKey)
                    signer.update(data)
                    return signer.sign()
                }
            }

            document.addSignature(signature, signatureInterface, signatureOptions)

            // --- Add visual image manually to page ---
            val page: PDPage = document.getPage(pageNumber - 1)
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                val baos = ByteArrayOutputStream()
                visualSignatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val pdImage = PDImageXObject.createFromByteArray(document, baos.toByteArray(), "sig_img")
                cs.drawImage(pdImage, position.lowerLeftX, position.lowerLeftY, position.width, position.height)
            }

            document.save(outputStream)
            callback.onSuccess("Signed PDF successfully written.")
        } catch (e: Exception) {
            callback.onError(e)
        } finally {
            try {
                document?.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }
}
