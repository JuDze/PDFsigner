package com.example.pdfsigner

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Hashtable

class PdfSigner {
    init {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    fun embedVisualSignature(
        pdfInput: InputStream,
        pdfOutput: OutputStream,
        visualSignatureBitmap: Bitmap,
        pageNumber: Int,
        position: PDRectangle,
        biometricJson: String
    ) {
        PDDocument.load(pdfInput).use { doc ->
            val page = doc.getPage(pageNumber - 1)
            val baos = java.io.ByteArrayOutputStream().apply {
                visualSignatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
            }
            val pdImage = PDImageXObject.createFromByteArray(doc, baos.toByteArray(), "sig_image")
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                cs.drawImage(pdImage, position.lowerLeftX, position.lowerLeftY, position.width, position.height)
            }
            doc.documentInformation.setCustomMetadataValue("BiometricData", biometricJson)
            doc.save(pdfOutput)
        }
    }

    fun addDigitalSignature(
        inputFile: File,
        outputFile: File,
        privateKey: PrivateKey,
        certificateChain: Array<Certificate>,
        biometricJson: String,
        reason: String,
        location: String
    ) {
        fun makeBdb(json: String) = DERSequence(arrayOf(
            ASN1ObjectIdentifier("1.1.19785.0.257.0.14"),
            ASN1Integer(14),
            DEROctetString(json.toByteArray(Charsets.UTF_8))
        ))

        val certs = certificateChain.map { it as X509Certificate }
        val certStore = JcaCertStore(certs)
        val digestProv = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        val attr = Attribute(
            ASN1ObjectIdentifier("1.1.19785.0.257.0.14"),
            DERSet(makeBdb(biometricJson))
        )
        val attrTable = AttributeTable(Hashtable<Any, Any>().apply { put(attr.attrType, attr) })
        val signedAttrGen = DefaultSignedAttributeTableGenerator(attrTable)

        // Wrap AndroidKeyStore key in a BC ContentSigner
        class AndroidKeyStoreSigner(
            key: PrivateKey,
            algorithm: String = "SHA512withRSA"
        ) : ContentSigner {
            private val sig: Signature = Signature.getInstance(algorithm).apply { initSign(key) }
            private val sigAlgId = DefaultSignatureAlgorithmIdentifierFinder().find(algorithm)
            override fun getAlgorithmIdentifier() = sigAlgId
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(b: Int) = sig.update(b.toByte())
                override fun write(b: ByteArray, off: Int, len: Int) = sig.update(b, off, len)
            }
            override fun getSignature(): ByteArray = sig.sign()
        }

        val contentSigner: ContentSigner = AndroidKeyStoreSigner(privateKey, "SHA512withRSA")
        val signerInfo = JcaSignerInfoGeneratorBuilder(digestProv)
            .setSignedAttributeGenerator(signedAttrGen)
            .build(contentSigner, certs[0])

        val cmsGen = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(signerInfo)
            addCertificates(certStore)
        }

        PDDocument.load(inputFile).use { doc ->
            val pdSig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                this.reason = reason
                this.location = location
                signDate = Calendar.getInstance()
            }
            doc.addSignature(pdSig, SignatureInterface { stream ->
                val data = stream.readBytes()
                cmsGen.generate(object : CMSTypedData {
                    override fun getContentType() = CMSObjectIdentifiers.data
                    override fun getContent() = data
                    override fun write(out: OutputStream) = out.write(data)
                }, false).encoded
            })
            FileOutputStream(outputFile).use { doc.saveIncremental(it) }
        }
    }
}