package org.compass.cng.domain.server

import java.net.URI

class ServerConnection private constructor(
    val baseUrl: String,
    val username: String,
    val password: String,
    val allowInsecureHttp: Boolean,
) {
    val usesHttps: Boolean get() = baseUrl.startsWith("https://", ignoreCase = true)
    val hasCredentials: Boolean get() = username.isNotEmpty() && password.isNotEmpty()

    companion object {
        fun create(
            baseUrl: String,
            username: String = "",
            password: String = "",
            allowInsecureHttp: Boolean = false,
            requireCredentials: Boolean = false,
        ): ServerConnection {
            val trimmedUrl = baseUrl.trim()
            val parsed = runCatching { URI(trimmedUrl) }.getOrNull()
                ?: throw IllegalArgumentException("L'indirizzo del server non è valido.")
            val scheme = parsed.scheme?.lowercase()
            require(scheme == "https" || scheme == "http") {
                "L'indirizzo deve iniziare con https:// oppure http://."
            }
            require(!parsed.host.isNullOrBlank()) {
                "L'indirizzo deve includere il nome host del server."
            }
            require(parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
                "Non inserire credenziali, parametri o frammenti nell'indirizzo."
            }
            require(scheme != "http" || allowInsecureHttp) {
                "Per usare HTTP devi accettare esplicitamente la connessione non cifrata."
            }
            val cleanUsername = username.trim()
            require(!requireCredentials || cleanUsername.isNotEmpty()) {
                "Inserisci il nome utente."
            }
            require(!requireCredentials || password.isNotEmpty()) {
                "Inserisci la password."
            }
            require(':' !in cleanUsername) {
                "Il nome utente non può contenere due punti."
            }
            require(cleanUsername.all { it.code in 33..126 } &&
                password.all { it.code in 32..126 }) {
                "Nome utente e password devono usare caratteri ASCII stampabili."
            }
            require((cleanUsername.isEmpty() && password.isEmpty()) ||
                (cleanUsername.isNotEmpty() && password.isNotEmpty())) {
                "Nome utente e password devono essere configurati insieme."
            }
            val normalizedUrl = if (trimmedUrl.endsWith('/')) trimmedUrl else "$trimmedUrl/"
            return ServerConnection(
                baseUrl = normalizedUrl,
                username = cleanUsername,
                password = password,
                allowInsecureHttp = allowInsecureHttp,
            )
        }
    }
}

interface ServerConnectionRepository {
    fun load(): ServerConnection

    fun save(connection: ServerConnection): ServerConnection
}

class InMemoryServerConnectionRepository(
    initial: ServerConnection = ServerConnection.create(
        baseUrl = "http://10.0.2.2:8000/",
        allowInsecureHttp = true,
    ),
) : ServerConnectionRepository {
    private var value = initial

    override fun load(): ServerConnection = value

    override fun save(connection: ServerConnection): ServerConnection {
        value = connection
        return value
    }
}
