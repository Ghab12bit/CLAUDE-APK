package com.focusblock.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusblock.app.ui.theme.*

/**
 * The one thing every screen should answer before anything else: is this
 * working right now?
 *
 * The previous interface answered it with a 16dp grey tick among four other
 * 16dp grey ticks, which is why a fully-protected phone looked identical to a
 * broken one. State gets size, colour and space here, and the explanatory text
 * gets demoted -- the reverse of before.
 */

enum class HeroState { LIVE, IDLE, BROKEN }

/**
 * The state of the app, stated on the ground rather than inside a card.
 *
 * This used to be a rounded card with a tinted fill, a coloured border and a
 * pulsing dot -- which is the hero treatment every blocker ships, and it put a
 * container around the one piece of text on the screen that least needed one.
 * A headline at this size does not need a box to be found.
 *
 * What replaces it: a small state line, a large headline, a quiet detail, and
 * a hairline underneath. Nothing else. The state reads from the words and the
 * colour of the state line, and -- because colour must never be the only
 * carrier -- from the wording of the headline itself.
 */
@Composable
fun StatusHero(
    state: HeroState,
    headline: String,
    detail: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    val accent = when (state) {
        HeroState.LIVE -> Ember
        HeroState.IDLE -> InkFaint
        HeroState.BROKEN -> Alarm
    }

    Column(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp)) {
        Text(
            text = when (state) {
                HeroState.LIVE -> "BLOCKING"
                HeroState.IDLE -> "STANDING BY"
                HeroState.BROKEN -> "NOT WORKING"
            },
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.8.sp
        )

        Spacer(Modifier.height(12.dp))

        // The answer, at a size that does not need looking for.
        Text(
            text = headline,
            color = Ink,
            fontSize = 38.sp,
            lineHeight = 42.sp,
            fontWeight = FontWeight.Bold
        )

        if (detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(detail, color = InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
        }

        trailing?.let {
            Spacer(Modifier.height(18.dp))
            it()
        }
    }
}

/**
 * A running session, drawn as a bar that fills as it goes.
 *
 * This is the app's one signature visual, and it is deliberately not a ring
 * and not a shield. Rings belong to timer apps and to Opal; shields belong to
 * AppBlock and to every security product ever made. A bar filling left to
 * right is the shape of a stretch of time being spent, which is what a
 * protected window actually is, and it carries its own end time so the
 * question people ask -- "how much longer?" -- is answered inside the mark
 * rather than beside it.
 *
 * It animates only at the rate the session actually runs. Nothing here pulses.
 */
@Composable
fun FocusBar(
    fraction: Float,
    label: String,
    endLabel: String,
    modifier: Modifier = Modifier
) {
    val fill by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "focusFill"
    )

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GroundRaised)
        ) {
            // The elapsed part. A solid warm block, not a gradient.
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill)
                    .background(EmberQuiet)
            )
            // The leading edge: the only bright mark on the screen.
            if (fill > 0.003f) {
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .fillMaxHeight()
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Ember)
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(endLabel, color = InkMuted, fontSize = 13.sp)
            }
        }
    }
}

/**
 * A hairline. The primary way one section is separated from the next, in place
 * of giving each of them a card.
 */
@Composable
fun SectionRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Rule)
    )
}

/** Section heading: small, spaced, quiet. Structure without shouting. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = InkFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.8.sp,
        modifier = modifier.padding(top = 18.dp, bottom = 4.dp)
    )
}

/** Big page title, matching the hero's weight. */
@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(text, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = InkMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/**
 * A compact "everything is fine" row that expands on demand.
 *
 * Five separate permission rows all reading OK is noise: it costs a screenful
 * to say nothing is wrong. Collapsed, it says so in one line and gives the
 * space back.
 */
@Composable
fun SummaryRow(
    ok: Boolean,
    okText: String,
    problemText: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (ok) SignalGlow else CardDark)
            .then(
                if (ok) Modifier.border(1.dp, SignalBorder, RoundedCornerShape(16.dp))
                else Modifier.border(1.dp, AccentOrange.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) Signal else AccentOrange)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (ok) okText else problemText,
            color = if (ok) Signal else TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text("Details", color = TextTertiary, fontSize = 12.sp)
    }
}
