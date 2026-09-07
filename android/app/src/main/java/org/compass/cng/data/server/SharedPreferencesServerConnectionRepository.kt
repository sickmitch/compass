package org.compass.cng.data.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.compass.cng.domain.server.ServerConnection
import org.compass.cng.domain.server.ServerConnectionRepository

class SharedPreferencesServerConnectionRepository internal constructor(
    context: Context,
    private val defaultConnection: ServerConnection,
    private val credentialCipher: CredentialCipher = AndroidKeystoreCredentialCipher(),
) : ServerConnectionRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): ServerConnection {
        val baseUrl = preferences.getString(BASE_URL_KEY, null) ?: return defaultConnection
        return runCatching {
            ServerConnection.create(
                baseUrl = baseUrl,
                username = preferences.getString(USERNAME_KEY, "").orEmpty(),
                password = credentialCipher.decrypt(
                    preferences.getString(ENCRYPTED_PASSWORD_KEY, "").orEmpty(),
                ),
                allowInsecureHttp = preferences.getBoolean(ALLOW_HTTP_KEY, false),
            )
        }.getOrElse { defaultConnection }
    }

    override fun save(connection: ServerConnection): ServerConnection {
        val encryptedPassword = credentialCipher.encrypt(connection.password)
        check(
            preferences.edit()
                .putString(BASE_URL_KEY, connection.baseUrl)
                .putString(USERNAME_KEY, connection.username)
                .putString(ENCRYPTED_PASSWORD_KEY, encryptedPassword)
                .putBoolean(ALLOW_HTTP_KEY, connection.allowInsecureHttp)
                .commit(),
        ) { "server connection could not be persisted" }
        return connection
    }

    private companion object {
        const val PREFERENCES_NAME = "compass_server_connection"
        const val BASE_URL_KEY = "base_url_v1"
        const val USERNAME_KEY = "username_v1"
        const val ENCRYPTED_PASSWORD_KEY = "password_v1"
        const val ALLOW_HTTP_KEY = "allow_http_v1"
    }
}

internal interface CredentialCipher {
    fun encrypt(value: String): String

    fun decrypt(value: String): String
}

internal class AndroidKeystoreCredentialCipher : CredentialCipher {
    override fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${encode(cipher.iv)}:${encode(ciphertext)}"
    }

    override fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "invalid encrypted credential" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            loadOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, decode(parts[0])),
        )
        return cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "compass_api_password_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
