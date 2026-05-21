package com.msakasaka.keyboard.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AIPrediction(private val apiKey: String) {

    suspend fun predict(textBefore: String, japanese: Boolean): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        try {
            val context = textBefore.takeLast(200)
            val conn = URL("https://api.groq.com/openai/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.doOutput = true

            val system: String
            val user: String
            if (japanese) {
                system = "あなたはモバイルキーボードの次の単語予測AIです。入力されたテキストの続きとして自然な次の単語・フレーズを5つ提案してください。JSONの文字列配列のみを返してください。説明は不要です。"
                user = "続きを予測してください: \"$context\""
            } else {
                system = "You are a next-word predictor for a mobile keyboard. Return ONLY a JSON array of 5 short suggestions (1-3 words each). No explanation."
                user = "Continue this text: \"$context\""
            }

            val body = JSONObject().apply {
                put("model", "llama-3.1-8b-instant")
                put("max_tokens", 150)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val raw = conn.inputStream.bufferedReader().readText()
            val text = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }.take(5)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
