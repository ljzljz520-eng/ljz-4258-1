package com.dairy.receiving.app.device.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.dairy.receiving.core.device.StableReadingAccumulator
import com.dairy.receiving.core.model.ProbeReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE 温度计/采样秤接入。ScanResult 中 service data 解析为数值（厂商协议占位），
 * 稳定判定复用 core 的 StableReadingAccumulator，保证设备与规则同口径。
 * 未稳定的读数会发出（供 UI 显示），但 stable=false 不进入判定。
 */
@SuppressLint("MissingPermission")
class BleProbeSource(
    context: Context,
    private val deviceMac: String,
    private val deviceId: String,
    private val epsilon: Double = 0.3,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    fun readings(): Flow<ProbeReading> = callbackFlow {
        val acc = StableReadingAccumulator(window = 5, epsilon = epsilon, deviceId = deviceId)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address != deviceMac) return
                val value = result.scanRecord?.serviceData?.values?.firstOrNull()
                    ?.let { decodeTemperature(it) } ?: return
                acc.accept(value)?.let { runCatching { trySend(it) } }
            }
        }
        val scanner = manager.adapter.bluetoothLeScanner
        scanner?.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner?.stopScan(callback) } }
    }

    /** 占位解码：厂商协议通常为 2/4 字节定点数。 */
    private fun decodeTemperature(bytes: ByteArray): Double {
        if (bytes.size < 2) return Double.NaN
        val raw = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        return raw / 100.0
    }
}
