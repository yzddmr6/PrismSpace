package com.yzddmr6.prismspace.prism.service

import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import com.yzddmr6.prismspace.prism.transfer.transferProgressPercent

class CrossSpaceFileTransferPolicyTest {
    @Test fun imageAndDocumentDestinationsAreDeterministic() {
        assertEquals("Pictures/PrismSpace/", CrossSpaceFileTransferPolicy.destination("image/png").relativePath)
        assertEquals("Pictures/PrismSpace", CrossSpaceFileTransferPolicy.destination("image/png").displayLocation)
        assertEquals("Download/PrismSpace/", CrossSpaceFileTransferPolicy.destination("application/pdf").relativePath)
        assertEquals("Download/PrismSpace", CrossSpaceFileTransferPolicy.destination("application/pdf").displayLocation)
        assertFalse(CrossSpaceFileTransferPolicy.destination(null).isImage)
    }

    @Test fun bridgeFailuresCollapseToFourUserRelevantClasses() {
        assertEquals(FileTransferFailureReason.SpaceUnavailable, crossSpaceFailureReason(ProfileBridgeResult.SpaceMissing))
        assertEquals(FileTransferFailureReason.SpaceUnavailable, crossSpaceFailureReason(ProfileBridgeResult.SpaceInactive("quiet")))
        assertEquals(FileTransferFailureReason.BridgeNotReady, crossSpaceFailureReason(
            ProfileBridgeResult.BridgeNotReady(ShuttleNotReadyCause.PermissionDenied)))
        assertEquals(FileTransferFailureReason.BridgeNotReady, crossSpaceFailureReason(ProfileBridgeResult.TimedOut))
        assertEquals(FileTransferFailureReason.TargetWriteFailed, crossSpaceFailureReason(
            ProfileBridgeResult.Failed(IllegalStateException())))
    }

    @Test fun directionWireFormatIsBackwardCompatible() {
        assertNull(TransferDirection.fromWireValue(null))
        assertNull(TransferDirection.fromWireValue(""))
        assertEquals(TransferDirection.ToMain, TransferDirection.fromWireValue("toMain"))
        assertEquals(TransferDirection.ToProfile, TransferDirection.fromWireValue("toProfile"))
    }

    @Test fun blockCopyReportsProgress() {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        var lastProgress = 0L
        val copied = copyCancellable(ByteArrayInputStream(bytes), output, TransferCancellationSignal()) {
            lastProgress = it
            assertEquals(output.size().toLong(), it)
        }
        assertEquals(bytes.size.toLong(), copied)
        assertEquals(copied, lastProgress)
        assertEquals(bytes.toList(), output.toByteArray().toList())
    }

    @Test fun singleCopyOpensSourceExactlyOnce() {
        val opens = AtomicInteger()
        val output = ByteArrayOutputStream()
        var aborts = 0
        val result = transferSingleCopy(
            source = TransferSource.testing(declaredSize = 3L) {
                opens.incrementAndGet()
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            },
            output = output,
            cancellation = TransferCancellationSignal(),
            abort = { aborts++ },
        )

        assertEquals(SingleCopyTransferResult.Written(3L), result)
        assertEquals(1, opens.get())
        assertEquals(0, aborts)
        assertEquals(listOf<Byte>(1, 2, 3), output.toByteArray().toList())
    }

    @Test fun sourceCandidateFallbackStopsAtFirstReadableStream() {
        val attempts = mutableListOf<String>()
        val opened = openFirstReadableCandidate(listOf("plain", "18@provider", "unused")) { candidate ->
            attempts += candidate
            if (candidate == "18@provider") ByteArrayInputStream(byteArrayOf(7)) else null
        }

        assertEquals(listOf("plain", "18@provider"), attempts)
        assertEquals("18@provider", opened.candidate)
        assertEquals(1, opened.index)
        assertEquals(7, opened.stream.use { it.read() })
    }

    @Test fun sourceCandidateFallbackPreservesLastFailureWhenNoneAreReadable() {
        val failure = runCatching {
            openFirstReadableCandidate(listOf("plain", "18@provider")) { candidate ->
                if (candidate == "plain") throw IOException("wrong user")
                null
            }
        }.exceptionOrNull()

        assertTrue(failure is java.io.FileNotFoundException)
        assertEquals("wrong user", failure?.cause?.message)
    }

    @Test fun sourceOpenAndReadFailuresAbortTarget() {
        var openAborts = 0
        val openFailure = transferSingleCopy(
            TransferSource.testing { throw IOException("gone") },
            ByteArrayOutputStream(),
            TransferCancellationSignal(),
            abort = { openAborts++ },
        )
        var readAborts = 0
        val readFailure = transferSingleCopy(
            TransferSource.testing { object : InputStream() {
                override fun read(): Int = throw IOException("revoked")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("revoked")
            } },
            ByteArrayOutputStream(),
            TransferCancellationSignal(),
            abort = { readAborts++ },
        )

        assertEquals(SingleCopyTransferResult.SourceUnreadable, openFailure)
        assertEquals(SingleCopyTransferResult.SourceUnreadable, readFailure)
        assertEquals(1, openAborts)
        assertEquals(1, readAborts)
    }

    @Test fun targetFailureAndCancellationAbortTarget() {
        var writeAborts = 0
        val writeFailure = transferSingleCopy(
            TransferSource.testing { ByteArrayInputStream(byteArrayOf(1)) },
            object : OutputStream() { override fun write(b: Int) = throw IOException("full") },
            TransferCancellationSignal(),
            abort = { writeAborts++ },
        )
        val cancellation = TransferCancellationSignal().also { it.cancel() }
        var cancelAborts = 0
        val cancelled = transferSingleCopy(
            TransferSource.testing { ByteArrayInputStream(byteArrayOf(1)) },
            ByteArrayOutputStream(),
            cancellation,
            abort = { cancelAborts++ },
        )

        assertEquals(SingleCopyTransferResult.TargetWriteFailed, writeFailure)
        assertEquals(SingleCopyTransferResult.Cancelled, cancelled)
        assertEquals(1, writeAborts)
        assertEquals(1, cancelAborts)
    }

    @Test fun unknownSizeDoesNotInventPercentage() {
        assertNull(transferProgressPercent(null, 1_024L))
        assertNull(transferProgressPercent(-1L, 1_024L))
        assertNull(transferProgressPercent(0L, 0L))
        assertNull(transferProgressPercent(0L, 1_024L))
        assertEquals(50, transferProgressPercent(200L, 100L))
    }
}
