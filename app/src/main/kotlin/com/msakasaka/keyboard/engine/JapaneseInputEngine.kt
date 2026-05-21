package com.msakasaka.keyboard.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class InputMode { JAPANESE, ENGLISH }
enum class InputState { COMPOSING, CONVERTING, IDLE }

data class EngineSnapshot(
    val composing: String,
    val state: InputState,
    val candidates: List<String>,
    val selectedCandidateIndex: Int
)

class JapaneseInputEngine(private val dictionary: Dictionary) {

    var mode: InputMode = InputMode.JAPANESE
        private set

    private var _composing = StringBuilder()
    private var _state = InputState.IDLE
    private var _candidates = listOf<String>()
    private var _selectedIndex = 0

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
        if (_state == InputState.CONVERTING) cancelConversion()
        _composing.append(char)
        _state = InputState.COMPOSING
        _candidates = emptyList()
        notifyChanged()
        triggerPredictiveLookup()
    }

    /** 直前の1文字に小/゛を適用 */
    fun applyModifierToLast() {
        if (_composing.isEmpty()) return
        if (_state == InputState.CONVERTING) cancelConversion()
        val last = _composing.last().toString()
        val modified = FlickCharTable.applyModifier(last)
        if (modified != null) {
            _composing.deleteCharAt(_composing.length - 1)
            _composing.append(modified)
            notifyChanged()
        }
    }

    fun backspace(): Boolean {
        return when {
            _state == InputState.CONVERTING -> {
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
            val fromDict = dictionary.lookup(reading)
            _candidates = buildCandidateList(reading, fromDict)
            _selectedIndex = 0
            _state = InputState.CONVERTING
            notifyChanged()
        }
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
        _candidates = emptyList()
        _selectedIndex = 0
        notifyChanged()
    }

    fun reset() {
        _composing.clear()
        _state = InputState.IDLE
        _candidates = emptyList()
        _selectedIndex = 0
        notifyChanged()
    }

    private fun triggerPredictiveLookup() {
        val capturedReading = _composing.toString()
        scope.launch {
            dictionary.ensureLoaded()
            if (_state == InputState.COMPOSING && _composing.toString() == capturedReading) {
                val fromDict = dictionary.lookup(capturedReading)
                _candidates = buildCandidateList(capturedReading, fromDict)
                notifyChanged()
            }
        }
    }

    private fun buildCandidateList(reading: String, dictResults: List<String>): List<String> {
        val result = mutableListOf<String>()
        result.addAll(dictResults)
        if (!result.contains(reading)) result.add(reading)
        // カタカナ変換を追加
        val katakana = toKatakana(reading)
        if (katakana != reading && !result.contains(katakana)) result.add(katakana)
        return result
    }

    private fun toKatakana(hiragana: String): String {
        return buildString {
            for (ch in hiragana) {
                val code = ch.code
                if (code in 0x3041..0x3096) {
                    append((code + 0x60).toChar())
                } else {
                    append(ch)
                }
            }
        }
    }

    private fun notifyChanged() {
        onStateChanged?.invoke(
            EngineSnapshot(_composing.toString(), _state, _candidates, _selectedIndex)
        )
    }
}
