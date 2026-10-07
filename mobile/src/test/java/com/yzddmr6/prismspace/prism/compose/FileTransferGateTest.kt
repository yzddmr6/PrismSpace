package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.vm.GateAction
import com.yzddmr6.prismspace.prism.compose.vm.fileTransferGate
import com.yzddmr6.prismspace.prism.compose.vm.testZhResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferGateTest {

    @Test fun usableSpaceEnablesSending() {
        val gate = fileTransferGate(SpaceUsability.Usable, testZhResolver)

        assertTrue(gate.enabled)
        assertNull(gate.guidance)
    }

    @Test fun everyUnusableStateDisablesSendingWithGuidance() {
        listOf(
            SpaceUsability.Suspended,
            SpaceUsability.LockedNeedsUnlock,
            SpaceUsability.BridgeNotReady,
            SpaceUsability.NotProvisioned,
            SpaceUsability.Unknown,
        ).forEach { state ->
            val gate = fileTransferGate(state, testZhResolver)
            assertFalse("$state must disable sending", gate.enabled)
            assertTrue("$state must carry guidance", !gate.guidance.isNullOrBlank())
        }
    }

    @Test fun openGateHasItsOwnCopyForEveryState() {
        val states = listOf(
            SpaceUsability.Suspended,
            SpaceUsability.LockedNeedsUnlock,
            SpaceUsability.BridgeNotReady,
            SpaceUsability.NotProvisioned,
            SpaceUsability.Unknown,
        )
        val openCopy = states.map { state ->
            val open = fileTransferGate(state, testZhResolver, GateAction.Open)
            val send = fileTransferGate(state, testZhResolver, GateAction.Send)
            assertFalse("$state must block opening", open.enabled)
            assertNotEquals("$state open copy must differ from send copy", send.guidance, open.guidance)
            assertTrue("$state open copy names the open action", open.guidance!!.contains("打开文件"))
            open.guidance
        }
        assertEquals("open copy must be state specific", states.size, openCopy.toSet().size)
        assertTrue(fileTransferGate(SpaceUsability.Usable, testZhResolver, GateAction.Open).enabled)
        assertEquals(
            "请先在设置中恢复双开空间，然后再打开文件",
            fileTransferGate(SpaceUsability.Suspended, testZhResolver, GateAction.Open).guidance,
        )
    }

    @Test fun guidanceCopyIsStateSpecificAndActionSpecific() {
        assertEquals(
            "请先在设置中恢复双开空间，然后再发送文件",
            fileTransferGate(SpaceUsability.Suspended, testZhResolver).guidance,
        )
        assertEquals(
            "请先在设置中修复双开空间连接，然后再发送文件",
            fileTransferGate(SpaceUsability.BridgeNotReady, testZhResolver).guidance,
        )
    }
}
