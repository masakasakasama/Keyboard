package com.msakasaka.keyboard.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class UpdateInfo(val versionCode: Int, val downloadUrl: String, val tagName: String)

class UpdateChecker(private val context: Context) {

    private val apiUrl = "https://api.github.com/repos/masakasakasama/Keyboard/releases/latest"

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(apiUrl).openConnection()
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")

            val json = JSONObject(conn.getInputStream().bufferedReader().readText())
            val tag = json.getString("tag_name")          // "v42"
            val remoteVc = tag.removePrefix("v").toIntOrNull() ?: return@withContext null
            val localVc = localVersionCode()

            if (remoteVc <= localVc) return@withContext null

            // APK アセットの URL を取得
            val assets = json.getJSONArray("assets")
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (downloadUrl.isEmpty()) return@withContext null

            UpdateInfo(remoteVc, downloadUrl, tag)
        } catch (e: Exception) {
            null
        }
    }

    fun localVersionCode(): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
}
