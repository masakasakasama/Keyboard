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
            val mime = clip.description.getMimeType(0) ?: continue
            if (mime.startsWith("image/")) {
                results.add(ClipboardImage(uri, mime))
            }
        }
        return results
    }

    fun hasImage(): Boolean {
        val clip = manager.primaryClip ?: return false
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val mime = clip.description.getMimeType(0) ?: continue
            if (mime.startsWith("image/")) return true
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
