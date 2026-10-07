package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.engine.CrossProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferForwardSpecTest {

    private val file = TransferOpenRequest("t1", OpenMode.File, "content://media/external/images/media/7", "image/png", "Pictures/PrismSpace")

    @Test fun mainSpaceRequestCarriesManagedProfileCategory() {
        val spec = TransferOpenActivity.forwardSpec(file, fromParent = true)
        assertEquals(CrossProfile.ACTION_TRANSFER_OPEN, spec.action)
        assertEquals(CrossProfile.CATEGORY_MANAGED_PROFILE, spec.category)
    }

    @Test fun dualSpaceRequestCarriesParentProfileCategory() {
        val spec = TransferOpenActivity.forwardSpec(file, fromParent = false)
        assertEquals(CrossProfile.ACTION_TRANSFER_OPEN, spec.action)
        assertEquals(CrossProfile.CATEGORY_PARENT_PROFILE, spec.category)
    }

    @Test fun onlyPointersTravelAsExtras() {
        val spec = TransferOpenActivity.forwardSpec(file, fromParent = true)
        assertEquals(
            mapOf(
                TransferOpenActivity.EXTRA_RECORD_ID to "t1",
                TransferOpenActivity.EXTRA_MODE to "File",
                TransferOpenActivity.EXTRA_URI to "content://media/external/images/media/7",
            ),
            spec.stringExtras,
        )
        assertTrue(spec.listExtras.isEmpty())
        assertFalse(spec.category == "android.intent.category.DEFAULT")
    }

    @Test fun folderWithoutUriOmitsTheUriExtra() {
        val spec = TransferOpenActivity.forwardSpec(TransferOpenRequest("old", OpenMode.Folder, null, null, null), fromParent = false)
        assertEquals(setOf(TransferOpenActivity.EXTRA_RECORD_ID, TransferOpenActivity.EXTRA_MODE), spec.stringExtras.keys)
    }

    @Test fun shareCarriesParallelUriAndMimeLists() {
        val share = TransferOpenRequest("handoff", OpenMode.Share, null, null, null, listOf(
            ShareItem("content://a", "image/png"),
            ShareItem("content://b", null),
        ))
        val spec = TransferOpenActivity.forwardSpec(share, fromParent = true)
        assertEquals(listOf("content://a", "content://b"), spec.listExtras[TransferOpenActivity.EXTRA_SHARE_URIS])
        assertEquals(listOf("image/png", ""), spec.listExtras[TransferOpenActivity.EXTRA_SHARE_MIMES])
        assertEquals("Share", spec.stringExtras[TransferOpenActivity.EXTRA_MODE])
        // What the receiver reads back is exactly what was sent.
        assertEquals(share.shareItems, TransferOpenPlanner.shareItemsOf(
            spec.listExtras[TransferOpenActivity.EXTRA_SHARE_URIS], spec.listExtras[TransferOpenActivity.EXTRA_SHARE_MIMES]))
    }
}
