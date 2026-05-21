package com.msakasaka.keyboard.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        if (!cursor.moveToFirst()) { cursor.close(); return }

        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            val uriStr = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            val path = Uri.parse(uriStr).path
            if (path != null) {
                val file = File(path)
                if (file.exists() && file.name.endsWith(".apk")) {
                    val apkUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(apkUri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        cursor.close()
    }
}
