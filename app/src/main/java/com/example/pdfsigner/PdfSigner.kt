package com.example.pdfsigner

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.*
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.util.*
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder

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

    fun addVisualAndDigitalSignature(
        pdfInputStream: InputStream,
        outputStream: OutputStream,
        visualSignatureBitmap: Bitmap,
        position: PDRectangle,
        pageNumber: Int,
        privateKey: PrivateKey,
        certificateChain: Array<Certificate>,
        reason: String,
        location: String,
        callback: PdfSignCallback
    ) {
        var tempFile: File? = null
        var document: PDDocument? = null

        try {
            // 1️⃣ Copy input to temp file
            tempFile = File.createTempFile("pdf_to_sign", ".pdf")
            FileOutputStream(tempFile).use { fos ->
                pdfInputStream.copyTo(fos)
            }

            // 2️⃣ Load document
            document = PDDocument.load(tempFile)

            // 3️⃣ Draw visual signature
            val page: PDPage = document.getPage(pageNumber - 1)
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                val baos = ByteArrayOutputStream()
                visualSignatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val pdImage = PDImageXObject.createFromByteArray(document, baos.toByteArray(), "sig_img")
                cs.drawImage(
                    pdImage,
                    position.lowerLeftX,
                    position.lowerLeftY,
                    position.width,
                    position.height
                )
            }

            // 4️⃣ Create signature dictionary
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                name = "RVPP APP"
                this.reason = reason
                this.location = location
                signDate = Calendar.getInstance()
            }

            // 5️⃣ Signature interface
            val signatureInterface = object : SignatureInterface {
                override fun sign(content: InputStream): ByteArray {
                    val data = content.readBytes()
                    val generator = CMSSignedDataGenerator()

                    val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
                        .build(privateKey)

                    val cert = certificateChain[0] as? java.security.cert.X509Certificate
                        ?: throw IllegalStateException("Certificate is not an X509Certificate")

                    val signerInfo = JcaSignerInfoGeneratorBuilder(
                        JcaDigestCalculatorProviderBuilder().build()
                    ).build(contentSigner, cert)

                    generator.addSignerInfoGenerator(signerInfo)

                    val certStore = JcaCertStore(certificateChain.map { it as java.security.cert.X509Certificate })
                    generator.addCertificates(certStore)

                    val cmsData = CMSProcessableByteArray(data)
                    val signedData = generator.generate(cmsData, false)

                    return signedData.encoded
                }
            }

            // 6️⃣ Add signature
            document.addSignature(signature, signatureInterface)

            // 7️⃣ Save incremental back to temp file
            FileOutputStream(tempFile, true).use { fos ->
                document.saveIncremental(fos)
            }

            // 8️⃣ Copy fully signed file to final OutputStream
            FileInputStream(tempFile).use { fis ->
                fis.copyTo(outputStream)
            }

            callback.onSuccess("Signed PDF successfully written.")
        } catch (e: Exception) {
            callback.onError(e)
        } finally {
            try {
                document?.close()
            } catch (e: IOException) {
                // ignore
            }
            tempFile?.delete()
        }
    }
}
