package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment
import com.yzddmr6.prismspace.prism.compose.vm.SpaceUiState
import com.yzddmr6.prismspace.prism.compose.vm.mainAppIsCloned
import com.yzddmr6.prismspace.prism.compose.space.PrismSpace
import com.yzddmr6.prismspace.prism.compose.space.PrismSpaceKind
import com.yzddmr6.prismspace.prism.compose.space.resolveSpaceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpaceUiStateTest {

    private val main = PrismSpace("main", 0, PrismSpaceKind.Main, "主空间")
    private val dual = PrismSpace("space_22", 22, PrismSpaceKind.Dual, "双开空间")

    @Test
    fun `main and dual spaces default to user apps like the approved demo`() {
        val state = SpaceUiState()

        assertFalse(state.showSystem)
        assertFalse(state.showSystemDual)
    }

    @Test
    fun `dual segment falls back to main when no dual space exists`() {
        val selection = resolveSpaceSelection(SpaceSegment.Dual, "space_22", listOf(main))

        assertEquals(SpaceSegment.Main, selection.segment)
        assertEquals(null, selection.selectedDualSpaceId)
    }

    @Test
    fun `dual segment remains selected when its space exists`() {
        val selection = resolveSpaceSelection(SpaceSegment.Dual, "space_22", listOf(main, dual))

        assertEquals(SpaceSegment.Dual, selection.segment)
        assertEquals("space_22", selection.selectedDualSpaceId)
    }

    @Test
    fun `main segment preserves a valid dual selection for later`() {
        val selection = resolveSpaceSelection(SpaceSegment.Main, "space_22", listOf(main, dual))

        assertEquals(SpaceSegment.Main, selection.segment)
        assertEquals("space_22", selection.selectedDualSpaceId)
    }

    @Test
    fun `stale dual selection resolves to the first real dual space`() {
        val selection = resolveSpaceSelection(SpaceSegment.Dual, "space_99", listOf(main, dual))

        assertEquals(SpaceSegment.Dual, selection.segment)
        assertEquals("space_22", selection.selectedDualSpaceId)
    }

    @Test
    fun `system package presence alone is not a clone`() {
        assertEquals(false, mainAppIsCloned(isSystem = true, installedInDual = true, policyEnabledInDual = false))
    }

    @Test
    fun `system package the dual policy enables by choice is cloned`() {
        assertEquals(true, mainAppIsCloned(isSystem = true, installedInDual = true, policyEnabledInDual = true))
    }

    @Test
    fun `policy enabled but not installed in dual is not cloned`() {
        assertEquals(false, mainAppIsCloned(isSystem = true, installedInDual = false, policyEnabledInDual = true))
    }

    @Test
    fun `third party clone follows dual installation fact`() {
        assertEquals(true, mainAppIsCloned(isSystem = false, installedInDual = true, policyEnabledInDual = false))
        assertEquals(false, mainAppIsCloned(isSystem = false, installedInDual = false, policyEnabledInDual = true))
    }
}
