package com.focusblock.app.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusblock.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Real launcher icons, everywhere apps appear.
 *
 * Every screen in this app previously rendered its apps as a comma-separated
 * sentence -- "Calendar, Clock, Gmail, Maps, Telegram, WhatsApp" -- which reads
 * like a document rather than software, and which the eye has to parse word by
 * word. An icon is recognised instantly and without reading. This component was
 * specified in the design brief twice before it was actually built; it is the
 * single largest difference between a screen that looks like an app and one
 * that looks like a list.
 */

/** Decoded icons, kept for the process lifetime. Decoding is not free. */
private val iconCache = ConcurrentHashMap<String, ImageBitmap?>()

@Composable
private fun rememberAppIcon(packageName: String, sizePx: Int = 144): ImageBitmap? {
    val context = LocalContext.current
    val state by produceState<ImageBitmap?>(initialValue = iconCache[packageName], packageName) {
        if (iconCache.containsKey(packageName)) {
            value = iconCache[packageName]
            return@produceState
        }
        value = withContext(Dispatchers.IO) { loadIcon(context, packageName, sizePx) }
            .also { iconCache[packageName] = it }
    }
    return state
}

private fun loadIcon(context: Context, packageName: String, sizePx: Int): ImageBitmap? = try {
    context.packageManager
        .getApplicationIcon(packageName)
        .toBitmap(sizePx, sizePx)
        .asImageBitmap()
} catch (e: Exception) {
    null
}

/**
 * One app icon. Falls back to a lettered tile when the package is missing --
 * an uninstalled app in a rule should still render as something, not a hole.
 */
@Composable
fun AppIcon(
    packageName: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    fallbackLabel: String = ""
) {
    val icon = rememberAppIcon(packageName)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .then(if (dimmed) Modifier.alpha(0.45f) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fallbackLabel.firstOrNull()?.uppercase() ?: "?",
                    color = TextSecondary,
                    fontSize = (size.value / 2.6f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * A compact row of icons for a card: shows up to [max], then "+N".
 *
 * Used where a rule needs to say WHAT it covers without spending four lines of
 * text on it.
 */
@Composable
fun AppIconRow(
    packages: List<String>,
    modifier: Modifier = Modifier,
    max: Int = 6,
    size: Dp = 28.dp,
    dimmed: Boolean = false
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        packages.take(max).forEach { AppIcon(it, size = size, dimmed = dimmed) }
        val remaining = packages.size - max
        if (remaining > 0) {
            Box(
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(size / 4))
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+$remaining",
                    color = TextSecondary,
                    fontSize = (size.value / 3f).sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * A selectable grid of apps with names underneath.
 *
 * Replaces the checkbox list used by the picker and by setup. A grid of icons
 * is scannable at a glance; a vertical list of checkboxes and labels is not.
 */
@Composable
fun AppIconGrid(
    apps: List<GridApp>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        apps.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { app ->
                    GridCell(app, Modifier.weight(1f)) { onToggle(app.packageName) }
                }
                // Keep the last row aligned with the ones above it.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

data class GridApp(
    val packageName: String,
    val label: String,
    val selected: Boolean = false,
    /** Communication or utility app: marked so it is never picked by accident. */
    val essential: Boolean = false,
    /** Optional caption, e.g. "1h 29m a day". */
    val caption: String = ""
)

@Composable
private fun GridCell(app: GridApp, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (app.selected)
                            Modifier.background(SignalGlow).border(2.dp, Signal, RoundedCornerShape(16.dp))
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(app.packageName, size = 40.dp, fallbackLabel = app.label)
            }

            // A tick, rather than a separate checkbox column, so selection is
            // read on the thing being selected.
            if (app.selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Signal),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = Ground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            app.label,
            color = if (app.selected) TextPrimary else TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (app.essential) {
            Text("needed", color = AccentOrange, fontSize = 9.sp, textAlign = TextAlign.Center)
        } else if (app.caption.isNotBlank()) {
            Text(app.caption, color = TextTertiary, fontSize = 9.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A rule's window drawn as a shape on a 24-hour track.
 *
 * "8:45pm - 10:30pm · weekdays" is a sentence to be read. This is a picture: a
 * bar you glance at to see when in the day the rule bites, and where you are in
 * it right now.
 */
@Composable
fun TimeWindowBar(
    startMinute: Int,
    endMinute: Int,
    nowMinute: Int,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val dayMinutes = 24 * 60f
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceElevated)
        ) {
            if (startMinute <= endMinute) {
                WindowSegment(startMinute / dayMinutes, (endMinute - startMinute) / dayMinutes, active)
            } else {
                // Crosses midnight: draw both halves.
                WindowSegment(startMinute / dayMinutes, (dayMinutes - startMinute) / dayMinutes, active)
                WindowSegment(0f, endMinute / dayMinutes, active)
            }

            // Where the day currently is.
            Box(
                Modifier
                    .fillMaxWidth(nowMinute / dayMinutes)
                    .fillMaxHeight()
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(TextPrimary)
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12a", "6a", "12p", "6p", "12a").forEach {
                Text(it, color = TextTertiary, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun BoxScope.WindowSegment(startFraction: Float, widthFraction: Float, active: Boolean) {
    // Laid out with weights rather than absolute offsets so the bar scales to
    // whatever width the card gives it.
    Row(Modifier.fillMaxSize()) {
        if (startFraction > 0f) Spacer(Modifier.weight(startFraction))
        Box(
            Modifier
                .fillMaxHeight()
                .weight(widthFraction.coerceAtLeast(0.001f))
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) Signal else SignalDim)
        )
        val trailing = 1f - startFraction - widthFraction
        if (trailing > 0f) Spacer(Modifier.weight(trailing))
    }
}
