package com.financetracker.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.financetracker.app.util.MerchantLogos
import com.financetracker.app.util.MerchantPhotos

/**
 * A company's logo in a small circle, for transactions and bank connections. [fallback] shows
 * whenever there's no logo — no [domain], still loading, offline, or the logo service has none —
 * so a missing logo never leaves a blank gap. [photo] is the person's own picture (Base64, see
 * [MerchantPhotos]) and is shown instead of the website logo.
 */
@Composable
fun CompanyLogo(
    domain: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier.size(28.dp),
    photo: String? = null,
    fallback: @Composable () -> Unit
) {
    val picture = remember(photo) { photo?.let { MerchantPhotos.decode(it) } }
    if (picture != null) {
        Image(
            bitmap = picture,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape)
        )
        return
    }
    var loaded by remember(domain) { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!loaded) fallback()
        if (domain != null) {
            AsyncImage(
                model = MerchantLogos.logoUrl(domain),
                contentDescription = contentDescription,
                // White backing so dark logos stay visible in dark theme; only once there's a logo.
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .then(if (loaded) Modifier.background(Color.White).padding(4.dp) else Modifier),
                onSuccess = { loaded = true },
                onError = { loaded = false }
            )
        }
    }
}
