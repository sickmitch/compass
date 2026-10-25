import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class PublishCompassApk : DefaultTask() {
    @get:InputDirectory abstract val apkDirectory: DirectoryProperty
    @get:OutputDirectory abstract val distributionDirectory: DirectoryProperty

    @TaskAction
    fun publish() {
        val sourceDirectory = apkDirectory.get().asFile
        val metadata = JsonSlurper().parse(sourceDirectory.resolve("output-metadata.json")) as Map<*, *>
        val element = (metadata["elements"] as List<*>).single() as Map<*, *>
        val source = sourceDirectory.resolve(element["outputFile"] as String)
        check(!source.name.contains("unsigned")) { "Unsigned APK cannot be published as an update" }
        val version = (element["versionName"] as String).removeSuffix("-debug")
        check(Regex("\\d+\\.\\d+\\.\\d+").matches(version))
        val dist = distributionDirectory.get().asFile
        dist.mkdirs()
        val name = "compass-$version.apk"
        val stagedApk = dist.resolve("$name.part")
        source.copyTo(stagedApk, overwrite = true)
        Files.move(stagedApk.toPath(), dist.resolve(name).toPath(),
            StandardCopyOption.REPLACE_EXISTING)
        val stagedManifest = dist.resolve("last.txt.part")
        stagedManifest.writeText("$name\n")
        Files.move(stagedManifest.toPath(), dist.resolve("last.txt").toPath(),
            StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("Published dist/$name and updated dist/last.txt")
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val publishApk = tasks.register<PublishCompassApk>("publish${capitalized}CompassApk") {
        apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
        distributionDirectory.set(rootProject.layout.projectDirectory.dir("../dist"))
        outputs.upToDateWhen { false }
    }
    tasks.matching { it.name == "assemble$capitalized" }.configureEach {
        finalizedBy(publishApk)
    }
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val compassApiBaseUrl = providers.gradleProperty("COMPASS_API_BASE_URL")
    .orElse("http://10.0.2.2:8000/")
    .get()
val legacyCompassMapStyleUrl = providers.gradleProperty("COMPASS_MAP_STYLE_URL")
val compassMapDayStyleUrl = providers.gradleProperty("COMPASS_MAP_DAY_STYLE_URL")
    .orElse(legacyCompassMapStyleUrl)
    .orElse("asset://compass-day.json")
    .get()
val compassMapNightStyleUrl = providers.gradleProperty("COMPASS_MAP_NIGHT_STYLE_URL")
    .orElse(legacyCompassMapStyleUrl)
    .orElse("asset://compass-night.json")
    .get()
val compassMapAmbientCacheMb = providers.gradleProperty("COMPASS_MAP_AMBIENT_CACHE_MB")
    .orElse("100")
    .get()
    .toLong()
val destinationSearchDebounceMs = providers.gradleProperty("DESTINATION_SEARCH_DEBOUNCE_MS")
    .orElse("600")
    .get()
    .toLong()
val destinationSearchMinChars = providers.gradleProperty("DESTINATION_SEARCH_MIN_CHARS")
    .orElse("3")
    .get()
    .toInt()

require(compassApiBaseUrl.endsWith("/")) {
    "COMPASS_API_BASE_URL must end with '/': $compassApiBaseUrl"
}
require(compassMapAmbientCacheMb in 16..1024) {
    "COMPASS_MAP_AMBIENT_CACHE_MB must be between 16 and 1024"
}
require(destinationSearchDebounceMs in 100..2_000) {
    "DESTINATION_SEARCH_DEBOUNCE_MS must be between 100 and 2000"
}
require(destinationSearchMinChars in 1..20) {
    "DESTINATION_SEARCH_MIN_CHARS must be between 1 and 20"
}

android {
    namespace = "org.compass.cng"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.compass.cng"
        minSdk = 26
        targetSdk = 37
        versionCode = 91
        versionName = "0.28.45"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "COMPASS_API_BASE_URL", compassApiBaseUrl.asBuildConfigString())
        buildConfigField(
            "String",
            "COMPASS_MAP_DAY_STYLE_URL",
            compassMapDayStyleUrl.asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "COMPASS_MAP_NIGHT_STYLE_URL",
            compassMapNightStyleUrl.asBuildConfigString(),
        )
        buildConfigField(
            "long",
            "COMPASS_MAP_AMBIENT_CACHE_BYTES",
            "${compassMapAmbientCacheMb * 1024L * 1024L}L",
        )
        buildConfigField("long", "DESTINATION_SEARCH_DEBOUNCE_MS", "${destinationSearchDebounceMs}L")
        buildConfigField("int", "DESTINATION_SEARCH_MIN_CHARS", "$destinationSearchMinChars")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
