package com.msakasaka.keyboard.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.msakasaka.keyboard.R
import com.msakasaka.keyboard.util.AutoUpdater
import com.msakasaka.keyboard.util.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var updateChecker: UpdateChecker
    private var pendingDownloadId: Long = -1

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == pendingDownloadId) installDownloadedApk(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        updateChecker = UpdateChecker(this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        setupImeStatus()
        setupUpdateCheck()

        // 設定起動時に毎回アップデート確認（throttle なし）
        lifecycleScope.launch {
            try { AutoUpdater(this@SettingsActivity).checkAndDownloadIfNeeded(force = true) } catch (_: Exception) {}
        }

        ContextCompat.registerReceiver(
            this, downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }

    private fun setupImeStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }

        val statusText = findViewById<MaterialTextView>(R.id.status_text)
        statusText.text = if (isEnabled) "✓ キーボードが有効になっています" else "⚠ キーボードが無効です\n有効化してください"

        findViewById<MaterialButton>(R.id.btn_open_ime_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_switch_ime).setOnClickListener {
            imm.showInputMethodPicker()
        }
    }

    private fun setupUpdateCheck() {
        val versionText = findViewById<MaterialTextView>(R.id.version_text)
        val updateBar = findViewById<View>(R.id.update_bar)
        val updateText = findViewById<MaterialTextView>(R.id.update_text)
        val btnUpdate = findViewById<MaterialButton>(R.id.btn_update)

        val localVc = updateChecker.localVersionCode()
        versionText.text = "現在のバージョン: v$localVc"
        updateBar.visibility = View.GONE

        lifecycleScope.launch {
            val info = updateChecker.check()
            if (info != null) {
                updateBar.visibility = View.VISIBLE
                updateText.text = "新しいバージョン ${info.tagName} があります"
                btnUpdate.setOnClickListener { downloadAndInstall(info.downloadUrl) }
                if (info.releaseBody.isNotBlank()) {
                    val notesView = findViewById<MaterialTextView>(R.id.release_notes_text)
                    notesView.text = info.releaseBody.trim()
                    notesView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun downloadAndInstall(url: String) {
        val btn = findViewById<MaterialButton>(R.id.btn_update)
        btn.text = "ダウンロード中..."
        btn.isEnabled = false

        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("MyKeyboard アップデート")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "MyKeyboard.apk")

        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        pendingDownloadId = dm.enqueue(req)
    }

    private fun installDownloadedApk(downloadId: Long) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        if (cursor.moveToFirst()) {
            val colIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val uriStr = cursor.getString(colIdx) ?: return
            val file = File(Uri.parse(uriStr).path ?: return)
            val apkUri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        cursor.close()
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.keyboard_settings, rootKey)
        findPreference<Preference>("user_dictionary")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), UserDictionaryActivity::class.java))
            true
        }
    }
}
