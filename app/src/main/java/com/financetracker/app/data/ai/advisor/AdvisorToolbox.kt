package com.financetracker.app.data.ai.advisor

import com.financetracker.app.data.prefs.UserAlerts
import com.financetracker.app.data.prefs.UserAlert
import com.financetracker.app.data.prefs.AlertKind
import com.financetracker.app.data.ai.CategorySuggester
import com.financetracker.app.data.ai.LearnedCategoryRules
import com.financetracker.app.data.ai.LocalCategoryMatcher
import com.financetracker.app.data.ai.agent.ToolCall
import com.financetracker.app.data.ai.agent.ToolSpec
import com.financetracker.app.data.bank.LegacyCategories
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.prefs.SavingsGoal
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.CategoryNames
import com.financetracker.app.util.advisor.FinanceAnalysis
import com.financetracker.app.util.advisor.FinanceCalculators
import com.financetracker.app.util.advisor.GoalTracking
import com.financetracker.app.util.isTransferCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Everything the advisor looks at for one question, read once when the question is asked. */
class AdvisorSnapshot(
    val transactions: List<TransactionWithDetails>,
    val accounts: List<Account>,
    val balances: Map<Long, Double>,
    val categories: List<Category>,
    val now: Long = System.currentTimeMillis()
) {
    companion object {
        suspend fun load(repository: FinanceRepository): AdvisorSnapshot {
            val accounts = repository.getAccounts()
            return AdvisorSnapshot(
                transactions = repository.observeTransactions().first(),
                accounts = accounts,
                balances = accounts.associate { it.id to repository.getAccountBalance(it) },
                categories = repository.getCategories()
            )
        }
    }
}

/**
 * The functions the advisor AI can call. Look-ups and calculators answer straight away from the
 * [snapshot] (the app does the maths, the AI interprets); changes only prepare an [AdvisorCard]
 * the user applies (or not) themselves.
 */
class AdvisorToolbox(private val snapshot: AdvisorSnapshot) {

    private val _cards = mutableListOf<AdvisorCard>()
    val cards: List<AdvisorCard> get() = _cards.toList()
    private var nextCardId = 1

    /** Forget cards from an attempt that failed (the next AI starts the question over). */
    fun reset() {
        _cards.clear()
    }

    private val currency get() = CurrencySettings.currencyCode.value
    private val excludeTransfers get() = BudgetSettings.excludeTransfersFromSpending.value
    private val shiftSalary get() = BudgetSettings.shiftSalaryToNextMonth.value
    private val mainAccountId get() = MainAccountSettings.mainAccountId.value

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val monthLabel = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private val uncategorizedIds: Set<Long> = snapshot.categories
        .filter { (it.name == "Uncategorized" && it.mainCategory == "Uncategorized") || LegacyCategories.isAmbiguousBucket(it) }
        .map { it.id }.toSet()

    private fun isUncategorized(tx: TransactionWithDetails) = tx.categoryId == null || tx.categoryId in uncategorizedIds

    // ---------------------------------------------------------------- the tools

    val tools: List<ToolSpec> = listOf(
        spec(
            "get_overview",
            "Snapshot of the user's finances: account balances, this month and last month income/spending, " +
                "6-month averages and savings rate, uncategorized count, budgets, saved loans and goals. Call this first for general questions.",
            obj("account" to str(ACCOUNT_DESC))
        ),
        spec(
            "get_spending",
            "Totals of spending (or income) for a period, grouped by category, main category, merchant, month or account. " +
                "Use for 'where does my money go', comparisons between periods, biggest costs.",
            obj(
                "from" to str(FROM_DESC), "to" to str(TO_DESC),
                "group_by" to enumStr("How to group. Default category.", "category", "main_category", "merchant", "month", "account"),
                "type" to enumStr("expense (default) or income.", "expense", "income"),
                "account" to str(ACCOUNT_DESC),
                "category" to str("Only this category (\"Main/Category\" or just the category name)."),
                "limit" to int("Max rows, default 25.")
            )
        ),
        spec(
            "find_transactions",
            "Search individual transactions (includes transfers). Returns the count, the total and up to 50 rows with their ids. " +
                "Use to inspect transactions before changing them.",
            filterSchema(withSort = true)
        ),
        spec(
            "get_budget_status",
            "This month's budgets against actual spending so far: used %, projected month-end spending at the current pace, and " +
                "which are on track to be exceeded. Also lists the user's average monthly spending per category over the last 3 full months.",
            obj("account" to str(ACCOUNT_DESC))
        ),
        spec(
            "get_recurring_payments",
            "Subscriptions and other repeating payments detected from the transactions (still active), with cadence, latest amount, " +
                "price changes and yearly cost. Use for subscription reviews and finding savings.",
            obj("account" to str(ACCOUNT_DESC))
        ),
        spec(
            "get_monthly_trend",
            "Income, spending, net and savings rate per calendar month, oldest first.",
            obj(
                "months" to int("How many months back including this one, default 12, max 36."),
                "account" to str(ACCOUNT_DESC),
                "category" to str("Only this category's spending (\"Main/Category\" or the category name).")
            )
        ),
        spec(
            "list_categories",
            "The user's categories with type, whether marked as a fixed cost, any budget, and how many transactions each has.",
            obj("type" to enumStr("Only expense or income categories.", "expense", "income"))
        ),
        spec(
            "get_loans_and_goals",
            "The loans/mortgages (with terms read from the user's own loan documents) and savings goals the user saved in the app.",
            obj("include_summaries" to bool("Include each loan's document summary. Default true."))
        ),
        spec(
            "loan_calculator",
            "Exact annuity loan maths: monthly payment, total interest, and the effect of paying extra each month. " +
                "For Danish mortgages pass the contribution rate (bidragssats) too. Use this instead of calculating yourself.",
            obj(
                "principal" to num("Loan amount or remaining debt."),
                "annual_rate_pct" to num("Yearly interest rate in %, e.g. 4.0."),
                "years" to num("Years left / term."),
                "contribution_rate_pct" to num("Danish mortgage contribution rate (bidragssats) in % a year, if any."),
                "extra_monthly" to num("Extra paid each month on top of the normal payment, to compare.")
            ),
            required = listOf("principal", "annual_rate_pct", "years")
        ),
        spec(
            "savings_goal_calculator",
            "Savings goal maths: months to reach a target saving a monthly amount, or the monthly amount needed to reach it in a number of months.",
            obj(
                "target" to num("Target amount."),
                "current" to num("Already saved, default 0."),
                "monthly" to num("Monthly saving, to get the months needed."),
                "months" to int("Months available, to get the monthly saving needed."),
                "annual_return_pct" to num("Expected yearly return/interest in %, default 0.")
            ),
            required = listOf("target")
        ),
        spec(
            "show_transactions",
            "Show the user a list of transactions as a card under your reply (no changes). Use when the user wants to see them.",
            filterSchema(withSort = true).apply {
                getJSONObject("properties").put("title", str("Card title, e.g. \"Uncategorized in September\"."))
            }
        ),
        spec(
            "categorize_uncategorized",
            "Prepare categories for uncategorized transactions (using the user's own past choices, the app's merchant list and AI). " +
                "Shows a card the user must Apply; nothing changes until then.",
            obj("account" to str(ACCOUNT_DESC))
        ),
        spec(
            "recategorize",
            "Prepare moving transactions to another category: by ids, or all transactions whose text contains match_text " +
                "(optionally only those currently in from_category / within dates). Creates the category if it doesn't exist. " +
                "Shows a card the user must Apply.",
            obj(
                "to_category" to str("Target category name."),
                "to_main_category" to str("Target main category (required if the category doesn't exist yet)."),
                "transaction_ids" to arr("Ids from find_transactions.", JSONObject().put("type", "integer")),
                "match_text" to str("Move transactions whose text contains this (case-insensitive)."),
                "from_category" to str("Only transactions now in this category (\"Main/Category\", the name, or \"Uncategorized\")."),
                "from" to str(FROM_DESC), "to" to str(TO_DESC)
            ),
            required = listOf("to_category")
        ),
        spec(
            "set_budgets",
            "Prepare monthly budgets for one scope (an account or all accounts): an overall limit and/or per-category limits. " +
                "Amount 0 removes that budget. Shows a card the user must Apply.",
            obj(
                "account" to str("Account name, or omit for All accounts."),
                "overall_amount" to num("Overall monthly spending limit (0 removes it). Omit to leave unchanged."),
                "category_budgets" to arr(
                    "Per-category monthly limits.",
                    obj(
                        "category" to str("Category name or \"Main/Category\"."),
                        "amount" to num("Monthly limit, 0 removes it.")
                    ).put("required", JSONArray(listOf("category", "amount")))
                ),
                "reason" to str("One sentence on why, shown on the card.")
            )
        ),
        spec(
            "set_fixed_categories",
            "Prepare marking expense categories as fixed costs (rent, insurance, loans…) or not. Shows a card the user must Apply.",
            obj(
                "changes" to arr(
                    "Categories to change.",
                    obj("category" to str("Category name or \"Main/Category\"."), "fixed" to bool("true = fixed cost."))
                        .put("required", JSONArray(listOf("category", "fixed")))
                )
            ),
            required = listOf("changes")
        ),
        spec(
            "create_alert",
            "Prepare an alert the app checks itself after each bank sync and sends as a phone notification: " +
                "a category going over an amount this month (with an early warning), or any single payment over an amount. Card to Apply.",
            obj(
                "kind" to enumStr("category_limit or large_payment.", "category_limit", "large_payment"),
                "amount" to num("The limit (category_limit: per month) or the payment size (large_payment)."),
                "category" to str("category_limit: the category, e.g. \"Takeaway\" or \"Food/Takeaway\"."),
                "warn_at_percent" to num("category_limit: also warn at this % of the limit (default 80; 0 = no early warning)."),
                "account" to str("Only this account (name); omit for all accounts.")
            ),
            required = listOf("kind", "amount")
        ),
        spec("list_alerts", "The alerts the user has set up, with their ids.", obj()),
        spec(
            "remove_alert",
            "Prepare removing one of the user's alerts (id from list_alerts). Card to Apply.",
            obj("id" to str("The alert's id.")),
            required = listOf("id")
        ),
        spec(
            "set_weekly_summary",
            "Prepare switching the weekly AI summary (Sundays 18:00, on Home and as a notification) on or off. Card to Apply.",
            obj("enabled" to bool("true = on.")),
            required = listOf("enabled")
        ),
        spec(
            "create_category",
            "Prepare a new category. Shows a card the user must Apply.",
            obj(
                "main_category" to str("Main category, e.g. \"Food\"."),
                "name" to str("Category name, e.g. \"Takeaway\"."),
                "type" to enumStr("expense or income.", "expense", "income")
            ),
            required = listOf("main_category", "name", "type")
        ),
        spec(
            "save_loan",
            "Prepare saving (or updating, with id) a loan or mortgage the user told you about in My loans & goals. Card to Apply.",
            obj(
                "id" to str("Id of a saved loan to update; omit for a new one."),
                "name" to str("Short name, e.g. \"House mortgage\"."),
                "lender" to str("Bank or mortgage institute."),
                "kind" to enumStr("Kind of loan.", "mortgage", "bank_loan", "car_loan", "student_loan", "other"),
                "loan_type" to str("e.g. \"Fixed-rate 30y (fastforrentet)\"."),
                "remaining_debt" to num("Remaining debt."),
                "interest_rate_pct" to num("Interest rate % a year."),
                "contribution_rate_pct" to num("Contribution rate (bidragssats) % a year."),
                "monthly_payment" to num("Monthly payment."),
                "years_left" to num("Years left."),
                "rate_type" to enumStr("fixed or variable.", "fixed", "variable"),
                "other_terms" to str("Other terms worth remembering.")
            ),
            required = listOf("name")
        ),
        spec(
            "save_goal",
            "Prepare saving (or updating, with id) a savings goal in My loans & goals. Card to Apply.",
            obj(
                "id" to str("Id of a saved goal to update; omit for a new one."),
                "name" to str("e.g. \"Emergency fund\"."),
                "target_amount" to num("Target amount."),
                "saved_amount" to num("Saved so far."),
                "monthly_saving" to num("Planned monthly saving."),
                "target_date" to str("Target date YYYY-MM-DD, if any."),
                "account" to str("Account the money is saved in, so progress follows its balance (omit to enter amounts by hand).")
            ),
            required = listOf("name")
        )
    )

    suspend fun execute(call: ToolCall): String = withContext(Dispatchers.Default) {
        val a = call.args
        when (call.name) {
            "get_overview" -> overview(a)
            "get_spending" -> spending(a)
            "find_transactions" -> findTransactions(a)
            "get_budget_status" -> budgetStatus(a)
            "get_recurring_payments" -> recurring(a)
            "get_monthly_trend" -> monthlyTrend(a)
            "list_categories" -> listCategories(a)
            "get_loans_and_goals" -> loansAndGoals(a)
            "loan_calculator" -> loanCalculator(a)
            "savings_goal_calculator" -> goalCalculator(a)
            "show_transactions" -> showTransactions(a)
            "categorize_uncategorized" -> categorizeUncategorized(a)
            "recategorize" -> recategorize(a)
            "set_budgets" -> setBudgets(a)
            "set_fixed_categories" -> setFixed(a)
            "create_category" -> createCategory(a)
            "create_alert" -> createAlert(a)
            "list_alerts" -> listAlerts()
            "remove_alert" -> removeAlert(a)
            "set_weekly_summary" -> setWeeklySummary(a)
            "save_loan" -> saveLoan(a)
            "save_goal" -> saveGoal(a)
            else -> "Error: there's no function called ${call.name}."
        }
    }

    // ---------------------------------------------------------------- look-ups

    private fun overview(a: JSONObject): String {
        val (accountId, error) = account(a)
        error?.let { return it }
        val counted = counted(accountId)
        val months = FinanceAnalysis.monthlyTotals(counted, 7, snapshot.now)
        val thisMonth = months.last()
        val lastMonth = months[months.size - 2]
        val full = months.dropLast(1)
        val avgIncome = full.map { it.income }.average()
        val avgExpense = full.map { it.expense }.average()
        val scoped = snapshot.transactions.filter { accountId == null || it.accountId == accountId }
        return buildString {
            appendLine("Today: ${day.format(snapshot.now)}. Currency: $currency. Scope: ${scopeName(accountId)}.")
            appendLine("Accounts (balance today):")
            snapshot.accounts.forEach {
                appendLine("- ${it.name}: ${money(snapshot.balances[it.id] ?: it.initialBalance)}${if (it.id == mainAccountId) " (main account)" else ""}")
            }
            appendLine(rulesNote())
            appendLine("This month so far: income ${money(thisMonth.income)}, spending ${money(thisMonth.expense)}, net ${money(thisMonth.net)} (${pct(FinanceAnalysis.elapsedFractionOfMonth(snapshot.now) * 100)} of the month gone).")
            appendLine("Last month (${monthLabel.format(lastMonth.monthStart)}): income ${money(lastMonth.income)}, spending ${money(lastMonth.expense)}, net ${money(lastMonth.net)}${lastMonth.savingsRatePct?.let { ", savings rate ${pct(it)}" } ?: ""}.")
            appendLine("Average of the last 6 full months: income ${money(avgIncome)}, spending ${money(avgExpense)}, net ${money(avgIncome - avgExpense)}${if (avgIncome > 0) ", savings rate ${pct((avgIncome - avgExpense) / avgIncome * 100)}" else ""}.")
            appendLine("Uncategorized transactions: ${scoped.count(::isUncategorized)}.")
            val budgets = BudgetLimits.overallBudgets.value.size + BudgetLimits.categoryBudgets.value.size
            appendLine("Budgets set: $budgets. Saved loans: ${LoansAndGoals.loans.value.size}. Saved goals: ${LoansAndGoals.goals.value.size}.")
            if (scoped.isNotEmpty()) appendLine("Transactions on record: ${scoped.size}, from ${day.format(scoped.minOf { it.date })} to ${day.format(scoped.maxOf { it.date })}.")
        }.trim()
    }

    private fun spending(a: JSONObject): String {
        val (accountId, error) = account(a)
        error?.let { return it }
        val type = if (a.optString("type") == "income") TransactionType.INCOME else TransactionType.EXPENSE
        val (from, to) = period(a, defaultFrom = FinanceAnalysis.monthStart(snapshot.now))
        val category = text(a, "category")?.let { name -> resolveCategoryFilter(name) ?: return "Error: no category called \"$name\". Use list_categories." }
        val txs = counted(accountId).filter { it.type == type && it.date in from until to && (category == null || category(it)) }
        val groupBy = a.optString("group_by").ifBlank { "category" }
        val totals = FinanceAnalysis.totalsBy(txs, type) { tx ->
            when (groupBy) {
                "main_category" -> tx.mainCategoryName ?: "Uncategorized"
                "merchant" -> FinanceAnalysis.merchantOf(tx.note)
                "month" -> monthLabel.format(tx.date)
                "account" -> tx.accountName
                else -> categoryLabel(tx)
            }
        }.let { if (groupBy == "month") it.sortedBy { t -> t.key } else it }
        val sum = txs.sumOf { it.amount }
        val limit = a.optInt("limit", 25).coerceIn(1, 100)
        return buildString {
            appendLine("${if (type == TransactionType.EXPENSE) "Spending" else "Income"} ${periodText(from, to)}, ${scopeName(accountId)}, grouped by $groupBy: total ${money(sum)} in ${txs.size} transactions. ${rulesNote()}")
            totals.take(limit).forEach { appendLine("- ${it.key}: ${money(it.amount)} (${it.count}×, ${pct(if (sum > 0) it.amount / sum * 100 else 0.0)})") }
            if (totals.size > limit) appendLine("…and ${totals.size - limit} more groups.")
        }.trim()
    }

    private fun findTransactions(a: JSONObject): String {
        val matches = filtered(a).let { it.first ?: return it.second!! }
        val limit = a.optInt("limit", 30).coerceIn(1, 50)
        return buildString {
            appendLine("Found ${matches.size} transactions, total ${money(matches.sumOf { signed(it) })} (income positive, spending negative). Showing ${minOf(limit, matches.size)}:")
            appendLine("id | date | account | category | amount | text")
            matches.take(limit).forEach { appendLine(row(it)) }
        }.trim()
    }

    private fun budgetStatus(a: JSONObject): String {
        val (accountId, error) = account(a)
        error?.let { return it }
        val elapsed = FinanceAnalysis.elapsedFractionOfMonth(snapshot.now)
        val start = FinanceAnalysis.monthStart(snapshot.now)
        val scopes = (BudgetLimits.overallBudgets.value.keys + BudgetLimits.categoryBudgets.value.keys.map { it.second })
            .toSet().filter { a.optString("account").isBlank() || it == accountId }
        return buildString {
            appendLine("Budgets for ${monthLabel.format(start)} (${pct(elapsed * 100)} of the month gone). ${rulesNote()}")
            if (scopes.isEmpty()) appendLine("No budgets are set${if (a.optString("account").isBlank()) "" else " for this scope"}.")
            scopes.forEach { scope ->
                val month = counted(scope).filter { it.type == TransactionType.EXPENSE && it.date >= start }
                appendLine("${scopeName(scope)}:")
                BudgetLimits.overallBudgets.value[scope]?.let { budget ->
                    appendLine("- Overall: ${budgetLine(FinanceAnalysis.BudgetLine("Overall", budget, month.sumOf { it.amount }, elapsed))}")
                }
                BudgetLimits.categoryBudgets.value.filterKeys { it.second == scope }.forEach { (key, budget) ->
                    val cat = snapshot.categories.firstOrNull { it.id == key.first } ?: return@forEach
                    val spent = month.filter { it.categoryId == cat.id }.sumOf { it.amount }
                    appendLine("- ${cat.mainCategory}/${cat.name}: ${budgetLine(FinanceAnalysis.BudgetLine(cat.name, budget, spent, elapsed))}")
                }
            }
            val from = FinanceAnalysis.monthStart(snapshot.now, -3)
            val past = counted(accountId).filter { it.type == TransactionType.EXPENSE && it.date in from until start }
            appendLine("Average monthly spending per category over the last 3 full months (${scopeName(accountId)}):")
            FinanceAnalysis.totalsBy(past, TransactionType.EXPENSE, ::categoryLabel).take(30).forEach {
                appendLine("- ${it.key}: ${money(it.amount / 3)}")
            }
            appendLine("Average total: ${money(past.sumOf { it.amount } / 3)} a month.")
        }.trim()
    }

    private fun budgetLine(line: FinanceAnalysis.BudgetLine) =
        "budget ${money(line.budget)}, spent ${money(line.spent)} (${pct(line.usedPct)}), projected ${money(line.projected)}" +
            if (line.onTrackToExceed) " — ON TRACK TO EXCEED" else ""

    private fun recurring(a: JSONObject): String {
        val (accountId, error) = account(a)
        error?.let { return it }
        val payments = FinanceAnalysis.recurringPayments(counted(accountId), snapshot.now)
        if (payments.isEmpty()) return "No active repeating payments found (needs at least 3 regular payments to the same place)."
        return buildString {
            appendLine("Active repeating payments, ${scopeName(accountId)} (total ${money(payments.sumOf { it.yearlyCost })} a year):")
            payments.take(40).forEach { p ->
                append("- ${p.merchant} (${p.category ?: "Uncategorized"}): ${money(p.lastAmount)} ${p.cadence.label}, last ${day.format(p.lastDate)}, ${p.occurrences} payments, ${money(p.yearlyCost)}/year")
                p.priceChangePct?.let { append(", changed from ${money(p.previousAmount!!)} (${if (it > 0) "+" else ""}${pct(it)})") }
                appendLine()
            }
        }.trim()
    }

    private fun monthlyTrend(a: JSONObject): String {
        val (accountId, error) = account(a)
        error?.let { return it }
        val months = a.optInt("months", 12).coerceIn(1, 36)
        val categoryName = text(a, "category")
        val category = categoryName?.let { resolveCategoryFilter(it) ?: return "Error: no category called \"$it\"." }
        val txs = counted(accountId).filter { category == null || category(it) }
        return buildString {
            appendLine("Per month, ${scopeName(accountId)}${categoryName?.let { ", category $it" } ?: ""}. ${rulesNote()}")
            FinanceAnalysis.monthlyTotals(txs, months, snapshot.now).forEach { m ->
                appendLine("- ${monthLabel.format(m.monthStart)}: income ${money(m.income)}, spending ${money(m.expense)}, net ${money(m.net)}${m.savingsRatePct?.let { ", savings rate ${pct(it)}" } ?: ""}")
            }
            appendLine("(The current month is not finished.)")
        }.trim()
    }

    private fun listCategories(a: JSONObject): String {
        val type = when (a.optString("type")) {
            "expense" -> TransactionType.EXPENSE
            "income" -> TransactionType.INCOME
            else -> null
        }
        val counts = snapshot.transactions.groupingBy { it.categoryId }.eachCount()
        val fixed = FixedExpenseCategories.fixedCategoryIds.value
        return buildString {
            appendLine("Main/Category | type | fixed cost | budget (all accounts) | transactions")
            snapshot.categories.filter { type == null || it.type == type }.sortedWith(compareBy({ it.type }, { it.mainCategory }, { it.name })).forEach { c ->
                appendLine("${c.mainCategory}/${c.name} | ${c.type.name.lowercase()} | ${if (c.id in fixed) "fixed" else "-"} | ${BudgetLimits.categoryBudgetFor(c.id, null)?.let(::money) ?: "-"} | ${counts[c.id] ?: 0}")
            }
        }.trim()
    }

    private fun loansAndGoals(a: JSONObject): String {
        val loans = LoansAndGoals.loans.value
        val goals = LoansAndGoals.goals.value
        if (loans.isEmpty() && goals.isEmpty()) {
            return "The user hasn't saved any loans or goals. They can add them in Settings > My loans & goals (and read a loan document there), or tell you the details."
        }
        val withSummaries = !a.has("include_summaries") || a.optBoolean("include_summaries", true)
        return buildString {
            appendLine("Loans:")
            if (loans.isEmpty()) appendLine("(none)")
            loans.forEach { loan ->
                val json = LoansAndGoals.loanToJson(loan)
                if (!withSummaries) json.remove("summary")
                appendLine(compact(json))
            }
            appendLine("Savings goals:")
            if (goals.isEmpty()) appendLine("(none)")
            goals.forEach { goal ->
                val json = LoansAndGoals.goalToJson(goal)
                json.remove("started_at")
                json.remove("start_amount")
                goal.accountId?.let { id ->
                    json.remove("account_id")
                    json.put("linked_account", scopeName(id))
                    snapshot.balances[id]?.let { json.put("saved_amount", it) }
                }
                appendLine(compact(json))
                goalStatusLine(goal)?.let { appendLine("  status: $it") }
            }
        }.trim()
    }

    /** Progress of a goal with a target, worked out the same way as the Dashboard's checks. */
    private fun goalStatusLine(goal: SavingsGoal): String? {
        val target = goal.targetAmount?.takeIf { it > 0 } ?: return null
        val saved = goal.accountId?.let { snapshot.balances[it] } ?: goal.savedAmount ?: return null
        val status = GoalTracking.status(
            target, saved, GoalTracking.parseTargetDate(goal.targetDate), goal.startedAt, goal.startAmount, goal.monthlySaving, snapshot.now
        )
        return buildString {
            append("${pct(saved / target * 100)} saved")
            if (status.reached) {
                append(", goal reached")
                return@buildString
            }
            status.pacePerMonth?.let { append(", ${if (status.paceMeasured) "actually saving" else "planned"} ${money(it)}/month") }
            status.monthsLeft?.let { append(", $it months to the target date") }
            status.neededPerMonth?.let { append(", needs ${money(it)}/month to make it") }
            status.projectedMonths?.let { append(", reached in $it months at this pace") }
            if (status.behind) append(", BEHIND by ${money(status.behindBy)}") else if (status.monthsLeft != null) append(", on track")
        }
    }

    private fun loanCalculator(a: JSONObject): String {
        val principal = number(a, "principal") ?: return "Error: principal is required."
        val rate = number(a, "annual_rate_pct") ?: return "Error: annual_rate_pct is required."
        val years = number(a, "years") ?: return "Error: years is required."
        val contribution = number(a, "contribution_rate_pct") ?: 0.0
        val extra = number(a, "extra_monthly") ?: 0.0
        return runCatching {
            val result = FinanceCalculators.extraPayment(principal, rate, years, extra, contribution)
            val base = result.base
            buildString {
                appendLine("Loan ${money(principal)} at $rate%${if (contribution > 0) " + $contribution% contribution" else ""} over $years years (annuity, monthly payments, before tax deduction):")
                appendLine("- Monthly payment: ${money(base.monthlyPayment)}; ${base.months} payments; total interest${if (contribution > 0) " and contribution" else ""} ${money(base.totalInterest)}; total paid ${money(base.totalPaid)}.")
                if (extra > 0) {
                    val w = result.withExtra
                    appendLine("- Paying ${money(extra)} extra a month: paid off in ${w.months} months (${result.monthsSaved} months sooner), total interest ${money(w.totalInterest)}, saving ${money(result.interestSaved)}.")
                }
            }.trim()
        }.getOrElse { "Error: ${it.message}" }
    }

    private fun goalCalculator(a: JSONObject): String {
        val target = number(a, "target") ?: return "Error: target is required."
        val current = number(a, "current") ?: 0.0
        val annual = number(a, "annual_return_pct") ?: 0.0
        val monthly = number(a, "monthly")
        val months = if (a.has("months")) a.optInt("months").takeIf { it > 0 } else null
        return buildString {
            appendLine("Goal ${money(target)}, saved ${money(current)}, expected return $annual% a year:")
            monthly?.let {
                val needed = FinanceCalculators.monthsToGoal(target, current, it, annual)
                appendLine(if (needed == null) "- Saving ${money(it)} a month never reaches it." else "- Saving ${money(it)} a month: reached in $needed months (${"%.1f".format(Locale.US, needed / 12.0)} years).")
            }
            months?.let { appendLine("- To reach it in $it months: save ${money(FinanceCalculators.monthlyNeeded(target, current, it, annual))} a month.") }
            if (monthly == null && months == null) appendLine("- Pass monthly or months to calculate.")
        }.trim()
    }

    // ---------------------------------------------------------------- cards

    private fun showTransactions(a: JSONObject): String {
        val matches = filtered(a).let { it.first ?: return it.second!! }
        if (matches.isEmpty()) return "No transactions match, so no card was shown."
        val shown = matches.take(150)
        val title = text(a, "title") ?: "Transactions"
        addCard(AdvisorCard(0, title, lines = listOf("${matches.size} transactions, total ${money(matches.sumOf { signed(it) })}"), transactions = shown))
        return "Showed the user a card \"$title\" with ${shown.size} of ${matches.size} transactions (total ${money(matches.sumOf { signed(it) })})."
    }

    private suspend fun categorizeUncategorized(a: JSONObject): String {
        val (accountId, error) = account(a)
        error?.let { return it }
        val targets = snapshot.transactions.filter { isUncategorized(it) && (accountId == null || it.accountId == accountId) }
        if (targets.isEmpty()) return "There are no uncategorized transactions${if (accountId != null) " in this account" else ""}."
        val usable = snapshot.categories.filter { it.id !in uncategorizedIds }
        val groups = targets.groupBy { it.note.trim().lowercase() to it.type }.entries.toList().take(MAX_NOTE_GROUPS)
        val matches = arrayOfNulls<Category>(groups.size)
        groups.forEachIndexed { i, (key, _) ->
            val ofType = usable.filter { it.type == key.second }
            matches[i] = LearnedCategoryRules.suggest(key.first, ofType) ?: LocalCategoryMatcher.suggest(key.first, ofType)
        }
        var aiFailed = false
        for (type in TransactionType.entries) {
            val ofType = usable.filter { it.type == type }
            val pending = groups.indices.filter { matches[it] == null && groups[it].key.second == type && groups[it].key.first.isNotBlank() }
            for (chunk in pending.chunked(CategorySuggester.BATCH_SIZE)) {
                val result = CategorySuggester.suggestBatch(chunk.map { groups[it].value.first().note }, ofType).getOrNull()
                if (result == null) {
                    aiFailed = true
                    continue
                }
                chunk.forEachIndexed { n, index -> matches[index] = result.getOrNull(n)?.takeIf { it.id !in uncategorizedIds } }
            }
        }
        val byCategory = groups.indices.filter { matches[it] != null }
            .groupBy { matches[it]!! }
            .mapValues { (_, indices) -> indices.flatMap { groups[it].value } }
        val count = byCategory.values.sumOf { it.size }
        val left = targets.size - count
        if (count == 0) return "Couldn't find a confident category for any of the ${targets.size} uncategorized transactions${if (aiFailed) " (the AI lookup failed)" else ""}. Suggest the user categorizes a few by hand so the app learns them."
        val lines = byCategory.entries.sortedByDescending { it.value.size }.map { (cat, txs) ->
            "${cat.mainCategory} › ${cat.name}: ${txs.size} (${txs.map { it.note.trim() }.distinct().take(3).joinToString(", ") { it.take(28) }})"
        }
        val card = addCard(
            AdvisorCard(
                0, "Categorize $count transaction${if (count == 1) "" else "s"}",
                lines = lines + listOfNotNull(left.takeIf { it > 0 }?.let { "$it stay uncategorized (no confident match)." }),
                plan = ActionPlan.Recategorize(byCategory.map { (cat, txs) -> ActionPlan.Assignment(txs.map { it.id }, cat.id) })
            )
        )
        return "Prepared card #${card.id} to categorize $count of ${targets.size} uncategorized transactions into ${byCategory.size} categories" +
            "${if (left > 0) "; $left had no confident match" else ""}${if (aiFailed) " (AI lookup partly failed)" else ""}. " +
            "Nothing has changed yet — the user reviews it and taps Apply.\n" + lines.take(15).joinToString("\n")
    }

    private fun recategorize(a: JSONObject): String {
        val targetName = text(a, "to_category") ?: return "Error: to_category is required."
        val targetMain = text(a, "to_main_category")
        val ids = a.optJSONArray("transaction_ids")?.let { array -> (0 until array.length()).map { array.optLong(it) }.toSet() }
        val matchText = text(a, "match_text")
        if (ids.isNullOrEmpty() && matchText == null && text(a, "from_category") == null) {
            return "Error: say which transactions — transaction_ids, match_text or from_category."
        }
        val fromFilter = text(a, "from_category")?.let { name -> resolveCategoryFilter(name) ?: return "Error: no category called \"$name\"." }
        val (from, to) = period(a, defaultFrom = Long.MIN_VALUE)
        val txs = snapshot.transactions.filter { tx ->
            (ids.isNullOrEmpty() || tx.id in ids) &&
                (matchText == null || tx.note.contains(matchText, ignoreCase = true)) &&
                (fromFilter == null || fromFilter(tx)) &&
                tx.date in from until to
        }
        if (txs.isEmpty()) return "No transactions match, so nothing was prepared. Check with find_transactions."
        val type = txs.groupingBy { it.type }.eachCount().maxByOrNull { it.value }!!.key
        // A differently spelled or placed name ("Take away" for Food › Takeaway) is the same category.
        val existing = findCategory(targetName, targetMain, type)
            ?: CategoryNames.existing(snapshot.categories.filter { it.id !in uncategorizedIds }, targetName.substringAfterLast('/').substringAfterLast('›').trim(), type)
        val newCategory = if (existing == null) {
            targetMain ?: return "Error: there's no category \"$targetName\"; give to_main_category to create it, or pick one from list_categories."
            Category(name = targetName.trim(), mainCategory = CategoryNames.canonicalMain(snapshot.categories, targetMain), type = type)
        } else {
            null
        }
        val moving = txs.filter { existing == null || it.categoryId != existing.id }
        if (moving.isEmpty()) return "All ${txs.size} matching transactions are already in ${existing!!.mainCategory}/${existing.name}."
        val label = existing?.let { "${it.mainCategory} › ${it.name}" } ?: "${newCategory!!.mainCategory} › ${newCategory.name} (new category)"
        val fromSummary = moving.groupingBy { categoryLabel(it) }.eachCount().entries.sortedByDescending { it.value }
            .take(4).joinToString(", ") { "${it.value} from ${it.key}" }
        val card = addCard(
            AdvisorCard(
                0, "Move ${moving.size} transaction${if (moving.size == 1) "" else "s"} to $label",
                lines = listOf(fromSummary) + moving.take(5).map { "${day.format(it.date)}  ${it.note.take(32)}  ${money(it.amount)}" } +
                    listOfNotNull((moving.size - 5).takeIf { it > 0 }?.let { "…and $it more" }),
                plan = ActionPlan.Recategorize(listOf(ActionPlan.Assignment(moving.map { it.id }, existing?.id)), newCategory)
            )
        )
        return "Prepared card #${card.id}: move ${moving.size} transactions to $label ($fromSummary). Nothing has changed yet — the user reviews it and taps Apply."
    }

    private fun setBudgets(a: JSONObject): String {
        val (accountId, error) = account(a)
        error?.let { return it }
        val overall = number(a, "overall_amount")
        val lines = mutableListOf<String>()
        overall?.let {
            val old = BudgetLimits.overallBudgetFor(accountId)
            lines += "Overall: ${change(old, it)}"
        }
        val categoryChanges = mutableMapOf<Long, Double?>()
        val items = a.optJSONArray("category_budgets")
        for (i in 0 until (items?.length() ?: 0)) {
            val item = items!!.optJSONObject(i) ?: continue
            val name = text(item, "category") ?: continue
            val amount = number(item, "amount") ?: continue
            val cat = findCategory(name, null, TransactionType.EXPENSE) ?: return "Error: no expense category called \"$name\". Use list_categories."
            categoryChanges[cat.id] = amount.takeIf { it > 0 }
            lines += "${cat.mainCategory} › ${cat.name}: ${change(BudgetLimits.categoryBudgetFor(cat.id, accountId), amount)}"
        }
        if (lines.isEmpty()) return "Error: give overall_amount and/or category_budgets."
        text(a, "reason")?.let { lines += it }
        val card = addCard(
            AdvisorCard(
                0, "Monthly budgets · ${scopeName(accountId)}", lines = lines,
                plan = ActionPlan.SetBudgets(accountId, overall != null, overall?.takeIf { it > 0 }, categoryChanges)
            )
        )
        return "Prepared card #${card.id} with these budget changes for ${scopeName(accountId)}: ${lines.joinToString("; ")}. Nothing has changed yet — the user reviews it and taps Apply."
    }

    private fun setFixed(a: JSONObject): String {
        val items = a.optJSONArray("changes") ?: return "Error: changes is required."
        val changes = mutableMapOf<Long, Boolean>()
        val lines = mutableListOf<String>()
        val current = FixedExpenseCategories.fixedCategoryIds.value
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = text(item, "category") ?: continue
            val cat = findCategory(name, null, TransactionType.EXPENSE) ?: return "Error: no expense category called \"$name\"."
            val fixed = item.optBoolean("fixed", true)
            if ((cat.id in current) == fixed) continue
            changes[cat.id] = fixed
            lines += "${cat.mainCategory} › ${cat.name}: ${if (fixed) "mark as fixed cost" else "not a fixed cost"}"
        }
        if (changes.isEmpty()) return "Those categories are already set that way; nothing to change."
        val card = addCard(AdvisorCard(0, "Fixed costs", lines = lines, plan = ActionPlan.SetFixed(changes)))
        return "Prepared card #${card.id}: ${lines.joinToString("; ")}. Nothing has changed yet — the user taps Apply."
    }

    private fun createCategory(a: JSONObject): String {
        val main = text(a, "main_category") ?: return "Error: main_category is required."
        val name = text(a, "name") ?: return "Error: name is required."
        val type = if (a.optString("type") == "income") TransactionType.INCOME else TransactionType.EXPENSE
        // Never a second copy: the same name (ignoring capitals, spaces, "&"/"and", plurals) in
        // any main category is the same category.
        CategoryNames.existing(snapshot.categories, name, type)?.let {
            return "The category ${it.mainCategory}/${it.name} already exists, so no new one was prepared. Use that one."
        }
        val mainName = CategoryNames.canonicalMain(snapshot.categories, main)
        val card = addCard(
            AdvisorCard(
                0, "New category", lines = listOf("$mainName › $name (${type.name.lowercase()})"),
                plan = ActionPlan.CreateCategory(Category(name = name, mainCategory = mainName, type = type))
            )
        )
        return "Prepared card #${card.id} to create $main/$name. Nothing has changed yet — the user taps Apply."
    }

    private fun createAlert(a: JSONObject): String {
        val amount = number(a, "amount")?.takeIf { it > 0 } ?: return "Error: amount must be above 0."
        val (accountId, error) = account(a)
        if (error != null) return error
        val alert = when (a.optString("kind")) {
            "large_payment" -> UserAlert(kind = AlertKind.LARGE_PAYMENT, limit = amount, accountId = accountId)
            "category_limit" -> {
                val name = text(a, "category") ?: return "Error: category_limit needs a category."
                val category = findCategory(name, null, TransactionType.EXPENSE)
                    ?: CategoryNames.existing(snapshot.categories, name.substringAfterLast('/').trim(), TransactionType.EXPENSE)
                    ?: return "Error: no expense category called \"$name\". Use list_categories."
                val warn = number(a, "warn_at_percent")?.toInt() ?: 80
                UserAlert(
                    kind = AlertKind.CATEGORY_LIMIT, limit = amount, categoryId = category.id, categoryName = category.name,
                    accountId = accountId, warnAtPercent = warn.takeIf { it in 1..99 }
                )
            }
            else -> return "Error: kind must be category_limit or large_payment."
        }
        val scope = scopeName(accountId)
        val lines = when (alert.kind) {
            AlertKind.CATEGORY_LIMIT -> listOf(
                "When: ${alert.categoryName} goes over ${money(amount)} in a month",
                alert.warnAtPercent?.let { "Early warning: at $it%" } ?: "No early warning",
                "Accounts: $scope"
            )
            AlertKind.LARGE_PAYMENT -> listOf("When: any single payment of ${money(amount)} or more", "Accounts: $scope")
        }
        val card = addCard(AdvisorCard(0, "New alert", lines = lines + "Checked by the app after each bank sync; sent as a notification.", plan = ActionPlan.SaveAlert(alert)))
        return "Prepared card #${card.id} for the alert (${lines.first()}). Nothing has changed yet — the user taps Apply. " +
            "The app checks it itself, so it doesn't use the AI."
    }

    private fun listAlerts(): String {
        val alerts = UserAlerts.alerts.value
        if (alerts.isEmpty()) return "The user has no alerts yet."
        return alerts.joinToString("\n") { alert ->
            "- id ${alert.id}: ${UserAlerts.describe(alert, ::money)}" +
                (alert.warnAtPercent?.takeIf { alert.kind == AlertKind.CATEGORY_LIMIT }?.let { ", early warning at $it%" } ?: "") +
                ", ${scopeName(alert.accountId)}" + if (alert.enabled) "" else " (switched off)"
        }
    }

    private fun removeAlert(a: JSONObject): String {
        val id = text(a, "id") ?: return "Error: id is required."
        val alert = UserAlerts.alerts.value.firstOrNull { it.id == id } ?: return "Error: no alert with id $id. Use list_alerts."
        val card = addCard(AdvisorCard(0, "Remove alert", lines = listOf(UserAlerts.describe(alert, ::money)), plan = ActionPlan.RemoveAlert(alert)))
        return "Prepared card #${card.id} to remove the alert. Nothing has changed yet — the user taps Apply."
    }

    private fun setWeeklySummary(a: JSONObject): String {
        val on = a.optBoolean("enabled", true)
        val card = addCard(
            AdvisorCard(
                0, if (on) "Weekly summary on" else "Weekly summary off",
                lines = listOf(if (on) "A short AI summary of your week every Sunday at 18:00, on Home and as a notification." else "No more Sunday summaries."),
                plan = ActionPlan.SetWeeklySummary(on)
            )
        )
        return "Prepared card #${card.id}. Nothing has changed yet — the user taps Apply."
    }

    private fun saveLoan(a: JSONObject): String {
        val existing = text(a, "id")?.let { id -> LoansAndGoals.loans.value.firstOrNull { it.id == id } ?: return "Error: no saved loan with id $id." }
        val merged = LoansAndGoals.loanToJson(existing ?: Loan())
        a.keys().forEach { key -> if (key != "id" && !a.isNull(key)) merged.put(key, a.get(key)) }
        val loan = LoansAndGoals.loanFromJson(merged, existing?.id ?: Loan().id)
        val lines = listOfNotNull(
            loan.lender.takeIf { it.isNotBlank() },
            loan.loanType.takeIf { it.isNotBlank() },
            loan.remainingDebt?.let { "Remaining debt ${money(it)}" },
            loan.interestRatePct?.let { "Interest $it%" + (loan.contributionRatePct?.let { c -> " + contribution $c%" } ?: "") },
            loan.monthlyPayment?.let { "Monthly payment ${money(it)}" },
            loan.yearsLeft?.let { "$it years left" }
        )
        val card = addCard(AdvisorCard(0, "${if (existing == null) "Save" else "Update"} loan: ${loan.name}", lines = lines, plan = ActionPlan.SaveLoan(loan)))
        return "Prepared card #${card.id} to save the loan \"${loan.name}\". Nothing is saved until the user taps Apply."
    }

    private fun saveGoal(a: JSONObject): String {
        val existing = text(a, "id")?.let { id -> LoansAndGoals.goals.value.firstOrNull { it.id == id } ?: return "Error: no saved goal with id $id." }
        val base = existing ?: SavingsGoal()
        val goal = base.copy(
            name = text(a, "name") ?: base.name,
            targetAmount = number(a, "target_amount") ?: base.targetAmount,
            savedAmount = number(a, "saved_amount") ?: base.savedAmount,
            monthlySaving = number(a, "monthly_saving") ?: base.monthlySaving,
            targetDate = text(a, "target_date") ?: base.targetDate,
            accountId = if (text(a, "account") != null) account(a).let { (id, error) -> error?.let { return it }; id } else base.accountId
        )
        val lines = listOfNotNull(
            goal.targetAmount?.let { "Target ${money(it)}" },
            goal.accountId?.let { "Saved in ${scopeName(it)} (${money(snapshot.balances[it] ?: 0.0)})" } ?: goal.savedAmount?.let { "Saved ${money(it)}" },
            goal.monthlySaving?.let { "Saving ${money(it)} a month" },
            goal.targetDate.takeIf { it.isNotBlank() }?.let { "By $it" }
        )
        val card = addCard(AdvisorCard(0, "${if (existing == null) "Save" else "Update"} goal: ${goal.name}", lines = lines, plan = ActionPlan.SaveGoal(goal)))
        return "Prepared card #${card.id} to save the goal \"${goal.name}\". Nothing is saved until the user taps Apply."
    }

    private fun addCard(card: AdvisorCard): AdvisorCard = card.copy(id = nextCardId++).also { _cards += it }

    // ---------------------------------------------------------------- helpers

    /** Transactions that count toward totals for this scope (the app's transfer rules), with
     * salaries moved to the month they're for when that setting is on. */
    private fun counted(accountId: Long?): List<TransactionWithDetails> =
        FinanceAnalysis.counted(snapshot.transactions, accountId, excludeTransfers, shiftSalary, mainAccountId)

    private fun rulesNote(): String =
        "Totals follow the app's settings: transfers between own accounts ${if (excludeTransfers) "excluded" else "included"}" +
            (if (shiftSalary) "; salary counted in the month after it's paid" else "") + "."

    /** The account named in "account", or All accounts when blank. */
    private fun account(a: JSONObject): Pair<Long?, String?> {
        val name = text(a, "account") ?: return null to null
        if (name.equals("all", true) || name.equals("all accounts", true)) return null to null
        val match = snapshot.accounts.firstOrNull { it.name.equals(name, true) }
            ?: snapshot.accounts.firstOrNull { it.name.contains(name, true) }
            ?: return null to "Error: no account called \"$name\". Accounts: ${snapshot.accounts.joinToString { it.name }}."
        return match.id to null
    }

    private fun scopeName(accountId: Long?) = accountId?.let { id -> snapshot.accounts.firstOrNull { it.id == id }?.name } ?: "All accounts"

    /** A category by "Main/Name" or name (preferring [type], then [main]). */
    private fun findCategory(raw: String, main: String?, type: TransactionType?): Category? {
        val parts = raw.split("/", "›", ">").map { it.trim() }.filter { it.isNotEmpty() }
        val name = parts.lastOrNull() ?: return null
        val mainName = main ?: parts.takeIf { it.size > 1 }?.first()
        val candidates = snapshot.categories.filter { it.name.equals(name, true) && it.id !in uncategorizedIds }
        return candidates.sortedByDescending {
            (if (mainName != null && it.mainCategory.equals(mainName, true)) 2 else 0) + (if (it.type == type) 1 else 0)
        }.firstOrNull()?.takeIf { mainName == null || it.mainCategory.equals(mainName, true) || candidates.size == 1 }
    }

    /** A predicate for "category" filters: a category, a whole main category, or Uncategorized. */
    private fun resolveCategoryFilter(raw: String): ((TransactionWithDetails) -> Boolean)? {
        if (raw.trim().equals("uncategorized", true)) return ::isUncategorized
        findCategory(raw, null, null)?.let { cat -> return { it.categoryId == cat.id } }
        val main = raw.trim()
        if (snapshot.categories.any { it.mainCategory.equals(main, true) }) return { it.mainCategoryName.equals(main, true) }
        return null
    }

    private fun categoryLabel(tx: TransactionWithDetails) =
        if (isUncategorized(tx)) "Uncategorized" else "${tx.mainCategoryName}/${tx.categoryName}"

    /** The shared filters of find_transactions / show_transactions. Returns the matches, or an error. */
    private fun filtered(a: JSONObject): Pair<List<TransactionWithDetails>?, String?> {
        val (accountId, error) = account(a)
        if (error != null) return null to error
        val category = text(a, "category")?.let { name -> resolveCategoryFilter(name) ?: return null to "Error: no category called \"$name\". Use list_categories." }
        val type = when (a.optString("type")) {
            "expense" -> TransactionType.EXPENSE
            "income" -> TransactionType.INCOME
            else -> null
        }
        val search = text(a, "text")
        val min = number(a, "min_amount")
        val max = number(a, "max_amount")
        val (from, to) = period(a, defaultFrom = Long.MIN_VALUE)
        val uncategorized = a.optBoolean("uncategorized", false)
        val result = snapshot.transactions.filter { tx ->
            (accountId == null || tx.accountId == accountId) &&
                (category == null || category(tx)) &&
                (type == null || tx.type == type) &&
                (search == null || tx.note.contains(search, true)) &&
                (min == null || tx.amount >= min) && (max == null || tx.amount <= max) &&
                tx.date in from until to &&
                (!uncategorized || isUncategorized(tx))
        }
        return (if (a.optString("sort") == "largest") result.sortedByDescending { it.amount } else result) to null
    }

    private fun filterSchema(withSort: Boolean) = obj(
        "text" to str("Text the transaction's description contains (case-insensitive)."),
        "category" to str("Category (\"Main/Category\", the name, a main category, or \"Uncategorized\")."),
        "uncategorized" to bool("Only uncategorized transactions."),
        "type" to enumStr("expense or income.", "expense", "income"),
        "min_amount" to num("Minimum amount (positive number)."),
        "max_amount" to num("Maximum amount."),
        "from" to str(FROM_DESC), "to" to str(TO_DESC),
        "account" to str(ACCOUNT_DESC),
        "limit" to int("Max rows."),
        *(if (withSort) arrayOf("sort" to enumStr("newest (default) or largest.", "newest", "largest")) else emptyArray())
    )

    /** [from, to) from "from"/"to" (YYYY-MM-DD or YYYY-MM; "to" inclusive). */
    private fun period(a: JSONObject, defaultFrom: Long): Pair<Long, Long> {
        val from = text(a, "from")?.let { parseDate(it, end = false) } ?: defaultFrom
        val to = text(a, "to")?.let { parseDate(it, end = true) } ?: Long.MAX_VALUE
        return from to to
    }

    private fun parseDate(raw: String, end: Boolean): Long? {
        val value = raw.trim()
        return runCatching {
            if (value.length == 7) {
                val start = monthLabel.parse(value)!!.time
                if (end) FinanceAnalysis.monthStart(start, 1) else start
            } else {
                val start = day.parse(value.take(10))!!.time
                if (end) start + DAY_MS else start
            }
        }.getOrNull()
    }

    private fun periodText(from: Long, to: Long) = when {
        from == Long.MIN_VALUE && to == Long.MAX_VALUE -> "for all time"
        to == Long.MAX_VALUE -> "from ${day.format(from)}"
        from == Long.MIN_VALUE -> "until ${day.format(to - 1)}"
        else -> "from ${day.format(from)} to ${day.format(to - 1)}"
    }

    private fun row(tx: TransactionWithDetails) =
        "${tx.id} | ${day.format(tx.date)} | ${tx.accountName} | ${categoryLabel(tx)}${if (isTransferCategory(tx.mainCategoryName, tx.categoryName)) " (transfer)" else ""} | " +
            "${money(signed(tx))} | ${tx.note.replace('\n', ' ').take(60)}"

    private fun signed(tx: TransactionWithDetails) = if (tx.type == TransactionType.INCOME) tx.amount else -tx.amount

    private fun change(old: Double?, new: Double) = when {
        new <= 0 -> "remove${old?.let { " (was ${money(it)})" } ?: ""}"
        old == null -> money(new)
        else -> "${money(old)} → ${money(new)}"
    }

    private fun money(value: Double) = String.format(Locale.US, "%,.2f %s", value, currency)
    private fun pct(value: Double) = String.format(Locale.US, "%.0f%%", value)

    private fun compact(json: JSONObject): String {
        val copy = JSONObject()
        json.keys().forEach { key ->
            val v = json.opt(key)
            if (v != null && v != JSONObject.NULL && v.toString().isNotBlank()) copy.put(key, v)
        }
        return copy.toString()
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_NOTE_GROUPS = 200
        private const val ACCOUNT_DESC = "Account name; omit for all accounts."
        private const val FROM_DESC = "Start date, YYYY-MM-DD or YYYY-MM."
        private const val TO_DESC = "End date (inclusive), YYYY-MM-DD or YYYY-MM."

        private fun text(a: JSONObject, key: String): String? =
            if (a.has(key) && !a.isNull(key)) a.optString(key).trim().takeIf { it.isNotEmpty() } else null

        private fun number(a: JSONObject, key: String): Double? =
            if (a.has(key) && !a.isNull(key)) a.optDouble(key).takeIf { !it.isNaN() }?.let { abs(it) } else null

        private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
            JSONObject().put("type", "object")
                .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }

        private fun spec(name: String, description: String, parameters: JSONObject, required: List<String> = emptyList()) =
            ToolSpec(name, description, parameters.apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })

        private fun str(description: String) = JSONObject().put("type", "string").put("description", description)
        private fun num(description: String) = JSONObject().put("type", "number").put("description", description)
        private fun int(description: String) = JSONObject().put("type", "integer").put("description", description)
        private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
        private fun arr(description: String, items: JSONObject) =
            JSONObject().put("type", "array").put("description", description).put("items", items)
        private fun enumStr(description: String, vararg values: String) =
            JSONObject().put("type", "string").put("description", description).put("enum", JSONArray(values.toList()))
    }
}
