package com.financetracker.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Home", Icons.Filled.Home)
    data object Transactions : Screen("transactions", "Activity", Icons.Filled.ReceiptLong)
    data object Overview : Screen("overview", "Spending", Icons.Filled.PieChart)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Tune)

    companion object {
        val bottomNavItems = listOf(Dashboard, Transactions, Overview, Settings)
    }
}
