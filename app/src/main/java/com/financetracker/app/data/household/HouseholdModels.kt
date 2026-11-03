package com.financetracker.app.data.household

/** One line of the shared budget: a main category and its monthly limit. */
data class HouseholdBudgetLine(
    val id: String,
    val mainCategory: String,
    val limit: Double,
    val updatedBy: String,
    val updatedAt: Long
)

/** A household member as the other phone sees them. */
data class HouseholdMember(val uid: String, val name: String, val sharesDetails: Boolean)

/** One account's spending in one main category for the month. [account] is the account's
 * private tag: the same on both phones for a joint account both of you connect. */
data class AccountTotal(val account: String, val mainCategory: String, val amount: Double)

/** One shared transaction (only when its owner shares details). */
data class SharedTransaction(
    val id: String,
    val account: String,
    val mainCategory: String,
    val category: String,
    val amount: Double,
    val date: Long,
    val text: String
)

/** What one phone shares for one month. */
data class MemberReport(
    val uid: String,
    val month: String,
    val totals: List<AccountTotal>,
    val transactions: List<SharedTransaction>?,
    val updatedAt: Long
)

enum class Payer { ME, PARTNER, JOINT }

/** Spending in one main category, split by who paid. */
data class HouseholdSplit(val me: Double = 0.0, val partner: Double = 0.0, val joint: Double = 0.0) {
    val total: Double get() = me + partner + joint
}

data class HouseholdTransaction(val transaction: SharedTransaction, val payer: Payer)

/** Adds both phones' reports together. An account reported by both phones is a joint account
 * you both connect: it's counted once (from whichever member sorts first, so both phones agree)
 * and shown as joint. */
object HouseholdMath {

    /** For each account, the member whose numbers count, and whether it's joint. */
    private fun owners(reports: List<MemberReport>): Map<String, Pair<String, Boolean>> {
        val byAccount = mutableMapOf<String, MutableSet<String>>()
        reports.forEach { r -> r.totals.forEach { byAccount.getOrPut(it.account) { mutableSetOf() } += r.uid } }
        return byAccount.mapValues { (_, uids) -> uids.min() to (uids.size > 1) }
    }

    private fun payer(uid: String, joint: Boolean, myUid: String) = when {
        joint -> Payer.JOINT
        uid == myUid -> Payer.ME
        else -> Payer.PARTNER
    }

    fun splits(reports: List<MemberReport>, myUid: String): Map<String, HouseholdSplit> {
        val owners = owners(reports)
        val result = mutableMapOf<String, HouseholdSplit>()
        reports.forEach { r ->
            r.totals.forEach { t ->
                val (owner, joint) = owners.getValue(t.account)
                if (owner != r.uid) return@forEach
                val s = result[t.mainCategory] ?: HouseholdSplit()
                result[t.mainCategory] = when (payer(r.uid, joint, myUid)) {
                    Payer.ME -> s.copy(me = s.me + t.amount)
                    Payer.PARTNER -> s.copy(partner = s.partner + t.amount)
                    Payer.JOINT -> s.copy(joint = s.joint + t.amount)
                }
            }
        }
        return result
    }

    /** The transactions behind [mainCategory] (or all of them), newest first. A member who
     * shares totals only contributes no transactions. */
    fun transactions(reports: List<MemberReport>, myUid: String, mainCategory: String? = null): List<HouseholdTransaction> {
        val owners = owners(reports)
        return reports.flatMap { r ->
            r.transactions.orEmpty()
                .filter { (mainCategory == null || it.mainCategory == mainCategory) && owners[it.account]?.first == r.uid }
                .map { HouseholdTransaction(it, payer(r.uid, owners.getValue(it.account).second, myUid)) }
        }.sortedByDescending { it.transaction.date }
    }
}

/** The shared-bills screen as the phone that set it up sees it, so the partner's phone shows the
 * same numbers. "Publisher" is the phone that wrote it. */
data class SharedBillsSnapshot(
    val accountName: String,
    val month: String,
    val publisherShare: Double,
    val monthlyBills: Double,
    val publisherTarget: Double,
    val otherTarget: Double,
    val publisherToTransfer: Double,
    val otherToTransfer: Double,
    val publisherPaidIn: Double,
    val otherPaidIn: Double,
    val publisherBalance: Double,
    val otherBalance: Double,
    /** The full screen, so both phones show the same: net incomes, card bills this month and the bill lines. */
    val publisherIncome: Double = 0.0,
    val otherIncome: Double = 0.0,
    val publisherCard: Double = 0.0,
    val otherCard: Double = 0.0,
    val payees: List<com.financetracker.app.util.SharedLine> = emptyList(),
    val publisherBills: List<com.financetracker.app.util.SharedLine> = emptyList(),
    val otherBills: List<com.financetracker.app.util.SharedLine> = emptyList(),
    /** Bills ahead as the publishing phone sees it (its owner's side). */
    val ahead: com.financetracker.app.util.AheadView? = null
)

/** One phone's shared-bills report: its own side (income, shared card bills, transfers out) and,
 * if it has the split set up, the screen's numbers. */
data class SharedBillsMemberReport(
    val uid: String,
    val side: com.financetracker.app.util.PartnerReport,
    val snapshot: SharedBillsSnapshot?,
    val updatedAt: Long
)
