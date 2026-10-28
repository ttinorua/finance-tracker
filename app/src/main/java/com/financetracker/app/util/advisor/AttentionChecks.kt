package com.financetracker.app.util.advisor

import com.financetracker.app.data.prefs.UserAlert
import com.financetracker.app.data.prefs.AlertKind
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.SavingsGoal
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.SharedBillsStatus
import com.financetracker.app.util.findLikelyTransfers
import com.financetracker.app.util.isHabitCategory
import com.financetracker.app.util.isSalaryCategory
import com.financetracker.app.util.isTransferCategory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Most important first. GOOD is good news (a goal reached). */
enum class AttentionLevel { URGENT, WARNING, INFO, GOOD }

/** Opens the matching transactions from an attention card. */
data class AttentionFilter(
    val label: String,
    val type: TransactionType? = null,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val uncategorizedOnly: Boolean = false,
    val thisMonthOnly: Boolean = false
)

/**
 * One thing that needs the user's attention. [key] identifies it across checks (so a dismissed or
 * already-notified item isn't shown or sent again); it includes the month or the amount where the
 * same subject should come back later (e.g. a budget next month).
 */
data class AttentionItem(
    val key: String,
    val level: AttentionLevel,
    val title: String,
    val detail: String,
    /** Worth a phone notification (not every item is). */
    val notify: Boolean,
    /** What "Ask advisor" / "Fix" sends to the AI advisor. */
    val prompt: String? = null,
    val promptLabel: String = "Ask advisor",
    val filter: AttentionFilter? = null,
    /** A one-tap fix the app carries out itself (see [AttentionAction]). */
    val action: AttentionAction? = null
)

enum class AttentionActionKind {
    /** Re-categorize [AttentionAction.transactionIds] as "Other (Transfer)". */
    MARK_TRANSFER,
    /** Mark the merchant in [AttentionAction.text] as a fixed bill. */
    MARK_FIXED_MERCHANT
}

/** A button on an attention card that changes the person's data when tapped: always a
 * confirmed suggestion, never something done silently. */
data class AttentionAction(
    val label: String,
    val kind: AttentionActionKind,
    val transactionIds: List<Long> = emptyList(),
    val text: String? = null
)

data class BankConsent(val bankName: String, val validUntil: Long?)

/** Everything the checks look at — read once by [com.financetracker.app.data.advisor.AttentionMonitor]. */
data class AttentionInput(
    val now: Long,
    val currency: String,
    val transactions: List<TransactionWithDetails>,
    val accounts: List<Account>,
    val balances: Map<Long, Double>,
    val categories: List<Category>,
    val uncategorizedCategoryIds: Set<Long>,
    val excludeTransfers: Boolean,
    val shiftSalary: Boolean,
    val mainAccountId: Long?,
    val overallBudgets: Map<Long?, Double>,
    val categoryBudgets: Map<Pair<Long, Long?>, Double>,
    val fixedCategoryIds: Set<Long>,
    val goals: List<SavingsGoal>,
    val loans: List<Loan>,
    val consents: List<BankConsent>,
    val lastSyncFailure: String?,
    val backupEnabled: Boolean,
    val backupLastSuccessAt: Long?,
    val backupLastError: String?,
    /** Alerts the person set up (usually by asking the AI advisor). */
    val userAlerts: List<UserAlert> = emptyList(),
    /** Merchants confirmed as fixed bills ([com.financetracker.app.data.prefs.FixedMerchants]). */
    val fixedMerchants: Set<String> = emptySet(),
    /** The shared bills account's status, when one is set up. */
    val sharedBills: SharedBillsStatus? = null,
    val partnerName: String = "",
    /** When the partner's income was last typed in (Shared bills), for the yearly reminder. */
    val partnerIncomeUpdatedAt: Long? = null
)

/**
 * The checks behind the Dashboard's "Needs your attention" and its notifications. Plain rules on
 * the user's own data (no AI, so they're free, fast and run in the background); the AI advisor
 * comes in when the user taps Ask advisor on a card.
 */
object AttentionChecks {

    private const val DAY = 24L * 60 * 60 * 1000
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    fun run(input: AttentionInput): List<AttentionItem> {
        val items = mutableListOf<AttentionItem>()
        val all = FinanceAnalysis.counted(input.transactions, null, input.excludeTransfers, input.shiftSalary, input.mainAccountId)
        // Moving money between your own accounts is never a subscription or an unusual payment.
        val payments = all.filter { !isTransferCategory(it.mainCategoryName, it.categoryName) }
        items += budgets(input)
        items += userAlerts(input)
        items += recurring(input, payments)
        items += unusual(input, payments)
        items += balances(input)
        items += goals(input)
        items += loans(input)
        items += connections(input)
        items += uncategorized(input)
        items += likelyTransfers(input)
        items += sharedBills(input)
        items += monthlyReview(input, all)
        return items.sortedBy { it.level.ordinal }
    }

    // ---------------------------------------------------------------- budgets

    private fun budgets(input: AttentionInput): List<AttentionItem> {
        val start = FinanceAnalysis.monthStart(input.now)
        val month = monthKey(input.now)
        val elapsed = FinanceAnalysis.elapsedFractionOfMonth(input.now)
        val dayOfMonth = ((input.now - start) / DAY).toInt() + 1
        val scopes = input.overallBudgets.keys + input.categoryBudgets.keys.map { it.second }
        val items = mutableListOf<AttentionItem>()
        for (scope in scopes.toSet()) {
            val spending = FinanceAnalysis.counted(input.transactions, scope, input.excludeTransfers, input.shiftSalary, input.mainAccountId)
                .filter { it.type == TransactionType.EXPENSE && it.date >= start }
            val scopeName = scope?.let { id -> input.accounts.firstOrNull { it.id == id }?.name }
            val lines = mutableListOf<Triple<String, Long?, Double>>()
            input.overallBudgets[scope]?.let { lines += Triple("Overall budget", null, it) }
            input.categoryBudgets.filterKeys { it.second == scope }.forEach { (key, budget) ->
                val category = input.categories.firstOrNull { it.id == key.first } ?: return@forEach
                lines += Triple(category.name, category.id, budget)
            }
            for ((name, categoryId, budget) in lines) {
                if (budget <= 0) continue
                val spent = spending.filter { categoryId == null || it.categoryId == categoryId }.sumOf { it.amount }
                val line = FinanceAnalysis.BudgetLine(name, budget, spent, elapsed)
                val label = name + (scopeName?.let { " · $it" } ?: "")
                val filter = AttentionFilter(name, TransactionType.EXPENSE, categoryId, scope, thisMonthOnly = true)
                val keyBase = "${scope ?: "all"}:${categoryId ?: "overall"}:$month"
                when {
                    spent > budget -> items += AttentionItem(
                        "budget-over:$keyBase", AttentionLevel.WARNING,
                        "$label is over budget",
                        "${money(input, spent)} spent of ${money(input, budget)} this month (${money(input, spent - budget)} over).",
                        notify = true,
                        prompt = "My $name budget${scopeName?.let { " for $it" } ?: ""} is over this month. What pushed it over, and what should I do for the rest of the month?",
                        filter = filter
                    )
                    // Projections are too jumpy in the first days of a month.
                    dayOfMonth >= 6 && line.onTrackToExceed -> items += AttentionItem(
                        "budget-pace:$keyBase", AttentionLevel.WARNING,
                        "$label is on track to go over",
                        "${money(input, spent)} of ${money(input, budget)} spent with ${pct((1 - elapsed) * 100)} of the month left — " +
                            "about ${money(input, line.projected)} by month end at this pace.",
                        notify = true,
                        prompt = "My $name budget${scopeName?.let { " for $it" } ?: ""} is on track to be exceeded this month. Where is the money going and how can I stay within it?",
                        filter = filter
                    )
                }
            }
        }
        return items
    }

    // ---------------------------------------------------------------- the person's own alerts

    private fun userAlerts(input: AttentionInput): List<AttentionItem> {
        val start = FinanceAnalysis.monthStart(input.now)
        val month = monthKey(input.now)
        val items = mutableListOf<AttentionItem>()
        for (alert in input.userAlerts.filter { it.enabled && it.limit > 0 }) {
            val scopeName = alert.accountId?.let { id -> input.accounts.firstOrNull { it.id == id }?.name }
            val expenses = FinanceAnalysis.counted(input.transactions, alert.accountId, input.excludeTransfers, input.shiftSalary, input.mainAccountId)
                .filter { it.type == TransactionType.EXPENSE }
            when (alert.kind) {
                AlertKind.CATEGORY_LIMIT -> {
                    val categoryId = alert.categoryId ?: continue
                    val name = input.categories.firstOrNull { it.id == categoryId }?.name ?: alert.categoryName ?: continue
                    val spent = expenses.filter { it.categoryId == categoryId && it.date >= start }.sumOf { it.amount }
                    val label = name + (scopeName?.let { " · $it" } ?: "")
                    val filter = AttentionFilter(name, TransactionType.EXPENSE, categoryId, alert.accountId, thisMonthOnly = true)
                    val share = spent / alert.limit * 100
                    when {
                        spent > alert.limit -> items += AttentionItem(
                            "alert-over:${alert.id}:$month", AttentionLevel.WARNING,
                            "$label is over your ${money(input, alert.limit)} alert",
                            "${money(input, spent)} spent this month (${money(input, spent - alert.limit)} over). You asked to be told.",
                            notify = true,
                            prompt = "My $name spending went over the ${money(input, alert.limit)} alert I set. What pushed it over and how can I cut back for the rest of the month?",
                            filter = filter
                        )
                        alert.warnAtPercent != null && share >= alert.warnAtPercent -> items += AttentionItem(
                            "alert-early:${alert.id}:$month", AttentionLevel.INFO,
                            "$label at ${pct(share)} of ${money(input, alert.limit)}",
                            "${money(input, spent)} spent so far this month. Your alert is at ${money(input, alert.limit)}.",
                            notify = true,
                            prompt = "I'm at ${pct(share)} of my ${money(input, alert.limit)} alert for $name this month. How do I stay under it?",
                            filter = filter
                        )
                    }
                }
                AlertKind.LARGE_PAYMENT -> {
                    // Only recent payments, so creating an alert doesn't bring up years of old ones.
                    expenses.filter { it.amount >= alert.limit && input.now - it.date <= 14 * DAY && it.date <= input.now }
                        .forEach { tx ->
                            items += AttentionItem(
                                "alert-big:${alert.id}:${tx.id}", AttentionLevel.INFO,
                                "Payment over ${money(input, alert.limit)}: ${tx.note.ifBlank { tx.categoryName ?: "a payment" }}",
                                "${money(input, tx.amount)} on ${date(tx.date)} from ${tx.accountName}.",
                                notify = true,
                                prompt = "Tell me about the ${money(input, tx.amount)} payment \"${tx.note}\" on ${date(tx.date)}. Is it a one-off or does it repeat?"
                            )
                        }
                }
            }
        }
        return items
    }

    // ---------------------------------------------------------------- subscriptions & payments

    private fun recurring(input: AttentionInput, all: List<TransactionWithDetails>): List<AttentionItem> {
        val items = mutableListOf<AttentionItem>()
        val payments = FinanceAnalysis.recurringPayments(all, input.now)
        // A bill-like repeat that isn't fixed yet: suggest it, a few at a time, biggest first.
        // Weekly repeats and habit categories (groceries, parking, fuel) are routines, not bills.
        items += payments.filter { p ->
            p.cadence != FinanceAnalysis.Cadence.WEEKLY && !isHabitCategory(p.category) &&
                p.merchant !in input.fixedMerchants && p.categoryId !in input.fixedCategoryIds
        }.take(3).map { p ->
            AttentionItem(
                "fixed-suggest:${p.merchant}", AttentionLevel.INFO,
                "${display(p.merchant)} looks ${p.cadence.label} (${money(input, p.lastAmount)})",
                "Mark it as a fixed bill to count it in upcoming expenses. Next one around ${date(p.nextDue)}.",
                notify = false,
                action = AttentionAction("Mark as fixed", AttentionActionKind.MARK_FIXED_MERCHANT, text = p.merchant)
            )
        }
        for (payment in payments) {
            val change = payment.priceChangePct
            if (change != null && change >= 5 && input.now - payment.lastDate <= 40 * DAY) {
                items += AttentionItem(
                    "price:${payment.merchant}:${payment.lastDate}", AttentionLevel.INFO,
                    "${display(payment.merchant)} went up ${pct(change)}",
                    "Now ${money(input, payment.lastAmount)} ${payment.cadence.label}, was ${money(input, payment.previousAmount!!)} — " +
                        "${money(input, (payment.lastAmount - payment.previousAmount) * payment.cadence.perYear)} more a year.",
                    notify = true,
                    prompt = "${display(payment.merchant)} raised its price from ${money(input, payment.previousAmount)} to ${money(input, payment.lastAmount)}. Is it worth keeping, and are there cheaper alternatives?"
                )
            }
            val newWindow = when (payment.cadence) {
                FinanceAnalysis.Cadence.WEEKLY -> 30 * DAY
                FinanceAnalysis.Cadence.MONTHLY -> 100 * DAY
                else -> 0L
            }
            if (newWindow > 0 && input.now - payment.firstDate <= newWindow) {
                items += AttentionItem(
                    "newsub:${payment.merchant}", AttentionLevel.INFO,
                    "New repeating payment: ${display(payment.merchant)}",
                    "${money(input, payment.lastAmount)} ${payment.cadence.label} since ${date(payment.firstDate)} — " +
                        "${money(input, payment.yearlyCost)} a year.",
                    notify = true,
                    prompt = "I have a new repeating payment to ${display(payment.merchant)} (${money(input, payment.lastAmount)} ${payment.cadence.label}). Do I need it, and how does it fit my budget?"
                )
            }
        }
        return items
    }

    private fun unusual(input: AttentionInput, all: List<TransactionWithDetails>): List<AttentionItem> {
        val expenses = all.filter { it.type == TransactionType.EXPENSE }
        val recent = expenses.filter { input.now - it.date in 0..(7 * DAY) }
        if (recent.isEmpty()) return emptyList()
        val threeMonthsAgo = FinanceAnalysis.monthStart(input.now, -3)
        val monthStart = FinanceAnalysis.monthStart(input.now)
        val avgMonthly = expenses.filter { it.date in threeMonthsAgo until monthStart }.sumOf { it.amount } / 3
        val byMerchant = expenses.groupBy { FinanceAnalysis.merchantOf(it.note) }
        return recent.mapNotNull { tx ->
            if (tx.categoryId in input.fixedCategoryIds) return@mapNotNull null
            val merchant = FinanceAnalysis.merchantOf(tx.note)
            val earlier = byMerchant[merchant].orEmpty().filter { it.date < tx.date }.map { it.amount }.sorted()
            val reason = when {
                earlier.size >= 3 -> {
                    val median = earlier[earlier.size / 2]
                    if (tx.amount > median * 2 && tx.amount - median > 300) "usually about ${money(input, median)} there" else null
                }
                earlier.isEmpty() && avgMonthly > 0 && tx.amount >= maxOf(3000.0, avgMonthly * 0.3) ->
                    "${pct(tx.amount / avgMonthly * 100)} of a normal month's spending, at a new place"
                else -> null
            } ?: return@mapNotNull null
            AttentionItem(
                "unusual:${tx.id}", AttentionLevel.WARNING,
                "Unusual payment: ${money(input, tx.amount)}",
                "${tx.note.trim().take(40)} on ${date(tx.date)} — $reason.",
                notify = true,
                prompt = "There's an unusual payment of ${money(input, tx.amount)} to \"${tx.note.trim().take(40)}\" on ${date(tx.date)}. How does it affect my month?",
                filter = AttentionFilter(tx.categoryName ?: "Uncategorized", TransactionType.EXPENSE, tx.categoryId, tx.accountId, uncategorizedOnly = tx.categoryId == null, thisMonthOnly = true)
            )
        }
    }

    private fun balances(input: AttentionInput): List<AttentionItem> = input.accounts.mapNotNull { account ->
        val balance = input.balances[account.id] ?: return@mapNotNull null
        if (balance >= 0) return@mapNotNull null
        AttentionItem(
            "negative:${account.id}:${monthKey(input.now)}", AttentionLevel.URGENT,
            "${account.name} is overdrawn",
            "The balance is ${money(input, balance)}.",
            notify = true,
            prompt = "My ${account.name} account is overdrawn (${money(input, balance)}). What should I do, and what's coming out before my next income?"
        )
    }

    // ---------------------------------------------------------------- goals & loans

    private fun goals(input: AttentionInput): List<AttentionItem> = input.goals.mapNotNull { goal ->
        val target = goal.targetAmount?.takeIf { it > 0 } ?: return@mapNotNull null
        val saved = goal.accountId?.let { input.balances[it] } ?: goal.savedAmount ?: return@mapNotNull null
        val targetDate = GoalTracking.parseTargetDate(goal.targetDate)
        val status = GoalTracking.status(target, saved, targetDate, goal.startedAt, goal.startAmount, goal.monthlySaving, input.now)
        when {
            status.reached -> AttentionItem(
                "goal-reached:${goal.id}", AttentionLevel.GOOD,
                "Goal reached: ${goal.name}",
                "${money(input, saved)} saved of ${money(input, target)}.",
                notify = true,
                prompt = "I reached my savings goal \"${goal.name}\". What would be a good next goal?"
            )
            status.behind && targetDate != null -> {
                val projection = status.projectedMonths?.let {
                    "at this pace you'll reach it in ${monthName(FinanceAnalysis.monthStart(input.now, it))} instead of ${monthName(targetDate)}"
                } ?: "at this pace you won't reach it"
                AttentionItem(
                    "goal-behind:${goal.id}:${monthKey(input.now)}", AttentionLevel.WARNING,
                    "${goal.name} is behind",
                    "${money(input, status.behindBy)} behind — $projection. " +
                        (status.neededPerMonth?.let { "Saving ${money(input, it)} a month would make it." } ?: ""),
                    notify = true,
                    prompt = "My savings goal \"${goal.name}\" is behind: ${money(input, saved)} of ${money(input, target)} saved, " +
                        "target ${goal.targetDate}. Make me a plan to get back on track — what to cut, how much to move each month, or whether to move the date."
                )
            }
            else -> null
        }
    }

    private fun loans(input: AttentionInput): List<AttentionItem> = input.loans.flatMap { loan ->
        listOfNotNull(
            parseDate(loan.nextRateReset)?.takeIf { it - input.now in 0..(60 * DAY) }?.let { reset ->
                AttentionItem(
                    "rate-reset:${loan.id}:$reset", AttentionLevel.INFO,
                    "${loan.name}: interest rate resets ${date(reset)}",
                    "Your rate will be set again then — a good moment to check whether to keep the loan type.",
                    notify = true,
                    prompt = "The interest rate on my loan \"${loan.name}\" resets on ${date(reset)}. What could happen to my payment, and should I consider switching loan type?"
                )
            },
            // A home value older than half a year makes friværdi stale.
            loan.homeValueUpdatedAt?.takeIf { input.now - it > 182 * DAY }?.let { updated ->
                AttentionItem(
                    "home-value:${loan.id}:$updated", AttentionLevel.INFO,
                    "Update your home's value",
                    "It was last updated ${date(updated)}. Settings > Mortgage uses it for your friværdi.",
                    notify = false
                )
            },
            // Once a year the årsopgørelse gives the exact debt; the estimate drifts without it.
            loan.debtAsOf?.takeIf { loan.paymentMerchant.isNotBlank() && input.now - it > 330 * DAY }?.let { asOf ->
                AttentionItem(
                    "mortgage-statement:${loan.id}:$asOf", AttentionLevel.INFO,
                    "${loan.name}: correct the debt with the årsopgørelse",
                    "The debt has been estimated from payments since ${date(asOf)}. Upload the yearly statement in Settings > Mortgage.",
                    notify = false
                )
            },
            parseDate(loan.interestOnlyUntil)?.takeIf { it - input.now in 0..(180 * DAY) }?.let { end ->
                AttentionItem(
                    "io-end:${loan.id}:$end", AttentionLevel.WARNING,
                    "${loan.name}: interest-only ends ${date(end)}",
                    "Repayments start then, so the monthly payment will go up.",
                    notify = true,
                    prompt = "The interest-only period on my loan \"${loan.name}\" ends on ${date(end)}. How much will my payment rise, and how should I prepare?"
                )
            }
        )
    }

    // ---------------------------------------------------------------- shared bills

    private fun sharedBills(input: AttentionInput): List<AttentionItem> {
        val status = input.sharedBills ?: return emptyList()
        val partner = input.partnerName.ifBlank { "Partner" }
        val month = monthKey(input.now)
        val day = ((input.now - FinanceAnalysis.monthStart(input.now)) / DAY).toInt() + 1
        val items = mutableListOf<AttentionItem>()
        status.warnings.forEach { w ->
            items += AttentionItem(
                "shared-short:${w.label}:${w.dueDate}", AttentionLevel.WARNING,
                "${display(w.label)} ${money(input, w.amount)} due ${date(w.dueDate)}",
                "The bills account will be ${money(input, w.shortBy)} short. Adding ${money(input, w.extraPerMonth)} a month in total until then covers it.",
                notify = true
            )
        }
        // Give transfers until the 10th before saying anyone is behind.
        if (day >= 10) {
            if (status.partnerShortThisMonth > 0) items += AttentionItem(
                "shared-partner:$month", AttentionLevel.INFO,
                "$partner is ${money(input, status.partnerShortThisMonth)} short on shared bills",
                "Paid in ${money(input, status.partnerPaidInThisMonth)} of ${money(input, status.partnerToTransfer)} this month.",
                notify = false
            )
            if (status.myShortThisMonth > 0) items += AttentionItem(
                "shared-me:$month", AttentionLevel.INFO,
                "You're ${money(input, status.myShortThisMonth)} short on shared bills",
                "Paid in ${money(input, status.myPaidInThisMonth)} of ${money(input, status.myToTransfer)} this month.",
                notify = false
            )
        }
        // 1m: payday — salary landed in the last 3 days and this month's transfer isn't done yet.
        val paidRecently = input.transactions.any {
            it.type == TransactionType.INCOME && isSalaryCategory(it.mainCategoryName, it.categoryName) && input.now - it.date in 0..(3 * DAY)
        }
        if (paidRecently && status.hasTarget && status.myShortThisMonth > 0) items += AttentionItem(
            "shared-payday:$month", AttentionLevel.INFO,
            "Payday: transfer ${money(input, status.myShortThisMonth)} to the bills account",
            "Your ${(status.myShare * 100).roundToInt()}% share of the shared bills, minus the bills you already paid by card.",
            notify = true
        )
        // 1k: a typed-in income goes stale; once a year, ask to check it.
        if (!status.partnerConnected) input.partnerIncomeUpdatedAt?.takeIf { input.now - it > 365 * DAY }?.let { at ->
            items += AttentionItem(
                "shared-partner-income:$at", AttentionLevel.INFO,
                "Is $partner's income still ${money(input, status.partnerIncome)}?",
                "It was typed in ${date(at)}. Update it in Settings > Shared bills so the split stays fair.",
                notify = false
            )
        }
        if (status.unknown.isNotEmpty()) items += AttentionItem(
            "shared-unknown:${status.unknown.joinToString(",") { it.transaction.id.toString() }}", AttentionLevel.INFO,
            "Who sent this? ${status.unknown.size} payment${if (status.unknown.size == 1) "" else "s"} into the bills account",
            "Open Settings > Shared bills to say who it was from.",
            notify = false
        )
        return items
    }

    // ---------------------------------------------------------------- transfers

    /** Money that left one account and arrived in another the same day, not yet marked as a
     * transfer: otherwise it counts as spending on one side and income on the other. */
    private fun likelyTransfers(input: AttentionInput): List<AttentionItem> =
        findLikelyTransfers(input.transactions, input.now).take(5).map { pair ->
            AttentionItem(
                pair.key, AttentionLevel.INFO,
                "Looks like a transfer: ${money(input, pair.out.amount)}",
                "From ${pair.out.accountName} to ${pair.into.accountName} on ${date(pair.out.date)}. Marking it keeps it out of your spending and income.",
                notify = false,
                action = AttentionAction("Mark as transfer", AttentionActionKind.MARK_TRANSFER, listOf(pair.out.id, pair.into.id))
            )
        }

    // ---------------------------------------------------------------- the app itself

    private fun connections(input: AttentionInput): List<AttentionItem> {
        val items = mutableListOf<AttentionItem>()
        input.consents.forEach { consent ->
            val until = consent.validUntil ?: return@forEach
            val left = until - input.now
            when {
                left <= 0 -> items += AttentionItem(
                    "consent-expired:${consent.bankName}:$until", AttentionLevel.URGENT,
                    "${consent.bankName} connection has expired",
                    "New transactions won't sync until you reconnect in Settings > Bank.", notify = true
                )
                left <= 14 * DAY -> items += AttentionItem(
                    "consent:${consent.bankName}:$until", AttentionLevel.WARNING,
                    "Reconnect ${consent.bankName} soon",
                    "The bank connection expires ${date(until)}. Reconnect in Settings > Bank to keep syncing.", notify = true
                )
            }
        }
        input.lastSyncFailure?.takeIf { it.isNotBlank() }?.let {
            items += AttentionItem(
                "sync-failed:${it.hashCode()}:${dayKey(input.now)}", AttentionLevel.WARNING,
                "Bank sync failed", it.take(160), notify = true
            )
        }
        if (input.backupEnabled) {
            if (!input.backupLastError.isNullOrBlank()) {
                items += AttentionItem(
                    "backup-failed:${input.backupLastError.hashCode()}", AttentionLevel.WARNING,
                    "Automatic backup failed", input.backupLastError.take(160) + " Check Settings > Backup.", notify = true
                )
            } else if (input.backupLastSuccessAt != null && input.now - input.backupLastSuccessAt > 10 * DAY) {
                items += AttentionItem(
                    "backup-old:${input.backupLastSuccessAt}", AttentionLevel.INFO,
                    "No backup for ${(input.now - input.backupLastSuccessAt) / DAY} days",
                    "The last automatic backup was ${date(input.backupLastSuccessAt)}. Open Settings > Backup and tap Back up now.", notify = false
                )
            }
        }
        return items
    }

    private fun uncategorized(input: AttentionInput): List<AttentionItem> {
        val count = input.transactions.count { it.categoryId == null || it.categoryId in input.uncategorizedCategoryIds }
        if (count < 10) return emptyList()
        return listOf(
            AttentionItem(
                // Comes back after a dismiss once the backlog has grown by another 25.
                "uncategorized:${count / 25}", AttentionLevel.INFO,
                "$count uncategorized transactions",
                "Categorized transactions make budgets, insights and advice accurate.",
                notify = false,
                prompt = "Categorize my uncategorized transactions",
                promptLabel = "Fix",
                filter = AttentionFilter("Uncategorized", uncategorizedOnly = true)
            )
        )
    }

    // ---------------------------------------------------------------- monthly review

    private fun monthlyReview(input: AttentionInput, all: List<TransactionWithDetails>): List<AttentionItem> {
        val start = FinanceAnalysis.monthStart(input.now)
        if (input.now - start > 7 * DAY) return emptyList()
        val months = FinanceAnalysis.monthlyTotals(all, 7, input.now).dropLast(1)
        val last = months.last()
        if (last.income == 0.0 && last.expense == 0.0) return emptyList()
        val before = months.dropLast(1).filter { it.income > 0 || it.expense > 0 }
        val avgExpense = before.map { it.expense }.average().takeIf { !it.isNaN() }
        val lastStart = last.monthStart
        val lastSpending = all.filter { it.type == TransactionType.EXPENSE && it.date in lastStart until start }
        val earlierSpending = all.filter { it.type == TransactionType.EXPENSE && it.date in FinanceAnalysis.monthStart(input.now, -4) until lastStart }
        val earlierAvg = earlierSpending.groupBy { it.categoryName ?: "Uncategorized" }.mapValues { (_, t) -> t.sumOf { it.amount } / 3 }
        val biggestRise = lastSpending.groupBy { it.categoryName ?: "Uncategorized" }
            .map { (name, t) -> name to t.sumOf { it.amount } - (earlierAvg[name] ?: 0.0) }
            .maxByOrNull { it.second }?.takeIf { it.second > 300 }
        val goalLines = input.goals.mapNotNull { goal ->
            val target = goal.targetAmount ?: return@mapNotNull null
            val saved = goal.accountId?.let { input.balances[it] } ?: goal.savedAmount ?: return@mapNotNull null
            "${goal.name} ${pct(saved / target * 100)}"
        }
        val detail = buildString {
            append("Income ${money(input, last.income)}, spending ${money(input, last.expense)}, ")
            append(if (last.net >= 0) "saved ${money(input, last.net)}" else "${money(input, -last.net)} more out than in")
            last.savingsRatePct?.takeIf { last.net >= 0 }?.let { append(" (${pct(it)})") }
            append(".")
            avgExpense?.takeIf { it > 0 }?.let {
                val diff = (last.expense - it) / it * 100
                if (abs(diff) >= 5) append(" Spending was ${pct(abs(diff))} ${if (diff > 0) "above" else "below"} your usual month.")
            }
            biggestRise?.let { append(" Biggest rise: ${it.first} (+${money(input, it.second)}).") }
            if (goalLines.isNotEmpty()) append(" Goals: ${goalLines.joinToString(", ")}.")
        }
        val name = monthName(lastStart)
        return listOf(
            AttentionItem(
                "review:${monthKey(lastStart)}", AttentionLevel.INFO, "Your $name review", detail,
                notify = true,
                prompt = "Give me a review of $name: what went well, what didn't, how my goals and budgets are doing, and what to change this month."
            )
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun money(input: AttentionInput, value: Double) = Formatters.currency(value, input.currency)
    private fun pct(value: Double) = String.format(Locale.US, "%.0f%%", value)
    private fun display(merchant: String) = merchant.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    private fun date(time: Long) = SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = utc }.format(time)
    private fun monthName(time: Long) = SimpleDateFormat("MMMM yyyy", Locale.US).apply { timeZone = utc }.format(time)
    private fun monthKey(time: Long) = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = utc }.format(time)
    private fun dayKey(time: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.format(time)

    /** Dates as the loan reader writes them: YYYY-MM-DD (or YYYY-MM), or Danish DD-MM-YYYY / DD.MM.YYYY. */
    internal fun parseDate(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        GoalTracking.parseTargetDate(text)?.let { return it }
        val danish = Regex("""(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})""").find(text) ?: return null
        val (d, m, y) = danish.destructured
        return GoalTracking.parseTargetDate("$y-$m-$d")
    }
}
