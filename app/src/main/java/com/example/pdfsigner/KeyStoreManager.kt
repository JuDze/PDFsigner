package com.example.pdfsigner

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.security.auth.x500.X500Principal

class KeyStoreManager(private val context: Context) {
    private val ANDROID_KEY_STORE = "AndroidKeyStore"
    private val KEY_ALIAS = "RVPP_dokuments"
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    fun deleteKeyIfExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun generateKeyPairAndCertificate() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }

        val kpg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE
            )
        } else {
            TODO("VERSION.SDK_INT < M")
        }
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=RVPP APP, OU=SelfSigned, O=RVPP_dokuments"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(start.time)
            .setCertificateNotAfter(end.time)
            .setUserAuthenticationRequired(false)

        kpg.initialize(specBuilder.build())
        kpg.generateKeyPair()
    }

    fun getPrivateKey() = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.privateKey
    fun getCertificateChain() = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.certificateChain
}