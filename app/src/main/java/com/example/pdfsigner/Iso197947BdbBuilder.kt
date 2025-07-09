// app/src/main/java/com/example/pdfsigner/Iso197947BdbBuilder.kt
package com.example.pdfsigner

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet

object Iso197947BdbBuilder {
    private val FORMAT_OWNER_OID = ASN1ObjectIdentifier("1.3.36.3.2.1")
    private const val FORMAT_TYPE_VERSION = 2L

    /**
     * Builds a DER-encoded ISO/IEC 19794-7 Biometric Data Block.
     *
     * This will accept either:
     *  • A full object: { "resolution":{…}, "points":[…] }
     *  • Or just a flat array:  [ {…}, {…}, … ]
     *
     * If you pass only an array, it automatically assumes 600×600 DPI.
     */
    fun buildDer(
        bioJson: String,
        defaultHorz: Long = 600,
        defaultVert: Long = 600
    ): ByteArray {
        // 1) Normalize: if the JSON starts with '[', wrap it into an object
        val normalized = bioJson.trimStart().let {
            if (it.startsWith("[")) {
                """{"resolution":{"horz":$defaultHorz,"vert":$defaultVert},"points":$it}"""
            } else it
        }

        // 2) Parse once
        val parsed = JsonParser.parseString(normalized)
        val obj    = parsed.asJsonObject // now always an object

        // 3) Extract resolution & points
        val res   = obj.getAsJsonObject("resolution")
        val horz  = res.get("horz").asLong
        val vert  = res.get("vert").asLong
        val pointsArray = obj.getAsJsonArray("points")

        // 4) Build the ASN.1 BDB
        val header = DERSequence(arrayOf<ASN1Encodable>(
            FORMAT_OWNER_OID,
            ASN1Integer(FORMAT_TYPE_VERSION)
        ))
        val dpiSeq = DERSequence(arrayOf(
            ASN1Integer(horz),
            ASN1Integer(vert)
        ))
        val count = ASN1Integer(pointsArray.size().toLong())
        val samples = pointsArray.map { el ->
            val o = el.asJsonObject
            DERSequence(arrayOf(
                ASN1Integer(o.get("x").asLong),
                ASN1Integer(o.get("y").asLong),
                ASN1Integer(o.get("t").asLong),
                ASN1Integer(o.get("p").asLong)
            ))
        }.toTypedArray()
        val sampleSet = DERSet(samples)

        val bdbSeq = DERSequence(arrayOf<ASN1Encodable>(
            header, dpiSeq, count, sampleSet
        ))
        return bdbSeq.encoded
    }
}
