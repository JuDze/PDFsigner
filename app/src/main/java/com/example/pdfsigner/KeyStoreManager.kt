package com.example.pdfsigner

import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.Build
import java.io.IOException
import java.math.BigInteger
import java.security.InvalidAlgorithmParameterException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.PrivateKey
import java.security.UnrecoverableEntryException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.util.Calendar
import javax.security.auth.x500.X500Principal
import java.security.Provider
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

class KeyStoreManager(private val context: Context) {

    private val ANDROID_KEY_STORE = "AndroidKeyStore"
    private val KEY_ALIAS = "RVPP dokuments" // Unique alias for your key

    private val keyStore: KeyStore

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
    }

    fun containsKey(alias: String = KEY_ALIAS): Boolean {
        return keyStore.containsAlias(alias)
    }

    fun deleteKeyIfExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun generateKeyPairAndCertificate() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return
        }

        val start = Calendar.getInstance()
        val end = Calendar.getInstance()
        end.add(Calendar.YEAR, 20)

        val kpg: KeyPairGenerator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                ANDROID_KEY_STORE
            ).apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal("CN=RVPP APP, OU=SelfSigned, O=RVPP dokuments"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(start.time)
                        .setCertificateNotAfter(end.time)
                        .build()
                )
            }
        } else {
            @Suppress("DEPRECATION")
            KeyPairGenerator.getInstance(
                "RSA",
                ANDROID_KEY_STORE
            ).apply {
                initialize(
                    KeyPairGeneratorSpec.Builder(context)
                        .setAlias(KEY_ALIAS)
                        .setSubject(X500Principal("CN=RVPP APP, OU=SelfSigned, O=RVPP dokuments"))
                        .setSerialNumber(BigInteger.ONE)
                        .setStartDate(start.time)
                        .setEndDate(end.time)
                        .build()
                )
            }
        }
        kpg.generateKeyPair()
    }

    fun getPrivateKey(): PrivateKey? {
        val entry = keyStore.getEntry(KEY_ALIAS, null)
        return if (entry is KeyStore.PrivateKeyEntry) {
            entry.privateKey
        } else {
            null
        }
    }

    fun getCertificate(): Certificate? {
        val entry = keyStore.getEntry(KEY_ALIAS, null)
        return if (entry is KeyStore.PrivateKeyEntry) {
            entry.certificate
        } else {
            null
        }
    }

    fun getCertificateChain(): Array<Certificate>? {
        val entry = keyStore.getEntry(KEY_ALIAS, null)
        return if (entry is KeyStore.PrivateKeyEntry) {
            entry.certificateChain?.takeIf { it.isNotEmpty() }
                ?: arrayOf(entry.certificate)
        } else {
            null
        }
    }


    fun getKeyAlias(): String = KEY_ALIAS
}
