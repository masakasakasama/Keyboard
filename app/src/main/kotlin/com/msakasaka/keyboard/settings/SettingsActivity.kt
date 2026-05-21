package com.msakasaka.keyboard.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.msakasaka.keyboard.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        // キーボードが有効かチェックして案内
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        val isEnabled = enabledMethods.any { it.packageName == packageName }

        val statusText = findViewById<MaterialTextView>(R.id.status_text)
        statusText.text = if (isEnabled) "✓ キーボードが有効です" else "⚠ キーボードが無効です\n下の手順で有効にしてください"

        findViewById<MaterialButton>(R.id.btn_open_ime_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btn_switch_ime).setOnClickListener {
            imm.showInputMethodPicker()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.keyboard_settings, rootKey)
    }
}
