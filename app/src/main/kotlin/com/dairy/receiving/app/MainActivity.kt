package com.dairy.receiving.app

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import com.dairy.receiving.app.data.RoomTripStore
import com.dairy.receiving.app.data.local.AppDatabase
import com.dairy.receiving.app.device.ble.BleScaleSource
import com.dairy.receiving.app.device.nfc.AndroidNfcReader
import com.dairy.receiving.app.ui.screens.CompartmentScreen
import com.dairy.receiving.app.ui.screens.TripOverviewScreen
import com.dairy.receiving.app.ui.theme.ReceivingTheme
import com.dairy.receiving.app.workflow.ReceiveViewModel
import com.dairy.receiving.core.model.DEFAULT_SCALE_DEVICE_ID

class MainActivity : ComponentActivity() {

    companion object {
        /** 收奶区指定采样秤配对 MAC（设备台账占位；生产环境由配置/台账下发）。 */
        const val PAIRED_SCALE_MAC = "00:1A:7D:DA:71:13"
    }

    private lateinit var vm: ReceiveViewModel
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = RoomTripStore(AppDatabase.get(applicationContext))
        // 指定 BLE 采样秤：MAC 与设备 ID 配对登记，只有该设备的稳定读数能形成重量链。
        // 实际 MAC 由收奶区设备台账下发；此处为收奶区秤的占位地址。
        val scaleSource = BleScaleSource(
            context = applicationContext,
            deviceMac = PAIRED_SCALE_MAC,
            scaleDeviceId = DEFAULT_SCALE_DEVICE_ID,
        )
        vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ReceiveViewModel(store, scaleSource = scaleSource) as T
        })[ReceiveViewModel::class.java]

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            ReceivingTheme {
                val state by vm.ui.collectAsState()
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(state.toast) {
                    state.toast?.let { snackbar.showSnackbar(it); vm.consumeToast() }
                }
                var tab by remember { mutableIntStateOf(0) }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                                icon = { Text("车") }, label = { Text("本车") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                                icon = { Text("仓") }, label = { Text("逐仓作业") })
                            NavigationBarItem(selected = tab == 2, onClick = { tab = 2; vm.buildReport() },
                                icon = { Text("报") }, label = { Text("报告/留痕") })
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when (tab) {
                            0 -> TripOverviewScreen(state,
                                onSelect = { vm.select(it); tab = 1 },
                                onPickDemo = vm::loadDemo)
                            1 -> CompartmentScreen(state,
                                onManualSeal = vm::manualSeal,
                                onProbe = vm::probeReading,
                                onTruckLogGap = vm::attachTruckLog,
                                onSensory = vm::sensory,
                                onStir = vm::stirring,
                                onSample = { depth -> vm.takeIndividualSample(depth) },
                                onReload = vm::reportReload,
                                onComposite = vm::declareComposite,
                                onSeparate = vm::declareSeparate,
                                onConnectPipeline = vm::connectPipeline,
                                onSwapHose = vm::swapHose,
                                onOpen = vm::humanOpenValve,
                                onClose = { complete -> vm.humanCloseValve(complete) },
                                onOverride = vm::supervisorOverride)
                            2 -> ReportTab(state)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pi, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
            val tag = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
            else @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.nfc.Tag>(NfcAdapter.EXTRA_TAG)
            tag ?: return
            vm.onNfcScan(AndroidNfcReader.read(tag))
        }
    }
}

@Composable
private fun ReportTab(state: com.dairy.receiving.app.workflow.TripUiState) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("总建议：${state.overall}", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("近期审计链：", style = MaterialTheme.typography.labelLarge)
        state.auditTail.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        if (state.report.isNotBlank()) {
            Text(state.report, style = MaterialTheme.typography.bodySmall)
        }
    }
}
