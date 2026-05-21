package com.msakasaka.keyboard.engine

data class FlickChars(
    val center: String,
    val up: String = "",
    val right: String = "",
    val down: String = "",
    val left: String = ""
)

object FlickCharTable {

    val JA_KEYS: Map<String, FlickChars> = mapOf(
        "あ" to FlickChars("あ", "う", "え", "お", "い"),
        "か" to FlickChars("か", "く", "け", "こ", "き"),
        "さ" to FlickChars("さ", "す", "せ", "そ", "し"),
        "た" to FlickChars("た", "つ", "て", "と", "ち"),
        "な" to FlickChars("な", "ぬ", "ね", "の", "に"),
        "は" to FlickChars("は", "ふ", "へ", "ほ", "ひ"),
        "ま" to FlickChars("ま", "む", "め", "も", "み"),
        "や" to FlickChars("や", "よ", "？", "！", "ゆ"),
        "ら" to FlickChars("ら", "る", "れ", "ろ", "り"),
        "わ" to FlickChars("わ", "ん", "ー", "〜", "を"),
        "。" to FlickChars("。", "？", "！", "・", "、")
    )

    // 行ごとの小文字マップ（小/゛キー用）
    private val KOGAKI_MAP: Map<String, String> = mapOf(
        "あ" to "ぁ", "い" to "ぃ", "う" to "ぅ", "え" to "ぇ", "お" to "ぉ",
        "ya" to "ゃ", "yu" to "ゅ", "yo" to "ょ",
        "や" to "ゃ", "ゆ" to "ゅ", "よ" to "ょ",
        "つ" to "っ", "わ" to "ゎ",
        // 逆引き
        "ぁ" to "あ", "ぃ" to "い", "ぅ" to "う", "ぇ" to "え", "ぉ" to "お",
        "ゃ" to "や", "ゅ" to "ゆ", "ょ" to "よ",
        "っ" to "つ", "ゎ" to "わ"
    )

    private val DAKUTEN_MAP: Map<String, String> = mapOf(
        "か" to "が", "き" to "ぎ", "く" to "ぐ", "け" to "げ", "こ" to "ご",
        "さ" to "ざ", "し" to "じ", "す" to "ず", "せ" to "ぜ", "そ" to "ぞ",
        "た" to "だ", "ち" to "ぢ", "つ" to "づ", "て" to "で", "と" to "ど",
        "は" to "ば", "ひ" to "び", "ふ" to "ぶ", "へ" to "べ", "ほ" to "ぼ",
        "が" to "か", "ぎ" to "き", "ぐ" to "く", "げ" to "け", "ご" to "こ",
        "ざ" to "さ", "じ" to "し", "ず" to "す", "ぜ" to "せ", "ぞ" to "そ",
        "だ" to "た", "ぢ" to "ち", "づ" to "つ", "で" to "て", "ど" to "と",
        "ば" to "ぱ", "び" to "ぴ", "ぶ" to "ぷ", "べ" to "ぺ", "ぼ" to "ぽ",
        "ぱ" to "は", "ぴ" to "ひ", "ぷ" to "ふ", "ぺ" to "へ", "ぽ" to "ほ"
    )

    /** 最後の1文字に対して小/゛を適用。変化した文字を返す（変化なしはnull） */
    fun applyModifier(char: String): String? {
        return DAKUTEN_MAP[char] ?: KOGAKI_MAP[char]
    }

    fun hasDakuten(char: String): Boolean = DAKUTEN_MAP.containsKey(char)
    fun hasKogaki(char: String): Boolean = KOGAKI_MAP.containsKey(char)
}
