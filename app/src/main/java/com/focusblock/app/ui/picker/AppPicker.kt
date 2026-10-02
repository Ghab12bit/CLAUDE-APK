package com.focusblock.app.ui.picker

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focusblock.app.R
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.InstalledApps
import com.focusblock.app.core.csv
import com.focusblock.app.database.entity.AppGroup
import com.focusblock.app.ui.components.AppIcon
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.FbSheet
import com.focusblock.app.ui.components.LabeledField
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.SectionLabel
import com.focusblock.app.ui.components.SkeletonLine
import com.focusblock.app.ui.components.TextLink
import com.focusblock.app.ui.components.pluralRes
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the picker was opened from; decides its title (spec 3.2) and what can be selected. */
enum class PickerContext { BLOCK, BLOCK_ADD, RULE, APP_LIMIT, DAILY_LIMIT, ESSENTIALS, SET, ONBOARDING }

data class PickerData(
    val loaded: Boolean = false,
    val apps: List<InstalledApps.App> = emptyList(),
    val recent: List<String> = emptyList(),
    val sets: List<AppGroup> = emptyList(),
    val often: List<String> = emptyList(),
    val essentials: Set<String> = emptySet(),
    val safety: Set<String> = emptySet(),
)

/** Activity-scoped so app labels and icons load once (spec 11.4: picker opens without freezing). */
class PickerViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(PickerData())
    val data: StateFlow<PickerData> = mutable.asStateFlow()

    init {
        viewModelScope.launch { graph.db.appGroupDao().getAllGroups().collect { sets -> mutable.update { it.copy(sets = sets) } } }
        viewModelScope.launch { graph.essentials.flow().collect { e -> mutable.update { it.copy(essentials = e) } } }
    }

    fun load() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val apps = graph.apps.all()
                val installed = apps.map { it.packageName }.toSet()
                val recent = buildList {
                    graph.sessions.lastSetup()?.packages?.let(::addAll)
                    graph.db.blockSessionDao().since(0).take(10).forEach { addAll(csv(it.packages)) }
                }.distinct().filter { it in installed }.take(8)
                val since = System.currentTimeMillis() - 14L * 24 * 3_600_000
                val often = graph.db.attemptDao().since(since).groupingBy { it.packageName }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key }.filter { it in installed }.take(8)
                Triple(apps, recent, often) to graph.safety.packages()
            }
            mutable.update { it.copy(loaded = true, apps = loaded.first.first, recent = loaded.first.second, often = loaded.first.third, safety = loaded.second) }
        }
    }

    fun saveSet(name: String, packages: List<String>) {
        viewModelScope.launch {
            graph.db.appGroupDao().insert(AppGroup(name = name.trim().take(40), packages = packages.joinToString(",")))
        }
    }

    fun deleteSet(group: AppGroup) { viewModelScope.launch { graph.db.appGroupDao().delete(group) } }
    fun renameSet(group: AppGroup, name: String) { viewModelScope.launch { graph.db.appGroupDao().update(group.copy(name = name.trim().take(40), updatedAt = System.currentTimeMillis())) } }
    fun updateSet(group: AppGroup, packages: List<String>) { viewModelScope.launch { graph.db.appGroupDao().updatePackages(group.id, packages.joinToString(",")) } }

    fun label(pkg: String): String = mutable.value.apps.firstOrNull { it.packageName == pkg }?.label ?: graph.apps.label(pkg)
}

@Composable
fun pickerViewModel(): PickerViewModel {
    val activity = LocalContext.current as ComponentActivity
    val graph = AppGraph.get(activity.applicationContext)
    return viewModel<PickerViewModel>(activity, factory = viewModelFactory { initializer { PickerViewModel(graph) } })
}

/**
 * Bottom-sheet app picker (spec 4.2). Selection is kept with rememberSaveable (the saved-state
 * registry) so it survives rotation and process death. A one-off selection is returned to the
 * caller only; the picker never writes a permanent blocklist.
 */
@Composable
fun AppPickerSheet(
    context: PickerContext,
    initial: List<String>,
    onDismiss: () -> Unit,
    onDone: (List<String>) -> Unit,
    ruleName: String? = null,
    /** Apps that stay selected and cannot be removed (apps already in a running block). */
    fixed: Set<String> = emptySet(),
) {
    val vm = pickerViewModel()
    LaunchedEffect(Unit) { vm.load() }
    val data by vm.data.collectAsStateWithLifecycle()
    var selectedCsv by rememberSaveable { mutableStateOf(initial.joinToString(",")) }
    val selected = remember(selectedCsv) { csv(selectedCsv).toSet() }
    var query by rememberSaveable { mutableStateOf("") }
    var savingSet by rememberSaveable { mutableStateOf(false) }

    val essentialsSelectable = context == PickerContext.ESSENTIALS
    fun locked(pkg: String) = pkg in data.safety || (!essentialsSelectable && pkg in data.essentials)
    fun toggle(pkg: String) {
        if (locked(pkg) || pkg in fixed) return
        val next = if (pkg in selected) selected - pkg else selected + pkg
        selectedCsv = next.joinToString(",")
    }
    val title = when (context) {
        PickerContext.BLOCK -> stringResource(R.string.picker_title_block)
        PickerContext.BLOCK_ADD -> stringResource(R.string.picker_title_block_add)
        PickerContext.RULE -> stringResource(R.string.picker_title_rule, ruleName ?: stringResource(R.string.type_routine))
        PickerContext.APP_LIMIT -> stringResource(R.string.picker_title_app_limit)
        PickerContext.DAILY_LIMIT -> stringResource(R.string.picker_title_daily_limit)
        PickerContext.ESSENTIALS -> stringResource(R.string.picker_title_essentials)
        PickerContext.SET -> stringResource(R.string.picker_title_set)
        PickerContext.ONBOARDING -> stringResource(R.string.picker_title_onboarding)
    }
    val byPkg = remember(data.apps) { data.apps.associateBy { it.packageName } }

    FbSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Text(title, style = FbType.heading, modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(12.dp))
            LabeledField(stringResource(R.string.picker_search), query, { query = it }, stringResource(R.string.picker_search),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f)) {
                if (!data.loaded) {
                    Column { repeat(8) { SkeletonLine(0.8f, 40.dp) } }
                } else {
                    val q = query.trim().lowercase()
                    val matches = if (q.isEmpty()) data.apps else data.apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        if (q.isEmpty()) {
                            val showSets = context != PickerContext.ESSENTIALS && context != PickerContext.SET
                            val recent = data.recent.mapNotNull(byPkg::get)
                            if (recent.isNotEmpty() && context != PickerContext.ESSENTIALS) {
                                item(key = "h_recent") { SectionLabel(stringResource(R.string.picker_recent)) }
                                items(recent, key = { "r_" + it.packageName }) { app -> AppRow(app, app.packageName in selected || app.packageName in fixed, locked(app.packageName), app.packageName in data.safety, app.packageName in fixed) { toggle(app.packageName) } }
                            }
                            if (showSets && data.sets.isNotEmpty()) {
                                item(key = "h_sets") { SectionLabel(stringResource(R.string.picker_saved_sets)) }
                                items(data.sets, key = { "s_" + it.id }) { set ->
                                    val packages = csv(set.packages)
                                    SetRow(set.name, packages.size) {
                                        selectedCsv = (selected + packages.filter { !locked(it) }).joinToString(",")
                                    }
                                }
                            }
                            val often = data.often.mapNotNull(byPkg::get)
                            if (often.isNotEmpty() && context != PickerContext.ESSENTIALS) {
                                item(key = "h_often") { SectionLabel(stringResource(R.string.picker_often_blocked)) }
                                items(often, key = { "o_" + it.packageName }) { app -> AppRow(app, app.packageName in selected || app.packageName in fixed, locked(app.packageName), app.packageName in data.safety, app.packageName in fixed) { toggle(app.packageName) } }
                            }
                            item(key = "h_all") { SectionLabel(stringResource(R.string.picker_all_apps)) }
                        }
                        if (matches.isEmpty()) {
                            item(key = "empty") { Text(stringResource(R.string.picker_no_results, query), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(Fb.gutter)) }
                        }
                        items(matches, key = { "a_" + it.packageName }) { app -> AppRow(app, app.packageName in selected || app.packageName in fixed, locked(app.packageName), app.packageName in data.safety, app.packageName in fixed) { toggle(app.packageName) } }
                    }
                }
            }
            FbDivider(inset = 0.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter, vertical = 10.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pluralRes(R.plurals.apps_selected, (selected + fixed).size), style = FbType.label)
                    if (context == PickerContext.BLOCK || context == PickerContext.RULE || context == PickerContext.APP_LIMIT) {
                        TextLink(stringResource(R.string.picker_save_set), { savingSet = true }, enabled = selected.isNotEmpty(), accent = true, modifier = Modifier.padding(start = 0.dp))
                    }
                }
                Box(Modifier.width(140.dp)) {
                    PrimaryButton(stringResource(R.string.action_done), {
                        val all = selected + fixed
                        onDone(data.apps.map { it.packageName }.filter { it in all } + all.filter { s -> data.apps.none { it.packageName == s } })
                    })
                }
            }
        }
    }

    if (savingSet) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { savingSet = false },
            containerColor = Fb.surface,
            title = { Text(stringResource(R.string.picker_save_set), style = FbType.heading) },
            text = { LabeledField(stringResource(R.string.picker_set_name), name, { name = it }, stringResource(R.string.picker_set_name), maxLength = 40, horizontalPadding = 0.dp) },
            confirmButton = {
                TextButton(onClick = { vm.saveSet(name, selected.toList()); savingSet = false }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.action_save), color = if (name.isNotBlank()) Fb.accent else Fb.disabled)
                }
            },
            dismissButton = { TextButton(onClick = { savingSet = false }) { Text(stringResource(R.string.action_cancel), color = Fb.textSecondary) } },
        )
    }
}

@Composable
private fun AppRow(app: InstalledApps.App, selected: Boolean, locked: Boolean, safety: Boolean, fixed: Boolean = false, onToggle: () -> Unit) {
    val description = stringResource(if (selected) R.string.app_selected_cd else R.string.app_not_selected_cd, app.label)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = !locked && !fixed, role = Role.Checkbox, onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = Fb.gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, null, 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = FbType.body.copy(color = if (locked) Fb.textSecondary else Fb.textPrimary), maxLines = 1)
            if (locked) Text(stringResource(R.string.picker_always_available), style = FbType.caption)
            else if (fixed) Text(stringResource(R.string.picker_in_this_block), style = FbType.caption)
        }
        if (!locked) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) (if (fixed) Fb.textSecondary else Fb.buttonPrimaryBg) else Color.Transparent)
                    .border(1.5.dp, if (selected) (if (fixed) Fb.textSecondary else Fb.buttonPrimaryBg) else Fb.textSecondary, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Outlined.Check, null, tint = Fb.buttonPrimaryText, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun SetRow(name: String, count: Int, onUse: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onUse).padding(horizontal = Fb.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = FbType.body)
            Text(pluralRes(R.plurals.apps_count, count), style = FbType.caption)
        }
        Text(stringResource(R.string.picker_add_set), style = FbType.label.copy(color = Fb.accent))
    }
}
