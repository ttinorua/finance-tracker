package com.financetracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GolfCourse
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TakeoutDining
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.prefs.CategoryIconChoices
import com.financetracker.app.ui.theme.AppPalette
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.util.CategoryIconRules
import kotlin.math.abs

/** The icons categories can have, by the keys [CategoryIconRules] and [CategoryIconChoices] use.
 * The order is the order of the picker. */
object CategoryIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "shopping_cart" to Icons.Filled.ShoppingCart,
        "restaurant" to Icons.Filled.Restaurant,
        "takeout" to Icons.Filled.TakeoutDining,
        "bakery" to Icons.Filled.BakeryDining,
        "cafe" to Icons.Filled.LocalCafe,
        "home" to Icons.Filled.Home,
        "key" to Icons.Filled.Key,
        "chair" to Icons.Filled.Chair,
        "handyman" to Icons.Filled.Handyman,
        "bolt" to Icons.Filled.Bolt,
        "flame" to Icons.Filled.LocalFireDepartment,
        "water" to Icons.Filled.WaterDrop,
        "heat" to Icons.Filled.Thermostat,
        "wifi" to Icons.Filled.Wifi,
        "media" to Icons.Filled.SmartDisplay,
        "movie" to Icons.Filled.Movie,
        "music" to Icons.Filled.MusicNote,
        "book" to Icons.AutoMirrored.Filled.MenuBook,
        "car" to Icons.Filled.DirectionsCar,
        "fuel" to Icons.Filled.LocalGasStation,
        "parking" to Icons.Filled.LocalParking,
        "bus" to Icons.Filled.DirectionsBus,
        "boat" to Icons.Filled.DirectionsBoat,
        "build" to Icons.Filled.Build,
        "bike" to Icons.Filled.PedalBike,
        "flight" to Icons.Filled.Flight,
        "hotel" to Icons.Filled.Hotel,
        "activity" to Icons.Filled.LocalActivity,
        "theater" to Icons.Filled.TheaterComedy,
        "fitness" to Icons.Filled.FitnessCenter,
        "pool" to Icons.Filled.Pool,
        "hiking" to Icons.Filled.Hiking,
        "sailing" to Icons.Filled.Sailing,
        "golf" to Icons.Filled.GolfCourse,
        "games" to Icons.Filled.SportsEsports,
        "toys" to Icons.Filled.Toys,
        "palette" to Icons.Filled.Palette,
        "gift" to Icons.Filled.Redeem,
        "groups" to Icons.Filled.Groups,
        "pets" to Icons.Filled.Pets,
        "child" to Icons.Filled.ChildCare,
        "checkroom" to Icons.Filled.Checkroom,
        "spa" to Icons.Filled.Spa,
        "glasses" to Icons.Filled.Visibility,
        "medical" to Icons.Filled.MedicalServices,
        "school" to Icons.Filled.School,
        "devices" to Icons.Filled.Devices,
        "work" to Icons.Filled.Work,
        "payments" to Icons.Filled.Payments,
        "bank" to Icons.Filled.AccountBalance,
        "savings" to Icons.Filled.Savings,
        "pension" to Icons.Filled.Elderly,
        "invest" to Icons.AutoMirrored.Filled.ShowChart,
        "credit_card" to Icons.Filled.CreditCard,
        "loan" to Icons.Filled.RequestQuote,
        "percent" to Icons.Filled.Percent,
        "receipt" to Icons.Filled.Receipt,
        "shield" to Icons.Filled.Shield,
        "cash" to Icons.Filled.LocalAtm,
        "transfer" to Icons.Filled.SwapHoriz,
        "block" to Icons.Filled.Block,
        "more" to Icons.Filled.MoreHoriz,
        "category" to Icons.Filled.Category,
        "help" to Icons.AutoMirrored.Filled.HelpOutline
    )

    fun icon(key: String): ImageVector = all[key] ?: Icons.Filled.Category

    /** A colour per default main category; any other main category gets one from the same set. */
    private val MAIN_COLOURS = mapOf(
        "clothing and pers. care prod." to Color(0xFFF472B6),
        "education and institution" to Color(0xFFA78BFA),
        "food" to Color(0xFF34D399),
        "home" to Color(0xFF60A5FA),
        "income" to Color(0xFF4ADE80),
        "insurance" to Color(0xFF2DD4BF),
        "leisure" to Color(0xFFFB923C),
        "loan and debt" to Color(0xFFF87171),
        "media" to Color(0xFFC084FC),
        "savings and investment" to Color(0xFF38BDF8),
        "transportation" to Color(0xFFFBBF24)
    )
    private val GREY = Color(0xFF94A3B8)

    fun mainColour(mainCategory: String): Color {
        val key = mainCategory.trim().lowercase()
        MAIN_COLOURS[key]?.let { return it }
        if (key in setOf("other", "uncategorized", "not included in budget and consumption")) return GREY
        val set = MAIN_COLOURS.values.toList()
        return set[abs(key.hashCode()) % set.size]
    }
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + .05f
    val lb = b.luminance() + .05f
    return maxOf(la, lb) / minOf(la, lb)
}

/** [colour], lightened on dark themes or darkened on light ones until it stands out from the
 * background, so dark category colours (e.g. a deep brown) still show. */
fun AppPalette.readable(colour: Color): Color {
    val target = if (isDark) Color.White else Color.Black
    var c = colour
    var step = 0
    while (contrast(c, base) < 4.5f && step < 9) {
        step++
        c = lerp(colour, target, step * .1f)
    }
    return c
}

/** The colour of [colorHex], or grey when it doesn't parse. */
fun parseHexColour(colorHex: String?): Color =
    colorHex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color(0xFF9E9E9E)

/** An icon in a softly tinted rounded tile. */
@Composable
fun IconTile(icon: ImageVector, colour: Color, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tint = LocalAppPalette.current.readable(colour)
    val shape = RoundedCornerShape(size * .3f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = .15f))
            .border(1.dp, tint.copy(alpha = .33f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * .56f))
    }
}

/** The icon key a category shows: the user's pick, or the one guessed from its name. */
@Composable
fun categoryIconKey(categoryId: Long?, name: String?, mainCategory: String?): String {
    val chosen by CategoryIconChoices.icons.collectAsState()
    return categoryId?.let { chosen[it] }
        ?: if (name == null) "help" else CategoryIconRules.guess(name, mainCategory.orEmpty())
}

/** A category's icon tile, in the category's own colour. */
@Composable
fun CategoryIconTile(
    categoryId: Long?,
    name: String?,
    mainCategory: String?,
    colorHex: String?,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    IconTile(CategoryIcons.icon(categoryIconKey(categoryId, name, mainCategory)), parseHexColour(colorHex), size, modifier, onClick)
}

/** A main category's (group's) icon tile. */
@Composable
fun MainCategoryIconTile(mainCategory: String, size: Dp = 38.dp, modifier: Modifier = Modifier) {
    IconTile(CategoryIcons.icon(CategoryIconRules.forMain(mainCategory)), CategoryIcons.mainColour(mainCategory), size, modifier)
}

/** A grid of every icon to choose from, with [selected] highlighted. */
@Composable
fun CategoryIconPicker(selected: String, colour: Color, onSelect: (String) -> Unit, modifier: Modifier = Modifier, columns: Int = 6) {
    val tint = LocalAppPalette.current.readable(colour)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryIcons.all.entries.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, icon) ->
                    val isSelected = key == selected
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.1f)
                            .clip(shape)
                            .background(if (isSelected) tint.copy(alpha = .2f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f))
                            .then(if (isSelected) Modifier.border(2.dp, tint, shape) else Modifier)
                            .clickable { onSelect(key) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = key.replace('_', ' '),
                            tint = if (isSelected) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
