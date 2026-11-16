package lk.smsexpense

/** One money movement found in a bank SMS. */
data class Txn(
    val sender: String,
    val body: String,
    val date: Long,
    val amount: Double,
    val currency: String,
    val isDebit: Boolean,
    val place: String
)

/**
 * Turns bank SMS text into transactions. Works on wording, not on a fixed
 * template, so it handles most Sri Lankan banks (BOC, Commercial, Sampath,
 * HNB, NDB, People's, Seylan, DFCC, NTB/Amex...) and most card alerts.
 */
object SmsParser {

    private const val CURR = "LKR|SLR|Rs\\.?|USD|US\\\$|\\\$|EUR|GBP|INR|AUD|SGD|AED"
    private const val NUM = "[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?"

    // "LKR 2,500.00", "Rs.500", "USD 12.99"
    private val amtBefore = Regex("(?i)(?<![A-Za-z])($CURR)\\s*[:.]?\\s*($NUM)")
    // "2,500.00 LKR"
    private val amtAfter = Regex("(?i)(?<![0-9.,])($NUM)\\s*(LKR|USD|EUR|GBP|INR|AUD|SGD|AED)\\b")

    private val debitRx = Regex(
        "(?i)\\b(debited|withdrawn|withdrawal|spent|purchased?|purchases|paid|payment|charged|" +
            "deducted|pos|debit of|(?:transaction|txn|trx) (?:of|at|for)|used (?:for|at)|transfer(?:red)? to|sent to|dr)\\b"
    )
    private val creditRx = Regex(
        "(?i)\\b(credited|deposited|deposit|received|refund(?:ed)?|reversal|reversed|" +
            "transfer(?:red)? from|cr)\\b"
    )

    // Messages that mention money but are not a completed transaction.
    private val skipRx = Regex(
        "(?i)\\b(otp|one[- ]time|password|pin|verification code|will be debited|due date|" +
            "minimum payment|min\\.? ?(?:amt|amount|payment)|statement|declined|unsuccessful|" +
            "failed|insufficient|reject(?:ed)?|request(?:ed)? for)\\b"
    )

    private val accountWords = Regex("(?i)(a/c|acc\\b|acct|account|card|bank)")
    private val phoneSender = Regex("^[+0-9 ]+$")
    private val balanceWords = listOf("bal", "limit", "avl", "available", "outstanding", "o/s")

    private val placeRx = Regex(
        "(?i)(?:\\bat|\\bto|@|merchant[:\\s]+)\\s*([A-Za-z0-9][A-Za-z0-9&'*._ -]{1,40}?)" +
            "(?=\\s+(?:on|for|ref|via|from|using|with|dated|date|avl|available|bal|is|was)\\b" +
            "|\\s+\\d{1,2}[/-]|[,;:]|\\.\\s|\\.$|$)"
    )

    fun parse(sender: String, body: String, date: Long): Txn? {
        val text = body.replace('\n', ' ')
        if (skipRx.containsMatchIn(text)) return null
        if (phoneSender.matches(sender) && !accountWords.containsMatchIn(text)) return null

        val d = debitRx.find(text)
        val c = creditRx.find(text)
        val isDebit = when {
            d == null && c == null -> return null
            d == null -> false
            c == null -> true
            else -> d!!.range.first <= c!!.range.first
        }
        val keyPos = (if (isDebit) d else c)!!.range.first

        // Every amount in the message, minus the ones that are balances or limits.
        data class Hit(val pos: Int, val value: Double, val cur: String)
        val hits = mutableListOf<Hit>()
        for (m in amtBefore.findAll(text)) {
            toNumber(m.groupValues[2])?.let { hits += Hit(m.range.first, it, m.groupValues[1]) }
        }
        for (m in amtAfter.findAll(text)) {
            toNumber(m.groupValues[1])?.let { hits += Hit(m.range.first, it, m.groupValues[2]) }
        }
        val good = hits.filter { h ->
            val before = text.substring(maxOf(0, h.pos - 30), h.pos).lowercase()
            h.value > 0 && balanceWords.none { before.contains(it) }
        }
        val best = good.minByOrNull { Math.abs(it.pos - keyPos) } ?: return null

        return Txn(
            sender = sender,
            body = body,
            date = date,
            amount = best.value,
            currency = normalizeCurrency(best.cur),
            isDebit = isDebit,
            place = findPlace(text) ?: sender
        )
    }

    private fun toNumber(s: String): Double? = s.replace(",", "").toDoubleOrNull()

    private fun normalizeCurrency(c: String): String {
        val u = c.uppercase().trimEnd('.')
        return when (u) {
            "RS", "SLR" -> "LKR"
            "$", "US$" -> "USD"
            else -> u
        }
    }

    private fun findPlace(text: String): String? {
        for (m in placeRx.findAll(text)) {
            val p = m.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val low = p.lowercase()
            if (p.length < 2) continue
            if (p.all { it.isDigit() || it == ' ' || it == '.' || it == '-' }) continue
            if (low.startsWith("a/c") || low.startsWith("acc") || low.startsWith("your")) continue
            if (low.startsWith("lkr") || low.startsWith("rs")) continue
            return p.take(32)
        }
        return null
    }
}
