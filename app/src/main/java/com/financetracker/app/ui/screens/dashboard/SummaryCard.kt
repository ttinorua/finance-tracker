package com.financetracker.app.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.ai.AiSummary
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.ui.theme.gradientBrush

/**
 * Home's AI summary (it replaced the AI insight card): made by itself every Sunday evening for
 * the past week, or for whatever period and account is on screen with Refresh now. The figures
 * come from the app; the AI writes the title, the points and the tip.
 */
@Composable
fun SummaryCard(
    summary: AiSummary?,
    isMaking: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onHide: () -> Unit,
    onAsk: (String) -> Unit
) {
    val palette = LocalAppPalette.current
    GlassCard(modifier = Modifier.fillMaxWidth(), aiBorder = true) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(
                        summary?.title ?: "AI summary",
                        style = MaterialTheme.typography.titleSmall.merge(TextStyle(brush = palette.gradientBrush())),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        summary?.subtitle ?: "Every Sunday at 18:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isMaking) {
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh summary", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    if (summary != null) {
                        IconButton(onClick = onHide) {
                            Icon(Icons.Filled.Close, contentDescription = "Hide summary", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (summary == null) {
                Text(
                    "A short read on your week arrives here every Sunday evening: what you spent compared with usual, " +
                        "what stood out, and one thing to do next. Tap Refresh now for the period on screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp)) {
                    summary.stats.forEach { stat ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(10.dp)
                        ) {
                            Text(stat.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stat.value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stat.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (stat.good) {
                                    true -> palette.positive
                                    false -> palette.negative
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 8.dp)) {
                    summary.points.forEach { point ->
                        Row {
                            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                            Text(point.replace("**", ""), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                summary.tip?.let { tip ->
                    Column(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(12.dp)
                    ) {
                        Text("TIP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(tip.replace("**", ""), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (summary != null) Button(onClick = { onAsk(summary.followUpPrompt()) }) { Text("Ask about this") }
                OutlinedButton(onClick = onRefresh, enabled = !isMaking) { Text("Refresh now") }
            }
        }
    }
}
