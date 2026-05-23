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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.msakasaka.keyboard.R
import com.msakasaka.keyboard.engine.Dictionary
import com.msakasaka.keyboard.engine.InputMode
import com.msakasaka.keyboard.engine.InputState
import com.msakasaka.keyboard.engine.JapaneseInputEngine
import com.msakasaka.keyboard.ui.CandidateView
import com.msakasaka.keyboard.ui.FlickKeyboardView
import com.msakasaka.keyboard.ui.KeyboardListener
import com.msakasaka.keyboard.ui.QwertyKeyboardView
import com.msakasaka.keyboard.util.AutoUpdater
import com.msakasaka.keyboard.util.EnglishText
import com.msakasaka.keyboard.util.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var updateChecker: UpdateChecker
    private var pendingDownloadId: Long = -1

    private lateinit var previewEngine: JapaneseInputEngine
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val previewCommitted = StringBuilder()

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
        setupKeyboardPreview()

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
        previewScope.cancel()
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

    private fun setupKeyboardPreview() {
        val container = findViewById<FrameLayout>(R.id.preview_container)
        val btnJp = findViewById<MaterialButton>(R.id.btn_preview_japanese)
        val btnEn = findViewById<MaterialButton>(R.id.btn_preview_english)
        val inputText = findViewById<EditText>(R.id.preview_input_text)
        val candidateView = findViewById<CandidateView>(R.id.preview_candidate_view)

        val flickView = FlickKeyboardView(this)
        val qwertyView = QwertyKeyboardView(this)
        container.addView(flickView)
        container.addView(qwertyView)
        qwertyView.visibility = View.GONE

        // prevent system keyboard from showing
        inputText.showSoftInputOnFocus = false

        val dict = Dictionary.get(this)
        previewEngine = JapaneseInputEngine(dict)
        previewScope.launch(Dispatchers.IO) { dict.ensureLoaded() }

        previewEngine.onStateChanged = { snapshot ->
            val display = snapshot.currentCandidate
            val full = previewCommitted.toString() + display.ifEmpty { snapshot.composing }
            val span = SpannableStringBuilder(full)
            if (display.isNotEmpty()) {
                val s = previewCommitted.length
                span.setSpan(UnderlineSpan(), s, s + display.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            inputText.text = span
            inputText.setSelection(full.length)
            candidateView.candidates = snapshot.candidates
            candidateView.selectedIndex = snapshot.selectedCandidateIndex
            if (snapshot.state == InputState.IDLE) candidateView.candidates = emptyList()
        }

        candidateView.onCandidateClick = { index ->
            when (previewEngine.state) {
                InputState.SEGMENTED -> {
                    previewEngine.selectSegmentCandidate(index)
                    if (!previewEngine.nextSegment()) {
                        val text = previewEngine.commitAllSegments()
                        previewCommitted.append(text)
                        inputText.setText(previewCommitted.toString())
                        inputText.setSelection(previewCommitted.length)
                    }
                }
                else -> {
                    val selected = previewEngine.selectCandidate(index)
                    previewCommitted.append(selected)
                    inputText.setText(previewCommitted.toString())
                    inputText.setSelection(previewCommitted.length)
                }
            }
        }

        val listener = object : KeyboardListener {
            override fun onChar(ch: String) {
                if (previewEngine.mode == InputMode.ENGLISH) {
                    if (ch.matches(Regex("[a-zA-Z]"))) {
                        val input = if (previewEngine.composing.isEmpty() && ch[0].isLowerCase() &&
                            EnglishText.shouldCapitalize(previewCommitted.toString())) ch.uppercase() else ch
                        previewEngine.appendChar(input)
                    } else {
                        commitPreviewComposing(inputText)
                        previewCommitted.append(ch)
                        inputText.setText(previewCommitted.toString())
                        inputText.setSelection(previewCommitted.length)
                    }
                } else {
                    previewEngine.appendChar(ch)
                }
            }

            override fun onBackspace() {
                if (!previewEngine.backspace()) {
                    if (previewCommitted.isNotEmpty()) {
                        previewCommitted.deleteCharAt(previewCommitted.length - 1)
                        inputText.setText(previewCommitted.toString())
                        inputText.setSelection(previewCommitted.length)
                    }
                }
            }

            override fun onEnter() {
                when (previewEngine.state) {
                    InputState.COMPOSING -> commitPreviewComposing(inputText)
                    InputState.CONVERTING -> {
                        val s = previewEngine.selectCandidate(previewEngine.selectedIndex)
                        previewCommitted.append(s)
                        inputText.setText(previewCommitted.toString())
                        inputText.setSelection(previewCommitted.length)
                    }
                    InputState.SEGMENTED -> {
                        val text = previewEngine.commitAllSegments()
                        previewCommitted.append(text)
                        inputText.setText(previewCommitted.toString())
                        inputText.setSelection(previewCommitted.length)
                    }
                    InputState.IDLE -> {
                        previewCommitted.append("\n")
                        inputText.setText(previewCommitted.toString())
                        inputText.setSelection(previewCommitted.length)
                    }
                }
            }

            override fun onSpace() {
                when (previewEngine.state) {
                    InputState.COMPOSING -> {
                        if (previewEngine.mode == InputMode.ENGLISH) {
                            commitPreviewComposing(inputText)
                            previewCommitted.append(" ")
                            inputText.setText(previewCommitted.toString())
                            inputText.setSelection(previewCommitted.length)
                        } else {
                            previewEngine.startConversion()
                        }
                    }
                    InputState.CONVERTING, InputState.SEGMENTED -> previewEngine.nextCandidate()
                    InputState.IDLE -> {
                        val sp = if (previewEngine.mode == InputMode.JAPANESE) "　" else " "
                        previewCommitted.append(sp)
                        inputText.setText(previewCommitted.toString())
                        inputText.setSelection(previewCommitted.length)
                    }
                }
            }

            override fun onConvert() {
                when (previewEngine.state) {
                    InputState.COMPOSING -> previewEngine.startConversion()
                    InputState.CONVERTING, InputState.SEGMENTED -> previewEngine.nextCandidate()
                    else -> {}
                }
            }

            override fun onModifier() {
                if (previewEngine.state == InputState.CONVERTING) previewEngine.cancelConversion()
                previewEngine.applyModifierToLast()
            }

            override fun onSwitchMode() {
                commitPreviewComposing(inputText)
                previewEngine.toggleMode()
                flickView.currentMode = previewEngine.mode
            }

            override fun onClipboardOpen() {}
        }

        flickView.listener = listener
        qwertyView.listener = listener

        btnJp.setOnClickListener {
            commitPreviewComposing(inputText)
            previewEngine.setMode(InputMode.JAPANESE)
            flickView.currentMode = InputMode.JAPANESE
            flickView.visibility = View.VISIBLE
            qwertyView.visibility = View.GONE
            btnJp.alpha = 1f; btnEn.alpha = 0.5f
        }
        btnEn.setOnClickListener {
            commitPreviewComposing(inputText)
            previewEngine.setMode(InputMode.ENGLISH)
            flickView.currentMode = InputMode.ENGLISH
            flickView.visibility = View.GONE
            qwertyView.visibility = View.VISIBLE
            btnJp.alpha = 0.5f; btnEn.alpha = 1f
        }
        btnEn.alpha = 0.5f
    }

    private fun commitPreviewComposing(inputText: EditText) {
        val raw = previewEngine.commitComposing()
        val text = if (previewEngine.mode == InputMode.ENGLISH) EnglishText.autoCorrect(raw) else raw
        if (text.isNotEmpty()) {
            previewCommitted.append(text)
            inputText.setText(previewCommitted.toString())
            inputText.setSelection(previewCommitted.length)
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
