package os.aether.discipline.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeystoreCrypto {
    private const val ALIAS = "aether.aes.v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + encrypted.size)
        iv.copyInto(packed)
        encrypted.copyInto(packed, iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        if (blob.isBlank()) return ""
        return try {
            val packed = Base64.decode(blob, Base64.NO_WRAP)
            if (packed.size <= IV_SIZE) return ""
            val iv = packed.copyOfRange(0, IV_SIZE)
            val body = packed.copyOfRange(IV_SIZE, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val base = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        try {
            generator.init(base.setIsStrongBoxBacked(true).build())
            return generator.generateKey()
        } catch (_: Exception) {
            generator.init(base.setIsStrongBoxBacked(false).build())
            return generator.generateKey()
        }
    }
}
