package org.compass.cng.update

data class ClientVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ClientVersion> {
    override fun compareTo(other: ClientVersion): Int =
        compareValuesBy(this, other, ClientVersion::major, ClientVersion::minor, ClientVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(value: String): ClientVersion? {
            val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-debug)?$").matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return ClientVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class ClientRelease(val fileName: String, val version: ClientVersion) {
    val downloadUrl: String
        get() = "https://github.com/sickmitch/compass/raw/refs/heads/main/dist/$fileName"

    companion object {
        const val CHECK_URL = "https://raw.githubusercontent.com/sickmitch/compass/refs/heads/main/dist/last.txt"

        fun parse(text: String): ClientRelease? {
            val name = text.trim()
            val match = Regex("^compass-(\\d+\\.\\d+\\.\\d+)(?:-debug)?\\.apk$")
                .matchEntire(name) ?: return null
            return ClientRelease(name, ClientVersion.parse(match.groupValues[1]) ?: return null)
        }
    }
}
