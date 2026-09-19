package com.campusmeal.android.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists CampusMeal session tokens encrypted at rest.
 *
 * The encryption key lives inside Android Keystore and cannot be exported.
 * SharedPreferences only stores AES-GCM ciphertext and its IV.
 */
class KeystoreSessionStorage(
    context: Context,
) : SessionStorage {

    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    private val mutex = Mutex()

    private val state =
        MutableStateFlow(
            readStoredSession(),
        )

    override val session: StateFlow<SessionTokens?> =
        state.asStateFlow()

    override suspend fun save(
        tokens: SessionTokens,
    ) {
        mutex.withLock {
            val access =
                encrypt(tokens.accessToken)

            val refresh =
                encrypt(tokens.refreshToken)

            preferences
                .edit()
                .putString(
                    ACCESS_TOKEN_CIPHERTEXT,
                    access.ciphertext,
                )
                .putString(
                    ACCESS_TOKEN_IV,
                    access.iv,
                )
                .putString(
                    REFRESH_TOKEN_CIPHERTEXT,
                    refresh.ciphertext,
                )
                .putString(
                    REFRESH_TOKEN_IV,
                    refresh.iv,
                )
                .apply()

            state.value = tokens
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            preferences
                .edit()
                .clear()
                .apply()

            state.value = null
        }
    }

    private fun readStoredSession(): SessionTokens? {
        val accessCiphertext =
            preferences.getString(
                ACCESS_TOKEN_CIPHERTEXT,
                null,
            ) ?: return null

        val accessIv =
            preferences.getString(
                ACCESS_TOKEN_IV,
                null,
            ) ?: return null

        val refreshCiphertext =
            preferences.getString(
                REFRESH_TOKEN_CIPHERTEXT,
                null,
            ) ?: return null

        val refreshIv =
            preferences.getString(
                REFRESH_TOKEN_IV,
                null,
            ) ?: return null

        return try {
            SessionTokens(
                accessToken =
                    decrypt(
                        ciphertext =
                            accessCiphertext,
                        iv = accessIv,
                    ),
                refreshToken =
                    decrypt(
                        ciphertext =
                            refreshCiphertext,
                        iv = refreshIv,
                    ),
            )
        } catch (_: Exception) {
            /*
             * A corrupted or no-longer-decryptable session must never
             * become an authenticated session.
             */
            preferences
                .edit()
                .clear()
                .apply()

            null
        }
    }

    private fun encrypt(
        value: String,
    ): EncryptedValue {
        val cipher =
            Cipher.getInstance(
                TRANSFORMATION,
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateKey(),
        )

        val ciphertext =
            cipher.doFinal(
                value.toByteArray(
                    Charsets.UTF_8,
                ),
            )

        return EncryptedValue(
            ciphertext =
                Base64.encodeToString(
                    ciphertext,
                    Base64.NO_WRAP,
                ),
            iv =
                Base64.encodeToString(
                    cipher.iv,
                    Base64.NO_WRAP,
                ),
        )
    }

    private fun decrypt(
        ciphertext: String,
        iv: String,
    ): String {
        val cipher =
            Cipher.getInstance(
                TRANSFORMATION,
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(
                128,
                Base64.decode(
                    iv,
                    Base64.NO_WRAP,
                ),
            ),
        )

        val plaintext =
            cipher.doFinal(
                Base64.decode(
                    ciphertext,
                    Base64.NO_WRAP,
                ),
            )

        return plaintext.toString(
            Charsets.UTF_8,
        )
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore =
            KeyStore.getInstance(
                ANDROID_KEYSTORE,
            ).apply {
                load(null)
            }

        val existingKey =
            keyStore.getKey(
                KEY_ALIAS,
                null,
            ) as? SecretKey

        if (existingKey != null) {
            return existingKey
        }

        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )

        val spec =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM,
                )
                .setEncryptionPaddings(
                    KeyProperties
                        .ENCRYPTION_PADDING_NONE,
                )
                .setRandomizedEncryptionRequired(
                    true,
                )
                .build()

        generator.init(spec)

        return generator.generateKey()
    }

    private data class EncryptedValue(
        val ciphertext: String,
        val iv: String,
    )

    private companion object {
        const val ANDROID_KEYSTORE =
            "AndroidKeyStore"

        const val KEY_ALIAS =
            "campusmeal_session_key"

        const val TRANSFORMATION =
            "AES/GCM/NoPadding"

        const val PREFERENCES_NAME =
            "campusmeal_secure_session"

        const val ACCESS_TOKEN_CIPHERTEXT =
            "access_token_ciphertext"

        const val ACCESS_TOKEN_IV =
            "access_token_iv"

        const val REFRESH_TOKEN_CIPHERTEXT =
            "refresh_token_ciphertext"

        const val REFRESH_TOKEN_IV =
            "refresh_token_iv"
    }
}