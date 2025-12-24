package com.focusblock.app.ui.statistics

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.viewmodel.AppCategory
import com.focusblock.app.viewmodel.PeakTimeDetails
import com.focusblock.app.viewmodel.PeakAppUsage
import com.focusblock.app.viewmodel.PeakSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeakTimeDetailScreen(
    details: PeakTimeDetails,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Peak Time Details", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
            )
        },
        containerColor = Color(0xFFF5F7FA)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Peak Window Summary Card
            item {
                PeakSummaryCard(details)
            }

            // Timeline Chart
            item {
                PeakTimelineCard(details)
            }

            // Top Apps During Peak
            item {
                Text(
                    text = "Top Apps During Peak",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF1E293B),
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                TopAppsDuringPeakCard(details.topApps)
            }

            // Sessions List
            item {
                Text(
                    text = "Sessions During Peak",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF1E293B),
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                SessionsListCard(details.sessions)
            }

            // Pickups During Peak
            item {
                PickupsDuringPeakCard(
                    pickupCount = details.pickupCount,
                    isEstimated = details.isPickupEstimated
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun PeakSummaryCard(details: PeakTimeDetails) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Peak time icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        if (details.isHighRisk) Color(0xFFEF4444).copy(alpha = 0.1f)
                        else Primary.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (details.isHighRisk) Icons.Filled.Warning else Icons.Filled.AccessTime,
                    contentDescription = null,
                    tint = if (details.isHighRisk) Color(0xFFEF4444) else Primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Peak Usage Window",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF94A3B8)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = details.peakWindowRange,
                style = MaterialTheme.typography.headlineSmall,
                color = if (details.isHighRisk) Color(0xFFEF4444) else Primary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${details.totalMinutes} minutes of usage",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B)
            )

            if (details.isHighRisk) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "High usage detected",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Category breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CategoryChip(
                    label = "Distractive",
                    minutes = details.distractiveMinutes,
                    color = DistractiveColor
                )
                CategoryChip(
                    label = "Neutral",
                    minutes = details.neutralMinutes,
                    color = NeutralColor
                )
                CategoryChip(
                    label = "Productive",
                    minutes = details.productiveMinutes,
                    color = ProductiveColor
                )
            }
        }
    }
}

@Composable
fun CategoryChip(label: String, minutes: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${minutes}m",
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8)
        )
    }
}

@Composable
fun PeakTimelineCard(details: PeakTimeDetails) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Activity During Peak Window",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF1E293B),
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Timeline bars for each hour in peak window
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                details.hourlyBreakdown.forEach { (hour, usage) ->
                    val totalMinutes = usage.first + usage.second + usage.third
                    val maxMinutes = 60f
                    val heightFraction = (totalMinutes / maxMinutes).coerceAtMost(1f)
                    val hasRisk = totalMinutes > 45

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        // Stacked bar
                        if (totalMinutes > 0) {
                            val totalHeight = (100 * heightFraction).dp

                            Column(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(totalHeight)
                                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            ) {
                                if (usage.first > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(usage.first.toFloat())
                                            .background(DistractiveColor)
                                    )
                                }
                                if (usage.second > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(usage.second.toFloat())
                                            .background(NeutralColor)
                                    )
                                }
                                if (usage.third > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(usage.third.toFloat())
                                            .background(ProductiveColor)
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFE2E8F0))
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = formatHour(hour),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasRisk) Color(0xFFEF4444) else Color(0xFF94A3B8)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendItem("Distractive", DistractiveColor)
                Spacer(modifier = Modifier.width(16.dp))
                LegendItem("Neutral", NeutralColor)
                Spacer(modifier = Modifier.width(16.dp))
                LegendItem("Productive", ProductiveColor)
            }
        }
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF64748B)
        )
    }
}

@Composable
fun TopAppsDuringPeakCard(apps: List<PeakAppUsage>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            if (apps.isEmpty()) {
                Text(
                    text = "No app usage data during peak window",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                apps.take(5).forEachIndexed { index, app ->
                    PeakAppRow(app)
                    if (index < apps.size - 1 && index < 4) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PeakAppRow(app: PeakAppUsage) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName) { AppUtils.getAppIcon(context, app.packageName) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        appIcon?.let { drawable ->
            Image(
                bitmap = drawable.toBitmap(40, 40).asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } ?: Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF1F5F9)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF1E293B),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (app.category) {
                                AppCategory.DISTRACTIVE -> DistractiveColor
                                AppCategory.NEUTRAL -> NeutralColor
                                AppCategory.PRODUCTIVE -> ProductiveColor
                            }
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = app.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Text(
            text = formatMinutes(app.durationMinutes),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF64748B),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SessionsListCard(sessions: List<PeakSession>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            if (sessions.isEmpty()) {
                Text(
                    text = "No sessions during peak window",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                sessions.take(10).forEachIndexed { index, session ->
                    SessionRow(session)
                    if (index < sessions.size - 1 && index < 9) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Color(0xFFE2E8F0))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SessionRow(session: PeakSession) {
    val isLongSession = session.durationMinutes > 45

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (isLongSession) {
                    it.background(
                        Color(0xFFEF4444).copy(alpha = 0.05f),
                        RoundedCornerShape(8.dp)
                    ).padding(8.dp)
                } else it
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.appName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLongSession) Color(0xFFEF4444) else Color(0xFF1E293B),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${session.startTime} - ${session.endTime}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLongSession) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Long session",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = formatMinutes(session.durationMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isLongSession) Color(0xFFEF4444) else Color(0xFF64748B),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (isLongSession) {
                Text(
                    text = "Long session",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF4444)
                )
            }
        }
    }
}

@Composable
fun PickupsDuringPeakCard(pickupCount: Int, isEstimated: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TouchApp,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Pickups During Peak",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF1E293B),
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isEstimated) {
                            Text(
                                text = "Estimated from app opens",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFADB5BD)
                            )
                        }
                    }
                }

                Text(
                    text = "${pickupCount}×",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// Helper functions
private fun formatHour(hour: Int): String {
    return when {
        hour == 0 -> "12a"
        hour < 12 -> "${hour}a"
        hour == 12 -> "12p"
        else -> "${hour - 12}p"
    }
}

private fun formatMinutes(minutes: Int): String {
    return when {
        minutes < 60 -> "${minutes}m"
        else -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "${h}h" else "${h}h ${m}m"
        }
    }
}
