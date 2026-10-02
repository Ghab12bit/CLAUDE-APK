package com.focusblock.app.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.focusblock.app.R
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Real app icons, loaded off the main thread and cached (spec 4.2, 11.4). */
object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(300)

    fun cached(pkg: String, px: Int): ImageBitmap? = cache.get("$pkg@$px")

    fun load(context: Context, pkg: String, px: Int): ImageBitmap? {
        cached(pkg, px)?.let { return it }
        val bitmap = runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap() }.getOrNull() ?: return null
        cache.put("$pkg@$px", bitmap)
        return bitmap
    }
}

/**
 * [label] is used for the content description only when the icon stands alone; pass null when a
 * visible label sits next to it, so TalkBack does not read the name twice.
 */
@Composable
fun AppIcon(pkg: String, label: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(initialValue = AppIconCache.cached(pkg, px), pkg, px) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIconCache.load(context, pkg, px) }
    }
    val description = label?.let { stringResource(R.string.app_icon_cd, it) }
    val shape = RoundedCornerShape(size * 0.24f)
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = description, modifier = modifier.size(size).clip(shape))
    } else {
        Box(
            modifier.size(size).clip(shape).background(Fb.track).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
            contentAlignment = Alignment.Center,
        ) { Text((label ?: pkg.substringAfterLast('.')).take(1).uppercase(), style = FbType.label) }
    }
}

/** Up to [max] icons with labels, then "+n" (spec 4.1). */
@Composable
fun AppIconRow(packages: List<String>, label: (String) -> String, modifier: Modifier = Modifier, max: Int = 5, onMore: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = Fb.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        packages.take(max).forEach { pkg ->
            Column(Modifier.widthIn(max = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(pkg, null, 44.dp)
                Spacer(Modifier.height(6.dp))
                Text(label(pkg), style = FbType.caption, maxLines = 1)
            }
        }
        if (packages.size > max) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Fb.track), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.apps_more, packages.size - max), style = FbType.label)
                }
            }
        }
    }
}

enum class VisualState { IDLE, STARTING, ACTIVE, ENDING_SOON, BREAK, COMPLETE }

/**
 * Signature active-block visual (spec 5.3): the brand mark's two bars. The tall bar fills as the
 * block progresses; the short accent bar is full during focus and empties during a break. Motion is
 * used only when the state changes, and is skipped when animations are turned off.
 */
@Composable
fun ActiveBlockVisual(
    state: VisualState,
    progress: Float?,
    headline: String,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val still = remember { reducedMotion(context) }
    val target = when (state) {
        VisualState.IDLE -> 0f
        VisualState.COMPLETE -> 1f
        else -> progress ?: 1f
    }
    val fill by animateFloatAsState(target, if (still) snap() else tween(600), label = "fill")
    val accentTarget = when (state) { VisualState.BREAK, VisualState.IDLE -> 0.15f; else -> 1f }
    val accent by animateFloatAsState(accentTarget, if (still) snap() else tween(400), label = "accent")
    ElevatedSurface(modifier.semantics(mergeDescendants = true) {}, color = Fb.surfaceActive) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 56.dp, height = 88.dp).clearAndSetSemantics {}) {
                val barW = size.width * 0.36f
                val gap = size.width * 0.16f
                val r = CornerRadius(barW * 0.22f)
                val tallH = size.height
                val shortH = size.height * 0.55f
                val track = Fb.track
                // Tall bar: track, then fill from the bottom.
                drawRoundRect(track, Offset(0f, 0f), Size(barW, tallH), r)
                val filled = tallH * fill.coerceIn(0f, 1f)
                drawRoundRect(Fb.textPrimary, Offset(0f, tallH - filled), Size(barW, filled), r)
                // Short accent bar.
                val x2 = barW + gap
                drawRoundRect(track, Offset(x2, tallH - shortH), Size(barW, shortH), r)
                val accentH = shortH * accent
                val color = if (state == VisualState.ENDING_SOON) Fb.textPrimary else Fb.accent
                drawRoundRect(color, Offset(x2, tallH - accentH), Size(barW, accentH), r)
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(headline, style = FbType.display.copy(fontSize = FbType.title.fontSize, lineHeight = FbType.title.lineHeight))
                if (caption != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(caption, style = FbType.label.copy(color = Fb.textSecondary))
                }
            }
        }
    }
}

/** Plain bar chart: axis, gridlines and value labels, no stylised forms (spec 4.8, 5.4). */
@Composable
fun HourBarChart(values: IntArray, hourLabel: (Int) -> String, description: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val top = niceCeiling(max)
    Canvas(
        modifier.fillMaxWidth().height(180.dp).padding(horizontal = Fb.gutter).semantics { contentDescription = description },
    ) {
        val labelH = 18.dp.toPx()
        val valueH = 14.dp.toPx()
        val axisW = 22.dp.toPx()
        val chartH = size.height - labelH - valueH
        val chartW = size.width - axisW
        val grid = Fb.divider
        val small = FbType.caption.copy(fontSize = FbType.caption.fontSize * 0.9f)
        // Gridlines at 0, half and top, with axis values.
        listOf(0, top / 2, top).distinct().forEach { v ->
            val y = valueH + chartH - chartH * v / top
            drawLine(grid, Offset(axisW, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(v.toString(), small)
            drawText(t, topLeft = Offset(axisW - t.size.width - 4.dp.toPx(), y - t.size.height / 2f))
        }
        val slot = chartW / 24f
        val barW = slot * 0.62f
        values.forEachIndexed { hour, v ->
            val x = axisW + slot * hour + (slot - barW) / 2f
            if (v > 0) {
                val h = chartH * v / top
                drawRoundRect(Fb.accent, Offset(x, valueH + chartH - h), Size(barW, h), CornerRadius(2.dp.toPx()))
                val t = measurer.measure(v.toString(), small)
                drawText(t, topLeft = Offset(x + barW / 2f - t.size.width / 2f, valueH + chartH - h - t.size.height))
            }
            if (hour % 6 == 0) {
                val t = measurer.measure(hourLabel(hour), small)
                drawText(t, topLeft = Offset((x + barW / 2f - t.size.width / 2f).coerceAtLeast(axisW), size.height - labelH + 2.dp.toPx()))
            }
        }
    }
}

private fun niceCeiling(max: Int): Int = when {
    max <= 4 -> 4
    max <= 10 -> 10
    max <= 20 -> 20
    else -> ((max + 9) / 10) * 10
}

data class StripSegment(val startMinute: Int, val endMinute: Int, val key: String)

/** Single horizontal 24-hour strip showing when blocking is active; tap a segment to open its rule. */
@Composable
fun CoverageStrip(
    segments: List<StripSegment>,
    nowMinute: Int,
    hourLabel: (Int) -> String,
    description: String,
    onSegment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = Fb.gutter)
            .semantics { contentDescription = description }
            .pointerInput(segments) {
                detectTapGestures { offset ->
                    val minute = (offset.x / size.width * 1440).toInt()
                    segments.firstOrNull { minute in it.startMinute until it.endMinute }?.let { onSegment(it.key) }
                }
            },
    ) {
        val barH = 20.dp.toPx()
        drawRoundRect(Fb.track, Offset(0f, 0f), Size(size.width, barH), CornerRadius(4.dp.toPx()))
        segments.forEach { s ->
            val x = size.width * s.startMinute / 1440f
            val w = size.width * (s.endMinute - s.startMinute) / 1440f
            drawRect(Fb.accent, Offset(x, 0f), Size(w, barH))
        }
        val nowX = size.width * nowMinute / 1440f
        drawLine(Fb.textPrimary, Offset(nowX, -2.dp.toPx()), Offset(nowX, barH + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
        val small = FbType.caption
        listOf(0, 6, 12, 18).forEach { h ->
            val t = measurer.measure(hourLabel(h), small)
            val x = (size.width * h / 24f).coerceAtMost(size.width - t.size.width)
            drawText(t, topLeft = Offset(x, barH + 6.dp.toPx()))
        }
    }
}

/** Bottom sheet with the surface colour; content scrolls inside. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FbSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Fb.surface,
        contentColor = Fb.textPrimary,
        dragHandle = {
            Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Fb.track))
        },
    ) { content() }
}
