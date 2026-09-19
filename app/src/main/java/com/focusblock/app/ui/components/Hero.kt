package com.focusblock.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun StatusHero(
    state: HeroState,
    headline: String,
    detail: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    val accent = when (state) {
        HeroState.LIVE -> Signal
        HeroState.IDLE -> TextSecondary
        HeroState.BROKEN -> AccentRed
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (state == HeroState.LIVE) SignalGlow else CardDark)
            .then(
                if (state == HeroState.LIVE)
                    Modifier.border(1.dp, SignalBorder, RoundedCornerShape(22.dp))
                else if (state == HeroState.BROKEN)
                    Modifier.border(1.dp, AccentRed.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                else Modifier
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(accent, live = state == HeroState.LIVE)
            Spacer(Modifier.width(10.dp))
            Text(
                text = when (state) {
                    HeroState.LIVE -> "PROTECTED"
                    HeroState.IDLE -> "STANDING BY"
                    HeroState.BROKEN -> "NOT WORKING"
                },
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        // Big enough to read from across the room. This is the answer.
        Text(
            text = headline,
            color = TextPrimary,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold
        )

        if (detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(detail, color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp)
        }

        trailing?.let {
            Spacer(Modifier.height(16.dp))
            it()
        }
    }
}

/** A slow pulse, so "on" reads as alive rather than as a static badge. */
@Composable
private fun PulseDot(color: Color, live: Boolean) {
    val alpha = if (live) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "pulseAlpha"
        ).value
    } else 1f

    Box(
        Modifier
            .size(9.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

/** Section heading: small, spaced, quiet. Structure without shouting. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = TextTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/** Big page title, matching the hero's weight. */
@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(text, color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = TextSecondary, fontSize = 14.sp)
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
