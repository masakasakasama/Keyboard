package com.msakasaka.keyboard.settings

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.msakasaka.keyboard.R
import com.msakasaka.keyboard.engine.Dictionary

class UserDictionaryActivity : AppCompatActivity() {

    private lateinit var dictionary: Dictionary
    private lateinit var wordListLayout: LinearLayout
    private lateinit var etReading: EditText
    private lateinit var etSurface: EditText
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_dictionary)

        supportActionBar?.apply {
            title = "ユーザー辞書"
            setDisplayHomeAsUpEnabled(true)
        }

        dictionary = Dictionary.get(this)
        wordListLayout = findViewById(R.id.word_list)
        etReading = findViewById(R.id.et_reading)
        etSurface = findViewById(R.id.et_surface)
        tvEmpty = findViewById(R.id.tv_empty)

        findViewById<MaterialButton>(R.id.btn_add).setOnClickListener {
            val reading = etReading.text.toString().trim()
            val surface = etSurface.text.toString().trim()
            when {
                reading.isEmpty() -> Toast.makeText(this, "読みを入力してください", Toast.LENGTH_SHORT).show()
                surface.isEmpty() -> Toast.makeText(this, "表記を入力してください", Toast.LENGTH_SHORT).show()
                else -> {
                    dictionary.addCustomWord(reading, surface)
                    etReading.text.clear()
                    etSurface.text.clear()
                    refreshWordList()
                    Toast.makeText(this, "「$surface」を登録しました", Toast.LENGTH_SHORT).show()
                }
            }
        }

        refreshWordList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshWordList() {
        wordListLayout.removeAllViews()
        val words = dictionary.getCustomWords()

        tvEmpty.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE

        words.forEach { (reading, surface) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 0, 8, 0)
            }

            val tv = MaterialTextView(this).apply {
                text = "$surface（$reading）"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 20, 0, 20)
            }

            val btnDelete = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "削除"
                setOnClickListener {
                    dictionary.removeCustomWord(reading, surface)
                    refreshWordList()
                }
            }

            row.addView(tv)
            row.addView(btnDelete)
            wordListLayout.addView(row)

            // 区切り線
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(0x22888888)
            }
            wordListLayout.addView(divider)
        }
    }
}
