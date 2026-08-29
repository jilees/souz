package ru.souz.db

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object AesGcmSecretCodec {
    private const val PAYLOAD_PREFIX = "enc:v1:"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private val secureRandom = SecureRandom()

    // PBKDF2 key derivation (PBKDF2_ITERATIONS) costs over a second per call by design. Each
    // encrypted payload carries its own random salt, so a given payload always decrypts to the
    // same plaintext; memoize by the exact ciphertext string instead of paying that cost on every
    // read (e.g. CodexOAuthService.refreshTokenIfNeeded on every LLM call). A write produces a
    // fresh salt/payload, so stale cache entries just go unused — no invalidation needed.
    private val decryptedValueCache = ConcurrentHashMap<String, String>()

    fun encrypt(
        masterKey: String,
        plainText: String,
    ): String {
        require(masterKey.isNotBlank()) { "masterKey must not be blank." }
        val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_SIZE).also(secureRandom::nextBytes)
        val secretKey = deriveKey(masterKey, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return buildString {
            append(PAYLOAD_PREFIX)
            append(b64(salt))
            append(':')
            append(b64(iv))
            append(':')
            append(b64(cipherText))
        }
    }

    fun decrypt(
        masterKey: String,
        payload: String,
    ): String {
        require(masterKey.isNotBlank()) { "masterKey must not be blank." }
        return decryptedValueCache.computeIfAbsent(payload) { decryptUncached(masterKey, payload) }
    }

    private fun decryptUncached(masterKey: String, payload: String): String {
        val parts = payload.removePrefix(PAYLOAD_PREFIX).split(':')
        require(parts.size == 3) { "Malformed encrypted payload" }
        val salt = b64Decode(parts[0])
        val iv = b64Decode(parts[1])
        val cipherText = b64Decode(parts[2])
        val secretKey = deriveKey(masterKey, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PAYLOAD_PREFIX)

    private fun deriveKey(masterKey: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(masterKey.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun b64Decode(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)
}
