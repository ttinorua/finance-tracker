package com.financetracker.app.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.ai.MerchantLogoLookup
import com.financetracker.app.data.prefs.MerchantOverrides
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.ui.screens.receipts.ReceiptNav
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.ReceiptSplits

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TransactionRow(
    transaction: TransactionWithDetails,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    runningBalance: Double? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // weight(1f) guarantees the amount/balance column on the right always keeps its
        // natural width; without it, a long merchant name can wrap and claim almost all
        // the row's width, squeezing the amount into a sliver that wraps one digit per line.
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Company logo when one is known; otherwise the category icon as before.
            val cachedDomains by MerchantLogoLookup.domains.collectAsState()
            val cachedNames by MerchantLogoLookup.names.collectAsState()
            val overrides by MerchantOverrides.rules.collectAsState()
            LaunchedEffect(transaction.note) { MerchantLogoLookup.request(transaction.note) }
            CompanyLogo(
                domain = MerchantLogoLookup.domainFor(transaction.note, cachedDomains, overrides),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                photo = MerchantOverrides.photoFor(transaction.note, overrides)
            ) {
                CategoryIconTile(
                    categoryId = transaction.categoryId,
                    name = transaction.categoryName,
                    mainCategory = transaction.mainCategoryName,
                    colorHex = transaction.categoryColorHex,
                    size = 36.dp
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    // The short company name ("HBO Max") when known; the bank's full text stays in
                    // the transaction itself and shows when editing.
                    text = MerchantLogoLookup.nameFor(transaction.note, cachedNames, overrides)
                        ?: transaction.note.ifBlank { transaction.categoryName ?: "Transaction" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val withReceipt by ReceiptNav.withReceipt.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ReceiptSplits.isPart(transaction.id) || transaction.id in withReceipt) {
                        Icon(
                            Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = "Has a receipt",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp).size(14.dp)
                        )
                    }
                    Text(
                        text = transaction.categoryName ?: "Uncategorized",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        val isIncome = transaction.type == TransactionType.INCOME
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = (if (isIncome) "" else "-") + Formatters.amount(transaction.amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (runningBalance != null) {
                Text(
                    text = Formatters.amount(runningBalance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
