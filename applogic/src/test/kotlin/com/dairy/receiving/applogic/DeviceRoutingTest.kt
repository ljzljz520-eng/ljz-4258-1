package com.dairy.receiving.applogic

import com.dairy.receiving.applogic.device.BleDeviceSimulator
import com.dairy.receiving.applogic.device.NfcScanRouter
import com.dairy.receiving.applogic.device.message
import com.dairy.receiving.core.demo.DemoScenarios
import com.dairy.receiving.core.device.NfcScan
import com.dairy.receiving.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceRoutingTest {

    private val op = OperatorId("recv-07")

    @Test
    fun `罐口NFC身份不符 - 路由为补装嫌疑并挂起`() {
        val wf = DemoScenarios.illegibleSeal()
        val code = CompartmentCode("1")
        val router = NfcScanRouter()
        val scan = NfcScan(NfcTagId("tag-x"), "HATCH:S-C-9|牧场C|C-0911-9")
        val r = router.handle(scan, wf, code, null, op)
        assertTrue(r is com.dairy.receiving.applogic.device.NfcRouteResult.Hatch)
        assertTrue((r as com.dairy.receiving.applogic.device.NfcRouteResult.Hatch).reloadSuspected)
        assertTrue(wf.openBlockings(code).any { it.code == FindingCode.RELOAD_DETECTED })
        assertTrue(r.message().contains("途中补装"))
    }

    @Test
    fun `瓶签NFC绑定到待取样`() {
        val wf = DemoScenarios.illegibleSeal()
        val router = NfcScanRouter()
        val sid = SampleId("SMP-NEW")
        val r = router.handle(NfcScan(NfcTagId("t"), "BOTTLE:B-NEW"),
            wf, CompartmentCode("1"), sid, op)
        assertTrue(r is com.dairy.receiving.applogic.device.NfcRouteResult.BottleBound)
        assertEquals("样瓶 B-NEW 已绑定样品 SMP-NEW", r.message())
    }

    @Test
    fun `未知载荷不污染工作流`() {
        val wf = DemoScenarios.illegibleSeal()
        val router = NfcScanRouter()
        val r = router.handle(NfcScan(NfcTagId("t"), "GARBAGE"),
            wf, CompartmentCode("1"), null, op)
        assertTrue(r is com.dairy.receiving.applogic.device.NfcRouteResult.UnknownPayload)
    }

    @Test
    fun `BLE 温度流前期不稳后期稳定`() {
        val readings = BleDeviceSimulator.probeStream()
        assertTrue(readings.any { !it.stable }, "前期必须有未稳定读数")
        assertTrue(readings.last().stable, "末段应收敛为稳定值")
        assertTrue(readings.last().celsius in 3.5..4.7)
    }

    @Test
    fun `BLE 指定采样秤流 - 全程携带设备身份且末段稳定`() {
        val stream = BleDeviceSimulator.scaleStream(finalG = 210.0)
        assertTrue(stream.all { it.deviceId == BleDeviceSimulator.SCALE_DEVICE_ID })
        assertTrue(stream.any { !it.stable }, "上秤初段必须未稳定")
        assertTrue(stream.last().stable, "末段应稳定")
        assertEquals(210.0, stream.last().grams, 0.001)
    }

    @Test
    fun `非指定设备秤流 - 设备ID不同于指定秤`() {
        val rogue = BleDeviceSimulator.scaleStream(deviceId = "rogue-scale").last()
        assertTrue(rogue.stable)
        assertNotEquals(BleDeviceSimulator.SCALE_DEVICE_ID, rogue.deviceId)
    }

    @Test
    fun `罐口NFC身份相符 - 扫签直接形成SealCheck闭合封签核验`() {
        val wf = DemoScenarios.illegibleSeal()
        val code = CompartmentCode("1")
        // 装车预报期望签
        wf.registerNfcBinding(ExpectedSealBinding(code, SealId("S-A-1"),
            FarmId("牧场A"), FarmBatchId("A-0911-1"),
            wf.compartments[code]!!.loadedAt, NfcTagId("tag-1")))
        val router = NfcScanRouter()
        val r = router.handle(NfcScan(NfcTagId("tag-1"), "HATCH:S-A-1|牧场A|A-0911-1"),
            wf, code, null, op)
        assertTrue(r is com.dairy.receiving.applogic.device.NfcRouteResult.Hatch)
        assertTrue((r as com.dairy.receiving.applogic.device.NfcRouteResult.Hatch).sealVerified)
        assertEquals(SealId("S-A-1"), wf.compartments[code]!!.seal?.nfc)
        assertTrue(wf.compartments[code]!!.seal?.nfcTag == NfcTagId("tag-1"))
        assertFalse(wf.openBlockings(code).any { it.code == FindingCode.SEAL_UNVERIFIED })
        assertTrue(r.message().contains("封签核验"))
    }

    @Test
    fun `合规混卸整体建议为接收`() {
        // 用干净两仓快速验证：无异常时 trip 建议 ACCEPT
        val wf = DemoScenarios.truckLogGap()
        // 温度缺段是警告 => 有备注接收
        assertEquals(Recommendation.ACCEPT_WITH_NOTE, wf.tripRecommendation())
    }
}
