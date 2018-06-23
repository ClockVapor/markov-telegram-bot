package clockvapor.markovtelegrambot

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.*

// Need to remain public for JSON writing
@Suppress("MemberVisibilityCanBePrivate")
class MarkovChain(val data: MutableMap<String, MutableMap<String, Int>>) {
    constructor() : this(mutableMapOf())

    fun generate(): List<String> {
        val result = mutableListOf<String>()
        var word = getWeightedRandomWord(EMPTY)
        while (word != null && word != EMPTY) {
            result += word
            word = getWeightedRandomWord(word)
        }
        return result
    }

    fun add(words: List<String>) {
        if (words.isNotEmpty()) {
            addPair(EMPTY, words.first())
            for (i in 0 until words.size - 1) {
                addPair(words[i], words[i + 1])
            }
            addPair(words.last(), EMPTY)
        }
    }

    fun remove(words: List<String>) {
        if (words.isNotEmpty()) {
            removePair(EMPTY, words.first())
            for (i in 0 until words.size - 1) {
                removePair(words[i], words[i + 1])
            }
            removePair(words.last(), EMPTY)
        }
    }

    private fun addPair(a: String, b: String) {
        data.getOrPut(a) { mutableMapOf() }.compute(b) { _, c -> c?.plus(1) ?: 1 }
    }

    private fun removePair(a: String, b: String) {
        data[a]?.let { wordMap ->
            wordMap.computeIfPresent(b) { _, count -> count - 1 }
            val c = wordMap[b]
            if (c != null && c <= 0) {
                wordMap -= b
                if (wordMap.isEmpty()) {
                    data -= a
                }
            }
        }
    }

    private fun getWeightedRandomWord(word: String): String? = data[word]?.let { wordMap ->
        val x = random.nextInt(wordMap.values.sum())
        var current = 0
        for ((w, count) in wordMap) {
            current += count
            if (x < current) {
                return w
            }
        }
        return null
    }

    fun write(path: String) {
        ObjectMapper().writeValue(File(path), this)
    }

    companion object {
        private const val EMPTY = ""
        private val random = Random()

        @Suppress("UNCHECKED_CAST")
        fun read(path: String): MarkovChain {
            val mapper = ObjectMapper()
            return mapper.readValue<MarkovChain>(File(path), MarkovChain::class.java)
        }
    }
}
