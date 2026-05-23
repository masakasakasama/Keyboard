package com.msakasaka.keyboard.util

/** 英語入力の標準的な自動補正（IME 本体と設定プレビューで共有） */
object EnglishText {

    /** カーソル直前テキストから、次の英字を大文字にすべきか判定（文頭・文末記号の後） */
    fun shouldCapitalize(textBefore: String): Boolean {
        val trimmed = textBefore.trimEnd(' ', '\t')
        if (trimmed.isEmpty()) return true
        val last = trimmed.last()
        return last == '.' || last == '!' || last == '?' || last == '\n'
    }

    private val contractions = mapOf(
        "im" to "I'm", "ive" to "I've", "ill" to "I'll", "id" to "I'd",
        "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't",
        "werent" to "weren't", "dont" to "don't", "doesnt" to "doesn't",
        "didnt" to "didn't", "cant" to "can't", "couldnt" to "couldn't",
        "wont" to "won't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't",
        "youre" to "you're", "youve" to "you've", "youll" to "you'll",
        "youd" to "you'd", "theyre" to "they're", "theyve" to "they've",
        "theyll" to "they'll", "theyd" to "they'd",
        "thats" to "that's", "whats" to "what's",
        "shes" to "she's", "hes" to "he's"
    )

    /** 単独の "i" → "I" や縮約形の補正 */
    fun autoCorrect(word: String): String {
        val lowered = word.lowercase()
        if (lowered == "i") return "I"
        contractions[lowered]?.let { corrected ->
            return if (word.isNotEmpty() && word[0].isUpperCase() && !corrected.startsWith("I"))
                corrected.replaceFirstChar { it.uppercaseChar() }
            else corrected
        }
        return word
    }
}
