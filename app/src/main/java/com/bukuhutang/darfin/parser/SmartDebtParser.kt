package com.bukuhutang.darfin.parser

import java.text.NumberFormat
import java.util.Locale

data class ParsedDebtResult(
    val raw: String,
    val type: DebtType,
    val name: String,
    val canonicalKey: String,
    val amount: Long,
    val note: String
)

enum class DebtType {
    DEBT,     // Tambah hutang
    PAYMENT,  // Cicil / bayar
    SETTLED;  // Lunas

    companion object {
        fun fromString(value: String?): DebtType {
            if (value.isNullOrBlank()) return DEBT
            return values().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: DEBT
        }
    }
}

object SmartDebtParser {

    /**
     * Normalizes contact name into canonical key.
     * Sulawesi prefix: "La tanto", "latanto", "tanto" -> "tanto"
     * Family distinction: "mama yesti" -> "mama_yesti", "bapak yesti" -> "bapak_yesti"
     */
    fun normalizeContactKey(rawName: String?): String {
        if (rawName.isNullOrBlank()) return ""
        var str = rawName.trim().lowercase(Locale.ROOT)

        // 1. Detect female family title
        val femaleRegex = Regex("^(mama|ibu|mak|mami|mbak|tante)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val femaleMatch = femaleRegex.find(str)
        if (femaleMatch != null) {
            val core = femaleMatch.groupValues[2].trim().replace("\\s+".toRegex(), "_")
            return "mama_$core"
        }

        // 2. Detect male family title
        val maleRegex = Regex("^(bapak|pak|papi|om|mas|bang)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val maleMatch = maleRegex.find(str)
        if (maleMatch != null) {
            val core = maleMatch.groupValues[2].trim().replace("\\s+".toRegex(), "_")
            return "bapak_$core"
        }

        // 3. Sulawesi honorifics: "La", "La Ode"
        if (Regex("^la\\s*ode\\s+", RegexOption.IGNORE_CASE).containsMatchIn(str)) {
            str = str.replace(Regex("^la\\s*ode\\s+", RegexOption.IGNORE_CASE), "")
        } else if (Regex("^laode\\s*", RegexOption.IGNORE_CASE).containsMatchIn(str)) {
            str = str.replace(Regex("^laode\\s*", RegexOption.IGNORE_CASE), "")
        } else if (Regex("^la\\s+", RegexOption.IGNORE_CASE).containsMatchIn(str)) {
            str = str.replace(Regex("^la\\s+", RegexOption.IGNORE_CASE), "")
        } else if (Regex("^la[a-z]{3,}", RegexOption.IGNORE_CASE).containsMatchIn(str)) {
            str = str.replace(Regex("^la", RegexOption.IGNORE_CASE), "")
        }

        return str.trim().replace("\\s+".toRegex(), "_")
    }

    fun parseRupiahAmount(amountStr: String?): Long {
        if (amountStr.isNullOrBlank()) return 0L
        val clean = amountStr.lowercase(Locale.ROOT)
            .replace(Regex("rp\\.?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",-\\s*$"), "")
            .trim()

        // 1. Suffix Millions (jt / juta)
        val jtMatch = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:jt|juta)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (jtMatch != null) {
            val numStr = jtMatch.groupValues[1].replace(',', '.')
            val d = numStr.toDoubleOrNull() ?: 0.0
            return (d * 1000000).toLong()
        }

        // 2. Suffix Thousands (rb / ribu / k)
        val rbMatch = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:rb|ribu|k)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (rbMatch != null) {
            val numStr = rbMatch.groupValues[1].replace(',', '.')
            val d = numStr.toDoubleOrNull() ?: 0.0
            return (d * 1000).toLong()
        }

        // 3. Dot-separated thousands format e.g. 50.000 or 1.500.000
        val dotMatch = Regex("\\b\\d{1,3}(?:\\.\\d{3})+\\b").find(clean)
        if (dotMatch != null) {
            val digits = dotMatch.value.replace(".", "")
            return digits.toLongOrNull() ?: 0L
        }

        // 4. Standalone numbers e.g. 50000 or shorthand (50 -> 50rb, 40 -> 40rb, 356 -> 356rb)
        val numberMatches = Regex("\\b\\d+\\b").findAll(clean).toList()
        if (numberMatches.isNotEmpty()) {
            val lastNumberStr = numberMatches.last().value
            val rawNum = lastNumberStr.toLongOrNull() ?: 0L
            return if (rawNum in 1..999) rawNum * 1000 else rawNum
        }

        return 0L
    }

    fun formatDisplayName(name: String): String {
        return name.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
    }

    fun formatRupiah(amount: Long): String {
        val format = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID"))
        return "Rp " + format.format(amount)
    }

    fun parse(text: String?): ParsedDebtResult? {
        if (text.isNullOrBlank()) return null
        val raw = text.trim()

        val isSettled = Regex("\\b(lunas|selesai|clear)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val isPayment = Regex("\\b(bayar|cicil|angsur|kurang)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)

        val type = when {
            isSettled -> DebtType.SETTLED
            isPayment -> DebtType.PAYMENT
            else -> DebtType.DEBT
        }

        if (isSettled && !Regex("\\d").containsMatchIn(raw)) {
            val clean = raw.replace(Regex("\\b(lunas|selesai|clear)\\b", RegexOption.IGNORE_CASE), "").trim()
            val words = clean.split("\\s+".toRegex()).filter { it.isNotBlank() }
            val namePart: String
            val notePart: String

            if (words.isEmpty()) {
                namePart = "Tanpa Nama"
                notePart = "Lunas"
            } else if (words.size == 1) {
                namePart = words[0]
                notePart = "Lunas"
            } else {
                val lower0 = words[0].lowercase(Locale.ROOT)
                val lower1 = words[1].lowercase(Locale.ROOT)
                val isTwoWordPrefix = (lower0 == "la" && lower1 == "ode")
                val isHonorificPrefix = lower0 in listOf("mama", "ibu", "mak", "mami", "tante", "bapak", "pak", "om", "mas", "bang", "kak", "la")

                if (isTwoWordPrefix && words.size >= 3) {
                    namePart = "${words[0]} ${words[1]} ${words[2]}"
                    notePart = words.drop(3).joinToString(" ").ifEmpty { "Lunas" }
                } else if (isHonorificPrefix && words.size >= 2) {
                    namePart = "${words[0]} ${words[1]}"
                    notePart = words.drop(2).joinToString(" ").ifEmpty { "Lunas" }
                } else {
                    namePart = words[0]
                    notePart = words.drop(1).joinToString(" ").ifEmpty { "Lunas" }
                }
            }

            return ParsedDebtResult(
                raw = raw,
                type = DebtType.SETTLED,
                name = formatDisplayName(namePart),
                canonicalKey = normalizeContactKey(namePart),
                amount = 0L,
                note = if (notePart == "Lunas") "Lunas" else "Lunas ($notePart)"
            )
        }

        // Amount pattern:
        // Priority 1: Explicit money pattern (with rp, rb/k/jt suffix, dot-separated thousands, or 4+ digits)
        val explicitMoneyRegex = Regex(
            "(?:rp\\.?\\s*)(\\d+(?:[.,]\\d+)?\\s*(?:jt|juta|rb|ribu|k)?)|" +
            "(\\d+(?:[.,]\\d+)?\\s*(?:jt|juta|rb|ribu|k))|" +
            "(\\d{1,3}(?:\\.\\d{3})+|\\d{4,})(?:\\s*,-)?",
            RegexOption.IGNORE_CASE
        )
        var match = explicitMoneyRegex.find(raw)

        // Priority 2: Standalone numbers (shorthand e.g. 50 -> 50rb, budi 40 -> budi 40rb, 356 -> 356rb)
        if (match == null) {
            val rawNumberRegex = Regex("\\b\\d+\\b")
            val allNumberMatches = rawNumberRegex.findAll(raw).toList()
            if (allNumberMatches.isNotEmpty()) {
                match = allNumberMatches.last()
            }
        }

        if (match == null) return null

        val rawAmountStr = match.value
        val amount = parseRupiahAmount(rawAmountStr)
        val matchIndex = match.range.first

        val before = raw.substring(0, matchIndex).trim()
        val after = raw.substring(match.range.last + 1).trim()

        val actionCleanRegex = Regex("\\b(bayar|cicil|angsur|lunas|utang|hutang)\\b", RegexOption.IGNORE_CASE)
        var namePart = before.replace(actionCleanRegex, "").trim()
        var notePart = after.replace(actionCleanRegex, "").trim()

        // If amount was at the end and 'before' contains quantity or item note (e.g. "budi 2 bungkus 40")
        if (notePart.isEmpty() && namePart.isNotEmpty()) {
            val words = namePart.split("\\s+".toRegex())
            var digitIdx = -1
            for (i in words.indices) {
                if (Regex("^\\d").containsMatchIn(words[i])) {
                    digitIdx = i
                    break
                }
            }
            if (digitIdx > 0) {
                namePart = words.subList(0, digitIdx).joinToString(" ")
                notePart = words.subList(digitIdx, words.size).joinToString(" ")
            }
        }

        if (namePart.isEmpty() && after.isNotEmpty()) {
            val parts = after.split("\\s+".toRegex())
            namePart = parts[0]
            notePart = parts.drop(1).joinToString(" ").trim()
        }

        if (namePart.isEmpty()) {
            namePart = "Tanpa Nama"
        }

        return ParsedDebtResult(
            raw = raw,
            type = type,
            name = formatDisplayName(namePart),
            canonicalKey = normalizeContactKey(namePart),
            amount = amount,
            note = if (notePart.isNotEmpty()) notePart else if (type == DebtType.PAYMENT) "Pembayaran" else ""
        )
    }
}
