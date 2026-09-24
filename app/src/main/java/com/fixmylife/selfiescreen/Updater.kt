package com.fixmylife.selfiescreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls the newest release from GitHub and installs it.
 * Releases are tagged build-<n> and the APK's versionCode is the same <n>.
 *
 * The download is done directly (no DownloadManager broadcast to miss), and if the
 * "install unknown apps" permission is still off we remember the pending update and
 * finish it automatically when the user comes back to the app.
 */
class Updater(private val activity: Activity) {

    companion object {
        const val RELEASES_API =
            "https://api.github.com/repos/fixmylifedesigns/selfie-screen/releases/latest"
        private const val TAG = "Updater"
        private const val PREFS = "updater"
        private const val KEY_URL = "pending_url"
        private const val KEY_BUILD = "pending_build"
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun currentBuild(): Long = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    private fun toast(msg: String) = main.post {
        if (!activity.isFinishing) Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    /** Called on resume: finishes an update that was waiting on the install permission. */
    fun resumePending() {
        val url = prefs.getString(KEY_URL, null) ?: return
        val build = prefs.getLong(KEY_BUILD, 0L)
        if (!activity.packageManager.canRequestPackageInstalls()) return
        prefs.edit().remove(KEY_URL).remove(KEY_BUILD).apply()
        download(build, url)
    }

    fun check(silent: Boolean) {
        Thread {
            try {
                val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                val remote = Regex("\\d+").find(tag)?.value?.toLongOrNull() ?: 0L
                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                val current = currentBuild()
                main.post {
                    if (activity.isFinishing) return@post
                    if (remote > current && apkUrl.isNotEmpty()) {
                        prompt(remote, apkUrl)
                    } else if (!silent) {
                        Toast.makeText(activity, "Up to date (build $current)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                if (!silent) toast("Update check failed")
            }
        }.start()
    }

    private fun prompt(build: Long, url: String) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Build $build is ready to install. Download it now?")
            .setPositiveButton("Update") { _, _ -> start(build, url) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun start(build: Long, url: String) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // Remember it, send them to the toggle, and finish the job when they return.
            prefs.edit().putString(KEY_URL, url).putLong(KEY_BUILD, build).apply()
            Toast.makeText(
                activity,
                "Allow Selfie Screen to install apps, then come back",
                Toast.LENGTH_LONG
            ).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "cannot open install-permission settings", e)
                toast("Enable install permission in Settings > Apps > Selfie Screen")
            }
            return
        }
        download(build, url)
    }

    private fun download(build: Long, url: String) {
        toast("Downloading build $build\u2026")
        Thread {
            var file: File? = null
            try {
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val target = File(dir, "SelfieScreen-$build.apk")
                if (target.exists()) target.delete()

                var link = url
                var conn: HttpURLConnection
                var hops = 0
                while (true) {
                    conn = URL(link).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = false
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 5) {
                        link = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                        hops++
                        continue
                    }
                    if (code != 200) throw IllegalStateException("HTTP $code")
                    break
                }

                conn.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                conn.disconnect()

                if (target.length() < 100_000) throw IllegalStateException("file too small")
                file = target
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                toast("Download failed: ${e.message}")
            }
            val done = file ?: return@Thread
            main.post { install(done) }
        }.start()
    }

    private fun install(file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "install intent failed", e)
            toast("Could not open installer: ${e.message}")
        }
    }
}
