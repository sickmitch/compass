package org.compass.cng.domain.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConnectionTest {
    @Test
    fun normalizesHttpsEndpointAndRequiresCredentialsForUserConfiguration() {
        val connection = ServerConnection.create(
            baseUrl = " https://compass.example.it/api ",
            username = " mobile ",
            password = "secret",
            requireCredentials = true,
        )

        assertEquals("https://compass.example.it/api/", connection.baseUrl)
        assertEquals("mobile", connection.username)
        assertTrue(connection.usesHttps)
        assertTrue(connection.hasCredentials)
    }

    @Test
    fun rejectsHttpUnlessTheOperatorExplicitlyAcceptsIt() {
        val error = runCatching {
            ServerConnection.create(
                baseUrl = "http://192.0.2.1:8000/",
                username = "mobile",
                password = "secret",
                requireCredentials = true,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        val fallback = ServerConnection.create(
            baseUrl = "http://192.0.2.1:8000/",
            username = "mobile",
            password = "secret",
            allowInsecureHttp = true,
            requireCredentials = true,
        )
        assertFalse(fallback.usesHttps)
    }

    @Test
    fun rejectsCredentialsEmbeddedInUrlAndPartialCredentials() {
        assertTrue(
            runCatching {
                ServerConnection.create(
                    baseUrl = "https://mobile:secret@compass.example.it/",
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ServerConnection.create(
                    baseUrl = "https://compass.example.it/",
                    username = "mobile",
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ServerConnection.create(
                    baseUrl = "https://compass.example.it/",
                    username = "mobile:user",
                    password = "secret",
                )
            }.isFailure,
        )
    }
}
