package com.focusblock.app.ui.block

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.focusblock.app.blocking.ImportedRuleStore
import com.focusblock.app.blocking.QuickBlockPolicy

/** Edit existing combined rules without converting them into a simpler schedule. */
@Composable
internal fun ImportedRuleEditor(r: ImportedRuleStore.Rule, change: (ImportedRuleStore.Rule) -> Unit, apps: () -> Unit, save: () -> Unit) {
    var start by remember(r.id) { mutableStateOf("%02d:%02d".format(r.start / 60, r.start % 60)) }
    var end by remember(r.id) { mutableStateOf("%02d:%02d".format(r.end / 60, r.end % 60)) }
    var minutes by remember(r.id) { mutableStateOf(r.minutes.coerceAtLeast(1).toString()) }
    var launches by remember(r.id) { mutableStateOf(r.launchLimit.coerceAtLeast(1).toString()) }
    fun time(value: String): Int? {
        val parts = value.split(':'); if(parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null; val m = parts[1].toIntOrNull() ?: return null
        return if(h in 0..23 && m in 0..59) h * 60 + m else null
    }
    Heading("Edit saved rule")
    OutlinedTextField(r.name, { change(r.copy(name = it)) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
    SheetRow("Apps", "${QuickBlockPolicy.packages(r.packages).size} selected", apps)
    Muted("Original protection: ${r.commitment.lowercase()}. Active committed rules cannot be weakened.")
    if (r.manual) {
        Gap(); Muted("${r.description()}. The existing activation deadline and commitment are preserved.")
    } else {
        ToggleCondition("Time window", r.timed) { change(r.copy(timed = it)) }
        if (r.timed) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(start, { start = it; time(it)?.let { t -> change(r.copy(start = t)) } }, label = { Text("Start HH:mm") }, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it; time(it)?.let { t -> change(r.copy(end = t)) } }, label = { Text("End HH:mm") }, modifier = Modifier.weight(1f))
            }
            val days = r.days.split(',').mapNotNull(String::toIntOrNull).toSet()
            Row(Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, label ->
                    TextButton(onClick = { change(r.copy(days = (if(i + 1 in days) days - (i + 1) else days + (i + 1)).sorted().joinToString(","))) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                        Text(label, color = if(i + 1 in days) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        ToggleCondition("Combined usage allowance", r.usage) { change(r.copy(usage = it, minutes = minutes.toIntOrNull() ?: 1)) }
        if (r.usage) {
            NumberField("Usage minutes (1–720)", minutes) { minutes = it; it.toIntOrNull()?.let { n -> change(r.copy(minutes = n)) } }
            ChoiceRow(listOf("Per day", "Per hour"), if(r.hourly) 1 else 0) { change(r.copy(hourly = it == 1)) }
        }
        if(r.supportsLaunch) ToggleCondition("App-open allowance", r.launches) { change(r.copy(launches = it, launchLimit = launches.toIntOrNull() ?: 1)) }
        if (r.launches) {
            NumberField("App opens (1–1000)", launches) { launches = it; it.toIntOrNull()?.let { n -> change(r.copy(launchLimit = n)) } }
            ChoiceRow(listOf("Per day", "Per hour"), if(r.launchHourly) 1 else 0) { change(r.copy(launchHourly = it == 1)) }
        }
        Gap(); Muted("Combined conditions: all enabled conditions must be met. Allowances are shared across this rule’s apps.")
    }
    Gap(); Action("Save rule", r.name.isNotBlank() && r.packages.isNotBlank() &&
        (!r.timed || (time(start) != null && time(end) != null && r.days.isNotBlank())) &&
        (!r.usage || minutes.toIntOrNull() in 1..720) && (!r.launches || launches.toIntOrNull() in 1..1000), save)
}

@Composable private fun ToggleCondition(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, change)
    }
}
