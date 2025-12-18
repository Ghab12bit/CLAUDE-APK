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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Block Activity",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (dailyBlocks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No data yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                // Simple bar chart
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val maxBlocks = dailyBlocks.values.maxOrNull()?.toFloat() ?: 1f

                    dailyBlocks.entries.toList().takeLast(7).forEach { (day, count) ->
                        val heightPercent = if (maxBlocks > 0) count / maxBlocks else 0f

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height((100 * heightPercent).dp.coerceAtLeast(4.dp))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        if (count > 0) Primary else Divider
                                    )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = day.takeLast(2), // Just show day number
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            appIcon?.let { drawable ->
                Image(
                    bitmap = drawable.toBitmap(48, 48).asImageBitmap(),
                    contentDescription = appName,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } ?: Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Divider),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Android,
                    contentDescription = null,
                    tint = TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Progress bar
                LinearProgressIndicator(
                    progress = percentage / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Primary,
                    trackColor = Divider
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = blockCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        appIcon?.let { drawable ->
            Image(
                bitmap = drawable.toBitmap(40, 40).asImageBitmap(),
                contentDescription = blockLog.appName,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } ?: Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Divider)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = blockLog.appName,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "Blocked by ${blockLog.blockedBy.name.lowercase().replace("_", " ")}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Text(
            text = TimeUtils.getTimeString(blockLog.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
    }
}
