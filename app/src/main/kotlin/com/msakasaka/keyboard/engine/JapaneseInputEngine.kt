package com.msakasaka.keyboard.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InputMode { JAPANESE, ENGLISH }
enum class InputState { COMPOSING, CONVERTING, SEGMENTED, IDLE }

private data class SegmentState(
    val reading: String,
    val candidates: List<String>,
    var selectedIndex: Int = 0
) {
    val current: String get() = candidates.getOrElse(selectedIndex) { reading }
}

data class EngineSnapshot(
    val composing: String,
    val state: InputState,
    val candidates: List<String>,
    val selectedCandidateIndex: Int,
    val convertedText: String = ""
) {
    val currentCandidate: String get() = when (state) {
        InputState.CONVERTING ->
            if (candidates.isNotEmpty() && selectedCandidateIndex < candidates.size)
                candidates[selectedCandidateIndex]
            else composing
        InputState.SEGMENTED -> convertedText
        else -> composing
    }
}

class JapaneseInputEngine(private val dictionary: Dictionary) {

    var mode: InputMode = InputMode.JAPANESE
        private set

    private var _composing = StringBuilder()
    private var _state = InputState.IDLE
    private var _candidates = listOf<String>()
    private var _selectedIndex = 0
    private var _segments: List<SegmentState> = emptyList()
    private var _focusSegment: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var onStateChanged: ((EngineSnapshot) -> Unit)? = null

    val composing: String get() = _composing.toString()
    val state: InputState get() = _state
    val candidates: List<String> get() = _candidates
    val selectedIndex: Int get() = _selectedIndex

    fun toggleMode() {
        mode = if (mode == InputMode.JAPANESE) InputMode.ENGLISH else InputMode.JAPANESE
        reset()
    }

    fun setMode(newMode: InputMode) {
        mode = newMode
        reset()
    }

    fun appendChar(char: String) {
        if (_state == InputState.CONVERTING || _state == InputState.SEGMENTED) cancelConversion()
        _composing.append(char)
        _state = InputState.COMPOSING
        notifyChanged()
        triggerPredictiveLookup()
    }

    fun applyModifierToLast() {
        if (_composing.isEmpty()) return
        if (_state == InputState.CONVERTING || _state == InputState.SEGMENTED) cancelConversion()
        val last = _composing.last().toString()
        val modified = FlickCharTable.applyModifier(last)
        if (modified != null) {
            _composing.deleteCharAt(_composing.length - 1)
            _composing.append(modified)
            notifyChanged()
            triggerPredictiveLookup()
        }
    }

    fun backspace(): Boolean {
        return when {
            _state == InputState.CONVERTING || _state == InputState.SEGMENTED -> {
                cancelConversion()
                true
            }
            _composing.isNotEmpty() -> {
                _composing.deleteCharAt(_composing.length - 1)
                if (_composing.isEmpty()) {
                    _state = InputState.IDLE
                    _candidates = emptyList()
                } else {
                    triggerPredictiveLookup()
                }
                notifyChanged()
                true
            }
            else -> false
        }
    }

    fun startConversion() {
        if (_composing.isEmpty()) return
        scope.launch {
            dictionary.ensureLoaded()
            val reading = _composing.toString()
            val segs = dictionary.segment(reading)
            if (segs.isEmpty()) {
                val fromDict = dictionary.lookup(reading)
                _candidates = buildCandidateList(reading, fromDict)
                _selectedIndex = 0
                _state = InputState.CONVERTING
                notifyChanged()
                return@launch
            }
            _segments = segs.map { (r, cands) -> SegmentState(r, cands) }
            _focusSegment = 0
            _state = InputState.SEGMENTED
            updateFromFocusedSegment()
            notifyChanged()
        }
    }

    private fun updateFromFocusedSegment() {
        val seg = _segments.getOrNull(_focusSegment)
        _candidates = seg?.candidates ?: emptyList()
        _selectedIndex = seg?.selectedIndex ?: 0
    }

    fun getConvertedText(): String = _segments.joinToString("") { it.current }

    fun nextSegment(): Boolean {
        if (_state != InputState.SEGMENTED) return false
        if (_focusSegment >= _segments.size - 1) return false
        _focusSegment++
        updateFromFocusedSegment()
        notifyChanged()
        return true
    }

    fun selectSegmentCandidate(index: Int) {
        if (_state != InputState.SEGMENTED) return
        val seg = _segments.getOrNull(_focusSegment) ?: return
        seg.selectedIndex = index.coerceIn(0, seg.candidates.size - 1)
        _selectedIndex = seg.selectedIndex
        notifyChanged()
    }

    fun commitAllSegments(): String {
        val text = getConvertedText()
        for (seg in _segments) dictionary.learn(seg.reading, seg.current)
        reset()
        return text
    }

    fun selectCandidate(index: Int): String {
        val list = _candidates
        if (index < 0 || index >= list.size) return _composing.toString()
        val selected = list[index]
        val reading = _composing.toString()
        reset()
        dictionary.learn(reading, selected)
        return selected
    }

    fun getCurrentCandidate(): String {
        return if (_candidates.isNotEmpty() && _selectedIndex < _candidates.size) {
            _candidates[_selectedIndex]
        } else {
            _composing.toString()
        }
    }

    fun nextCandidate() {
        if (_state == InputState.SEGMENTED) {
            val seg = _segments.getOrNull(_focusSegment) ?: return
            seg.selectedIndex = (seg.selectedIndex + 1) % seg.candidates.size
            _selectedIndex = seg.selectedIndex
            notifyChanged()
            return
        }
        if (_candidates.isEmpty()) return
        _selectedIndex = (_selectedIndex + 1) % _candidates.size
        notifyChanged()
    }

    fun commitComposing(): String {
        val text = _composing.toString()
        reset()
        return text
    }

    fun cancelConversion() {
        _state = InputState.COMPOSING
        _segments = emptyList()
        _focusSegment = 0
        _candidates = emptyList()
        _selectedIndex = 0
        notifyChanged()
    }

    fun reset() {
        _composing.clear()
        _state = InputState.IDLE
        _candidates = emptyList()
        _selectedIndex = 0
        _segments = emptyList()
        _focusSegment = 0
        notifyChanged()
    }

    private fun triggerPredictiveLookup() {
        val capturedReading = _composing.toString()
        scope.launch {
            dictionary.ensureLoaded()
            if (_state != InputState.COMPOSING || _composing.toString() != capturedReading) return@launch
            val lookupKey = if (mode == InputMode.ENGLISH) capturedReading.lowercase() else capturedReading
            val fromDict = withContext(Dispatchers.Default) { dictionary.lookup(lookupKey) }
            if (_state == InputState.COMPOSING && _composing.toString() == capturedReading) {
                _candidates = buildCandidateList(capturedReading, fromDict)
                notifyChanged()
            }
        }
    }

    private fun buildCandidateList(reading: String, dictResults: List<String>): List<String> {
        val result = mutableListOf<String>()
        val applyCap = mode == InputMode.ENGLISH && reading.isNotEmpty() && reading[0].isUpperCase()
        val adapted = if (applyCap) {
            dictResults.map { if (it.isNotEmpty() && it[0].isLowerCase()) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
        } else dictResults
        result.addAll(adapted)
        if (!result.contains(reading)) result.add(reading)
        if (mode == InputMode.JAPANESE) {
            val katakana = toKatakana(reading)
            if (katakana != reading && !result.contains(katakana)) result.add(katakana)
        }
        return result.distinct()
    }

    private fun toKatakana(hiragana: String): String {
        return buildString {
            for (ch in hiragana) {
                val code = ch.code
                if (code in 0x3041..0x3096) append((code + 0x60).toChar()) else append(ch)
            }
        }
    }

    private fun notifyChanged() {
        val converted = if (_state == InputState.SEGMENTED) getConvertedText() else ""
        onStateChanged?.invoke(
            EngineSnapshot(_composing.toString(), _state, _candidates, _selectedIndex, converted)
        )
    }
}
