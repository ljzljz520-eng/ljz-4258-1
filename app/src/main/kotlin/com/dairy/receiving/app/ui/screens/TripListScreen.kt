package com.dairy.receiving.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dairy.receiving.app.workflow.TripUiState
import com.dairy.receiving.core.model.CompartmentStatus
import com.dairy.receiving.core.model.FindingCode
import com.dairy.receiving.core.model.FindingSeverity
import com.dairy.receiving.core.model.Recommendation
import com.dairy.receiving.app.ui.theme.BlockRed
import com.dairy.receiving.app.ui.theme.SafeGreen
import com.dairy.receiving.app.ui.theme.WarnAmber

@Composable
fun RecommendationChip(rec: Recommendation) {
    val (label, color) = when (rec) {
        Recommendation.ACCEPT -> "建议接收" to SafeGreen
        Recommendation.ACCEPT_WITH_NOTE -> "附条件接收" to WarnAmber
        Recommendation.HOLD -> "建议挂起" to BlockRed
        Recommendation.REJECT -> "建议拒收" to BlockRed
    }
    Surface(color = color.copy(alpha = 0.12f)) {
        Text(label, color = color, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
fun SeverityDot(code: FindingCode) {
    val color = when (code.severity) {
        FindingSeverity.CRITICAL -> BlockRed
        FindingSeverity.WARNING -> WarnAmber
        FindingSeverity.INFO -> SafeGreen
    }
    Surface(color = color, modifier = Modifier.size(8.dp)) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripOverviewScreen(
    state: TripUiState,
    onSelect: (String) -> Unit,
    onPickDemo: (Int) -> Unit,
) {
    var demoMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("车 ${state.truckId.ifBlank { "—" }}  单 ${state.tripId.take(8)}",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                RecommendationChip(state.overall)
            }
            Box {
                OutlinedButton(onClick = { demoMenu = true }) { Text("演练异常") }
                DropdownMenu(expanded = demoMenu, onDismissRequest = { demoMenu = false }) {
                    val demos = listOf(
                        "封签手写不清", "车载温度缺段", "先卸后取样",
                        "混合样瓶贴错", "中途补装另一牧场")
                    demos.forEachIndexed { i, name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            demoMenu = false; onPickDemo(i)
                        })
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("独立仓室（混合卸载不合并身份）",
            style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        state.compartments.forEach { c ->
            ElevatedCard(
                onClick = { onSelect(c.code) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (c.code == state.selected)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    else MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("仓 ${c.code}", fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Text("${c.farm}/${c.farmBatch}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(c.status.name, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f))
                        RecommendationChip(c.recommendation)
                    }
                    val open = c.findings.filter { it.open }
                    if (open.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        open.take(3).forEach { f ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                SeverityDot(f.code)
                                Spacer(Modifier.width(6.dp))
                                Text(f.code.name + "：" + f.detail.take(42),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (open.size > 3) Text("…另 ${open.size - 3} 项",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Text("封签=${c.sealNfc ?: "—"} 温度=${c.probeCelsius ?: "—"}" +
                        " 样品=${c.sampleCount} 卸前样=${if (c.preUnloadSample) "有" else "缺"}" +
                        " 组=${c.group ?: "—"}",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
