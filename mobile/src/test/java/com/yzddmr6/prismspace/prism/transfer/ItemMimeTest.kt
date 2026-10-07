package com.yzddmr6.prismspace.prism.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemMimeTest {

    @Test fun perUriTypeBeatsTheBatchType() {
        assertEquals("application/pdf", resolveItemMime("application/pdf", "image/*"))
        assertEquals("image/png", resolveItemMime("image/png", "image/jpeg"))
    }

    @Test fun concreteBatchTypeFillsInOnlyWhenTheItemHasNone() {
        assertEquals("image/jpeg", resolveItemMime(null, "image/jpeg"))
        assertEquals("image/jpeg", resolveItemMime("*/*", "image/jpeg"))
    }

    @Test fun wildcardsNeverBecomeTheItemType() {
        assertEquals("application/octet-stream", resolveItemMime(null, "image/*"))
        assertEquals("application/octet-stream", resolveItemMime(null, "*/*"))
        assertEquals("application/octet-stream", resolveItemMime(null, null))
        assertEquals("application/octet-stream", resolveItemMime("", ""))
    }
}
