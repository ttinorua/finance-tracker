package com.financetracker.app.ui.screens.overview

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.util.CategoryFilter
import com.financetracker.app.util.GroupByOption
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.UNCATEGORIZED

/** What a tapped bar, row or KPI on the Spending and Trends screens drills into: the transactions
 * behind that number, opened in [GroupTransactionsScreen]. A null [groupBy] means "every
 * transaction" rather than one Account/Main category/Category bucket. */
data class TransactionsDrillDown(
    val title: String,
    val periodOption: PeriodOption,
    val customRange: Pair<Long, Long>?,
    val accountId: Long?,
    val groupBy: GroupByOption? = null,
    val key: String = "",
    val type: TransactionType? = null,
    val categoryFilter: CategoryFilter = CategoryFilter.All
) {
    private fun path(): String =
        "${groupBy?.name ?: NONE}/${Uri.encode(key.ifEmpty { NONE })}/${periodOption.name}/" +
            "${customRange?.first ?: -1L}/${customRange?.second ?: -1L}/${accountId ?: -1L}/" +
            "${type?.name ?: NONE}/${Uri.encode(categoryFilter.encode())}/${Uri.encode(title)}"

    /** Straight to the transactions. */
    fun route(): String = "group_transactions/" + path()

    /** The breakdown by main category, or by category within [mainCategory] when given.
     * [upcoming] adds a row for bills expected but not yet paid (the Home screen's Spent tile). */
    fun breakdownRoute(mainCategory: String? = null, upcoming: Boolean = false): String =
        "breakdown/" + path() + "/${Uri.encode(mainCategory ?: NONE)}/$upcoming"

    /** The same numbers across every main category, for going up from one main category's
     * breakdown that was opened directly (e.g. Food on Spending). */
    fun acrossMainCategories(mainCategory: String): TransactionsDrillDown = copy(
        title = if (title == mainCategory) "Spending" else title,
        groupBy = groupBy.takeIf { it != GroupByOption.MAIN_CATEGORY },
        key = if (groupBy == GroupByOption.MAIN_CATEGORY) "" else key,
        categoryFilter = if (categoryFilter is CategoryFilter.Main) CategoryFilter.All else categoryFilter
    )

    /** Where tapping this goes: a single category opens its transactions; a main category opens
     * its categories; anything wider opens the main categories first. */
    fun drillRoute(upcoming: Boolean = false): String = when {
        groupBy == GroupByOption.CATEGORY -> route()
        categoryFilter is CategoryFilter.Single || categoryFilter == CategoryFilter.Uncategorized -> route()
        groupBy == GroupByOption.MAIN_CATEGORY ->
            if (key == UNCATEGORIZED) route() else breakdownRoute(key, upcoming)
        categoryFilter is CategoryFilter.Main -> breakdownRoute(categoryFilter.name, upcoming)
        else -> breakdownRoute(null, upcoming)
    }

    companion object {
        private const val NONE = "NONE"

        private const val ARGS = "{groupBy}/{key}/{periodOption}/{from}/{to}/{accountId}/{type}/{filter}/{title}"
        const val ROUTE = "group_transactions/$ARGS"
        const val BREAKDOWN_ROUTE = "breakdown/$ARGS/{main}/{upcoming}"

        val breakdownArguments get() = arguments + listOf(
            navArgument("main") { type = NavType.StringType },
            navArgument("upcoming") { type = NavType.BoolType }
        )

        /** The main category a breakdown route is for, or null for all of them. */
        fun mainCategoryFrom(args: Bundle): String? = Uri.decode(args.getString("main")!!).takeIf { it != NONE }

        val arguments = listOf(
            navArgument("groupBy") { type = NavType.StringType },
            navArgument("key") { type = NavType.StringType },
            navArgument("periodOption") { type = NavType.StringType },
            navArgument("from") { type = NavType.LongType },
            navArgument("to") { type = NavType.LongType },
            navArgument("accountId") { type = NavType.LongType },
            navArgument("type") { type = NavType.StringType },
            navArgument("filter") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType }
        )

        fun from(args: Bundle): TransactionsDrillDown {
            val periodOption = PeriodOption.valueOf(args.getString("periodOption")!!)
            val from = args.getLong("from")
            val to = args.getLong("to")
            val key = Uri.decode(args.getString("key")!!)
            return TransactionsDrillDown(
                title = Uri.decode(args.getString("title")!!),
                periodOption = periodOption,
                customRange = if (periodOption == PeriodOption.CUSTOM && from >= 0 && to >= 0) from to to else null,
                accountId = args.getLong("accountId").takeIf { it >= 0 },
                groupBy = args.getString("groupBy")!!.takeIf { it != NONE }?.let { GroupByOption.valueOf(it) },
                key = if (key == NONE) "" else key,
                type = args.getString("type")!!.takeIf { it != NONE }?.let { TransactionType.valueOf(it) },
                categoryFilter = CategoryFilter.decode(Uri.decode(args.getString("filter")!!))
            )
        }
    }
}
