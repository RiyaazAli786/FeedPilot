package com.feedpilot.client.common

import java.text.Normalizer
import kotlin.random.Random

data class GeneratedIdentity(
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val username: String
)

object BrazilUsernameGenerator {

    var firstNames: List<String> = listOf(
        "Caio", "Lucas", "Enzo", "Kauan", "Vini",
        "Pedro", "Arthur", "Bruno", "Rafael", "Murilo",
        "Gabriel", "Matheus", "Felipe", "Thiago", "Diego",
        "Livia", "Sofia", "Isabela", "Camila", "Larissa"
    )
        private set

    var lastNames: List<String> = listOf(
        "Almeida", "Santos", "Oliveira", "Costa", "Mendes",
        "Rocha", "Lima", "Martins", "Souza", "Ferreira",
        "Ribeiro", "Carvalho", "Gomes", "Barros", "Teixeira"
    )
        private set

    var suffixes: List<String> = listOf(
        "zenn", "vex", "vyn", "zaro", "zyn", "vexo", "xyn", "zov", "rix", "zen",
        "varo", "nex", "zian", "voro", "xen", "rivo", "zelo", "vian", "nox", "zavi",
        "zuno", "ryx", "zayn", "viro", "xaro", "zeno", "vynx", "rizo"
    )
        private set

    private val nicheWords = listOf(
        "official", "real", "oficial", "dev", "tech", "style", "fit", "vlog",
        "pixels", "art", "design", "vibe", "studio", "mode", "lab", "pro"
    )

    fun updateDataset(newFirstNames: List<String>?, newLastNames: List<String>?, newSuffixes: List<String>?) {
        if (!newFirstNames.isNullOrEmpty()) firstNames = newFirstNames
        if (!newLastNames.isNullOrEmpty()) lastNames = newLastNames
        if (!newSuffixes.isNullOrEmpty()) suffixes = newSuffixes
    }

    private fun stripAccents(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    /**
     * Enhanced 22-Formula Instagram Username Generator for generating well-defined, realistic handles:
     */
    fun generate(): GeneratedIdentity {
        val first = firstNames.randomOrNull() ?: "Caio"
        val last = lastNames.randomOrNull() ?: "Almeida"
        val suffix = suffixes.randomOrNull() ?: "zenn"
        val niche = nicheWords.random()
        val num = Random.nextInt(10, 99)
        val formatId = Random.nextInt(1, 23)

        val rawUsername = when (formatId) {
            1 -> first + suffix
            2 -> first + suffix.take(2)
            3 -> first + "x" + suffix
            4 -> first + first.takeLast(1).repeat(2)
            5 -> first + "vexo"
            6 -> first.take(maxOf(3, first.length - 2)) + suffix
            7 -> "$first.$last"
            8 -> "${first}_$last"
            9 -> first + "zaro"
            10 -> first + last.take(4)
            11 -> "real_${first}_$last"
            12 -> "iam_${first}.$last"
            13 -> "the_${first}$suffix"
            14 -> "${first}_$niche"
            15 -> "${first}.$niche"
            16 -> "$first${last.take(1).lowercase()}$num"
            17 -> "${first}_${suffix}$num"
            18 -> "$first.${last}_"
            19 -> "its_${first}$suffix"
            20 -> "${first}_${last.take(4)}_$suffix"
            21 -> "${first}${first.takeLast(1)}$suffix"
            22 -> "official_$first"
            else -> "$first.$last"
        }

        val cleanUsername = stripAccents(rawUsername)
            .lowercase()
            .replace(" ", "")

        return GeneratedIdentity(
            firstName = first,
            lastName = last,
            fullName = "$first $last",
            username = cleanUsername
        )
    }

    /**
     * Generates a rich set of 10-12 well-defined suggested usernames derived from a seed handle.
     */
    fun generateSuggestionsForSeed(seed: String): List<String> {
        val base = stripAccents(seed.trim()).lowercase().replace(" ", "").replace("@", "")
        if (base.isBlank()) return emptyList()

        val results = LinkedHashSet<String>()
        val rndSuf1 = suffixes.random()
        val rndSuf2 = suffixes.random()
        val rndNiche1 = nicheWords.random()
        val rndNiche2 = nicheWords.random()
        val num = Random.nextInt(10, 99)

        results.add("${base}_$rndSuf1")
        results.add("${base}.$rndSuf1")
        results.add("${base}$rndSuf2")
        results.add("real_${base}")
        results.add("iam_${base}")
        results.add("the_${base}")
        results.add("${base}_$rndNiche1")
        results.add("${base}.$rndNiche2")
        results.add("${base}_$num")
        results.add("official_${base}")
        results.add("${base}x_$rndSuf1")
        results.add("${base}.$num")

        return results.take(10).toList()
    }
}
