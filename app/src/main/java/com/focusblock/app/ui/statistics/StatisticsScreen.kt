@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.TimeUtils
import com.focusblock.app.viewmodel.StatisticsViewModel
import com.focusblock.app.viewmodel.StatsPeriod

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
        }

        // Period selector
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatsPeriod.values().forEach { period ->
                    FilterChip(
                        selected = uiState.selectedPeriod == period,
                        onClick = { viewModel.setPeriod(period) },
                        label = { Text(period.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary,
                            selectedLabelColor = TextPrimary,
                            containerColor = CardDark,
                            labelColor = TextSecondary
                        )
                    )
                }
            }
        }

        // Summary cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatSummaryCard(
                    title = "Blocks",
                    value = uiState.totalBlocks.toString(),
                    icon = Icons.Outlined.Block,
                    color = AccentRed,
                    modifier = Modifier.weight(1f)
                )
                StatSummaryCard(
                    title = "Saved",
                    value = TimeUtils.formatDurationShort(uiState.savedTimeMillis),
                    icon = Icons.Outlined.Timer,
                    color = StatusActive,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Block count chart representation
        item {
            BlocksChartCard(
                dailyBlocks = uiState.dailyBlocks,
                period = uiState.selectedPeriod
            )
        }

        // Most blocked apps
        if (uiState.mostBlockedApps.isNotEmpty()) {
            item {
                Text(
                    text = "Most Blocked Apps",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }

            items(uiState.mostBlockedApps.take(5)) { (packageName, count) ->
                MostBlockedAppItem(
                    packageName = packageName,
                    blockCount = count,
                    totalBlocks = uiState.totalBlocks
                )
            }
        }

        // Recent blocks
        if (uiState.recentBlocks.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Blocks",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(uiState.recentBlocks.take(10)) { blockLog ->
                RecentBlockItem(blockLog = blockLog)
            }
        }
    }
}

@Composable
fun StatSummaryCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun BlocksChartCard(
    dailyBlocks: Map<String, Int>,
    period: StatsPeriod
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
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
                Text(
                    text = "Block Activity",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                if (dailyBlocks.isNotEmpty()) {
                    val totalBlocks = dailyBlocks.values.sum()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "$totalBlocks total",
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (dailyBlocks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BarChart,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No data yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                // Enhanced bar chart
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val maxBlocks = dailyBlocks.values.maxOrNull()?.toFloat() ?: 1f
                    val entries = dailyBlocks.entries.toList().takeLast(7)
                    val maxIndex = entries.indexOfFirst { it.value == maxBlocks.toInt() }

                    entries.forEachIndexed { index, (day, count) ->
                        val heightPercent = if (maxBlocks > 0) count / maxBlocks else 0f
                        val isHighest = index == maxIndex && count > 0

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (count > 0) {
                                Text(
                                    text = count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isHighest) Primary else TextTertiary,
                                    fontWeight = if (isHighest) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height((100 * heightPercent).dp.coerceAtLeast(6.dp))
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (count > 0) {
                                            if (isHighest) Primary else Primary.copy(alpha = 0.6f)
                                        } else {
                                            SurfaceElevated
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = day.takeLast(2),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isHighest) Primary else TextTertiary,
                                fontWeight = if (isHighest) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MostBlockedAppItem(
    packageName: String,
    blockCount: Int,
    totalBlocks: Int
) {
    val context = LocalContext.current
    val appName = remember(packageName) { AppUtils.getAppName(context, packageName) }
    val appIcon = remember(packageName) { AppUtils.getAppIcon(context, packageName) }
    val percentage = if (totalBlocks > 0) (blockCount.toFloat() / totalBlocks * 100).toInt() else 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            appIcon?.let { drawable ->
                Image(
                    bitmap = drawable.toBitmap(48, 48).asImageBitmap(),
                    contentDescription = appName,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } ?: Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Android,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Progress bar with rounded track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceElevated)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(percentage / 100f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Primary)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = blockCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary
                )
            }
        }
    }
}

@Composable
fun RecentBlockItem(
    blockLog: com.focusblock.app.database.entity.BlockLog
) {
    val context = LocalContext.current
    val appIcon = remember(blockLog.packageName) { AppUtils.getAppIcon(context, blockLog.packageName) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = CardDark
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            appIcon?.let { drawable ->
                Image(
                    bitmap = drawable.toBitmap(40, 40).asImageBitmap(),
                    contentDescription = blockLog.appName,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } ?: Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Android,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = blockLog.appName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Blocked by ${blockLog.blockedBy.name.lowercase().replace("_", " ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceElevated
            ) {
                Text(
                    text = TimeUtils.getTimeString(blockLog.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
