package com.financetracker.app.data.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financetracker.app.FinanceApp
import com.financetracker.app.MainActivity
import com.financetracker.app.R
import com.financetracker.app.data.advisor.AttentionMonitor
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.prefs.ViewFilter
import com.financetracker.app.data.prefs.WeeklySummarySettings
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.advisor.FinanceAnalysis
import com.financetracker.app.util.advisor.SummaryFacts
import com.financetracker.app.util.advisor.SummaryFactsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

data class SummaryStat(val label: String, val value: String, val detail: String, val good: Boolean? = null)

/** The AI summary card on Home: the app's own figures plus a few lines the AI wrote about them. */
data class AiSummary(
    val title: String,
    /** "13–19 Oct". */
    val subtitle: String,
    val createdAt: Long,
    /** Made by the Sunday schedule rather than Refresh now. */
    val automatic: Boolean,
    val stats: List<SummaryStat>,
    val points: List<String>,
    val tip: String?,
    /** The account it's about (null = all accounts) and its name, shown on the card. */
    val accountId: Long? = null,
    val accountName: String = "All accounts"
) {
    /** What "Ask about this" sends to the advisor. */
    fun followUpPrompt(): String = buildString {
        append("About my AI summary").append(if (accountName.isNotBlank()) " for $accountName" else "").append(", ").append(subtitle).append(": ").append(title).append(". ")
        points.forEach { append(it).append(' ') }
        append("Tell me more, and what I should do next.")
    }
}

/**
 * The weekly AI summary (Home card + a Sunday 18:00 notification), which replaced the AI insight
 * card. The app works out every figure ([SummaryFactsCalculator]); the AI only writes the title,
 * a few short points and a tip from them, in one short request. If no AI answers, the card is
 * still made from the figures alone.
 */
object WeeklySummary {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_SUMMARY = "ai_summary"
    private const val WORK = "weekly_ai_summary"
    private const val CHANNEL_ID = "weekly_summary"
    private const val NOTIFICATION_ID = 7400
    private const val DAY = 24L * 60 * 60 * 1000

    private var prefs: android.content.SharedPreferences? = null
    private val _summary = MutableStateFlow<AiSummary?>(null)
    val summary: StateFlow<AiSummary?> = _summary.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _summary.value = runCatching { p.getString(KEY_SUMMARY, null)?.let { decode(it) } }.getOrNull()
        WeeklySummarySettings.init(context) { on -> if (on) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK) }
        if (WeeklySummarySettings.enabled.value) schedule(context)
    }

    /** Hides the card until the next summary is made. */
    fun hide() = store(null)

    /** Every Sunday at 18:00 on the phone's clock. */
    private fun schedule(context: Context) {
        val next = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, 1)
        }
        val request = PeriodicWorkRequestBuilder<WeeklySummaryWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(next.timeInMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** The past 7 days, for the account Home is showing. */
    suspend fun makeWeekly(repository: FinanceRepository): AiSummary {
        val now = System.currentTimeMillis()
        return make(repository, ViewFilter.accountId.value, now - 7 * DAY, now, "Your week", automatic = true)
    }

    /** A summary of [from]..[to) for [accountId] (null = all accounts); stored and shown on Home. */
    suspend fun make(
        repository: FinanceRepository,
        accountId: Long?,
        from: Long,
        to: Long,
        periodLabel: String,
        automatic: Boolean
    ): AiSummary {
        val now = System.currentTimeMillis()
        val currency = CurrencySettings.currencyCode.value
        // Whole amounts, and no "kr." dot running into the end of a sentence.
        val symbol = Formatters.currencySymbol(currency).trimEnd('.')
        val money = { value: Double -> "${Formatters.whole(value)} $symbol" }
        val counted = FinanceAnalysis.counted(
            repository.observeTransactions().first(), accountId,
            BudgetSettings.excludeTransfersFromSpending.value, BudgetSettings.shiftSalaryToNextMonth.value,
            MainAccountSettings.mainAccountId.value
        )
        val facts = SummaryFactsCalculator.compute(counted, from, to, now, overallBudget(accountId))
        val accountName = accountId?.let { id -> repository.getAccounts().firstOrNull { it.id == id }?.name } ?: "All accounts"
        val subtitle = shortRange(from, minOf(to, now) - 1)

        val stats = buildList {
            add(
                SummaryStat(
                    "Spent", money(facts.spent),
                    facts.changePercent?.let { "${if (it <= 0) "↓" else "↑"}${abs(it).roundToInt()}% vs usual" } ?: "no comparison yet",
                    facts.changePercent?.let { it <= 0 }
                )
            )
            facts.budgetLeft?.let { add(SummaryStat("Budget left", money(it), "${facts.daysLeftInMonth} days left", it >= 0)) }
                ?: facts.topCategories.firstOrNull()?.let { (name, amount) -> add(SummaryStat("Biggest", money(amount), name)) }
            add(SummaryStat("Income", money(facts.income), periodLabel.lowercase()))
        }

        val written = AiService.ask(SYSTEM_PROMPT, factsText(facts, periodLabel, accountName, money), maxTokens = 700L, quick = true)
            .getOrNull()?.let(::parseWritten)
        val summary = AiSummary(
            title = written?.first ?: plainTitle(facts),
            subtitle = subtitle,
            createdAt = now,
            automatic = automatic,
            stats = stats,
            points = written?.second?.takeIf { it.isNotEmpty() } ?: plainPoints(facts, money),
            tip = written?.third,
            accountId = accountId,
            accountName = accountName
        )
        store(summary)
        return summary
    }

    /** "1–22 Oct", "28 Sep – 4 Oct" or "1 Jan 2025 – 3 Feb 2026". */
    internal fun shortRange(from: Long, toInclusive: Long): String {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        fun fmt(p: String, t: Long) = java.text.SimpleDateFormat(p, java.util.Locale.US).apply { timeZone = utc }.format(t)
        val sameYear = fmt("yyyy", from) == fmt("yyyy", toInclusive)
        val thisYear = fmt("yyyy", toInclusive) == fmt("yyyy", System.currentTimeMillis())
        return when {
            sameYear && fmt("MM", from) == fmt("MM", toInclusive) ->
                (if (fmt("d", from) == fmt("d", toInclusive)) "" else fmt("d", from) + "–") + fmt("d MMM", toInclusive) + if (thisYear) "" else fmt(" yyyy", toInclusive)
            sameYear -> fmt("d MMM", from) + " – " + fmt("d MMM", toInclusive) + if (thisYear) "" else fmt(" yyyy", toInclusive)
            else -> fmt("d MMM yyyy", from) + " – " + fmt("d MMM yyyy", toInclusive)
        }
    }

    private fun overallBudget(accountId: Long?): Double? {
        val budgets = BudgetLimits.overallBudgets.value
        if (accountId != null) return budgets[accountId]
        val perAccount = budgets.filterKeys { it != null }
        return if (perAccount.isNotEmpty()) perAccount.values.sum() else budgets[null]
    }

    private val SYSTEM_PROMPT = """
        You write the short summary card on the home screen of a personal finance app. Use only the figures given; never invent numbers.
        Reply with JSON only, no other text: {"title": "...", "points": ["...", "..."], "tip": "..."}
        - title: at most 6 words, e.g. "Your week: on track" or "Spending up on fuel".
        - points: 3 or 4 short sentences (under 15 words each) on what stood out, biggest first, with amounts and category names.
        - tip: one practical, specific thing to do next period, with the amount it would save.
        Write in plain English; keep Danish merchant and category names as they are.
    """.trimIndent()

    private fun factsText(facts: SummaryFacts, period: String, account: String, money: (Double) -> String) = buildString {
        appendLine("Period: $period, account: $account.")
        appendLine("Spent: ${money(facts.spent)}" + (facts.usualSpent?.let { ", usual for a period this long: ${money(it)}" } ?: ", no earlier periods to compare with") + ".")
        appendLine("Income: ${money(facts.income)}.")
        if (facts.topCategories.isNotEmpty()) appendLine("Top categories: " + facts.topCategories.joinToString { "${it.first} ${money(it.second)}" } + ".")
        if (facts.changes.isNotEmpty()) appendLine("Biggest changes vs usual: " + facts.changes.joinToString { "${it.name} ${money(it.spent)} (usual ${money(it.usual)})" } + ".")
        facts.budgetLeft?.let { appendLine("Monthly budget left: ${money(it)} with ${facts.daysLeftInMonth} days of the month to go.") }
        val attention = AttentionMonitor.items.value.take(4)
        if (attention.isNotEmpty()) appendLine("Also flagged in the app: " + attention.joinToString("; ") { it.title } + ".")
    }

    /** (title, points, tip) from the AI's JSON, or null when it isn't usable. */
    internal fun parseWritten(raw: String): Triple<String, List<String>, String?>? = runCatching {
        val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
        val points = json.optJSONArray("points") ?: JSONArray()
        Triple(
            json.getString("title").trim().ifBlank { return null },
            (0 until points.length()).map { points.getString(it).trim() }.filter { it.isNotEmpty() }.take(4),
            json.optString("tip").trim().ifBlank { null }
        )
    }.getOrNull()

    private fun plainTitle(facts: SummaryFacts): String = when {
        facts.changePercent == null -> "Your spending"
        facts.changePercent!! <= -5 -> "Spending below usual"
        facts.changePercent!! >= 5 -> "Spending above usual"
        else -> "On track"
    }

    private fun plainPoints(facts: SummaryFacts, money: (Double) -> String): List<String> = buildList {
        facts.topCategories.take(2).forEach { (name, amount) -> add("$name: ${money(amount)}") }
        facts.changes.firstOrNull()?.let { add("${it.name} was ${money(abs(it.difference))} ${if (it.difference > 0) "more" else "less"} than usual.") }
        facts.budgetLeft?.let { add(if (it >= 0) "${money(it)} of your monthly budget left" else "Monthly budget is ${money(-it)} over") }
    }

    private fun store(summary: AiSummary?) {
        _summary.value = summary
        prefs?.edit()?.apply { if (summary == null) remove(KEY_SUMMARY) else putString(KEY_SUMMARY, encode(summary)) }?.apply()
    }

    fun notify(context: Context, summary: AiSummary) {
        if (!AttentionMonitor.notificationsEnabled.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Weekly AI summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A short summary of your week every Sunday"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = summary.points.joinToString(" ")
        try {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(summary.title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text + (summary.tip?.let { "\nTip: $it" } ?: "")))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            // Permission withdrawn in the meantime.
        }
    }

    internal fun encode(s: AiSummary): String = JSONObject()
        .put("title", s.title).put("subtitle", s.subtitle).put("created", s.createdAt).put("auto", s.automatic)
        .put("stats", JSONArray().apply {
            s.stats.forEach { put(JSONObject().put("label", it.label).put("value", it.value).put("detail", it.detail).put("good", it.good ?: JSONObject.NULL)) }
        })
        .put("points", JSONArray(s.points)).put("tip", s.tip ?: JSONObject.NULL)
        .put("accountId", s.accountId ?: JSONObject.NULL).put("accountName", s.accountName)
        .toString()

    internal fun decode(raw: String): AiSummary {
        val o = JSONObject(raw)
        val stats = o.optJSONArray("stats") ?: JSONArray()
        val points = o.optJSONArray("points") ?: JSONArray()
        return AiSummary(
            title = o.getString("title"),
            subtitle = o.optString("subtitle"),
            createdAt = o.optLong("created"),
            automatic = o.optBoolean("auto"),
            stats = (0 until stats.length()).map { i ->
                val s = stats.getJSONObject(i)
                SummaryStat(s.getString("label"), s.getString("value"), s.optString("detail"), if (s.isNull("good")) null else s.getBoolean("good"))
            },
            points = (0 until points.length()).map { points.getString(it) },
            tip = if (o.isNull("tip")) null else o.optString("tip"),
            accountId = if (o.isNull("accountId")) null else o.optLong("accountId"),
            // Summaries saved before the account was stored keep it in their subtitle instead.
            accountName = o.optString("accountName", "")
        )
    }
}

/** Makes the weekly summary on Sunday evening and sends it as a notification. */
class WeeklySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!WeeklySummarySettings.enabled.value || !AiService.isConfigured) return Result.success()
        val repository = (applicationContext as FinanceApp).repository
        return try {
            WeeklySummary.notify(applicationContext, WeeklySummary.makeWeekly(repository))
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
