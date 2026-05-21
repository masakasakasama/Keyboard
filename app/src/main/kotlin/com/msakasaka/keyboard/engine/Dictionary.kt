package com.msakasaka.keyboard.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DictEntry(val reading: String, val surface: String, val freq: Int)

class Dictionary(private val context: Context) {

    // reading -> list of (surface, freq)
    private val index = HashMap<String, MutableList<Pair<String, Int>>>(8192)
    private var loaded = false

    private val userPrefs: SharedPreferences
        get() = context.getSharedPreferences("user_dict", Context.MODE_PRIVATE)

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            loadBuiltIn()
            loadFromAssets()
            loadUserData()
            loaded = true
        }
    }

    /** 候補選択時に呼ぶ。次回から優先表示される */
    fun learn(reading: String, surface: String) {
        if (reading.isEmpty() || surface.isEmpty()) return
        val key = "l\t$reading\t$surface"
        val count = userPrefs.getInt(key, 0) + 1
        userPrefs.edit().putInt(key, count).apply()
        // メモリ内インデックスも即時更新
        val list = index.getOrPut(reading) { mutableListOf() }
        val idx = list.indexOfFirst { it.first == surface }
        val newFreq = 9000 + count * 100
        if (idx >= 0) list[idx] = Pair(surface, newFreq)
        else list.add(Pair(surface, newFreq))
    }

    /** ユーザー辞書に単語を追加 */
    fun addCustomWord(reading: String, surface: String) {
        if (reading.isEmpty() || surface.isEmpty()) return
        val key = "c\t$reading\t$surface"
        userPrefs.edit().putBoolean(key, true).apply()
        add(reading, surface, 10000)
    }

    /** ユーザー辞書から単語を削除（次回 ensureLoaded 後に反映） */
    fun removeCustomWord(reading: String, surface: String) {
        val key = "c\t$reading\t$surface"
        userPrefs.edit().remove(key).apply()
        index.clear()
        loaded = false
    }

    /** ユーザーが手動登録した単語の一覧 */
    fun getCustomWords(): List<Pair<String, String>> {
        return userPrefs.all
            .filter { it.key.startsWith("c\t") }
            .map { it.key.removePrefix("c\t").split("\t") }
            .filter { it.size == 2 }
            .map { Pair(it[0], it[1]) }
            .sortedBy { it.first }
    }

    /** ひらがな読みが reading で始まるエントリを返す（最大 limit 件、頻度順） */
    fun lookup(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<String, Int>>()

        // 完全一致を優先
        index[reading]?.let { results.addAll(it) }

        // 前方一致（読みが長い単語を拾う）
        if (reading.length >= 2) {
            for ((key, pairs) in index) {
                if (key != reading && key.startsWith(reading)) {
                    results.addAll(pairs)
                }
            }
        }

        return results.sortedByDescending { it.second }
            .map { it.first }
            .distinct()
    }

    private fun add(reading: String, surface: String, freq: Int) {
        index.getOrPut(reading) { mutableListOf() }.add(Pair(surface, freq))
    }

    private fun loadUserData() {
        userPrefs.all.forEach { (key, value) ->
            when {
                key.startsWith("l\t") -> {
                    val parts = key.removePrefix("l\t").split("\t")
                    if (parts.size == 2) {
                        val count = value as? Int ?: 1
                        add(parts[0], parts[1], 9000 + count * 100)
                    }
                }
                key.startsWith("c\t") -> {
                    val parts = key.removePrefix("c\t").split("\t")
                    if (parts.size == 2) add(parts[0], parts[1], 10000)
                }
            }
        }
    }

    private fun loadFromAssets() {
        try {
            context.assets.open("japanese_dictionary.txt").bufferedReader().forEachLine { line ->
                if (line.startsWith("#") || line.isBlank()) return@forEachLine
                val parts = line.split("\t")
                if (parts.size >= 2) {
                    val reading = parts[0].trim()
                    val surface = parts[1].trim()
                    val freq = if (parts.size >= 3) parts[2].trim().toIntOrNull() ?: 100 else 100
                    if (reading.isNotEmpty() && surface.isNotEmpty()) {
                        add(reading, surface, freq)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadBuiltIn() {
        val entries = listOf(
            // 代名詞
            Triple("わたし", "私", 9000), Triple("わたし", "わたし", 7000),
            Triple("わたしたち", "私たち", 7000), Triple("ぼく", "僕", 8000),
            Triple("おれ", "俺", 7000), Triple("あなた", "あなた", 8000),
            Triple("あなた", "貴方", 5000), Triple("きみ", "君", 7000),
            Triple("かれ", "彼", 8000), Triple("かのじょ", "彼女", 8000),
            Triple("かれら", "彼ら", 6000), Triple("みんな", "みんな", 8000),
            Triple("みんな", "皆", 7000), Triple("じぶん", "自分", 8000),
            // 指示語
            Triple("これ", "これ", 9000), Triple("それ", "それ", 9000),
            Triple("あれ", "あれ", 8000), Triple("どれ", "どれ", 7000),
            Triple("この", "この", 9000), Triple("その", "その", 9000),
            Triple("あの", "あの", 8000), Triple("どの", "どの", 7000),
            Triple("ここ", "ここ", 9000), Triple("そこ", "そこ", 8000),
            Triple("あそこ", "あそこ", 7000), Triple("どこ", "どこ", 8000),
            Triple("こう", "こう", 7000), Triple("そう", "そう", 9000),
            Triple("ああ", "ああ", 6000), Triple("どう", "どう", 8000),
            // 時間
            Triple("いま", "今", 9000), Triple("きょう", "今日", 9000),
            Triple("きょう", "きょう", 6000), Triple("あした", "明日", 9000),
            Triple("あす", "明日", 8000), Triple("きのう", "昨日", 9000),
            Triple("おととい", "一昨日", 7000), Triple("あさって", "明後日", 7000),
            Triple("ことし", "今年", 9000), Triple("らいねん", "来年", 8000),
            Triple("きょねん", "去年", 8000), Triple("まいにち", "毎日", 8000),
            Triple("まいとし", "毎年", 7000), Triple("まいあさ", "毎朝", 7000),
            Triple("まいばん", "毎晩", 7000), Triple("ごぜん", "午前", 8000),
            Triple("ごご", "午後", 8000), Triple("あさ", "朝", 9000),
            Triple("ひる", "昼", 9000), Triple("よる", "夜", 9000),
            Triple("ばん", "晩", 7000), Triple("よあけ", "夜明け", 6000),
            Triple("じかん", "時間", 9000), Triple("ふん", "分", 8000),
            Triple("びょう", "秒", 7000), Triple("にち", "日", 8000),
            Triple("しゅう", "週", 7000), Triple("つき", "月", 8000),
            Triple("ねん", "年", 9000), Triple("せんしゅう", "先週", 8000),
            Triple("こんしゅう", "今週", 8000), Triple("らいしゅう", "来週", 8000),
            Triple("せんげつ", "先月", 8000), Triple("こんげつ", "今月", 8000),
            Triple("らいげつ", "来月", 8000), Triple("いちがつ", "1月", 7000),
            Triple("にがつ", "2月", 7000), Triple("さんがつ", "3月", 7000),
            Triple("しがつ", "4月", 7000), Triple("ごがつ", "5月", 7000),
            Triple("ろくがつ", "6月", 7000), Triple("しちがつ", "7月", 7000),
            Triple("はちがつ", "8月", 7000), Triple("くがつ", "9月", 7000),
            Triple("じゅうがつ", "10月", 7000), Triple("じゅういちがつ", "11月", 7000),
            Triple("じゅうにがつ", "12月", 7000),
            Triple("げつようび", "月曜日", 7000), Triple("かようび", "火曜日", 7000),
            Triple("すいようび", "水曜日", 7000), Triple("もくようび", "木曜日", 7000),
            Triple("きんようび", "金曜日", 7000), Triple("どようび", "土曜日", 7000),
            Triple("にちようび", "日曜日", 7000),
            // 場所・方向
            Triple("みぎ", "右", 8000), Triple("ひだり", "左", 8000),
            Triple("うえ", "上", 9000), Triple("した", "下", 9000),
            Triple("まえ", "前", 9000), Triple("うしろ", "後ろ", 8000),
            Triple("なか", "中", 9000), Triple("そと", "外", 8000),
            Triple("よこ", "横", 7000), Triple("となり", "隣", 7000),
            Triple("ちかく", "近く", 8000), Triple("とおく", "遠く", 7000),
            Triple("むこう", "向こう", 7000), Triple("あいだ", "間", 8000),
            Triple("うち", "家", 9000), Triple("うち", "うち", 7000),
            Triple("いえ", "家", 9000), Triple("へや", "部屋", 8000),
            Triple("がっこう", "学校", 8000), Triple("かいしゃ", "会社", 8000),
            Triple("びょういん", "病院", 7000), Triple("えき", "駅", 8000),
            Triple("みせ", "店", 8000), Triple("みち", "道", 8000),
            Triple("こうえん", "公園", 7000), Triple("としょかん", "図書館", 7000),
            Triple("ゆうびんきょく", "郵便局", 6000), Triple("ぎんこう", "銀行", 7000),
            Triple("ホテル", "ホテル", 7000),
            // 数
            Triple("いち", "1", 8000), Triple("いち", "一", 8000),
            Triple("に", "2", 8000), Triple("に", "二", 8000),
            Triple("さん", "3", 8000), Triple("さん", "三", 8000),
            Triple("し", "4", 7000), Triple("し", "四", 7000),
            Triple("よ", "4", 7000), Triple("よん", "4", 8000), Triple("よん", "四", 8000),
            Triple("ご", "5", 8000), Triple("ご", "五", 8000),
            Triple("ろく", "6", 8000), Triple("ろく", "六", 8000),
            Triple("なな", "7", 8000), Triple("なな", "七", 8000),
            Triple("しち", "7", 7000), Triple("しち", "七", 7000),
            Triple("はち", "8", 8000), Triple("はち", "八", 8000),
            Triple("きゅう", "9", 8000), Triple("きゅう", "九", 8000),
            Triple("く", "9", 6000), Triple("じゅう", "10", 8000), Triple("じゅう", "十", 8000),
            Triple("ひゃく", "百", 8000), Triple("せん", "千", 8000),
            Triple("まん", "万", 8000), Triple("おく", "億", 7000),
            // 人・関係
            Triple("ひと", "人", 9000), Triple("おとこ", "男", 8000),
            Triple("おんな", "女", 8000), Triple("こども", "子ども", 8000),
            Triple("こ", "子", 8000), Triple("おとな", "大人", 8000),
            Triple("ちち", "父", 8000), Triple("はは", "母", 8000),
            Triple("おとうさん", "お父さん", 8000), Triple("おかあさん", "お母さん", 8000),
            Triple("あに", "兄", 7000), Triple("あね", "姉", 7000),
            Triple("おにいさん", "お兄さん", 7000), Triple("おねえさん", "お姉さん", 7000),
            Triple("おとうと", "弟", 7000), Triple("いもうと", "妹", 7000),
            Triple("ともだち", "友達", 9000), Triple("かのじょ", "彼女", 8000),
            Triple("かれし", "彼氏", 8000), Triple("せんせい", "先生", 8000),
            Triple("がくせい", "学生", 7000), Triple("かいしゃいん", "会社員", 7000),
            // 挨拶・日常表現
            Triple("おはよう", "おはよう", 8000), Triple("おはようございます", "おはようございます", 8000),
            Triple("こんにちは", "こんにちは", 9000), Triple("こんばんは", "こんばんは", 8000),
            Triple("さようなら", "さようなら", 7000), Triple("じゃあ", "じゃあ", 8000),
            Triple("ありがとう", "ありがとう", 9000), Triple("ありがとうございます", "ありがとうございます", 9000),
            Triple("すみません", "すみません", 9000), Triple("ごめんなさい", "ごめんなさい", 8000),
            Triple("はい", "はい", 9000), Triple("いいえ", "いいえ", 8000),
            Triple("うん", "うん", 8000), Triple("ううん", "ううん", 6000),
            Triple("よろしく", "よろしく", 8000), Triple("よろしくおねがいします", "よろしくお願いします", 8000),
            Triple("おつかれさまでした", "お疲れ様でした", 8000),
            Triple("おつかれさま", "お疲れ様", 7000),
            Triple("おねがいします", "お願いします", 8000),
            Triple("おねがい", "お願い", 7000),
            Triple("なるほど", "なるほど", 7000), Triple("そうですか", "そうですか", 7000),
            Triple("わかりました", "わかりました", 8000), Triple("わかった", "わかった", 8000),
            Triple("しつれいします", "失礼します", 7000),
            // よく使う形容詞
            Triple("いい", "いい", 9000), Triple("よい", "良い", 8000),
            Triple("わるい", "悪い", 8000), Triple("おおきい", "大きい", 8000),
            Triple("ちいさい", "小さい", 8000), Triple("ながい", "長い", 8000),
            Triple("みじかい", "短い", 7000), Triple("たかい", "高い", 8000),
            Triple("やすい", "安い", 8000), Triple("ひくい", "低い", 7000),
            Triple("おもい", "重い", 7000), Triple("かるい", "軽い", 7000),
            Triple("あたらしい", "新しい", 8000), Triple("ふるい", "古い", 7000),
            Triple("はやい", "早い", 8000), Triple("はやい", "速い", 7000),
            Triple("おそい", "遅い", 7000), Triple("むずかしい", "難しい", 8000),
            Triple("やさしい", "優しい", 8000), Triple("やさしい", "易しい", 7000),
            Triple("たのしい", "楽しい", 8000), Triple("かなしい", "悲しい", 7000),
            Triple("うれしい", "嬉しい", 8000), Triple("つらい", "辛い", 7000),
            Triple("いたい", "痛い", 7000), Triple("あたたかい", "温かい", 7000),
            Triple("つめたい", "冷たい", 7000), Triple("あつい", "暑い", 7000),
            Triple("さむい", "寒い", 7000), Triple("あかい", "赤い", 7000),
            Triple("あおい", "青い", 7000), Triple("しろい", "白い", 7000),
            Triple("くろい", "黒い", 7000), Triple("すごい", "すごい", 8000),
            Triple("すごい", "凄い", 6000), Triple("かわいい", "可愛い", 8000),
            Triple("きれい", "きれい", 8000), Triple("きれい", "綺麗", 7000),
            Triple("おもしろい", "面白い", 8000), Triple("つまらない", "つまらない", 7000),
            Triple("ただしい", "正しい", 7000), Triple("まちがい", "間違い", 7000),
            Triple("たいせつ", "大切", 8000), Triple("だいじ", "大事", 8000),
            Triple("だめ", "ダメ", 8000), Triple("だめ", "駄目", 6000),
            Triple("むり", "無理", 8000),
            // よく使う副詞・接続詞
            Triple("とても", "とても", 9000), Triple("すごく", "すごく", 8000),
            Triple("すこし", "少し", 8000), Triple("ちょっと", "ちょっと", 9000),
            Triple("もっと", "もっと", 8000), Triple("もう", "もう", 9000),
            Triple("まだ", "まだ", 9000), Triple("すでに", "既に", 7000),
            Triple("もちろん", "もちろん", 8000), Triple("たぶん", "たぶん", 7000),
            Triple("たぶん", "多分", 7000), Triple("きっと", "きっと", 7000),
            Triple("ぜったい", "絶対", 8000), Triple("だいたい", "大体", 7000),
            Triple("ほとんど", "ほとんど", 7000), Triple("ぜんぜん", "全然", 8000),
            Triple("まったく", "全く", 7000), Triple("やはり", "やはり", 7000),
            Triple("やっぱり", "やっぱり", 8000), Triple("なぜ", "なぜ", 7000),
            Triple("どうして", "どうして", 7000), Triple("だから", "だから", 8000),
            Triple("でも", "でも", 9000), Triple("しかし", "しかし", 7000),
            Triple("けれど", "けれど", 7000), Triple("そして", "そして", 8000),
            Triple("それから", "それから", 7000), Triple("また", "また", 8000),
            Triple("あと", "あと", 8000), Triple("ところで", "ところで", 6000),
            Triple("じつは", "実は", 7000), Triple("ちなみに", "ちなみに", 7000),
            // 動詞
            Triple("いく", "行く", 9000), Triple("くる", "来る", 9000),
            Triple("かえる", "帰る", 8000), Triple("はいる", "入る", 8000),
            Triple("でる", "出る", 8000), Triple("あく", "開く", 7000),
            Triple("たつ", "立つ", 7000), Triple("すわる", "座る", 7000),
            Triple("ある", "ある", 9000), Triple("いる", "いる", 9000),
            Triple("する", "する", 9000), Triple("なる", "なる", 9000),
            Triple("おもう", "思う", 8000), Triple("かんがえる", "考える", 8000),
            Triple("わかる", "わかる", 9000), Triple("わかる", "分かる", 8000),
            Triple("しる", "知る", 8000), Triple("みる", "見る", 9000),
            Triple("きく", "聞く", 8000), Triple("よむ", "読む", 8000),
            Triple("かく", "書く", 8000), Triple("はなす", "話す", 8000),
            Triple("いう", "言う", 9000), Triple("たべる", "食べる", 8000),
            Triple("のむ", "飲む", 8000), Triple("ねる", "寝る", 8000),
            Triple("おきる", "起きる", 7000), Triple("あるく", "歩く", 7000),
            Triple("はしる", "走る", 7000), Triple("かう", "買う", 8000),
            Triple("つかう", "使う", 8000), Triple("つくる", "作る", 8000),
            Triple("もつ", "持つ", 7000), Triple("おく", "置く", 7000),
            Triple("とる", "取る", 7000), Triple("あそぶ", "遊ぶ", 7000),
            Triple("べんきょうする", "勉強する", 7000), Triple("でんわする", "電話する", 7000),
            Triple("おわる", "終わる", 7000), Triple("はじめる", "始める", 7000),
            Triple("できる", "できる", 9000), Triple("できる", "出来る", 7000),
            Triple("わすれる", "忘れる", 7000), Triple("かんじる", "感じる", 7000),
            // 名詞
            Triple("こと", "こと", 9000), Triple("こと", "事", 8000),
            Triple("もの", "もの", 9000), Triple("もの", "物", 8000),
            Triple("ところ", "ところ", 8000), Triple("とき", "時", 8000),
            Triple("きもち", "気持ち", 8000), Triple("こころ", "心", 8000),
            Triple("からだ", "体", 8000), Triple("あたま", "頭", 8000),
            Triple("て", "手", 8000), Triple("あし", "足", 8000),
            Triple("め", "目", 8000), Triple("かお", "顔", 7000),
            Triple("なまえ", "名前", 8000), Triple("ことば", "言葉", 8000),
            Triple("もんだい", "問題", 8000), Triple("こたえ", "答え", 7000),
            Triple("いみ", "意味", 8000), Triple("りゆう", "理由", 7000),
            Triple("はなし", "話", 8000), Triple("しごと", "仕事", 8000),
            Triple("べんきょう", "勉強", 7000), Triple("りょこう", "旅行", 7000),
            Triple("りょうり", "料理", 7000), Triple("おんがく", "音楽", 7000),
            Triple("えいが", "映画", 7000), Triple("ほん", "本", 8000),
            Triple("メール", "メール", 8000), Triple("でんわ", "電話", 8000),
            Triple("スマホ", "スマホ", 8000), Triple("アプリ", "アプリ", 8000),
            Triple("おかね", "お金", 9000), Triple("かいぎ", "会議", 7000),
            Triple("やすみ", "休み", 8000), Triple("てんき", "天気", 8000),
            Triple("あめ", "雨", 8000), Triple("ゆき", "雪", 7000),
            Triple("ごはん", "ご飯", 9000), Triple("たべもの", "食べ物", 7000),
            Triple("パン", "パン", 8000), Triple("コーヒー", "コーヒー", 8000),
            Triple("おちゃ", "お茶", 8000), Triple("ビール", "ビール", 7000),
            Triple("でんしゃ", "電車", 8000), Triple("くるま", "車", 8000),
            Triple("にほん", "日本", 9000), Triple("とうきょう", "東京", 8000),
            Triple("おおさか", "大阪", 7000), Triple("きょうと", "京都", 7000),
            Triple("せかい", "世界", 8000),
            Triple("しゃしん", "写真", 8000), Triple("どうが", "動画", 7000),
            Triple("かくにん", "確認", 8000), Triple("れんらく", "連絡", 8000),
            Triple("りょうかい", "了解", 8000), Triple("りょうかいです", "了解です", 7000),
            Triple("しょうち", "承知", 6000), Triple("すこし", "少し", 8000),
            Triple("たくさん", "たくさん", 8000), Triple("たくさん", "沢山", 6000)
        )

        for ((reading, surface, freq) in entries) {
            add(reading, surface, freq)
        }
    }
}
