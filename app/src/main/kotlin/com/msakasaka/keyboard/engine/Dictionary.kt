package com.msakasaka.keyboard.engine

import android.content.Context
import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Dictionary(private val context: Context) {

    companion object {
        @Volatile
        private var instance: Dictionary? = null

        /** プロセス内で辞書を1つだけ共有（IME と設定プレビューで二重ロードを避ける） */
        fun get(context: Context): Dictionary =
            instance ?: synchronized(this) {
                instance ?: Dictionary(context.applicationContext).also { instance = it }
            }

        private const val BOS_EOS = 0      // 文頭・文末の品詞ID
        private const val UNK_ID = 1851    // 名詞,一般（ユーザー語・未知語の既定品詞）
    }

    /** surface=表記, cost=単語コスト(小さいほど高頻度), lid/rid=連接用の左右品詞ID */
    private class Entry(val surface: String, val cost: Int, val lid: Int, val rid: Int)

    private class Node(val cost: Int, val rid: Int, val surface: String, val start: Int, val prevIdx: Int)

    // reading -> entries
    private val index = HashMap<String, MutableList<Entry>>(1 shl 19)
    // lexicographically sorted keys for fast prefix range scan
    private var sortedKeys: Array<String> = emptyArray()
    private var loaded = false

    // mozc 連接コスト行列（connN x connN, 行=直前語のrid, 列=次語のlid）
    private var conn: ShortArray = ShortArray(0)
    private var connN = 0

    private val PREFIX_SCAN_CAP = 4000

    // 打ち間違い補正用のアルファベット
    private val HIRAGANA: CharArray = ('ぁ'..'ゖ').toList().toCharArray()
    private val LATIN: CharArray = ('a'..'z').toList().toCharArray()

    private fun hasKanji(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF }

    private val userPrefs: SharedPreferences
        get() = context.getSharedPreferences("user_dict", Context.MODE_PRIVATE)

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            loadFromAssets("japanese_dictionary.txt")
            loadFromAssets("english_dictionary.txt")
            loadConnection()
            loadUserData()
            sortedKeys = index.keys.toTypedArray()
            sortedKeys.sort()
            loaded = true
        }
    }

    private fun addEntry(reading: String, e: Entry) {
        index.getOrPut(reading) { ArrayList(2) }.add(e)
    }

    private fun trans(rid: Int, lid: Int): Int {
        if (connN == 0) return 0
        return conn[rid * connN + lid].toInt()
    }

    /** 候補選択時に呼ぶ。次回から優先表示・優先変換される */
    fun learn(reading: String, surface: String) {
        if (reading.isEmpty() || surface.isEmpty()) return
        val key = "l\t$reading\t$surface"
        val count = userPrefs.getInt(key, 0) + 1
        userPrefs.edit().putInt(key, count).apply()
        val cost = maxOf(1, 800 - count * 150)
        val list = index.getOrPut(reading) { ArrayList(2) }
        val idx = list.indexOfFirst { it.surface == surface }
        val e = Entry(surface, cost, UNK_ID, UNK_ID)
        if (idx >= 0) list[idx] = e else list.add(e)
    }

    /** ユーザー辞書に単語を追加 */
    fun addCustomWord(reading: String, surface: String) {
        if (reading.isEmpty() || surface.isEmpty()) return
        val key = "c\t$reading\t$surface"
        userPrefs.edit().putBoolean(key, true).apply()
        addEntry(reading, Entry(surface, 1, UNK_ID, UNK_ID))
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

    /** ひらがな読みが reading で始まるエントリを返す。コスト昇順、最大 limit 件 */
    fun lookup(reading: String, limit: Int = 60): List<String> {
        if (reading.isEmpty()) return emptyList()
        val isJa = reading[0].code in 0x3041..0x3096

        // 候補は (surface, score)。score は小さいほど上位
        val results = ArrayList<Pair<String, Int>>()

        // 完全一致（コスト -1000 ブースト）。日本語はかな同一表記をスキップ（読み自体は後段で追加）
        index[reading]?.forEach { e ->
            if (isJa && e.surface == reading) return@forEach
            results.add(e.surface to (e.cost - 1000))
        }

        // 前方一致：ソート済みキー配列を二分探索し prefix 範囲のみ走査
        val keys = sortedKeys
        var lo = 0; var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < reading) lo = mid + 1 else hi = mid
        }
        var ki = lo
        var visited = 0
        while (ki < keys.size && keys[ki].startsWith(reading) && visited < PREFIX_SCAN_CAP) {
            val key = keys[ki]
            if (key != reading) {
                val penalty = (key.length - reading.length) * 200
                index[key]?.forEach { e ->
                    if (isJa && e.surface == key) return@forEach
                    results.add(e.surface to (e.cost + penalty))
                }
                visited++
            }
            ki++
        }

        // 誤入力補正：有効な変換が無いときだけ編集距離1で補う
        val needFuzzy = if (isJa) results.none { hasKanji(it.first) }
                        else results.count { it.first != reading } < 3
        if (needFuzzy) results.addAll(fuzzyCorrections(reading, requireKanji = isJa))

        return results
            .sortedBy { it.second }
            .map { it.first }
            .distinct()
            .take(limit)
    }

    /**
     * 編集距離1の誤入力補正。reading の各位置を置換/挿入/削除/転置し、
     * 辞書に存在する変換語を集める。requireKanji=true なら漢字を含む変換のみ。
     */
    private fun fuzzyCorrections(reading: String, requireKanji: Boolean): List<Pair<String, Int>> {
        if (reading.length < 3 || reading.length > 16) return emptyList()
        val alphabet = when {
            reading[0].code in 0x3041..0x3096 -> HIRAGANA
            reading[0] in 'a'..'z' -> LATIN
            else -> return emptyList()
        }
        val out = HashMap<String, Int>()
        fun consider(variant: String, penalty: Int) {
            if (variant == reading || variant.isEmpty()) return
            index[variant]?.forEach { e ->
                if (requireKanji && !hasKanji(e.surface)) return@forEach
                val score = e.cost + penalty
                val prev = out[e.surface]
                if (prev == null || score < prev) out[e.surface] = score
            }
        }
        for (i in reading.indices) {
            val pre = reading.substring(0, i)
            val suf = reading.substring(i + 1)
            for (c in alphabet) if (c != reading[i]) consider(pre + c + suf, 2000)
        }
        for (i in 0..reading.length) {
            val pre = reading.substring(0, i)
            val suf = reading.substring(i)
            for (c in alphabet) consider(pre + c + suf, 2300)
        }
        for (i in reading.indices) consider(reading.removeRange(i, i + 1), 2500)
        for (i in 0 until reading.length - 1) {
            if (reading[i] == reading[i + 1]) continue
            val sb = StringBuilder(reading)
            sb[i] = reading[i + 1]; sb[i + 1] = reading[i]
            consider(sb.toString(), 2200)
        }
        return out.entries.sortedBy { it.value }.map { it.key to it.value }.take(8)
    }

    private fun loadFromAssets(fileName: String) {
        try {
            context.assets.open(fileName).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val parts = line.split('\t')
                    when (parts.size) {
                        5 -> { // reading surface cost lid rid （日本語）
                            val cost = parts[2].toIntOrNull() ?: continue
                            val lid = parts[3].toIntOrNull() ?: UNK_ID
                            val rid = parts[4].toIntOrNull() ?: UNK_ID
                            if (parts[0].isNotEmpty() && parts[1].isNotEmpty())
                                addEntry(parts[0], Entry(parts[1], cost, lid, rid))
                        }
                        3 -> { // word word freq （英語）
                            val freq = parts[2].toIntOrNull() ?: 100
                            if (parts[0].isNotEmpty() && parts[1].isNotEmpty())
                                addEntry(parts[0], Entry(parts[1], 10000 - freq, BOS_EOS, BOS_EOS))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadConnection() {
        try {
            val bytes = context.assets.open("connection.bin").use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val n = bb.int
            val size = n * n
            val arr = ShortArray(size)
            bb.asShortBuffer().get(arr)
            connN = n
            conn = arr
        } catch (_: Exception) {
            connN = 0
        }
    }

    private fun loadUserData() {
        userPrefs.all.forEach { (key, value) ->
            when {
                key.startsWith("l\t") -> {
                    val parts = key.removePrefix("l\t").split("\t")
                    if (parts.size == 2) {
                        val count = value as? Int ?: 1
                        addEntry(parts[0], Entry(parts[1], maxOf(1, 800 - count * 150), UNK_ID, UNK_ID))
                    }
                }
                key.startsWith("c\t") -> {
                    val parts = key.removePrefix("c\t").split("\t")
                    if (parts.size == 2) addEntry(parts[0], Entry(parts[1], 1, UNK_ID, UNK_ID))
                }
            }
        }
    }

    /**
     * 連接コスト付きラティス Viterbi による形態素解析（文節分割＋変換）。
     * 各文節について (読み, 候補表記リスト) を返す。先頭候補が最尤変換。
     */
    fun segment(input: String): List<Pair<String, List<String>>> {
        if (input.isEmpty()) return emptyList()
        val n = input.length
        val ending = Array(n + 1) { ArrayList<Node>() }
        ending[0].add(Node(0, BOS_EOS, "", -1, -1))

        for (end in 1..n) {
            val minStart = maxOf(0, end - 16)
            for (start in minStart until end) {
                val prev = ending[start]
                if (prev.isEmpty()) continue
                val sub = input.substring(start, end)
                val cands = index[sub]
                if (cands != null && cands.isNotEmpty()) {
                    for (e in cands) addNode(ending[end], prev, e.cost, e.lid, e.rid, e.surface, start)
                } else if (end - start == 1) {
                    addNode(ending[end], prev, 12000, UNK_ID, UNK_ID, sub, start)
                }
            }
        }

        val last = ending[n]
        if (last.isEmpty()) return listOf(input to listOf(input))

        var bestCost = Int.MAX_VALUE; var bestIdx = -1
        for (i in last.indices) {
            val c = last[i].cost + trans(last[i].rid, BOS_EOS)
            if (c < bestCost) { bestCost = c; bestIdx = i }
        }

        val spans = ArrayList<Pair<Int, Int>>()
        val surfaces = ArrayList<String>()
        var pos = n; var idx = bestIdx
        while (pos > 0) {
            val node = ending[pos][idx]
            spans.add(node.start to pos)
            surfaces.add(node.surface)
            pos = node.start; idx = node.prevIdx
        }
        spans.reverse(); surfaces.reverse()

        return spans.mapIndexed { i, span ->
            val reading = input.substring(span.first, span.second)
            val cands = LinkedHashSet<String>()
            cands.add(surfaces[i])
            index[reading]?.sortedBy { it.cost }?.forEach { cands.add(it.surface) }
            cands.add(reading)
            reading to cands.toList().take(30)
        }
    }

    private fun addNode(dst: ArrayList<Node>, prev: ArrayList<Node>, wc: Int, lid: Int, rid: Int, surface: String, start: Int) {
        var best = Int.MAX_VALUE; var bestPi = -1
        for (pi in prev.indices) {
            val p = prev[pi]
            val c = p.cost + trans(p.rid, lid) + wc
            if (c < best) { best = c; bestPi = pi }
        }
        if (bestPi >= 0) dst.add(Node(best, rid, surface, start, bestPi))
    }

    /** 入力全体の最尤変換文字列（COMPOSING 中のインライン変換候補用） */
    fun bestConversion(input: String): String =
        segment(input).joinToString("") { it.second.firstOrNull() ?: "" }
}
