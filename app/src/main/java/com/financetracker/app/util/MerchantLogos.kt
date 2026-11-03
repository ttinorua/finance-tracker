package com.financetracker.app.util

import org.json.JSONObject

/**
 * Turns a transaction's free-text description (e.g. "Dankort-køb NETTO 1234 Aarhus C") into the
 * company's website domain, so the UI can show that company's logo. Transactions carry no
 * merchant ID, so this is all text matching: a built-in list of common merchants first, and a
 * normalised [merchantKey] that the AI lookup can cache its answer under for everything else.
 */
object MerchantLogos {
    // Compiled once, and declared first: KNOWN below already uses normalize while it's being
    // built, and this runs for every transaction row and on every search keystroke.
    private val DIGITS = Regex("\\p{N}+")

    /** A merchant in the built-in list. [name] is how it's shown in the transaction list; null
     * keeps the bank's text (MobilePay and PayPal: the text names the person or shop paid). */
    private data class Known(val keyword: String, val domain: String, val name: String?)

    /** Keyword → domain and name. Keywords are matched as whole words against [normalize]d text, in this
     * order, so specific names come before generic ones (MobilePay last: "MobilePay Netto" is Netto). */
    private val KNOWN: List<Known> = listOf(
        Known("netto", "netto.dk", "Netto"),
        Known("rema 1000", "rema1000.dk", "REMA 1000"),
        Known("føtex", "foetex.dk", "Føtex"),
        Known("foetex", "foetex.dk", "Føtex"),
        Known("bilka", "bilka.dk", "Bilka"),
        Known("salling", "salling.dk", "Salling"),
        Known("lidl", "lidl.dk", "Lidl"),
        Known("aldi", "aldi-nord.de", "Aldi"),
        Known("superbrugsen", "superbrugsen.dk", "SuperBrugsen"),
        Known("dagli brugsen", "dagli-brugsen.dk", "Dagli'Brugsen"),
        Known("kvickly", "kvickly.dk", "Kvickly"),
        Known("irma", "irma.dk", "Irma"),
        Known("coop", "coop.dk", "Coop"),
        Known("meny", "meny.dk", "Meny"),
        Known("spar", "spar.dk", "Spar"),
        Known("løvbjerg", "lovbjerg.dk", "Løvbjerg"),
        Known("7 eleven", "7-eleven.dk", "7-Eleven"),
        Known("circle k", "circlek.dk", "Circle K"),
        Known("q8", "q8.dk", "Q8"),
        Known("shell", "shell.dk", "Shell"),
        Known("ingo", "ingo.dk", "INGO"),
        Known("matas", "www.matas.dk", "Matas"),
        Known("jysk", "jysk.dk", "JYSK"),
        Known("ikea", "ikea.com", "IKEA"),
        Known("elgiganten", "elgiganten.dk", "Elgiganten"),
        Known("bauhaus", "bauhaus.dk", "Bauhaus"),
        Known("silvan", "silvan.dk", "Silvan"),
        Known("harald nyborg", "harald-nyborg.dk", "Harald Nyborg"),
        Known("flying tiger", "flyingtiger.com", "Flying Tiger"),
        Known("h m", "hm.com", "H&M"),
        Known("zalando", "zalando.dk", "Zalando"),
        Known("amazon", "amazon.com", "Amazon"),
        Known("apple", "apple.com", "Apple"),
        Known("google", "google.com", "Google"),
        Known("microsoft", "microsoft.com", "Microsoft"),
        Known("spotify", "spotify.com", "Spotify"),
        Known("netflix", "netflix.com", "Netflix"),
        Known("disney", "disneyplus.com", "Disney+"),
        Known("hbo", "max.com", "HBO Max"),
        Known("viaplay", "viaplay.dk", "Viaplay"),
        Known("tv 2", "tv2.dk", "TV 2"),
        Known("yousee", "yousee.dk", "YouSee"),
        Known("telenor", "telenor.dk", "Telenor"),
        Known("telia", "telia.com", "Telia"),
        Known("dsb", "dsb.dk", "DSB"),
        Known("rejsekort", "rejsekort.dk", "Rejsekort"),
        Known("mcdonald", "mcdonalds.com", "McDonald's"),
        Known("mcdonalds", "mcdonalds.com", "McDonald's"),
        Known("mc donald", "mcdonalds.com", "McDonald's"),
        Known("mc donalds", "mcdonalds.com", "McDonald's"),
        Known("burger king", "burgerking.dk", "Burger King"),
        Known("starbucks", "starbucks.com", "Starbucks"),
        Known("wolt", "wolt.com", "Wolt"),
        Known("just eat", "just-eat.dk", "Just Eat"),
        Known("uber", "uber.com", "Uber"),
        Known("airbnb", "airbnb.com", "Airbnb"),
        Known("booking com", "booking.com", "Booking.com"),
        Known("steam", "steampowered.com", "Steam"),
        Known("playstation", "playstation.com", "PlayStation"),
        Known("revolut", "revolut.com", "Revolut"),
        Known("paypal", "paypal.com", null),
        Known("mobilepay", "mobilepay.dk", null),
    ).map { it.copy(keyword = normalize(it.keyword)) }

    /** Payment-method noise banks put in front of the merchant name; dropped from [merchantKey]
     * so "Dankort-køb Café Rosa" and "Visa Café Rosa" share one cached AI answer. */
    private val NOISE = setOf(
        "dankort", "visa", "mastercard", "køb", "kortkøb", "betaling", "nota", "dk", "bs",
        "betalingsservice", "overførsel", "indbetaling", "udbetaling", "fra", "til", "c", "v", "n", "s", "k",
        // Card-type codes banks put first, e.g. Sydbank's "MCD 01914 Revolut" (Mastercard debit).
        "mcd", "mc", "vd", "debit"
    )

    private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /** Lowercase, punctuation → spaces, letters and digits split apart ("LIDL254S" → "lidl 254 s",
     * so a store number glued to the name doesn't hide it), single-spaced. Digits stay (for
     * "rema 1000", "7-eleven"). */
    fun normalize(text: String): String {
        // One pass by hand: this runs for every row and on every search keystroke, and the
        // regex version was the slowest part of searching.
        val out = StringBuilder(text.length + 8)
        var pendingSpace = false
        var last = 0 // 0 = nothing yet, 1 = letter, 2 = digit
        for (c in text) {
            val kind = when {
                Character.isLetter(c) -> 1
                Character.isDigit(c) -> 2
                else -> 0
            }
            if (kind == 0) {
                pendingSpace = true
                continue
            }
            if (last != 0 && (pendingSpace || kind != last)) out.append(' ')
            out.append(c.lowercaseChar())
            pendingSpace = false
            last = kind
        }
        return out.toString()
    }


    private fun known(description: String): Known? {
        val padded = " ${normalize(description)} "
        return KNOWN.firstOrNull { " ${it.keyword} " in padded }
    }

    /** Domain from the built-in list, or null. */
    fun knownDomain(description: String): String? = known(description)?.domain

    /** Short company name from the built-in list ("McDonald's"), or null. */
    fun knownName(description: String): String? = known(description)?.name

    /** True when [description] contains [keyword] as whole words (both normalised). */
    fun containsWords(description: String, keyword: String): Boolean {
        val k = normalize(keyword)
        return k.isNotEmpty() && " $k " in " ${normalize(description)} "
    }

    /** The word(s) to suggest for "apply to all transactions containing": the built-in keyword
     * when the merchant is known, else the first name-like word of [merchantKey]. */
    fun suggestedKeyword(description: String): String =
        known(description)?.keyword
            ?: merchantKey(description)?.split(' ')?.firstOrNull { it.length >= 3 }
            ?: normalize(description).split(' ').firstOrNull().orEmpty()

    /** Stable cache key for a merchant: no digits (store numbers, dates), no payment-method words,
     * at most four words. Null when nothing name-like is left. */
    fun merchantKey(description: String): String? =
        normalize(description).replace(DIGITS, " ")
            .split(' ')
            .filter { it.isNotBlank() && it !in NOISE }
            .take(4)
            .joinToString(" ")
            .takeIf { it.length >= 3 }

    /** Cleans an AI-supplied website into a bare domain ("https://www.Netto.dk/" → "netto.dk"), or
     * null when it isn't one. Keeps a bad answer from ever becoming a request URL. */
    fun sanitizeDomain(raw: String?): String? {
        val host = raw?.trim()?.lowercase()
            ?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")
            ?.substringBefore('/')?.substringBefore('?')
            ?: return null
        return host.takeIf { it.length <= 253 && DOMAIN.matches(it) }
    }

    /** Cleans an AI-supplied company name: one line, at most 40 characters; null when empty. */
    fun sanitizeName(raw: String?): String? =
        raw?.replace(Regex("\\s+"), " ")?.trim()?.take(40)?.trim()?.ifEmpty { null }

    /** Reads the AI's `{"key": {"domain": ..., "name": ...}}` reply into domains and names. Every
     * asked key gets an entry in both: the value, or "" meaning "none" so it isn't asked again. A
     * plain string value is read as the domain. Null when the reply isn't JSON at all. */
    fun parseLookupReply(text: String, keys: Collection<String>): Pair<Map<String, String>, Map<String, String>>? = runCatching {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = JSONObject(cleaned.substring(cleaned.indexOf('{'), cleaned.lastIndexOf('}') + 1))
        val domains = HashMap<String, String>()
        val names = HashMap<String, String>()
        keys.forEach { key ->
            val value = json.opt(key)
            val o = value as? JSONObject
            val domain = if (o != null) o.optString("domain").takeIf { !o.isNull("domain") } else (value as? String)
            val name = o?.optString("name")?.takeIf { !o.isNull("name") }
            domains[key] = sanitizeDomain(domain) ?: ""
            names[key] = sanitizeName(name) ?: ""
        }
        domains to names
    }.getOrNull()

    /** Small square company icon for [domain]. Unknown domains answer with an error status, so the
     * image loader fails and the UI keeps its fallback. */
    fun logoUrl(domain: String): String = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
}
