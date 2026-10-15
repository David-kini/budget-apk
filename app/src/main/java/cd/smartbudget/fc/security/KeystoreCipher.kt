package cd.smartbudget.fc.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement AES-256-GCM avec une clé générée et conservée dans Android Keystore.
 * La clé ne quitte jamais le Keystore (matériel sécurisé quand le téléphone en dispose).
 *
 * Format produit : [1 octet longueur IV][IV][texte chiffré + tag GCM].
 */
class KeystoreCipher(private val alias: String) {

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + body
    }

    fun decrypt(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "Données chiffrées vides" }
        val ivLength = data[0].toInt()
        require(ivLength in 12..16 && data.size > 1 + ivLength) { "Données chiffrées invalides" }
        val iv = data.copyOfRange(1, 1 + ivLength)
        val body = data.copyOfRange(1 + ivLength, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
