package com.focusblock.app.ui.components

import android.content.Context
import android.provider.Settings
import androidx.annotation.PluralsRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.policy.Strength
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType

/**
 * Horizontal inset for rows and controls. Screen-level content uses the gutter; inside an
 * [FbCard] the card's own padding takes over.
 */
val LocalRowInset = androidx.compose.runtime.staticCompositionLocalOf { Fb.gutter }

@Composable
fun pluralRes(@PluralsRes id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)

/** True when the user turned animations off ("Remove animations"); motion then only cuts. */
fun reducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** Brand mark + name + Settings icon (spec 4.1). */
@Composable
fun AppHeader(onSettings: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Fb.touch).padding(horizontal = Fb.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = FbType.label, modifier = Modifier.weight(1f))
        if (onSettings != null) {
            IconButton(onClick = onSettings, modifier = Modifier.size(Fb.touch)) {
                Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.settings), tint = Fb.textPrimary)
            }
        }
    }
}

/** Back arrow + title, for sub-screens. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = Fb.gutter), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp).size(Fb.touch)) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Fb.textPrimary)
        }
        Text(title, style = FbType.heading, modifier = Modifier.semantics { heading() })
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = Fb.gutter)) {
        Text(text, style = FbType.title, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = FbType.body.copy(color = Fb.textSecondary))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = LocalRowInset.current), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = FbType.label.copy(color = Fb.textSecondary), modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

@Composable
fun FbDivider(modifier: Modifier = Modifier, inset: Dp = LocalRowInset.current) {
    Box(modifier.fillMaxWidth().padding(horizontal = inset).height(1.dp).background(Fb.divider))
}

@Composable
fun SectionGap() = Spacer(Modifier.height(Fb.sectionGap))

/** A full-width row; inside an [FbCard] it lines up with the card padding. */
@Composable
fun DividerRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleMaxLines: Int = 2,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().then(clickable).heightIn(min = 56.dp).padding(horizontal = LocalRowInset.current, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = FbType.body, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = FbType.caption.copy(fontSize = FbType.label.fontSize, lineHeight = FbType.label.lineHeight))
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = FbType.body.copy(color = Fb.textSecondary), maxLines = 1, softWrap = false)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: ImageVector? = null, trailingIcon: ImageVector? = null) {
    val fg = if (enabled) Fb.buttonPrimaryText else Fb.disabled
    val bg = if (enabled) Modifier.background(Brush.horizontalGradient(listOf(Fb.buttonPrimaryBg, Fb.buttonPrimaryBgEnd))) else Modifier.background(Fb.track)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Fb.radius)).then(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) { Icon(leadingIcon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, style = FbType.body.copy(color = fg, fontWeight = FontWeight.SemiBold))
        if (trailingIcon != null) { Spacer(Modifier.width(10.dp)); Icon(trailingIcon, null, tint = fg, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: ImageVector? = null) {
    val fg = if (enabled) Fb.textPrimary else Fb.disabled
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Fb.radius)).background(Fb.surfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) { Icon(leadingIcon, null, tint = Fb.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = FbType.body.copy(color = fg, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** Low-emphasis text link. [accent] uses the single accent colour (links, selected state). */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = true, enabled: Boolean = true, style: TextStyle = FbType.label) {
    val color = when { !enabled -> Fb.disabled; accent -> Fb.accent; else -> Fb.textSecondary }
    Box(
        modifier.heightIn(min = Fb.touch).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = style.copy(color = color)) }
}

enum class StatusKind { OK, WARN, INFO }

/** One line, text + dot; never colour alone (spec 4.1). */
@Composable
fun StatusLine(text: String, kind: StatusKind, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val color = when (kind) { StatusKind.OK -> Fb.success; StatusKind.WARN -> Fb.warning; StatusKind.INFO -> Fb.textSecondary }
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = LocalRowInset.current), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, style = FbType.label.copy(color = if (kind == StatusKind.WARN) Fb.textPrimary else Fb.textSecondary), modifier = Modifier.weight(1f, fill = false))
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(4.dp))
            TextLink(actionLabel, onAction)
        }
    }
}

@Composable
fun StrengthLabel(strength: Strength, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (strength == Strength.STRICT) {
            Icon(Icons.Outlined.Lock, null, tint = Fb.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.strict_line), style = FbType.label)
        } else {
            Text(stringResource(R.string.strength_normal), style = FbType.label.copy(color = Fb.textSecondary))
        }
    }
}

enum class ChipKind { ACTIVE, NEXT, OFF, BROKEN }

/** Rule state: text + icon, never colour only (spec 4.6). */
@Composable
fun StateChip(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val (icon, color) = when (kind) {
        ChipKind.ACTIVE -> Icons.Outlined.CheckCircle to Fb.success
        ChipKind.NEXT -> Icons.Outlined.Schedule to Fb.textSecondary
        ChipKind.OFF -> Icons.Outlined.Info to Fb.textSecondary
        ChipKind.BROKEN -> Icons.Outlined.ErrorOutline to Fb.warning
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = FbType.caption.copy(color = if (kind == ChipKind.BROKEN) Fb.textPrimary else Fb.textSecondary), maxLines = 2)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = LocalRowInset.current, vertical = 16.dp)) {
        Text(text, style = FbType.body.copy(color = Fb.textSecondary))
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton(actionLabel, onAction)
        }
    }
}

/** "Blocking needs Accessibility · Fix" and "Blocking stopped working" (spec 4.11). */
@Composable
fun ProblemBanner(text: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = LocalRowInset.current).clip(RoundedCornerShape(Fb.radius))
            .background(Fb.warning.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, Fb.warning.copy(alpha = 0.5f)), RoundedCornerShape(Fb.radius)).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Fb.warning, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = FbType.label, modifier = Modifier.weight(1f))
        TextLink(actionLabel, onAction)
    }
}

@Composable
fun FbSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true, contentDescription: String? = null) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Fb.buttonPrimaryText,
            checkedTrackColor = Fb.buttonPrimaryBg,
            checkedBorderColor = Fb.buttonPrimaryBg,
            uncheckedThumbColor = Fb.textSecondary,
            uncheckedTrackColor = Fb.surfaceHigh,
            uncheckedBorderColor = Fb.textSecondary.copy(alpha = 0.6f),
            disabledCheckedTrackColor = Fb.accent.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = Fb.bg,
        ),
    )
}

/**
 * Segmented control: a pill track with the selected option filled.
 * Used for Timed / Intervals / Until I stop and Today / This week.
 */
@Composable
fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, trackColor: Color = Fb.surface) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = LocalRowInset.current).clip(RoundedCornerShape(26.dp)).background(trackColor).padding(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
                    .then(if (isSelected) Modifier.background(Brush.horizontalGradient(listOf(Fb.buttonPrimaryBg, Fb.buttonPrimaryBgEnd))) else Modifier)
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(value) }
                    .semantics { stateDescription = if (isSelected) "Selected" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = FbType.label.copy(color = if (isSelected) Fb.buttonPrimaryText else Fb.textSecondary, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Choice chips such as 25 · 45 · 60 · Custom. Selected is filled; all are 48 dp tall. */
@Composable
fun ChoiceChips(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().padding(horizontal = LocalRowInset.current), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                Modifier.weight(1f).heightIn(min = Fb.touch).clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Fb.accent.copy(alpha = 0.22f) else Fb.surfaceHigh)
                    .border(BorderStroke(1.5.dp, if (selected) Fb.accent else Color.Transparent), RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(i) }
                    .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = FbType.body.copy(color = Fb.textPrimary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
            }
        }
    }
}

/** Single-line field with a visible label; Done only closes the keyboard. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLength: Int = 80,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    focusManager: FocusManager = LocalFocusManager.current,
    horizontalPadding: Dp = LocalRowInset.current,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        Text(label, style = FbType.label.copy(color = Fb.textSecondary))
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(maxLength)) },
            enabled = enabled,
            singleLine = true,
            textStyle = FbType.body,
            cursorBrush = SolidColor(Fb.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Column {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Fb.surfaceHigh).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) Text(placeholder, style = FbType.body.copy(color = Fb.textSecondary.copy(alpha = 0.8f)))
                        inner()
                    }
                }
            },
        )
    }
}

/** The one elevated surface on a screen. */
@Composable
fun ElevatedSurface(modifier: Modifier = Modifier, color: Color = Fb.surface, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Fb.gutter).clip(RoundedCornerShape(Fb.cardRadius)).background(color).padding(20.dp), content = content)
}

/**
 * A card. With the default padding, rows inside line up with the card's padding; with
 * [contentPadding] 0, rows keep their own inset so their touch area reaches the card edges.
 */
@Composable
fun FbCard(
    modifier: Modifier = Modifier,
    color: Color = Fb.surface,
    brush: Brush? = null,
    contentPadding: Dp = 16.dp,
    outerPadding: Dp = Fb.gutter,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Fb.cardRadius)
    Column(
        modifier.fillMaxWidth().padding(horizontal = outerPadding).clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(color))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = if (contentPadding == 0.dp) 4.dp else contentPadding, horizontal = contentPadding),
    ) {
        CompositionLocalProvider(LocalRowInset provides if (contentPadding == 0.dp) 16.dp else 0.dp) { content() }
    }
}

/** Big section heading between cards ("Habits", "Focus"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = Fb.gutter, end = Fb.gutter - 8.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = FbType.heading.copy(fontSize = FbType.heading.fontSize * 1.1f), modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** Dot + label pill, for categories and states. */
@Composable
fun DotPill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = FbType.caption.copy(color = Fb.textPrimary), maxLines = 1)
    }
}

/** Horizontal progress bar with rounded ends. */
@Composable
fun FbProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 10.dp, brush: Brush? = null) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Fb.track)) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            Box(
                Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(height / 2))
                    .then(if (brush != null) Modifier.background(brush) else Modifier.background(color)),
            )
        }
    }
}

/** A small stat tile: icon, big value, caption label. */
@Composable
fun StatTile(value: String, label: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier, sub: String? = null) {
    Column(
        modifier.clip(RoundedCornerShape(Fb.cardRadius)).background(Fb.surface).padding(16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(value, style = FbType.title.copy(fontSize = FbType.heading.fontSize * 1.3f), maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = FbType.overline, maxLines = 2)
        if (sub != null) {
            Spacer(Modifier.height(4.dp))
            Text(sub, style = FbType.caption, maxLines = 2)
        }
    }
}

/** Placeholder shapes for loading states: a skeleton of the real layout, not a spinner (spec 4.11). */
@Composable
fun SkeletonLine(widthFraction: Float, height: Dp = 16.dp, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = Fb.gutter, vertical = 6.dp).fillMaxWidth(widthFraction).height(height).clip(RoundedCornerShape(6.dp)).background(Fb.track))
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = LocalRowInset.current), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FbType.body, modifier = Modifier.weight(1f))
        StepperButton("−", stringResource(R.string.decrease, label), onMinus, enabled)
        Text(value, style = FbType.body, modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 8.dp), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepperButton("+", stringResource(R.string.increase, label), onPlus, enabled)
    }
}

@Composable
private fun StepperButton(symbol: String, description: String, onClick: () -> Unit, enabled: Boolean) {
    Box(
        Modifier.size(Fb.touch).clip(CircleShape).background(Fb.surfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, style = FbType.heading.copy(color = if (enabled) Fb.textPrimary else Fb.disabled)) }
}

@Composable
fun rememberToday(): Long = remember { System.currentTimeMillis() }
