package com.dairy.receiving.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dairy.receiving.app.workflow.CompartmentUi
import com.dairy.receiving.app.workflow.TripUiState
import com.dairy.receiving.core.model.FindingCode
import com.dairy.receiving.core.model.ScreenAssay

@Composable
fun CompartmentScreen(
    state: TripUiState,
    onManualSeal: (String, Boolean) -> Unit,
    onProbe: (Double, Boolean) -> Unit,
    onTruckLogGap: (Long) -> Unit,
    onSensory: (Boolean, String) -> Unit,
    onStir: (Int) -> Unit,
    onSample: (Double) -> Unit,
    onReload: (String, String) -> Unit,
    onComposite: (List<String>, String) -> Unit,
    onSeparate: (String) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onOverride: (String, Set<FindingCode>) -> Unit,
) {
    val c = state.selectedCompartment ?: return
    val scale = state.scale
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("仓 ${c.code} · ${c.farm}/${c.farmBatch}",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(vertical = 6.dp)) {
            RecommendationChip(c.recommendation)
            Spacer(Modifier.width(8.dp))
            Text("状态 ${c.status.name}", style = MaterialTheme.typography.labelLarge)
        }

        FindingSection(c)

        StepCard("① 封签核对（罐口 NFC 扫签自动闭合；NFC 为权威，手写仅辅助）") {
            if (c.sealNfc != null) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()) {
                    Text("罐口 NFC 已核验：$c.sealNfc（装车单期望 ${c.sealExpected ?: "-"}）" +
                        if (c.sealWritten != null) "；手抄 ${c.sealWritten}" else "",
                        Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("请把罐口标签贴近设备背部 NFC 读头：扫签即完成封签核验",
                    style = MaterialTheme.typography.labelMedium)
            }
            var written by remember { mutableStateOf("") }
            var illegible by remember { mutableStateOf(false) }
            OutlinedTextField(value = written, onValueChange = { written = it },
                label = { Text("手抄封签号（看不清可留空，仅辅助）") },
                isError = illegible, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = illegible, onCheckedChange = { illegible = it })
                Text("手写不清")
            }
            Button(onClick = { onManualSeal(written, illegible) }) { Text("补登手抄封签") }
        }

        StepCard("② 温度（BLE 探针稳定值 + 车载记录）") {
            var temp by remember { mutableStateOf("4.0") }
            var stable by remember { mutableStateOf(true) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = temp, onValueChange = { temp = it },
                    label = { Text("探针 ℃") }, modifier = Modifier.width(140.dp))
                Spacer(Modifier.width(8.dp))
                Switch(checked = stable, onCheckedChange = { stable = it })
                Text("稳定值")
            }
            Button(onClick = { temp.toDoubleOrNull()?.let { onProbe(it, stable) } }) {
                Text("采用 BLE 探针读数")
            }
            var gap by remember { mutableStateOf("0") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = gap, onValueChange = { gap = it },
                    label = { Text("车载记录缺段分钟") }, modifier = Modifier.width(180.dp))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { gap.toLongOrNull()?.let(onTruckLogGap) }) { Text("关联") }
            }
        }

        StepCard("③ 感官") {
            var normal by remember { mutableStateOf(true) }
            var note by remember { mutableStateOf("色泽气味正常") }
            Row {
                FilterChip(selected = normal, onClick = { normal = true }, label = { Text("正常") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !normal, onClick = { normal = false }, label = { Text("异常") })
            }
            OutlinedTextField(value = note, onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSensory(normal, note) }) { Text("提交感官") }
        }

        StepCard("④ 搅拌与取样（深度 + 指定 BLE 采样秤稳定重量）") {
            var sec by remember { mutableStateOf("180") }
            Row {
                OutlinedTextField(value = sec, onValueChange = { sec = it },
                    label = { Text("搅拌秒数（开阀硬前置 ≥120s）") },
                    modifier = Modifier.width(220.dp))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { sec.toIntOrNull()?.let(onStir) }) { Text("确认搅拌") }
            }
            var depth by remember { mutableStateOf("50") }
            OutlinedTextField(value = depth, onValueChange = { depth = it },
                label = { Text("取样深度 cm（规定液位区间 30-80）") },
                modifier = Modifier.fillMaxWidth())

            // —— 样品重量链只能来自指定 BLE 采样秤，不提供手工克重输入 ——
            val grams = scale?.grams
            val scaleReady = grams != null && scale.stable
            Surface(
                color = (if (scaleReady) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.errorContainer)
                    .copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    if (scale == null) {
                        Text("未接入指定 BLE 采样秤：取样重量链将缺失并阻断开卸",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("BLE 采样秤 ${scale.deviceId}：" +
                            (grams?.let { "%.1f g".format(it) } ?: "等待广播…") +
                            if (scaleReady) "（稳定，可取样）" else "（未稳定，读数不进入判定）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
            Button(
                onClick = { onSample(depth.toDoubleOrNull() ?: 0.0) },
                enabled = scaleReady,
            ) { Text("留存卸前个体样（采用秤稳定值）") }
            if (!scaleReady)
                Text("按钮在指定秤稳定读数到达前禁用；非指定设备/手工重量一律不入链",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
            if (!c.preUnloadSample)
                Text("缺卸前个体样：系统不会登记开阀", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium)
        }

        StepCard("⑤ 卸奶边界（与封签/搅拌/样品同为开阀硬前置；系统不开阀）") {
            var tank by remember { mutableStateOf("T-1") }
            OutlinedTextField(value = tank, onValueChange = { tank = it },
                label = { Text("目标罐") }, modifier = Modifier.fillMaxWidth())
            Row {
                Button(onClick = { onSeparate(tank) }) { Text("声明独立卸入罐") }
                Spacer(Modifier.width(8.dp))
                Text(if (c.boundaryDeclared) "本仓边界已声明" else "本仓尚未声明边界",
                    style = MaterialTheme.typography.labelMedium)
            }
            val others = state.compartments.filter { it.code != c.code }.map { it.code }
            val picked = remember { mutableStateListOf<String>() }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                others.forEach { code ->
                    FilterChip(selected = code in picked,
                        onClick = { if (code in picked) picked.remove(code) else picked.add(code) },
                        label = { Text("仓$code") })
                    Spacer(Modifier.width(6.dp))
                }
            }
            Button(onClick = { onComposite(picked.toList(), tank) },
                enabled = picked.isNotEmpty()) { Text("声明多仓混合卸载（含组分身份）") }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row {
                Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error)) { Text("人工开阀") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClose) { Text("关阀完成") }
            }
            Text("系统只记录人工动作，绝不驱动阀门；封签/感官/搅拌/卸前样/卸奶边界" +
                "为同级硬前置，任一缺失即拒绝登记开阀。",
                style = MaterialTheme.typography.labelSmall)
        }

        StepCard("⑥ 异常处置：途中补装 / 主管双签") {
            var farm by remember { mutableStateOf("") }
            var batch by remember { mutableStateOf("") }
            Row {
                OutlinedTextField(value = farm, onValueChange = { farm = it },
                    label = { Text("补装牧场") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(value = batch, onValueChange = { batch = it },
                    label = { Text("补装批次") }, modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = { if (farm.isNotBlank()) onReload(farm, batch) }) {
                Text("登记中途补装（立即挂起）")
            }
            val openBlocking = c.findings.filter { it.code.blocking && it.open }
            if (openBlocking.isNotEmpty()) {
                var reason by remember { mutableStateOf("") }
                OutlinedTextField(value = reason, onValueChange = { reason = it },
                    label = { Text("主管覆核理由（必填，留痕）") },
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    onOverride(reason, openBlocking.map { it.code }.toSet())
                }, enabled = reason.isNotBlank()) { Text("主管双签覆核所选阻断项") }
            }
        }
    }
}

@Composable
private fun FindingSection(c: CompartmentUi) {
    if (c.findings.isEmpty()) {
        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth()) {
            Text("暂无缺陷", Modifier.padding(8.dp))
        }
        return
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            c.findings.sortedByDescending { it.code.severity.ordinal }.forEach { f ->
                Row {
                    SeverityDot(f.code)
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(f.code.name +
                            if (f.open) "" else "（已主管覆核）",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold)
                        Text(f.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun StepCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
