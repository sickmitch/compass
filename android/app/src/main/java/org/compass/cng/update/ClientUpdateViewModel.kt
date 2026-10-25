package org.compass.cng.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.compass.cng.BuildConfig

data class ClientUpdateState(
    val release: ClientRelease? = null,
    val downloading: Boolean = false,
    val progress: Int? = null,
    val ready: Boolean = false,
    val error: String? = null,
)

class ClientUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ClientUpdateState())
    val state = mutableState.asStateFlow()
    // A dedicated unauthenticated client: never forward Compass backend credentials to GitHub.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).followSslRedirects(false).build()
    private var checking = false
    private var lastCheck: Long? = null
    private val apk: File get() = File(getApplication<Application>().cacheDir, "updates/compass.apk")

    fun check() {
        val now = SystemClock.elapsedRealtime()
        if (checking || mutableState.value.release != null ||
            lastCheck?.let { now - it < TimeUnit.HOURS.toMillis(6) } == true) return
        checking = true
        lastCheck = now
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(ClientRelease.CHECK_URL)
                        .header("Cache-Control", "no-cache").build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "Manifest HTTP ${response.code}" }
                        val body = requireNotNull(response.body)
                        val bytes = ByteArray(513)
                        var count = 0
                        body.byteStream().use { input ->
                            while (count < bytes.size) {
                                val read = input.read(bytes, count, bytes.size - count)
                                if (read < 0) break
                                count += read
                            }
                        }
                        check(count <= 512) { "Manifest too large" }
                        requireNotNull(ClientRelease.parse(String(bytes, 0, count, Charsets.UTF_8)))
                    }
                }
                val installed = requireNotNull(ClientVersion.parse(BuildConfig.VERSION_NAME))
                if (release.version > installed) mutableState.value = ClientUpdateState(release)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Log.w("CompassUpdate", "Update check unavailable; navigation is unaffected")
            } finally {
                checking = false
            }
        }
    }

    fun dismiss() {
        if (!mutableState.value.downloading) mutableState.value = ClientUpdateState()
    }

    fun download() {
        val release = mutableState.value.release ?: return
        if (mutableState.value.downloading) return
        mutableState.value = ClientUpdateState(release, downloading = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target = apk
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    val partial = File(target.parentFile, "download.part")
                    try {
                        client.newCall(Request.Builder().url(release.downloadUrl).build())
                            .execute().use { response ->
                                check(response.isSuccessful) { "Download non disponibile (HTTP ${response.code})." }
                                val body = requireNotNull(response.body)
                                val length = body.contentLength()
                                check(length <= MAX_APK_BYTES) { "APK troppo grande." }
                                var received = 0L
                                body.byteStream().use { input ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            received += count
                                            check(received <= MAX_APK_BYTES) { "APK troppo grande." }
                                            output.write(buffer, 0, count)
                                            mutableState.value = mutableState.value.copy(
                                                progress = if (length > 0) (received * 100 / length).toInt() else null,
                                            )
                                        }
                                    }
                                }
                                check(received > 0 && (length < 0 || received == length)) {
                                    "Download incompleto. Riprova."
                                }
                            }
                        validateApk(partial, release)
                        check(partial.renameTo(target)) { "Impossibile salvare l'aggiornamento." }
                    } finally {
                        partial.delete()
                    }
                }
                mutableState.value = ClientUpdateState(release, ready = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = ClientUpdateState(
                    release, error = error.message ?: "Download non riuscito. Riprova.",
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: ClientRelease) {
        val app = getApplication<Application>()
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val candidate = requireNotNull(app.packageManager.getPackageArchiveInfo(file.path, flags)) {
            "Il file scaricato non è un APK valido."
        }
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        check(candidate.packageName == app.packageName) { "APK non compatibile con questa app." }
        val version = requireNotNull(candidate.versionName?.let(ClientVersion::parse)) {
            "La versione dell'APK non è valida."
        }
        check(version == release.version && version > ClientVersion.parse(BuildConfig.VERSION_NAME)!!) {
            "La versione dell'APK non corrisponde all'aggiornamento."
        }
        val newCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(newCode > oldCode) { "Il codice versione dell'APK non è più recente." }
        val newSigners = if (Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.apkContentsSigners
            else candidate.signatures
        val oldSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners
            else installed.signatures
        check(!newSigners.isNullOrEmpty() && !oldSigners.isNullOrEmpty() &&
            newSigners.toSet() == oldSigners.toSet()) { "Firma APK diversa da quella installata." }
    }

    fun canInstall(): Boolean = getApplication<Application>().packageManager.canRequestPackageInstalls()

    fun permissionIntent() = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${getApplication<Application>().packageName}"),
    )

    fun installIntent(): Intent {
        val app = getApplication<Application>()
        check(mutableState.value.ready && apk.isFile)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun installationFailed() {
        mutableState.value = mutableState.value.copy(error = "Impossibile aprire l'installer Android.")
    }

    companion object { private const val MAX_APK_BYTES = 256L * 1024 * 1024 }
}
