package com.msakasaka.keyboard.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

data class ClipboardImage(val uri: Uri, val mimeType: String)

class ClipboardHelper(private val context: Context) {

    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun getImages(): List<ClipboardImage> {
        val clip = manager.primaryClip ?: return emptyList()
        val results = mutableListOf<ClipboardImage>()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item.uri ?: continue
            // すべての MIME タイプを確認（0番目だけでは画像を見逃す場合がある）
            val mime = (0 until clip.description.mimeTypeCount)
                .mapNotNull { clip.description.getMimeType(it) }
                .firstOrNull { it.startsWith("image/") } ?: continue
            results.add(ClipboardImage(uri, mime))
        }
        return results
    }

    fun hasImage(): Boolean {
        val clip = manager.primaryClip ?: return false
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val has = (0 until clip.description.mimeTypeCount)
                .any { clip.description.getMimeType(it)?.startsWith("image/") == true }
            if (has) return true
        }
        return false
    }

    fun setClipboardChangeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        manager.addPrimaryClipChangedListener(listener)
    }

    fun removeClipboardChangeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        manager.removePrimaryClipChangedListener(listener)
    }
}
