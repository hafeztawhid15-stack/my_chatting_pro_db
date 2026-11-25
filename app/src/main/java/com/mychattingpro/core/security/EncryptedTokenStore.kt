package com.mychattingpro.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.tokenDataStore by preferencesDataStore(name = "mcp_secure_session")

data class StoredSession(
    val userId: String,
    val phoneE164: String,
    val displayName: String,
    val about: String,
    val avatarMediaId: String?,
    val accessToken: String,
    val refreshToken: String
)

/**
 * Stores access/refresh tokens and active session identity encrypted via
 * AndroidKeyStore AES-256-GCM inside Preferences DataStore.
 */
open class EncryptedTokenStore(private val context: Context) {

    private val keyAlias = "my_chatting_pro_session_aes_key"
    private val keyCipherPayload = stringPreferencesKey("encrypted_session_blob")
    private val keyOwnerMonitorCipher = stringPreferencesKey("encrypted_owner_key_blob")

    @Volatile
    private var cachedSession: StoredSession? = null

    @Volatile
    private var hasLoadedSession = false

    fun currentSessionOrNull(): StoredSession? = if (hasLoadedSession) cachedSession else null

    open val sessionFlow: Flow<StoredSession?> = context.tokenDataStore.data.map { prefs ->
        val encoded = prefs[keyCipherPayload]
        val parsed = encoded?.let { decryptSession(it) }
        cachedSession = parsed
        hasLoadedSession = true
        parsed
    }

    open suspend fun getSession(): StoredSession? {
        if (hasLoadedSession) return cachedSession
        return sessionFlow.first()
    }

    open suspend fun saveSession(session: StoredSession) {
        val raw = listOf(
            session.userId,
            session.phoneE164,
            encodeField(session.displayName),
            encodeField(session.about),
            session.avatarMediaId ?: "",
            session.accessToken,
            session.refreshToken
        ).joinToString("|")

        val encrypted = encryptString(raw)
        cachedSession = session
        hasLoadedSession = true
        context.tokenDataStore.edit { prefs ->
            prefs[keyCipherPayload] = encrypted
        }
    }

    suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val current = getSession() ?: return
        saveSession(current.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    open suspend fun updateUserProfile(displayName: String, about: String, avatarMediaId: String?) {
        val current = getSession() ?: return
        saveSession(
            current.copy(
                displayName = displayName,
                about = about,
                avatarMediaId = avatarMediaId
            )
        )
    }

    open suspend fun saveOwnerKey(ownerKey: String?) {
        val trimmed = ownerKey?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            context.tokenDataStore.edit { it.remove(keyOwnerMonitorCipher) }
        } else {
            val encrypted = encryptString(trimmed)
            context.tokenDataStore.edit { it[keyOwnerMonitorCipher] = encrypted }
        }
    }

    open suspend fun getOwnerKey(): String? {
        val prefs = context.tokenDataStore.data.first()
        val encoded = prefs[keyOwnerMonitorCipher] ?: return null
        return decryptPlainString(encoded)
    }

    open suspend fun clearSession() {
        cachedSession = null
        hasLoadedSession = true
        context.tokenDataStore.edit { it.clear() }
    }

    private fun getOrCreateKey(): SecretKey {
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getKey(keyAlias, null) as? SecretKey
            if (existing != null) return existing
            generateKeyStoreAesKey()
        }.getOrElse { firstErr ->
            SafeLogger.w("EncryptedTokenStore", "KeyStore load failed, resetting alias: ${firstErr.javaClass.simpleName}")
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(keyAlias)) {
                    keyStore.deleteEntry(keyAlias)
                }
            }
            generateKeyStoreAesKey()
        }
    }

    private fun generateKeyStoreAesKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encryptString(plain: String): String {
        return try {
            performAesGcmEncrypt(plain, getOrCreateKey())
        } catch (e: Exception) {
            SafeLogger.w("EncryptedTokenStore", "Encrypt retry after resetting key: ${e.javaClass.simpleName}")
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(keyAlias)) {
                    keyStore.deleteEntry(keyAlias)
                }
            }
            performAesGcmEncrypt(plain, generateKeyStoreAesKey())
        }
    }

    private fun performAesGcmEncrypt(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "$ivB64:$dataB64"
    }

    private fun decryptPlainString(encoded: String): String? {
        return try {
            val parts = encoded.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            SafeLogger.e("EncryptedTokenStore", "Failed to decrypt value", e.javaClass.simpleName)
            null
        }
    }

    private fun decryptSession(encoded: String): StoredSession? {
        return try {
            val plain = decryptPlainString(encoded) ?: return null
            val fields = plain.split("|")
            if (fields.size < 7) return null
            StoredSession(
                userId = fields[0],
                phoneE164 = fields[1],
                displayName = decodeField(fields[2]),
                about = decodeField(fields[3]),
                avatarMediaId = fields[4].ifEmpty { null },
                accessToken = fields[5],
                refreshToken = fields[6]
            )
        } catch (e: Exception) {
            SafeLogger.e("EncryptedTokenStore", "Failed to decrypt session", e.javaClass.simpleName)
            null
        }
    }

    private fun encodeField(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodeField(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}
