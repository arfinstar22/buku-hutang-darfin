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
    SETTLED   // Lunas
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
        var clean = amountStr.lowercase(Locale.ROOT)
            .replace(Regex("rp\\.?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",-\\s*$"), "")
            .trim()

        if (Regex("(\\d+(?:[.,]\\d+)?)\\s*(jt|juta)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            val numStr = clean.replace(Regex("\\s*(jt|juta).*", RegexOption.IGNORE_CASE), "").replace(',', '.')
            val d = numStr.toDoubleOrNull() ?: 0.0
            return (d * 1000000).toLong()
        } else if (Regex("(\\d+(?:[.,]\\d+)?)\\s*(rb|ribu|k)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            val numStr = clean.replace(Regex("\\s*(rb|ribu|k).*", RegexOption.IGNORE_CASE), "").replace(',', '.')
            val d = numStr.toDoubleOrNull() ?: 0.0
            return (d * 1000).toLong()
        }

        val digitsOnly = clean.replace(Regex("[^0-9]"), "")
        return digitsOnly.toLongOrNull() ?: 0L
    }

    fun formatDisplayName(name: String): String {
        return name.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
    }

    fun formatRupiah(amount: Long): String {
        val format = NumberFormat.getNumberInstance(Locale("in", "ID"))
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
            val namePart = raw.replace(Regex("\\b(lunas|selesai|clear)\\b", RegexOption.IGNORE_CASE), "").trim()
            return ParsedDebtResult(
                raw = raw,
                type = DebtType.SETTLED,
                name = formatDisplayName(namePart),
                canonicalKey = normalizeContactKey(namePart),
                amount = 0L,
                note = "Lunas"
            )
        }

        // Amount pattern
        val moneyRegex = Regex("(?:rp\\.?\\s*)?(\\d+(?:[.,]\\d+)?\\s*(?:jt|juta|rb|ribu|k)|(?:\\d{1,3}(?:\\.\\d{3})+|\\d{4,}))(?:\\s*,-)?", RegexOption.IGNORE_CASE)
        val match = moneyRegex.find(raw) ?: return null

        val rawAmountStr = match.value
        val amount = parseRupiahAmount(rawAmountStr)
        val matchIndex = match.range.first

        val before = raw.substring(0, matchIndex).trim()
        val after = raw.substring(match.range.last + 1).trim()

        val actionCleanRegex = Regex("\\b(bayar|cicil|angsur|lunas|utang|hutang)\\b", RegexOption.IGNORE_CASE)
        var namePart = before.replace(actionCleanRegex, "").trim()
        var notePart = after.replace(actionCleanRegex, "").trim()

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
