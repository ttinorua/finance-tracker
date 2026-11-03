package com.financetracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financetracker.app.ui.theme.ExpenseRed
import com.financetracker.app.ui.theme.IncomeGreen
import com.financetracker.app.util.TrendPoint
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** A zero-baseline bar per month or year, colored by the sign of income − expense. [trends] are
 * given oldest first but drawn newest on the left, matching [TrendBarChart]. Tapping a bar reports
 * its index into [trends]. */
@Composable
fun NetTrendChart(trends: List<TrendPoint>, onBarClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (trends.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 9.sp, color = axisColor)
    val currentOnBarClick by rememberUpdatedState(onBarClick)

    val positiveColor = IncomeGreen
    val negativeColor = ExpenseRed
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .pointerInput(trends.size) {
                detectTapGestures { offset ->
                    val padL = 38.dp.toPx()
                    val plotW = size.width - padL - 10.dp.toPx()
                    val position = ((offset.x - padL) / (plotW / trends.size)).toInt()
                    if (offset.x >= padL && position in trends.indices) currentOnBarClick(trends.lastIndex - position)
                }
            }
    ) {
        val padL = 38.dp.toPx()
        val padR = 10.dp.toPx()
        val padT = 10.dp.toPx()
        val padB = 20.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB
        val n = trends.size

        val axisMax = niceAxisMax(trends.maxOf { abs(it.net) }.coerceAtLeast(1.0))
        val zeroY = padT + plotH / 2f

        fun xCenter(i: Int): Float = padL + plotW * ((n - 1 - i) + 0.5f) / n
        fun barHeight(v: Double): Float = (plotH / 2f * (abs(v) / axisMax)).toFloat()

        drawLine(gridColor, Offset(padL, zeroY), Offset(size.width - padR, zeroY), strokeWidth = 1f)
        val zeroMeasured = textMeasurer.measure("0", labelStyle)
        drawText(
            textMeasurer,
            "0",
            topLeft = Offset(padL - 6f - zeroMeasured.size.width, zeroY - zeroMeasured.size.height / 2f),
            style = labelStyle
        )

        val barW = (plotW / n) * 0.46f
        trends.forEachIndexed { i, t ->
            val h = barHeight(t.net).coerceAtLeast(3f)
            val color = if (t.net >= 0) positiveColor else negativeColor
            val top = if (t.net >= 0) zeroY - h else zeroY
            drawRoundRect(
                color = color,
                topLeft = Offset(xCenter(i) - barW / 2f, top),
                size = Size(barW, h),
                cornerRadius = CornerRadius(4f, 4f)
            )
            val measured = textMeasurer.measure(t.label, labelStyle)
            drawText(
                textMeasurer,
                t.label,
                topLeft = Offset(xCenter(i) - measured.size.width / 2f, size.height - padB + 6f),
                style = labelStyle
            )
        }
    }
}

private fun niceAxisMax(value: Double): Double {
    if (value <= 0.0) return 100.0
    val magnitude = 10.0.pow(floor(ln(value) / ln(10.0)))
    val residual = value / magnitude
    val niceResidual = when {
        residual <= 1.0 -> 1.0
        residual <= 2.0 -> 2.0
        residual <= 5.0 -> 5.0
        else -> 10.0
    }
    return niceResidual * magnitude
}
