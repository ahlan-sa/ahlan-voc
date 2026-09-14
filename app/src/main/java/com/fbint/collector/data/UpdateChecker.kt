package com.fbint.collector.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.fbint.collector.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val isNewer: Boolean,
)

/**
 * Self-update via GitHub Releases. We hit the public `releases/latest` endpoint anonymously,
 * compare its tag against [BuildConfig.VERSION_NAME] (stripping a leading "v"), and download
 * the APK asset on demand. The system PackageInstaller takes over from there.
 *
 * Sideloaded APKs need the user to allow "install unknown apps" once for our package — we
 * detect this with packageManager.canRequestPackageInstalls and surface a settings deep-link
 * if it's missing.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val client: OkHttpClient,
    moshi: Moshi,
) {
    private val releaseAdapter = moshi.adapter(GitHubRelease::class.java)

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/essamharoon/ahlan-voc/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            val release = releaseAdapter.fromJson(body) ?: return@use null
            val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@use null
            val latest = release.tagName.removePrefix("v")
            val installed = BuildConfig.VERSION_NAME
            UpdateInfo(
                installedVersion = installed,
                latestVersion = latest,
                downloadUrl = asset.browserDownloadUrl,
                sizeBytes = asset.size,
                isNewer = compareVersions(latest, installed) > 0,
            )
        }
    }

    /** Streams the APK to app cache. [onProgress] is called with 0..100 (or -1 for unknown). */
    suspend fun download(url: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "ahlan-update.apk")
        if (target.exists()) target.delete()
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                        else onProgress(-1)
                    }
                }
            }
        }
        target
    }

    fun canInstallPackages(): Boolean = android.os.Build.VERSION.SDK_INT < 26 ||
        ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun launchInstaller(file: File) {
        val authority = "${ctx.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(ctx, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }

    /** Numeric compare of "0.1.0" vs "0.2.0" etc. Returns positive when [a] > [b]. */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val av = pa.getOrNull(i) ?: 0
            val bv = pb.getOrNull(i) ?: 0
            if (av != bv) return av - bv
        }
        return 0
    }
}

@JsonClass(generateAdapter = true)
internal data class GitHubRelease(
    @Json(name = "tag_name") val tagName: String,
    val assets: List<GitHubAsset>,
)

@JsonClass(generateAdapter = true)
internal data class GitHubAsset(
    val name: String,
    @Json(name = "browser_download_url") val browserDownloadUrl: String,
    val size: Long,
)
