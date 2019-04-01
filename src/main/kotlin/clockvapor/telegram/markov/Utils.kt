package clockvapor.telegram.markov

import clockvapor.markov.MarkovChain

val MarkovChain.wordCounts: Map<String, Int>
    get() {
        val map = hashMapOf<String, Int>()
        for ((_, dataMap) in data) {
            for ((word, count) in dataMap) {
                val sanitized = word.sanitize()
                if (sanitized.isNotBlank()) {
                    map.compute(sanitized.toLowerCase()) { _, n -> n?.plus(count) ?: count }
                }
            }
        }
        return map
    }

fun scoreMostDistinguishingWords(user: Map<String, Int>, universe: Map<String, Int>): Map<String, Double> {
    val scores = linkedMapOf<String, Double>()
    val userTotal = user.values.sum()
    val universeTotal = universe.values.sum()
    for ((word, count) in user) {
        scores[word] = Math.pow(count.toDouble(), 1.1) / userTotal / (universe.getValue(word).toDouble() / universeTotal)
    }
    return scores.toList().sortedWith(Comparator { a, b ->
        val c = b.second.compareTo(a.second)
        if (c == 0)
            a.first.compareTo(b.first)
        else
            c
    }).toMap()
}

fun computeUniverse(wordCountsCollection: Collection<Map<String, Int>>): Map<String, Int> {
    val universe = hashMapOf<String, Int>()
    for (wordCounts in wordCountsCollection) {
        for ((word, count) in wordCounts) {
            universe.compute(word) { _, n -> n?.plus(count) ?: count }
        }
    }
    return universe
}

private const val punctuation = "[`~!@#$%^&*()\\-_=+\\[\\],<.>/?\\\\|;:\"]+"
private fun String.sanitize(): String =
    replace("“", "\"").replace("”", "\"").replace("‘", "'").replace("’", "'")
        .replace(Regex("^$punctuation"), "")
        .replace(Regex("$punctuation$"), "")

