package com.financetracker.app

import com.financetracker.app.util.CategoryIconRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryIconRulesTest {

    private fun icon(name: String, main: String) = CategoryIconRules.guess(name, main)

    @Test
    fun `every default category gets a fitting icon`() {
        val expected = mapOf(
            ("Groceries" to "Food") to "shopping_cart",
            ("Take away and fast food" to "Food") to "takeout",
            ("Bakery, butcher, wine shop etc." to "Food") to "bakery",
            ("Café, restaurant and bar" to "Leisure") to "cafe",
            ("Fuel" to "Transportation") to "fuel",
            ("Parking" to "Transportation") to "parking",
            ("Taxis and public transportation" to "Transportation") to "bus",
            ("Bridge tolls and ferry ticket" to "Transportation") to "boat",
            ("Service and repair" to "Transportation") to "build",
            ("Loan and lease payment" to "Transportation") to "loan",
            ("Transportation (Other)" to "Transportation") to "car",
            ("Rent" to "Home") to "key",
            ("Electricity" to "Home") to "bolt",
            ("Maintenance" to "Home") to "handyman",
            ("Furniture and home accessories" to "Home") to "chair",
            ("Phone, internet, streaming and TV" to "Media") to "wifi",
            ("Films, music, apps and software" to "Media") to "movie",
            ("Newspapers, magazines and books" to "Media") to "book",
            ("Clothing, shoes and accessories" to "Clothing and pers. care prod.") to "checkroom",
            ("Dentist, doctor and medication" to "Clothing and pers. care prod.") to "medical",
            ("Hair and skin care" to "Clothing and pers. care prod.") to "spa",
            ("Glasses and contact lenses" to "Clothing and pers. care prod.") to "glasses",
            ("School, education and course expenses" to "Education and institution") to "school",
            ("Education and institution (Other)" to "Education and institution") to "school",
            ("Electronics and gadgets" to "Leisure") to "devices",
            ("Sport and leisure activities" to "Leisure") to "fitness",
            ("Concert, cinema and museum" to "Leisure") to "theater",
            ("Hotel and caravanning" to "Leisure") to "hotel",
            ("Gifts and charity" to "Leisure") to "gift",
            ("Interest groups" to "Leisure") to "groups",
            ("Games and toys" to "Leisure") to "toys",
            ("Holiday and travel" to "Leisure") to "flight",
            ("Bicycles and other equipment" to "Leisure") to "bike",
            ("Leisure (Other)" to "Leisure") to "activity",
            ("Union and unemployment insurance" to "Insurance") to "groups",
            ("Interest and fees" to "Loan and debt") to "percent",
            ("Consumer loan" to "Loan and debt") to "loan",
            ("Credit cards" to "Loan and debt") to "credit_card",
            ("Loan and debt (Other)" to "Loan and debt") to "loan",
            ("Pay, benefits and pension" to "Income") to "payments",
            ("Social security" to "Income") to "bank",
            ("Interest and capital income" to "Income") to "invest",
            ("Other income" to "Income") to "payments",
            ("Income (Other)" to "Income") to "payments",
            ("Savings" to "Savings and investment") to "savings",
            ("Pension savings" to "Savings and investment") to "pension",
            ("Securities" to "Savings and investment") to "invest",
            ("Other expense" to "Other") to "more",
            ("Other (Transfer)" to "Other") to "transfer",
            ("Other (Cash)" to "Other") to "cash",
            ("No category (Credit card)" to "Not included in budget and consumption") to "credit_card",
            ("Uncategorized" to "Uncategorized") to "help"
        )
        val wrong = expected.filter { (k, v) -> icon(k.first, k.second) != v }
            .map { (k, v) -> "${k.first}: ${icon(k.first, k.second)} (expected $v)" }
        assertTrue(wrong.joinToString("; "), wrong.isEmpty())
    }

    @Test
    fun `main categories and the user's own names`() {
        assertEquals("restaurant", CategoryIconRules.forMain("Food"))
        assertEquals("activity", CategoryIconRules.forMain("Leisure"))
        assertEquals("credit_card", CategoryIconRules.forMain("Loan and debt"))
        assertEquals("pets", CategoryIconRules.forMain("Pets"))
        assertEquals("category", CategoryIconRules.forMain("Something new"))
        assertEquals("flame", icon("Gaz", "Home"))
        assertEquals("pool", icon("Swimming", "Leisure"))
        assertEquals("cafe", icon("Lunch at work", "Food"))
        // Words inside other words don't count: "carpet" isn't a pet, "category" isn't a cat.
        assertEquals("home", icon("Carpet cleaning", "Home"))
        // Nothing matching: the main category's icon.
        assertEquals("activity", icon("Misc fun", "Leisure"))
    }
}
