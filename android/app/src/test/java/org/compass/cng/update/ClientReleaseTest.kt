package org.compass.cng.update

import org.junit.Assert.*
import org.junit.Test

class ClientReleaseTest {
    @Test fun manifestAcceptsNewAndLegacyNamesWithTrailingNewline() {
        val release = requireNotNull(ClientRelease.parse("compass-0.28.41.apk\n"))
        assertEquals(ClientVersion(0, 28, 41), release.version)
        assertEquals(
            "https://github.com/sickmitch/compass/raw/refs/heads/main/dist/compass-0.28.41.apk",
            release.downloadUrl,
        )
        assertNotNull(ClientRelease.parse("compass-0.28.40-debug.apk"))
    }

    @Test fun comparesNumericallyAndIgnoresInstalledDebugSuffix() {
        assertTrue(ClientVersion.parse("0.28.100")!! > ClientVersion.parse("0.28.99-debug")!!)
        assertTrue(ClientVersion.parse("1.0.0")!! > ClientVersion.parse("0.99.99")!!)
        assertEquals(ClientVersion.parse("0.28.41"), ClientVersion.parse("0.28.41-debug"))
        assertFalse(ClientVersion.parse("0.28.40")!! > ClientVersion.parse("0.28.41")!!)
    }

    @Test fun rejectsPathsUrlsMultipleLinesAndInvalidVersions() {
        listOf("../compass-0.28.42.apk", "https://evil.test/a.apk", "compass-1.2.3.apk?x=1",
            "compass-1.2.3.apk\ncompass-1.2.4.apk", "compass-1.2.apk", "", "<html>404</html>",
            "compass-99999999999999999999.2.3.apk", "compass-1.2.3-release.apk",
        ).forEach { assertNull(it, ClientRelease.parse(it)) }
    }
}
