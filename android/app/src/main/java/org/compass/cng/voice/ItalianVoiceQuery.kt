package org.compass.cng.voice

import java.text.Normalizer
import java.util.Locale

/** Rule-based and conservative: preserve proper names; no fuzzy automatic destination selection. */
object ItalianVoiceQuery {
    // ASR can separate the enclitic pronoun: "porta mi", "accompagna mi", "mostra mi".
    // Require a full command word so names such as Porta Milano remain unchanged.
    private val commands = Regex("^(?:per favore\\s+)?(?:porta\\s*mi|accompagna\\s*mi|mostra\\s*mi|guida\\s*mi|(?:vorrei|voglio)\\s+andare|avvia\\s+(?:la\\s+)?navigazione|raggiungi|naviga|vai|andiamo|cerca|trova)\\b\\s*", RegexOption.IGNORE_CASE)
    private val preposition = Regex("^(?:(?:fino\\s+)?(?:alla|alle|allo|agli|al|ai|a|in|verso|dalla|dalle|dallo|dagli|dal|dai|da)\\s+|(?:all|dall)['’]\\s*|(?:il|lo|la|i|gli|le|un|una)\\s+)", RegexOption.IGNORE_CASE)
    private val ones = listOf("zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove",
        "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove")
    private val numbers = buildMap {
        ones.forEachIndexed { i, word -> put(word, i) }
        listOf("venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta").forEachIndexed { i, ten ->
            put(ten, (i + 2) * 10)
            for (n in 1..9) put((if (n == 1 || n == 8) ten.dropLast(1) else ten) + ones[n], (i + 2) * 10 + n)
        }
        put("cento", 100)
    }
    fun normalize(raw: String): String {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()
            .trim(' ', '.', '!', '?', '"')
        val hasCommand = commands.containsMatchIn(text)
        text = text.replaceFirst(commands, "")
        if (hasCommand) text = text.replaceFirst(preposition, "").trim()
        text = text.replace(Regex("\\b(?:numero civico|civico|numero)\\s+([\\p{L}\\d]+(?:\\s+[\\p{L}\\d]+){0,5})", RegexOption.IGNORE_CASE)) {
            val words = it.groupValues[1].split(' ')
            val count = (words.size downTo 1).firstOrNull { n -> civic(words.take(n).joinToString("")) != null }
            if (count == null) it.value else ", ${civic(words.take(count).joinToString(""))}" +
                words.drop(count).joinToString(" ").let { tail -> if (tail.isEmpty()) "" else " $tail" }
        }
        text = text.replace(Regex("\\s+,"), ",").replace(Regex(",\\s*,"), ",")
        // Normalize street markers without rewriting names such as Via Quattro Novembre.
        text = text.replace(Regex("^viale\\s+", RegexOption.IGNORE_CASE), "Viale ")
            .replace(Regex("^piazza\\s+", RegexOption.IGNORE_CASE), "Piazza ")
            .replace(Regex("^via\\s+", RegexOption.IGNORE_CASE), "Via ")
        return text.trim().take(200)
    }
    internal fun civic(value: String): Int? {
        val word = key(value)
        word.toIntOrNull()?.let { return it.takeIf { n -> n in 0..9999 } }
        numbers[word]?.let { return it }
        if (word.startsWith("mille")) return if (word == "mille") 1000 else
            civic(word.removePrefix("mille"))?.takeIf { it < 1000 }?.plus(1000)
        val thousands = word.indexOf("mila")
        if (thousands > 0) {
            val n = numbers[word.substring(0, thousands)]?.takeIf { it in 2..9 } ?: return null
            val rest = word.substring(thousands + 4)
            return if (rest.isEmpty()) n * 1000 else civic(rest)?.takeIf { it < 1000 }?.plus(n * 1000)
        }
        val hundred = word.indexOf("cent")
        if (hundred >= 0) {
            val n = if (hundred == 0) 1 else numbers[word.substring(0, hundred)]?.takeIf { it in 2..9 } ?: return null
            val tail = word.substring(hundred + 4)
            for (rest in listOf(tail.removePrefix("o"), tail)) {
                if (rest.isEmpty()) return n * 100
                numbers[rest]?.takeIf { it < 100 }?.let { return n * 100 + it }
            }
        }
        return null
    }
    fun key(text: String): String = Normalizer.normalize(text.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('’', '\'').replace(Regex("\\s+"), " ").trim()
    fun isCng(query: String): Boolean = key(query).let {
        Regex("\\b(metano|cng)\\b").containsMatchIn(it) &&
            (Regex("\\b(distributore|distributori|rifornimento|rifornire|stazione|stazioni|fare il pieno)\\b").containsMatchIn(it) ||
                it in setOf("metano", "cng"))
    }
}
