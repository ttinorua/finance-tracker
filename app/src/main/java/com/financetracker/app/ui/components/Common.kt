package com.financetracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.db.entity.CategorySpend
import com.financetracker.app.ui.theme.BudgetWarning
import com.financetracker.app.ui.theme.ExpenseRed
import com.financetracker.app.util.Formatters

@Composable
fun SummaryCard(
    modifier: Modifier = Modifier,
    title: String,
    amount: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    amountStyle: TextStyle = MaterialTheme.typography.titleLarge,
    contentPadding: Dp = 16.dp,
    titleBadge: String? = null
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(contentPadding)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (titleBadge != null) {
                    Text(
                        text = titleBadge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            AutoSizeText(
                text = amount,
                style = amountStyle,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = colors,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) { content() }
    } else {
        Card(modifier = modifier, colors = colors, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            content()
        }
    }
}

@Composable
fun CategoryColorDot(colorHex: String, modifier: Modifier = Modifier.size(12.dp)) {
    val color = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(Color.Gray)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun CategoryBreakdownList(
    categories: List<CategorySpend>,
    currencyCode: String,
    modifier: Modifier = Modifier,
    selectedCategoryId: Long? = null,
    onCategoryClick: ((CategorySpend) -> Unit)? = null
) {
    val total = categories.sumOf { it.total }.takeIf { it > 0 } ?: 1.0
    Column(modifier = modifier) {
        categories.forEach { spend ->
            val fraction = (spend.total / total).coerceIn(0.0, 1.0)
            val isSelected = onCategoryClick != null && spend.categoryId == selectedCategoryId
            Column(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .then(
                        if (onCategoryClick != null) {
                            Modifier.clickable { onCategoryClick(spend) }
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (isSelected) {
                            Modifier.background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                RoundedCornerShape(8.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryIconTile(spend.categoryId, spend.categoryName, spend.mainCategory, spend.colorHex, size = 28.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = spend.categoryName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = Formatters.currency(spend.total, currencyCode),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                val barColor = runCatching { Color(android.graphics.Color.parseColor(spend.colorHex)) }.getOrDefault(Color.Gray)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.toFloat())
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(barColor)
                    )
                }
            }
        }
    }
}

/** A labeled progress bar comparing [spent] against [budget], color-coded by how close it is.
 * Tappable when [onClick] is given, to drill into the transactions it's made up of. */
@Composable
fun BudgetProgressRow(
    label: String,
    spent: Double,
    budget: Double,
    currencyCode: String,
    modifier: Modifier = Modifier,
    colorHex: String? = null,
    onClick: (() -> Unit)? = null
) {
    val ratio = if (budget > 0) spent / budget else 0.0
    val statusColor = when {
        ratio >= 1.0 -> ExpenseRed
        ratio >= 0.8 -> BudgetWarning
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier
            .padding(vertical = 6.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (colorHex != null) {
                    CategoryColorDot(colorHex)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${Formatters.amount(spent)} / ${Formatters.currency(budget, currencyCode)}",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(statusColor.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio.coerceIn(0.0, 1.0).toFloat())
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(statusColor)
            )
        }
    }
}

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Inbox
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
