package org.compass.cng.offline

import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.valhalla.Valhalla
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Test

/** Real shipped JNI actor: no GPS, network, downloaded graph or production data needed. */
class OfflineNativeInitializationTest {
    @Test fun packagedConfigInitializesNativeActor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-config-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val template = context.assets.open("offline-valhalla.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject
            }
            val graph = template.getValue("mjolnir").jsonObject.toMutableMap().apply {
                put("tile_dir", JsonPrimitive(directory.absolutePath))
                put("tile_extract", JsonPrimitive(File(directory, "no-graph.tar").absolutePath))
            }
            val config = File(directory, "config.json")
            config.writeText(JsonObject(template.toMutableMap().apply {
                put("mjolnir", JsonObject(graph))
            }).toString())
            Valhalla(config.absolutePath).use { /* Constructor must not throw missing-config errors. */ }
        } finally {
            directory.deleteRecursively()
        }
    }
}
