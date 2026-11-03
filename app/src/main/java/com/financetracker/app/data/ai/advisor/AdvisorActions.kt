package com.financetracker.app.data.ai.advisor

import com.financetracker.app.data.prefs.WeeklySummarySettings
import com.financetracker.app.data.prefs.UserAlerts
import com.financetracker.app.data.prefs.UserAlert
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.util.CategoryNames
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.prefs.SavingsGoal
import com.financetracker.app.data.repository.FinanceRepository
import kotlinx.coroutines.flow.first

/** A change the advisor prepared. Nothing happens until the user taps Apply on its card; each
 * plan records what it replaced so Undo can put it back. */
sealed interface ActionPlan {

    /** Adds (or replaces, same id) an alert the app checks itself. */
    data class SaveAlert(val alert: UserAlert) : ActionPlan

    data class RemoveAlert(val alert: UserAlert) : ActionPlan

    data class SetWeeklySummary(val enabled: Boolean) : ActionPlan

    /** Moves transactions to a category. [newCategory] is created on Apply when the target
     * doesn't exist yet (its transactions then have a null [Assignment.categoryId]). */
    data class Recategorize(val assignments: List<Assignment>, val newCategory: Category? = null) : ActionPlan

    data class Assignment(val transactionIds: List<Long>, val categoryId: Long?)

    /** Budgets for one scope ([accountId] null = All accounts). [overall] and [categories] hold the
     * new amounts, null meaning "remove"; [setOverall] says whether the overall budget changes. */
    data class SetBudgets(
        val accountId: Long?,
        val setOverall: Boolean,
        val overall: Double?,
        val categories: Map<Long, Double?>
    ) : ActionPlan

    data class SetFixed(val changes: Map<Long, Boolean>) : ActionPlan

    data class CreateCategory(val category: Category) : ActionPlan

    data class SaveLoan(val loan: Loan) : ActionPlan

    data class SaveGoal(val goal: SavingsGoal) : ActionPlan
}

/** What Apply replaced, to put back on Undo. */
sealed interface UndoInfo {
    data class Categories(val previous: Map<Long, Long?>, val createdCategoryId: Long?) : UndoInfo
    data class Budgets(val accountId: Long?, val overall: Double?, val restoreOverall: Boolean, val categories: Map<Long, Double?>) : UndoInfo
    data class Fixed(val previous: Map<Long, Boolean>) : UndoInfo
    data class CreatedCategory(val id: Long) : UndoInfo
    data class Alert(val previous: UserAlert?, val id: String) : UndoInfo
    data class WeeklySummary(val previous: Boolean) : UndoInfo
    /** Apply changed nothing, so there's nothing to put back. */
    data object Nothing : UndoInfo
    data class Loans(val previous: Loan?, val id: String) : UndoInfo
    data class Goals(val previous: SavingsGoal?, val id: String) : UndoInfo
}

enum class CardStatus { PENDING, APPLIED, UNDONE, DISMISSED }

/** A card under an advisor reply: either a change to review ([plan] set, with Apply/Dismiss and
 * then Undo), or a list of transactions to look at. */
data class AdvisorCard(
    val id: Int,
    val title: String,
    val lines: List<String> = emptyList(),
    val transactions: List<TransactionWithDetails> = emptyList(),
    val plan: ActionPlan? = null,
    val status: CardStatus = CardStatus.PENDING,
    val undo: UndoInfo? = null,
    /** The outcome of the last Apply/Undo, shown on the card. */
    val result: String? = null
)

object AdvisorActions {

    /** Applies [plan] and returns a short outcome plus what's needed to undo it. */
    suspend fun apply(repository: FinanceRepository, plan: ActionPlan): Pair<String, UndoInfo> = when (plan) {
        is ActionPlan.Recategorize -> {
            var createdId: Long? = null
            val newId = plan.newCategory?.let { new ->
                val existing = CategoryNames.existing(repository.getCategories(), new.name, new.type)
                existing?.id ?: repository.upsertCategory(new).also { createdId = it }
            }
            val previous = mutableMapOf<Long, Long?>()
            plan.assignments.forEach { assignment ->
                val target = assignment.categoryId ?: newId ?: return@forEach
                assignment.transactionIds.forEach { id ->
                    val tx = repository.getTransaction(id) ?: return@forEach
                    if (tx.categoryId == target) return@forEach
                    previous[id] = tx.categoryId
                    repository.updateTransaction(tx.copy(categoryId = target))
                }
            }
            "Moved ${previous.size} transaction${if (previous.size == 1) "" else "s"}." to UndoInfo.Categories(previous, createdId)
        }
        is ActionPlan.SetBudgets -> {
            val previousOverall = BudgetLimits.overallBudgetFor(plan.accountId)
            val previousCategories = plan.categories.keys.associateWith { BudgetLimits.categoryBudgetFor(it, plan.accountId) }
            if (plan.setOverall) BudgetLimits.setOverallBudget(plan.accountId, plan.overall)
            plan.categories.forEach { (categoryId, amount) -> BudgetLimits.setCategoryBudget(categoryId, plan.accountId, amount) }
            "Budgets updated." to UndoInfo.Budgets(plan.accountId, previousOverall, plan.setOverall, previousCategories)
        }
        is ActionPlan.SetFixed -> {
            val current = FixedExpenseCategories.fixedCategoryIds.value
            val previous = plan.changes.keys.associateWith { it in current }
            plan.changes.forEach { (id, fixed) -> FixedExpenseCategories.setFixed(id, fixed) }
            "Fixed costs updated." to UndoInfo.Fixed(previous)
        }
        is ActionPlan.SaveAlert -> {
            val previous = UserAlerts.alerts.value.firstOrNull { it.id == plan.alert.id }
            UserAlerts.save(plan.alert)
            "Alert saved. You'll get a notification when it's reached." to UndoInfo.Alert(previous, plan.alert.id)
        }
        is ActionPlan.RemoveAlert -> {
            UserAlerts.remove(plan.alert.id)
            "Alert removed." to UndoInfo.Alert(plan.alert, plan.alert.id)
        }
        is ActionPlan.SetWeeklySummary -> {
            val previous = WeeklySummarySettings.enabled.value
            WeeklySummarySettings.setEnabled(plan.enabled)
            (if (plan.enabled) "Weekly summary on: Sundays at 18:00." else "Weekly summary off.") to UndoInfo.WeeklySummary(previous)
        }
        is ActionPlan.CreateCategory -> {
            // Checked again here: the same category may have been added since the card was made
            // (a second card, or an earlier Apply).
            val existing = CategoryNames.existing(repository.getCategories(), plan.category.name, plan.category.type)
            if (existing != null) {
                "Already there as ${existing.mainCategory} › ${existing.name}, so nothing was added." to UndoInfo.Nothing
            } else {
                val main = CategoryNames.canonicalMain(repository.getCategories(), plan.category.mainCategory)
                val id = repository.upsertCategory(plan.category.copy(mainCategory = main))
                "Category created." to UndoInfo.CreatedCategory(id)
            }
        }
        is ActionPlan.SaveLoan -> {
            val previous = LoansAndGoals.loans.value.firstOrNull { it.id == plan.loan.id }
            LoansAndGoals.saveLoan(plan.loan)
            "Saved to My loans & goals." to UndoInfo.Loans(previous, plan.loan.id)
        }
        is ActionPlan.SaveGoal -> {
            val previous = LoansAndGoals.goals.value.firstOrNull { it.id == plan.goal.id }
            val balance = plan.goal.accountId?.let { id -> repository.getAccounts().firstOrNull { it.id == id }?.let { repository.getAccountBalance(it) } }
            LoansAndGoals.saveGoal(plan.goal, balance ?: plan.goal.savedAmount)
            "Saved to My loans & goals." to UndoInfo.Goals(previous, plan.goal.id)
        }
    }

    suspend fun undo(repository: FinanceRepository, undo: UndoInfo): String = when (undo) {
        is UndoInfo.Categories -> {
            undo.previous.forEach { (id, categoryId) ->
                repository.getTransaction(id)?.let { repository.updateTransaction(it.copy(categoryId = categoryId)) }
            }
            undo.createdCategoryId?.let { deleteIfUnused(repository, it) }
            "Undone — ${undo.previous.size} transaction${if (undo.previous.size == 1) "" else "s"} moved back."
        }
        is UndoInfo.Budgets -> {
            if (undo.restoreOverall) BudgetLimits.setOverallBudget(undo.accountId, undo.overall)
            undo.categories.forEach { (categoryId, amount) -> BudgetLimits.setCategoryBudget(categoryId, undo.accountId, amount) }
            "Undone — budgets are back to how they were."
        }
        is UndoInfo.Fixed -> {
            undo.previous.forEach { (id, fixed) -> FixedExpenseCategories.setFixed(id, fixed) }
            "Undone."
        }
        UndoInfo.Nothing -> "Nothing to undo."
        is UndoInfo.Alert -> {
            if (undo.previous != null) UserAlerts.save(undo.previous) else UserAlerts.remove(undo.id)
            "Undone."
        }
        is UndoInfo.WeeklySummary -> {
            WeeklySummarySettings.setEnabled(undo.previous)
            "Undone."
        }
        is UndoInfo.CreatedCategory ->
            if (deleteIfUnused(repository, undo.id)) "Undone — category removed." else "That category is in use now, so it was kept."
        is UndoInfo.Loans -> {
            if (undo.previous != null) LoansAndGoals.saveLoan(undo.previous) else LoansAndGoals.deleteLoan(undo.id)
            "Undone."
        }
        is UndoInfo.Goals -> {
            if (undo.previous != null) LoansAndGoals.restoreGoal(undo.previous) else LoansAndGoals.deleteGoal(undo.id)
            "Undone."
        }
    }

    private suspend fun deleteIfUnused(repository: FinanceRepository, categoryId: Long): Boolean {
        val inUse = repository.observeTransactions().first().any { it.categoryId == categoryId }
        if (inUse) return false
        repository.getCategories().firstOrNull { it.id == categoryId }?.let { repository.deleteCategory(it) }
        return true
    }
}
