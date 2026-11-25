package org.compass.cng.offline

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.compass.cng.domain.server.ServerConnection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineAssetClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun item(data: ByteArray) = RemoteOfflinePackage("italia", "Italia", "italia_22112026.zip", data.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
    private fun serverConnection(server: MockWebServer) = ServerConnection.create(server.url("/compass/").toString(),
        username = "user", password = "secret", allowInsecureHttp = true)

    @Test fun resumesPartialFileAndAuthenticatesWithoutLosingProxyPrefix() = runBlocking {
        MockWebServer().use { server ->
            val data = "verified package bytes".toByteArray()
            val item = item(data)
            val folder = temporary.newFolder()
            File(folder, item.sha256 + ".zip.part").writeBytes(data.take(5).toByteArray())
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-${data.size - 1}/${data.size}")
                .setBody(Buffer().write(data.copyOfRange(5, data.size))))
            val result = OfflineAssetClient(OkHttpClient()).download(serverConnection(server), item, folder)
            assertArrayEquals(data, result.readBytes())
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("bytes=5-", request.getHeader("Range"))
            assertEquals("\"${item.sha256}\"", request.getHeader("If-Range"))
            assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
            assertEquals("/compass/api/v1/offline-routing/packages/italia_22112026.zip", request.path)
        }
    }

    @Test fun corruptDownloadIsDiscardedAndRedirectsNeverForwardCredentials() = runBlocking {
        MockWebServer().use { server ->
            val item = item("good".toByteArray())
            val folder = temporary.newFolder()
            val client = OfflineAssetClient(OkHttpClient())
            server.enqueue(MockResponse().setBody("evil"))
            try { client.download(serverConnection(server), item, folder); fail() }
            catch (_: IllegalStateException) { }
            assertFalse(File(folder, item.sha256 + ".zip.part").exists())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.invalid/file"))
            try { client.download(serverConnection(server), item, folder); fail() }
            catch (_: java.io.IOException) { }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun policyCanPreventTheRequestBeforeAnyPackageBytesAreFetched() = runBlocking {
        MockWebServer().use { server ->
            try {
                OfflineAssetClient(OkHttpClient()).download(serverConnection(server), item("data".toByteArray()),
                    temporary.newFolder(), checkTransfer = { error("mobile approval required") })
                fail()
            } catch (_: IllegalStateException) { }
            assertEquals(0, server.requestCount)
        }
    }
}
