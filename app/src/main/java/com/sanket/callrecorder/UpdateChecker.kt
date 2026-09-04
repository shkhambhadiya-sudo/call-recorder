package com.sanket.callrecorder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the repo's GitHub Releases for a newer build and installs it.
 *
 * The repo is public, so lookups and downloads work unauthenticated (TOKEN is
 * blank). The optional bearer-token path is kept so the same code also works if
 * the repo is ever made private (token injected via BuildConfig at build time).
 *
 * Convention: CI tags each release "v<versionCode>" and attaches the APK asset.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private val TOKEN = BuildConfig.UPDATE_TOKEN

    /** apkApiUrl is the GitHub *API* asset URL (works for private repos). */
    data class Release(val versionCode: Int, val name: String, val apkApiUrl: String)

    private fun HttpURLConnection.applyAuth(accept: String) {
        setRequestProperty("Accept", accept)
        if (TOKEN.isNotBlank()) setRequestProperty("Authorization", "Bearer $TOKEN")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connectTimeout = 15000
        readTimeout = 60000
    }

    /** Returns a newer release, or null if we are up to date / offline / no token. */
    suspend fun findUpdate(): Release? = withContext(Dispatchers.IO) {
        try {
            val api = "https://api.github.com/repos/${BuildConfig.REPO_OWNER}/${BuildConfig.REPO_NAME}/releases/latest"
            val conn = (URL(api).openConnection() as HttpURLConnection)
            conn.applyAuth("application/vnd.github+json")
            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                return@withContext null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name").removePrefix("v")
            val latestCode = tag.toIntOrNull() ?: return@withContext null
            if (latestCode <= BuildConfig.VERSION_CODE) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkApiUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    // The API URL (not browser_download_url) is what a token can fetch.
                    apkApiUrl = a.optString("url")
                    break
                }
            }
            apkApiUrl ?: return@withContext null
            Release(latestCode, json.optString("name").ifBlank { "v$latestCode" }, apkApiUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** Downloads the APK (following GitHub's redirect to storage) and installs it. */
    suspend fun downloadAndInstall(activity: Activity, release: Release) {
        val apk = withContext(Dispatchers.IO) {
            try {
                // Step 1: hit the asset API URL with auth; do NOT auto-follow, so
                // we can drop the auth header before the redirect to storage
                // (signed storage URLs reject an unexpected Authorization header).
                val first = (URL(release.apkApiUrl).openConnection() as HttpURLConnection)
                first.applyAuth("application/octet-stream")
                first.instanceFollowRedirects = false

                val input = when (first.responseCode) {
                    in 300..399 -> {
                        val loc = first.getHeaderField("Location")
                        val redirected = (URL(loc).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 20000
                            readTimeout = 60000
                        }
                        redirected.inputStream
                    }
                    200 -> first.inputStream
                    else -> {
                        Log.e(TAG, "Asset download HTTP ${first.responseCode}")
                        null
                    }
                } ?: return@withContext null

                val out = File(activity.cacheDir, "update.apk")
                input.use { i -> out.outputStream().use { i.copyTo(it) } }
                out
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                null
            }
        }
        if (apk == null || !apk.exists()) {
            Toast.makeText(activity, "Update download failed", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", apk
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(install)
    }
}
