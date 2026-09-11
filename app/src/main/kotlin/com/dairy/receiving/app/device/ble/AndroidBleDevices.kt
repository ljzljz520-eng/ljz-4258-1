package com.dairy.receiving.app.device.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.dairy.receiving.core.device.StableReadingAccumulator
import com.dairy.receiving.core.device.StableScaleAccumulator
import com.dairy.receiving.core.model.DEFAULT_SCALE_DEVICE_ID
import com.dairy.receiving.core.model.ProbeReading
import com.dairy.receiving.core.model.WeightReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE 设备接入。两类设备各自按“设备 MAC + 设备 ID”配对过滤：
 *  - [BleProbeSource]：探针温度计，稳定读数进温度判定；
 *  - [BleScaleSource]：指定采样秤，**样品重量链的唯一合法来源**——
 *    只有来自配对 MAC、携带指定设备 ID 的稳定 [WeightReading] 才会被样品链接受
 *    （core 的 SamplingPolicy 再做一次设备身份校验，适配层失守也不影响规则）。
 * 稳定判定统一复用 core 的 StableXxxAccumulator，保证设备与规则同口径。
 */

/** 采样秤读数源（真实 BLE 或演练模拟），暴露其设备身份以便 UI/规则核对。 */
interface ScaleReadingSource {
    val scaleDeviceId: String
    fun readings(): Flow<WeightReading>
}

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

    /** 占位解码：厂商协议通常为 2/4 字节定点数（℃，0.01℃ 分辨率）。 */
    private fun decodeTemperature(bytes: ByteArray): Double {
        if (bytes.size < 2) return Double.NaN
        val raw = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        return raw / 100.0
    }
}

/**
 * 指定 BLE 采样秤。[deviceMac] 与 [deviceId] 必须同时匹配收奶区配对登记：
 * 扫描过滤器只接受该 MAC，解析出的读数强制标记 [deviceId]（不接受广播自报身份）。
 */
@SuppressLint("MissingPermission")
class BleScaleSource(
    context: Context,
    private val deviceMac: String,
    override val scaleDeviceId: String = DEFAULT_SCALE_DEVICE_ID,
    private val epsilon: Double = 1.0,
) : ScaleReadingSource {

    private val manager = context.getSystemService(BluetoothManager::class.java)

    override fun readings(): Flow<WeightReading> = callbackFlow {
        val acc = StableScaleAccumulator(window = 5, epsilon = epsilon, deviceId = scaleDeviceId)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // 双保险：过滤器之外再校验一次 MAC，非配对设备一克都不进重量链
                if (result.device.address != deviceMac) return
                val grams = result.scanRecord?.serviceData?.values?.firstOrNull()
                    ?.let { decodeGrams(it) } ?: return
                runCatching { trySend(acc.accept(grams)) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE 采样秤扫描失败 errorCode=$errorCode"))
            }
        }
        val scanner = manager.adapter.bluetoothLeScanner
        val filters = listOf(ScanFilter.Builder().setDeviceAddress(deviceMac).build())
        scanner?.startScan(filters, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner?.stopScan(callback) } }
    }

    /** 占位解码：厂商协议 2 字节无符号定点数（0.1g 分辨率）。 */
    private fun decodeGrams(bytes: ByteArray): Double {
        if (bytes.size < 2) return Double.NaN
        val raw = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        return raw / 10.0
    }
}
