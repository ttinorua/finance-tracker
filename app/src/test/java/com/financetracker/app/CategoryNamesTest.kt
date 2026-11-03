package com.financetracker.app

import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.util.CategoryNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryNamesTest {

    private fun cat(id: Long, main: String, name: String, type: TransactionType = TransactionType.EXPENSE) =
        Category(id = id, name = name, mainCategory = main, type = type)

    @Test
    fun `spelling variants are the same name`() {
        assertTrue(CategoryNames.same("Takeaway", "Take away"))
        assertTrue(CategoryNames.same("Takeaway", "take-aways"))
        assertTrue(CategoryNames.same("Café & bar", "Café and bar"))
        assertTrue(CategoryNames.same("Food", "  FOOD "))
    }

    @Test
    fun `different names stay different`() {
        assertFalse(CategoryNames.same("Fuel", "Food"))
        assertFalse(CategoryNames.same("Gas", "Ga"))
        assertFalse(CategoryNames.same("Glass", "Glas"))
    }

    @Test
    fun `existing finds a match in any main category, same type only`() {
        val list = listOf(cat(1, "Leisure", "Takeaway"), cat(2, "Income", "Takeaway", TransactionType.INCOME))
        assertEquals(1L, CategoryNames.existing(list, "Take away", TransactionType.EXPENSE)?.id)
        assertNull(CategoryNames.existing(list, "Fuel", TransactionType.EXPENSE))
    }

    @Test
    fun `main category reuses the existing spelling`() {
        val list = listOf(cat(1, "Food", "Groceries"))
        assertEquals("Food", CategoryNames.canonicalMain(list, "food"))
        assertEquals("Pets", CategoryNames.canonicalMain(list, " Pets "))
    }

    @Test
    fun `duplicates keep the built-in one, then the busiest, then the oldest`() {
        val list = listOf(
            cat(1, "Food", "Takeaway"), cat(5, "Food", "Take away"),
            cat(2, "Home", "Electricity"), cat(3, "Utilities", "Electricity"),
            cat(4, "Food", "Groceries")
        )
        val result = CategoryNames.duplicates(list, mapOf(5L to 9, 1L to 2), builtIn = setOf("Home|Electricity|EXPENSE"))
        assertEquals(2, result.size)
        val electricity = result.first { CategoryNames.same(it.keep.name, "Electricity") }
        assertEquals(2L, electricity.keep.id)
        assertEquals(listOf(3L), electricity.remove.map { it.id })
        val takeaway = result.first { CategoryNames.same(it.keep.name, "Takeaway") }
        assertEquals(5L, takeaway.keep.id)
    }

    @Test
    fun `income and expense with the same name are not duplicates`() {
        val list = listOf(cat(1, "Other", "Other (Transfer)"), cat(2, "Other", "Other (Transfer)", TransactionType.INCOME))
        assertTrue(CategoryNames.duplicates(list, emptyMap(), emptySet()).isEmpty())
    }
}
