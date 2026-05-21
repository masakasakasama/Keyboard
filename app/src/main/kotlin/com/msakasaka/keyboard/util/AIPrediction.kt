package com.msakasaka.keyboard.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AIPrediction(private val apiKey: String) {

    suspend fun predict(textBefore: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        try {
            val context = textBefore.takeLast(200)
            val url = URL("https://api.anthropic.com/v1/messages")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.doOutput = true

            val prompt = "Complete this text naturally. Return ONLY a JSON array of 5 short next-word or next-phrase suggestions (1-3 words each). No explanation, just the JSON array.\n\nText: \"$context\""

            val body = JSONObject().apply {
                put("model", "claude-haiku-4-5-20251001")
                put("max_tokens", 120)
                put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                ))
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val raw = conn.inputStream.bufferedReader().readText()
            val text = JSONObject(raw)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
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
