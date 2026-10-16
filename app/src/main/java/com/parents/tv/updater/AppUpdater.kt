package com.parents.tv.updater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String
)

sealed class UpdateCheckResult {
    data class Available(val updateInfo: UpdateInfo, val currentVersion: String) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class AppUpdater(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        private const val TAG = "AppUpdater"
        const val UPDATE_METADATA_URL =
            "https://raw.githubusercontent.com/daman-04/parents-tv/main/version.json"
    }

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
            val currentName = pInfo.versionName ?: "1.0.0"

            Log.d(TAG, "Current App Version: $currentName ($currentCode). Checking for updates at $UPDATE_METADATA_URL...")

            val request = Request.Builder()
                .url(UPDATE_METADATA_URL)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "ParentsTV-Updater/$currentName")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("HTTP Error ${response.code}: Could not fetch update info")
                }

                val body = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Empty response from update server")
                val json = JSONObject(body)
                val remoteCode = json.optInt("versionCode", 0)
                val remoteName = json.optString("versionName", "1.0.0")
                val apkUrl = json.optString("apkUrl", "")
                val releaseNotes = json.optString("releaseNotes", "New performance enhancements and channel improvements.")

                if (remoteCode > currentCode && apkUrl.isNotBlank()) {
                    Log.i(TAG, "New version available: $remoteName ($remoteCode) > Current ($currentCode)")
                    UpdateCheckResult.Available(
                        updateInfo = UpdateInfo(remoteCode, remoteName, apkUrl, releaseNotes),
                        currentVersion = currentName
                    )
                } else {
                    Log.i(TAG, "App is up to date ($currentName)")
                    UpdateCheckResult.UpToDate(currentVersion = currentName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update: ${e.message}", e)
            UpdateCheckResult.Error(e.message ?: "Failed to check for updates")
        }
    }

    suspend fun downloadAndInstall(
        activity: Activity,
        updateInfo: UpdateInfo,
        onProgress: (Int, Long, Long) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download of APK: ${updateInfo.apkUrl}")
            val request = Request.Builder()
                .url(updateInfo.apkUrl)
                .header("User-Agent", "ParentsTV-Updater")
                .build()

            val targetDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
            if (!targetDir.exists()) targetDir.mkdirs()
            val apkFile = File(targetDir, "TV_Update_${updateInfo.versionCode}.apk")

            if (apkFile.exists()) {
                apkFile.delete()
            }

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onError("Download failed: HTTP ${response.code}")
                    }
                    return@withContext
                }

                val body = response.body ?: run {
                    withContext(Dispatchers.Main) {
                        onError("Empty response body from APK server")
                    }
                    return@withContext
                }

                val totalLength = body.contentLength()
                var totalBytesRead = 0L

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var lastProgress = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (totalLength > 0) {
                                val progress = ((totalBytesRead * 100) / totalLength).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress, totalBytesRead, totalLength)
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            Log.i(TAG, "Download finished. APK size: ${apkFile.length()} bytes. Triggering installer intent...")

            withContext(Dispatchers.Main) {
                try {
                    val contentUri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        apkFile
                    )

                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    activity.startActivity(installIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching package installer: ${e.message}", e)
                    onError("Failed to launch installer: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onError(e.message ?: "Download failed")
            }
        }
    }
}
