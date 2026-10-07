package com.yzddmr6.prismspace.prism.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTransferPolicyTest {

    @Test fun normalizesBlankDisplayName() {
        assertEquals("prismspace-import.bin", FileTransferPolicy.safeDisplayName(""))
    }

    @Test fun removesPathSeparatorsFromDisplayName() {
        assertEquals("secret.txt", FileTransferPolicy.safeDisplayName("../secret.txt"))
    }
}
