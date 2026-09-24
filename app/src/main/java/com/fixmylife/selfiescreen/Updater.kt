package com.fixmylife.selfiescreen

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls the newest release from GitHub and installs it.
 * Releases are tagged build-<n> and the APK's versionCode is the same <n>,
 * so a plain number comparison tells us whether an update exists.
 */
class Updater(private val activity: Activity) {

    companion object {
        const val RELEASES_API =
            "https://api.github.com/repos/fixmylifedesigns/selfie-screen/releases/latest"
        private const val TAG = "Updater"
    }

    private val main = Handler(Looper.getMainLooper())

    private fun currentBuild(): Long = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
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
                if (!silent) main.post {
                    Toast.makeText(activity, "Update check failed", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun prompt(build: Long, url: String) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Build $build is ready to install. Download it now?")
            .setPositiveButton("Update") { _, _ -> download(build, url) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(build: Long, url: String) {
        val dm = activity.getSystemService(DownloadManager::class.java) ?: return
        val fileName = "SelfieScreen-$build.apk"
        val target = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Selfie Screen build $build")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = dm.enqueue(request)
        Toast.makeText(activity, "Downloading build $build…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try {
                    activity.unregisterReceiver(this)
                } catch (ignored: Exception) {
                }
                if (target.exists()) install(target) else
                    Toast.makeText(activity, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun install(file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow installs from this app, then tap Update again", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
