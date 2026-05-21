package com.msakasaka.keyboard.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

class AutoUpdater(private val context: Context) {

    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** force=true の場合は24時間スロットルを無視して即チェック（設定画面からの明示的な確認用） */
    suspend fun checkAndDownloadIfNeeded(force: Boolean = false) {
        if (!force) {
            val lastCheck = prefs.getLong("last_check", 0L)
            if (System.currentTimeMillis() - lastCheck < 24 * 3600 * 1000L) return
        }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()

        // UpdateChecker.check() は内部で新バージョンがなければ null を返す
        val info = UpdateChecker(context).check() ?: return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(
            DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle("MyKeyboard アップデート ${info.tagName}")
                .setDescription("ダウンロード完了後に自動インストール")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "MyKeyboard.apk")
        )
    }
}
