@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@file:Suppress("DEPRECATION")

package com.focusblock.app.ui.statistics

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.viewmodel.InsightsViewModel
import com.focusblock.app.viewmodel.InsightsTab
import com.focusblock.app.viewmodel.AppCategory
import com.focusblock.app.viewmodel.RepeatOffenderApp
import com.focusblock.app.viewmodel.OffenderSeverity
import kotlinx.coroutines.launch

// Color scheme for categories
val DistractiveColor = Color(0xFF8B5CF6) // Purple
val NeutralColor = Color(0xFF06B6D4) // Cyan
val ProductiveColor = Color(0xFF10B981) // Green

@Composable
fun StatisticsScreen(
    viewModel: InsightsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { InsightsTab.values().size })
    val coroutineScope = rememberCoroutineScope()

    // State for showing peak time detail screen
    var showPeakTimeDetail by remember { mutableStateOf(false) }

    // Sync pager with viewmodel
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setTab(InsightsTab.values()[pagerState.currentPage])
    }

    // Show Peak Time Detail screen if requested
    if (showPeakTimeDetail && uiState.peakTimeDetails != null) {
        PeakTimeDetailScreen(
            details = uiState.peakTimeDetails!!,
            onBackClick = { showPeakTimeDetail = false }
        )
        return
    }

    // Excluded apps dialog (shown as overlay)
    if (uiState.showExcludedAppsDialog) {
        ExcludedAppsDialog(
            excludedPackages = uiState.userExcludedPackages,
            onIncludeApp = { pkg -> viewModel.includeAppInUsage(pkg) },
            onDismiss = { viewModel.toggleExcludedAppsDialog() }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Header
        InsightsHeader(
            selectedTab = uiState.selectedTab,
            onTabSelected = { tab ->
                coroutineScope.launch {
                    pagerState.animateScrollToPage(tab.ordinal)
                }
                viewModel.setTab(tab)
            }
        )

        // Content - Reorganized: Summary → Key Metrics → Apps → Details (chart last)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Date Navigator (only for Day tab)
            if (uiState.selectedTab == InsightsTab.DAY) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Previous day button
                        Surface(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { viewModel.goToPreviousDay() },
                            shape = CircleShape,
                            color = CardDark
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ChevronLeft,
                                contentDescription = "Previous day",
                                tint = Primary,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(24.dp)
                            )
                        }

                        // Date label
                        Text(
                            text = uiState.dateLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Next day button (disabled if viewing today)
                        Surface(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(enabled = uiState.canGoForward) { viewModel.goToNextDay() },
                            shape = CircleShape,
                            color = if (uiState.canGoForward) CardDark else Color.Transparent
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Next day",
                                tint = if (uiState.canGoForward) Primary else TextSecondary,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(24.dp)
                            )
                        }
                    }
                }
            }

            // ========== SUMMARY SECTION ==========
            // Compact screen time summary - key number at a glance
            item {
                CompactSummaryCard(
                    screenTime = uiState.totalScreenTime,
                    changeText = uiState.screenTimeChange,
                    isPositive = uiState.isChangePositive,
                    pickupCount = uiState.pickupCount,
                    longestSession = uiState.longestContinuousUse,
                    hasSessionWarning = uiState.longestSessionWarning
                )
            }

            // ========== KEY METRICS SECTION ==========
            item {
                Text(
                    text = "Key Metrics",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }

            // Key metrics in a compact row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactMetricCard(
                        title = "Focus",
                        value = uiState.longestFocus,
                        icon = Icons.Outlined.Timer,
                        modifier = Modifier.weight(1f)
                    )
                    CompactMetricCard(
                        title = "Balance",
                        value = "${uiState.balancePercentage}%",
                        icon = Icons.Outlined.PieChart,
                        modifier = Modifier.weight(1f),
                        subtitle = "of awake time"
                    )
                    CompactMetricCard(
                        title = "Peak",
                        value = uiState.peakTimeRange.split(" - ").firstOrNull() ?: "",
                        icon = Icons.Outlined.Schedule,
                        modifier = Modifier.weight(1f),
                        hasWarning = uiState.peakTimeRisk
                    )
                }
            }

            // ========== APPS SECTION ==========
            item {
                Text(
                    text = "Apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }

            // Most Used Apps
            item {
                MostUsedAppsCard(
                    apps = uiState.mostUsedApps,
                    expanded = uiState.appsExpanded,
                    excludedPackages = uiState.userExcludedPackages,
                    onExpandClick = { viewModel.toggleAppsExpanded() },
                    onExcludeApp = { pkg, name -> viewModel.excludeAppFromUsage(pkg, name) },
                    onManageExcluded = { viewModel.toggleExcludedAppsDialog() }
                )
            }

            // Repeat Offenders (apps user keeps trying to open)
            if (uiState.hasRepeatOffenders) {
                item {
                    RepeatOffendersCard(
                        offenders = uiState.repeatOffenders
                    )
                }
            }

            // Usage Distribution (category breakdown)
            item {
                UsageDistributionCard(
                    distractivePercent = uiState.distractivePercent,
                    neutralPercent = uiState.neutralPercent,
                    productivePercent = uiState.productivePercent
                )
            }

            // ========== DETAILS SECTION ==========
            item {
                Text(
                    text = "Timeline",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }

            // Usage Timeline Chart - moved to bottom as it's detail-level
            item {
                UsageTimelineCard(
                    hourlyUsage = uiState.hourlyUsage
                )
            }

            // Peak Time Card with details
            item {
                PeakTimeCard(
                    peakTimeRange = uiState.peakTimeRange,
                    hourlyUsage = uiState.hourlyUsage,
                    hasRisk = uiState.peakTimeRisk,
                    onClick = { showPeakTimeDetail = true }
                )
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ========== NEW COMPACT SUMMARY CARD ==========
@Composable
fun CompactSummaryCard(
    screenTime: String,
    changeText: String,
    isPositive: Boolean,
    pickupCount: Int,
    longestSession: String,
    hasSessionWarning: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Main screen time - prominent but not overwhelming
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = screenTime,
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Screen Time",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Change indicator
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isPositive) Color(0xFF10B981).copy(alpha = 0.15f)
                            else Color(0xFFEF4444).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = changeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickStat(
                    label = "Pickups",
                    value = pickupCount.toString(),
                    icon = Icons.Outlined.TouchApp
                )
                QuickStat(
                    label = "Longest",
                    value = longestSession,
                    icon = Icons.Outlined.AccessTime,
                    hasWarning = hasSessionWarning
                )
            }
        }
    }
}

@Composable
private fun QuickStat(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    hasWarning: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (hasWarning) Color(0xFFF0883E) else TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (hasWarning) Color(0xFFF0883E) else TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

// ========== COMPACT METRIC CARD ==========
@Composable
fun CompactMetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    hasWarning: Boolean = false
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (hasWarning) Color(0xFFF0883E) else Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (hasWarning) Color(0xFFF0883E) else TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun InsightsHeader(
    selectedTab: InsightsTab,
    onTabSelected: (InsightsTab) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = TextSecondary
                )
                Text(
                    text = "Insights",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "AI",
                    tint = Primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tab Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(25.dp))
                    .background(CardDark),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InsightsTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onTabSelected(tab) },
                        color = if (isSelected) Primary else Color.Transparent,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) Color.White else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeroMetricCard(
    screenTime: String,
    changeText: String,
    isPositive: Boolean,
    weeklyTrendText: String = "",
    weeklyTrendPercent: Int = 0,
    hasWeeklyTrend: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = screenTime,
                style = MaterialTheme.typography.displayMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "SCREEN TIME",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = changeText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444)
            )

            // Weekly trend indicator
            if (hasWeeklyTrend && weeklyTrendText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .background(
                            color = when {
                                weeklyTrendPercent > 10 -> Color(0xFFEF4444).copy(alpha = 0.1f)
                                weeklyTrendPercent < -10 -> Color(0xFF10B981).copy(alpha = 0.1f)
                                else -> TextSecondary.copy(alpha = 0.1f)
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = when {
                            weeklyTrendPercent > 0 -> Icons.Filled.TrendingUp
                            weeklyTrendPercent < 0 -> Icons.Filled.TrendingDown
                            else -> Icons.Filled.TrendingFlat
                        },
                        contentDescription = null,
                        tint = when {
                            weeklyTrendPercent > 10 -> Color(0xFFEF4444)
                            weeklyTrendPercent < -10 -> Color(0xFF10B981)
                            else -> TextSecondary
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = weeklyTrendText,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            weeklyTrendPercent > 10 -> Color(0xFFEF4444)
                            weeklyTrendPercent < -10 -> Color(0xFF10B981)
                            else -> TextSecondary
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun UsageTimelineCard(
    hourlyUsage: Map<Int, Triple<Int, Int, Int>> // Hour -> (Distractive, Neutral, Productive) minutes
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
            // Y-axis labels and chart
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                // Y-axis labels
                Column(
                    modifier = Modifier
                        .width(30.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1h", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text("30m", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text("0s", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }

                // Chart bars
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val hours = listOf(0, 3, 6, 9, 12, 15, 18, 21)
                    val maxMinutes = 60f

                    hours.forEach { hour ->
                        val (distractive, neutral, productive) = hourlyUsage[hour] ?: Triple(0, 0, 0)
                        val total = distractive + neutral + productive

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.weight(1f)
                        ) {
                            // Stacked bar
                            if (total > 0) {
                                val totalHeight = (150 * (total / maxMinutes).coerceAtMost(1f)).dp

                                Column(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(totalHeight)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                ) {
                                    if (distractive > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(distractive.toFloat())
                                                .background(DistractiveColor)
                                        )
                                    }
                                    if (neutral > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(neutral.toFloat())
                                                .background(NeutralColor)
                                        )
                                    }
                                    if (productive > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(productive.toFloat())
                                                .background(ProductiveColor)
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(SurfaceElevated)
                                )
                            }
                        }
                    }
                }
            }

            // X-axis labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 38.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("12", "6", "12", "6").forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun MostUsedAppsCard(
    apps: List<AppUsageInfo>,
    expanded: Boolean,
    excludedPackages: Set<String> = emptySet(),
    onExpandClick: () -> Unit,
    onExcludeApp: (String, String) -> Unit = { _, _ -> },
    onManageExcluded: () -> Unit = {}
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
                    text = "Most used apps",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                if (excludedPackages.isNotEmpty()) {
                    TextButton(
                        onClick = onManageExcluded,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "${excludedPackages.size} hidden",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val displayApps = if (expanded) apps else apps.take(5)

            displayApps.forEach { app ->
                MostUsedAppRow(
                    app = app,
                    onExclude = { onExcludeApp(app.packageName, app.appName) }
                )
                if (app != displayApps.last()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            if (apps.size > 5) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onExpandClick,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = if (expanded) "Show less" else "More",
                        color = TextSecondary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MostUsedAppRow(
    app: AppUsageInfo,
    onExclude: () -> Unit = {}
) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName) { AppUtils.getAppIcon(context, app.packageName) }
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { /* Could open app details */ },
                onLongClick = { showMenu = true }
            ),
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
                .background(CardDark),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
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
                    color = TextSecondary
                )
            }
        }

        Text(
            text = app.usageDuration,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        // More options button with dropdown
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Options",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Hide from total", color = TextPrimary)
                        }
                    },
                    onClick = {
                        showMenu = false
                        onExclude()
                    }
                )
            }
        }
    }
}

@Composable
fun BalanceCard(
    phoneTime: String,
    awakeTime: String,
    percentage: Int
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
                    text = "Balance",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$percentage % of awake time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress bar
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

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Smartphone,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = phoneTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.WbSunny,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = awakeTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun PeakTimeCard(
    peakTimeRange: String,
    hourlyUsage: Map<Int, Triple<Int, Int, Int>>,
    hasRisk: Boolean = false,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Peak time",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (hasRisk) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "High usage warning",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peakTimeRange,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasRisk) Color(0xFFEF4444) else Primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "View details",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (hasRisk) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "High usage detected (>45min/hour)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF4444)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mini sparkline
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                (0..23).forEach { hour ->
                    val (d, n, p) = hourlyUsage[hour] ?: Triple(0, 0, 0)
                    val total = d + n + p
                    val maxMinutes = 60f
                    val height = (36 * (total / maxMinutes).coerceAtMost(1f)).dp.coerceAtLeast(2.dp)
                    val barHasRisk = total > 45

                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(height)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    total == 0 -> SurfaceBorder
                                    barHasRisk -> Color(0xFFEF4444).copy(alpha = 0.8f)
                                    else -> Primary.copy(alpha = 0.6f)
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun UsageDistributionCard(
    distractivePercent: Int,
    neutralPercent: Int,
    productivePercent: Int
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
            // Distractive
            UsageDistributionRow(
                label = "Distractive",
                color = DistractiveColor,
                percentage = distractivePercent
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Neutral
            UsageDistributionRow(
                label = "Neutral",
                color = NeutralColor,
                percentage = neutralPercent
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Productive
            UsageDistributionRow(
                label = "Productive",
                color = ProductiveColor,
                percentage = productivePercent
            )
        }
    }
}

@Composable
fun UsageDistributionRow(
    label: String,
    color: Color,
    percentage: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(100.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percentage / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "$percentage %",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(50.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun FocusMetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    hasWarning: Boolean = false
) {
    val warningColor = Color(0xFFEF4444)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (hasWarning) warningColor.copy(alpha = 0.1f) else Primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (hasWarning) Icons.Filled.Warning else icon,
                    contentDescription = null,
                    tint = if (hasWarning) warningColor else Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (hasWarning) warningColor else TextSecondary,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (hasWarning) warningColor else TextPrimary,
                fontWeight = FontWeight.SemiBold
            )

            if (hasWarning) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ">45min session",
                    style = MaterialTheme.typography.labelSmall,
                    color = warningColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun DistractionsCard(
    pickupCount: Int,
    isEstimated: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.TouchApp,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = "PICKUPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "${pickupCount}×",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
                if (isEstimated) {
                    Text(
                        text = "Estimated from app opens",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun RepeatOffendersCard(
    offenders: List<RepeatOffenderApp>
) {
    val context = LocalContext.current

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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF0883E),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Apps you keep trying to open",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            offenders.forEach { offender ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App icon
                    val icon = remember(offender.packageName) {
                        try {
                            context.packageManager.getApplicationIcon(offender.packageName)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (icon != null) {
                        Image(
                            bitmap = icon.toBitmap(48, 48).asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Android,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // App name and block count
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = offender.appName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${offender.blockCount} blocks today",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }

                    // Severity indicator
                    Box(
                        modifier = Modifier
                            .background(
                                color = when (offender.severity) {
                                    OffenderSeverity.HIGH -> Color(0xFFEF4444).copy(alpha = 0.1f)
                                    OffenderSeverity.MEDIUM -> Color(0xFFF0883E).copy(alpha = 0.1f)
                                    OffenderSeverity.LOW -> TextSecondary.copy(alpha = 0.1f)
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when (offender.severity) {
                                OffenderSeverity.HIGH -> "High"
                                OffenderSeverity.MEDIUM -> "Medium"
                                OffenderSeverity.LOW -> "Low"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (offender.severity) {
                                OffenderSeverity.HIGH -> Color(0xFFEF4444)
                                OffenderSeverity.MEDIUM -> Color(0xFFF0883E)
                                OffenderSeverity.LOW -> TextSecondary
                            }
                        )
                    }
                }
            }
        }
    }
}

// Data class for app usage info
data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageDuration: String,
    val usageMinutes: Int,
    val category: AppCategory
)

/**
 * Dialog to manage apps excluded from total usage
 */
@Composable
fun ExcludedAppsDialog(
    excludedPackages: Set<String>,
    onIncludeApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Hidden Apps",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
            ) {
                Text(
                    text = "These apps are excluded from your screen time total. Tap to show again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (excludedPackages.isEmpty()) {
                    Text(
                        text = "No hidden apps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    excludedPackages.forEach { packageName ->
                        val appName = try {
                            context.packageManager.getApplicationLabel(
                                context.packageManager.getApplicationInfo(packageName, 0)
                            ).toString()
                        } catch (e: Exception) {
                            packageName.substringAfterLast(".")
                        }
                        val appIcon = AppUtils.getAppIcon(context, packageName)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onIncludeApp(packageName) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            appIcon?.let { drawable ->
                                Image(
                                    bitmap = drawable.toBitmap(36, 36).asImageBitmap(),
                                    contentDescription = appName,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } ?: Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Android,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = appName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = "Show app",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (packageName != excludedPackages.last()) {
                            HorizontalDivider(
                                color = TextSecondary.copy(alpha = 0.2f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Primary)
            }
        },
        containerColor = CardDark,
        shape = RoundedCornerShape(20.dp)
    )
}
