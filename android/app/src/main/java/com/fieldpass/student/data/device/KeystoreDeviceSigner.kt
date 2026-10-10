package com.fieldpass.student.data.device

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Key pair held in the Android Keystore (non-exportable; works offline and in a sideloaded APK).
 * It is created on first use and lost when the app's data is cleared or the app is reinstalled:
 * the server then answers device_mismatch and faculty reset the phone on the dashboard.
 */
class KeystoreDeviceSigner : DeviceSigner {
    override fun publicKeyBase64(): String =
        Base64.getEncoder().encodeToString(entry().certificate.publicKey.encoded)

    override fun sign(message: ByteArray): String {
        val signature = Signature.getInstance(ALGORITHM).apply {
            initSign(entry().privateKey)
            update(message)
        }
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    @Synchronized
    private fun entry(): KeyStore.PrivateKeyEntry = load() ?: run {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        checkNotNull(load()) { "device key missing right after it was created" }
    }

    private fun load(): KeyStore.PrivateKeyEntry? =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fieldpass_device_key" // not the token key's alias (KeystoreTokenStore)
        const val CURVE = "secp256r1"
        const val ALGORITHM = "SHA256withECDSA"
    }
}
