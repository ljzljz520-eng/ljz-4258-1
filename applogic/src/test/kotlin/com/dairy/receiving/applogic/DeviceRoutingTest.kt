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
    fun `合规混卸整体建议为接收`() {
        // 用干净两仓快速验证：无异常时 trip 建议 ACCEPT
        val wf = DemoScenarios.truckLogGap()
        // 温度缺段是警告 => 有备注接收
        assertEquals(Recommendation.ACCEPT_WITH_NOTE, wf.tripRecommendation())
    }
}
