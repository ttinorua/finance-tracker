package com.financetracker.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** Money in (and good news) in the current theme. */
val IncomeGreen: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.positive

/** Money out (and over-budget) in the current theme. */
val ExpenseRed: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.negative

/** "Nearing the limit" in the current theme. */
val BudgetWarning: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.warning
