package com.focusblock.app.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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

/**
 * Icons with labels, as many as fit the width (at most [max]), then "+n" for the rest (spec 4.1).
 * "+n" always keeps its slot; with [onMore] it is a button.
 */
@Composable
fun AppIconRow(packages: List<String>, label: (String) -> String, modifier: Modifier = Modifier, max: Int = 5, onMore: (() -> Unit)? = null) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = LocalRowInset.current)) {
        val slot = 56.dp
        val gap = 8.dp
        val fit = ((maxWidth + gap) / (slot + gap)).toInt().coerceAtLeast(1)
        // When not every app fits, "+n" takes the last slot.
        val shown = if (packages.size <= minOf(max, fit)) packages.size else (minOf(max + 1, fit) - 1).coerceAtLeast(0)
        val more = packages.size - shown
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            packages.take(shown).forEach { pkg ->
                Column(Modifier.width(slot), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(pkg, null, 44.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(label(pkg), style = FbType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (more > 0) {
                val description = pluralRes(R.plurals.apps_more_cd, more)
                Box(
                    Modifier.width(slot).heightIn(min = Fb.touch).clip(RoundedCornerShape(12.dp))
                        .then(if (onMore != null) Modifier.clickable(role = Role.Button, onClick = onMore) else Modifier)
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Fb.surfaceHigh), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.apps_more, more), style = FbType.label, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
            }
        }
    }
}

enum class VisualState { IDLE, STARTING, ACTIVE, ENDING_SOON, BREAK, COMPLETE }

/**
 * Running-block visual: a progress ring in the brand gradient (blue to violet; green during a
 * break, coral in the last five minutes) with the time left in the middle. Motion only follows
 * progress and is skipped when animations are turned off.
 */
@Composable
fun ActiveBlockVisual(
    state: VisualState,
    progress: Float?,
    headline: String,
    caption: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
) {
    val context = LocalContext.current
    val still = remember { reducedMotion(context) }
    val target = when (state) {
        VisualState.IDLE -> 0f
        VisualState.COMPLETE -> 1f
        else -> progress ?: 1f
    }
    val fill by animateFloatAsState(target, if (still) snap() else tween(600), label = "fill")
    val colors = when (state) {
        VisualState.BREAK -> listOf(Fb.success, Fb.success)
        VisualState.ENDING_SOON -> listOf(Fb.warning, Fb.accentAlt)
        else -> listOf(Fb.accent, Fb.accentAlt, Fb.accent)
    }
    Box(
        modifier.fillMaxWidth().padding(horizontal = Fb.gutter).clip(RoundedCornerShape(Fb.cardRadius))
            .background(Brush.verticalGradient(listOf(Fb.surfaceActive, Fb.surface))).padding(vertical = 24.dp)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
                val stroke = 16.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(Fb.track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                val sweep = 360f * fill.coerceIn(0f, 1f)
                // Under ~2 degrees the round cap would show as a lone dot at 12 o'clock.
                if (sweep >= 2f) {
                    rotate(-90f) {
                        drawArc(Brush.sweepGradient(colors), 0f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                Text(headline, style = FbType.display.copy(fontSize = FbType.title.fontSize * 1.15f, lineHeight = FbType.title.lineHeight * 1.15f), textAlign = TextAlign.Center, maxLines = 2)
                if (caption != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(caption, style = FbType.label.copy(color = Fb.textSecondary), textAlign = TextAlign.Center)
                }
                if (footer != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(footer, style = FbType.overline, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Stacked bar chart for usage: one bar per bucket (hour or day), stacked by category, with time
 * gridlines. [highlight] draws one bar in [highlightColor] (used for the peak hour). When there is
 * nothing to draw, [emptyText] (if given) sits in the middle of the chart area.
 */
@Composable
fun StackedBarChart(
    values: List<LongArray>,
    colors: List<Color>,
    xLabel: (Int) -> String?,
    yLabel: (Long) -> String,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
    average: Long? = null,
    highlight: Int? = null,
    highlightColor: Color = Fb.peak,
    showAxis: Boolean = true,
    showYAxis: Boolean = true,
    emptyText: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val max = values.maxOfOrNull { it.sum() } ?: 0L
    val empty = max < MIN_BAR_MS
    val top = niceTimeCeiling(maxOf(max, average ?: 0L))
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val small = FbType.caption.copy(fontSize = FbType.caption.fontSize * 0.92f)
        val labelH = if (showAxis) 18.dp.toPx() else 0f
        val axisW = if (showAxis && showYAxis) 34.dp.toPx() else 0f
        val chartH = size.height - labelH - 6.dp.toPx()
        val chartW = size.width - axisW
        val y0 = 6.dp.toPx()
        if (showAxis && showYAxis) {
            // No middle gridline through the empty caption.
            (if (empty && emptyText != null) listOf(0L, top) else listOf(0L, top / 2, top)).forEach { v ->
                val y = y0 + chartH - chartH * v / top
                drawLine(Fb.divider, Offset(0f, y), Offset(chartW, y), strokeWidth = 1.dp.toPx())
                val t = measurer.measure(yLabel(v), small)
                drawText(t, topLeft = Offset(chartW + 6.dp.toPx(), y - t.size.height / 2f))
            }
        }
        val n = values.size.coerceAtLeast(1)
        val slot = chartW / n
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        val radius = CornerRadius(minOf(barW / 2f, 4.dp.toPx()))
        values.forEachIndexed { i, cats ->
            val x = slot * i + (slot - barW) / 2f
            // Faint empty track so the shape of the day stays readable; none on an empty chart.
            if (!empty) drawRoundRect(Fb.track.copy(alpha = 0.025f), Offset(x, y0), Size(barW, chartH), radius)
            if (cats.sum() >= MIN_BAR_MS) {
                var yTop = y0 + chartH
                cats.forEachIndexed { c, v ->
                    val h = chartH * v / top
                    if (h < 0.01f) return@forEachIndexed
                    val color = if (highlight == i) highlightColor else colors[c % colors.size]
                    drawRoundRect(color, Offset(x, yTop - h), Size(barW, h), radius)
                    yTop -= h
                }
            }
            xLabel(i)?.let { label ->
                val t = measurer.measure(label, small)
                drawText(t, topLeft = Offset((x + barW / 2f - t.size.width / 2f).coerceAtMost(chartW - t.size.width).coerceAtLeast(0f), size.height - labelH + 2.dp.toPx()))
            }
        }
        average?.takeIf { it > 0 }?.let { avg ->
            val y = y0 + chartH - chartH * avg / top
            drawLine(Fb.textSecondary, Offset(0f, y), Offset(chartW, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        emptyText?.takeIf { empty }?.let { text ->
            val t = measurer.measure(text, FbType.caption.copy(textAlign = TextAlign.Center), constraints = Constraints(maxWidth = chartW.toInt().coerceAtLeast(0)))
            drawText(t, topLeft = Offset((chartW - t.size.width) / 2f, y0 + (chartH - t.size.height) / 2f))
        }
    }
}

/** Usage reads in whole minutes, so a bar under one would be a sliver next to "0 min"; it is not drawn. */
private const val MIN_BAR_MS = 60_000L

/** 0 → 30 min, then whole halves of an hour, then whole hours. */
private fun niceTimeCeiling(ms: Long): Long {
    val min = 60_000L
    val steps = listOf(10 * min, 20 * min, 30 * min, 60 * min, 2 * 60 * min, 3 * 60 * min, 4 * 60 * min, 6 * 60 * min, 8 * 60 * min, 12 * 60 * min, 16 * 60 * min, 24 * 60 * min)
    return steps.firstOrNull { ms <= it } ?: (((ms / (60 * min)) + 1) * 60 * min)
}

/** Plain bar chart: axis, gridlines and value labels, no stylised forms (spec 4.8, 5.4). */
@Composable
fun HourBarChart(values: IntArray, hourLabel: (Int) -> String, description: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val top = niceCeiling(max)
    Canvas(
        modifier.fillMaxWidth().height(180.dp).padding(horizontal = LocalRowInset.current).semantics { contentDescription = description },
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
                drawRoundRect(Brush.verticalGradient(listOf(Fb.accentAlt, Fb.accent), startY = valueH + chartH - h, endY = valueH + chartH), Offset(x, valueH + chartH - h), Size(barW, h), CornerRadius(3.dp.toPx()))
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
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = LocalRowInset.current)
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
            drawRect(Brush.horizontalGradient(listOf(Fb.accent, Fb.accentAlt), startX = x, endX = x + w), Offset(x, 0f), Size(w, barH))
        }
        val nowX = size.width * nowMinute / 1440f
        drawLine(Fb.textPrimary, Offset(nowX, -2.dp.toPx()), Offset(nowX, barH + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
        val small = FbType.caption
        listOf(0, 6, 12, 18).forEach { h ->
            val t = measurer.measure(hourLabel(h), small)
            // Centred on its hour, kept inside the strip at the ends.
            val x = (size.width * h / 24f - t.size.width / 2f).coerceAtMost(size.width - t.size.width).coerceAtLeast(0f)
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
            Box(Modifier.padding(vertical = 10.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Fb.textSecondary.copy(alpha = 0.4f)))
        },
    ) { content() }
}
