package com.financetracker.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import com.financetracker.app.data.prefs.MainAccountSettings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.financetracker.app.data.db.entity.Account

/** Narrows a screen's data to one account, or all of them (null) when nothing is selected. The
 * main account carries a filled star; tapping another account's star makes it main. */
@Composable
fun AccountSelectorChip(
    accounts: List<Account>,
    selectedAccountId: Long?,
    onAccountSelected: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val mainAccountId by MainAccountSettings.mainAccountId.collectAsState()
    val selected = accounts.firstOrNull { it.id == selectedAccountId }
    val label = selected?.let { if (it.id == mainAccountId) "★ ${it.name}" else it.name } ?: "All accounts"

    Box(modifier = modifier) {
        AssistChip(
            onClick = { menuExpanded = true },
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null) }
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("All accounts") },
                onClick = {
                    menuExpanded = false
                    onAccountSelected(null)
                }
            )
            accounts.forEach { account ->
                val isMain = account.id == mainAccountId
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        menuExpanded = false
                        onAccountSelected(account.id)
                    },
                    trailingIcon = {
                        IconButton(onClick = { if (!isMain) MainAccountSettings.setMainAccount(account.id) }) {
                            Icon(
                                if (isMain) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (isMain) "Main account" else "Set ${account.name} as main",
                                tint = if (isMain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }
    }
}
