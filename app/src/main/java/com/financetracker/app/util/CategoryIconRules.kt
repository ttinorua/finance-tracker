package com.financetracker.app.util

/**
 * Picks an icon for a category from its name, so every category has one without the user
 * choosing. Icons are referred to by key; [com.financetracker.app.ui.components.CategoryIcons]
 * draws them. A key the user picks themselves is kept in
 * [com.financetracker.app.data.prefs.CategoryIconChoices] and wins over the guess.
 */
object CategoryIconRules {

    /** Most specific first: "Interest groups" must be groups, not interest; "Pension savings"
     * a pension, not savings. Matched against the lower-cased name. */
    private val RULES: List<Pair<Regex, String>> = listOf(
        "capital income|dividend" to "invest",
        "social security" to "bank",
        "\\bpay\\b|salary|wages?\\b|\\bløn|benefit" to "payments",
        "pension" to "pension",
        "securit|stocks?\\b|shares\\b|invest|aktie" to "invest",
        "saving|opsparing" to "savings",
        "credit ?cards?|kreditkort" to "credit_card",
        "interest groups?|clubs?\\b|\\bunion\\b|forening|a-kasse" to "groups",
        "interest|fees?\\b|gebyr|\\brenter\\b" to "percent",
        "loan|debt|lån|mortgage|realkredit" to "loan",
        "grocer|supermarket|dagligvare" to "shopping_cart",
        "take ?away|fast food|takeout" to "takeout",
        "baker|butcher|wine|bager|slagter" to "bakery",
        "caf[eé]|restaurant|\\bbars?\\b|coffee|kaffe|lunch|frokost|canteen|kantine" to "cafe",
        "fuel|petrol|benzin|diesel|charging" to "fuel",
        "\\bgas\\b|\\bgaz\\b" to "flame",
        "electricity|electric\\b|\\bel\\b|strøm|\\bpower\\b" to "bolt",
        "water|\\bvand\\b" to "water",
        "heating|\\bheat\\b|varme" to "heat",
        "parking|parkering" to "parking",
        "taxi|public transport|\\bbus\\b|train|metro|rejsekort" to "bus",
        "bridge|ferry|tolls?\\b|færge" to "boat",
        "repair|\\bservice\\b|mechanic|værksted" to "build",
        "\\brent\\b|husleje" to "key",
        "furniture|møbler" to "chair",
        "maintenance|vedligehold|\\bdiy\\b" to "handyman",
        "phone|internet|\\btv\\b|streaming|mobile" to "wifi",
        "films?\\b|movie|music|\\bapps?\\b|software|spotify|netflix" to "movie",
        "newspaper|magazine|\\bbooks?\\b" to "book",
        "cloth|shoe|fashion|tøj" to "checkroom",
        "dentist|doctor|medic|pharma|health|apotek|læge" to "medical",
        "\\bhair\\b|\\bskin\\b|beauty|frisør|cosmetic" to "spa",
        "glasses|lens|optic|briller" to "glasses",
        "child|\\bkids?\\b|børn|\\bbaby\\b|daycare" to "child",
        "school|educat|course|tuition|kursus|institution" to "school",
        "electronic|gadget|computer" to "devices",
        "swim|\\bpool\\b|svømme" to "pool",
        "\\bsports?\\b|\\bgym\\b|fitness|træning" to "fitness",
        "concert|cinema|museum|theat|koncert|biograf" to "theater",
        "hotel|caravan|camping" to "hotel",
        "gifts?\\b|charity|donation|\\bgaver?\\b" to "gift",
        "games?\\b|\\btoys?\\b|legetøj" to "toys",
        "holiday|travel|flight|ferie|rejse" to "flight",
        "bicycle|\\bbikes?\\b|cykel" to "bike",
        "insurance|forsikring" to "shield",
        "cash|kontant|\\batm\\b" to "cash",
        "transfer|overførsel" to "transfer",
        "\\bpets?\\b|\\bdogs?\\b|\\bcats?\\b|\\bvet\\b" to "pets",
        "\\btax\\b|\\bskat\\b" to "receipt",
        "\\bwork\\b|\\bjob\\b" to "work",
        "income|indkomst" to "payments",
        "uncategori" to "help"
    ).map { (pattern, key) -> Regex(pattern) to key }

    /** The default bank main categories, with the icon each one gets. */
    private val MAIN: Map<String, String> = mapOf(
        "clothing and pers. care prod." to "checkroom",
        "education and institution" to "school",
        "food" to "restaurant",
        "home" to "home",
        "income" to "payments",
        "insurance" to "shield",
        "leisure" to "activity",
        "loan and debt" to "credit_card",
        "media" to "media",
        "not included in budget and consumption" to "block",
        "other" to "more",
        "savings and investment" to "savings",
        "transportation" to "car",
        "uncategorized" to "help"
    )

    private fun match(text: String): String? {
        val lower = text.lowercase()
        return RULES.firstOrNull { (regex, _) -> regex.containsMatchIn(lower) }?.second
    }

    /** The icon for a main category (a group of categories). */
    fun forMain(mainCategory: String): String =
        MAIN[mainCategory.trim().lowercase()] ?: match(mainCategory) ?: "category"

    /** The icon a category gets from its name, falling back to its main category's. */
    fun guess(name: String, mainCategory: String): String = match(name) ?: forMain(mainCategory)
}
