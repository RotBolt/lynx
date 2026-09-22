package dev.lynx.nativehost

import dev.lynx.model.CaptureSession
import dev.lynx.model.CaptureState
import dev.lynx.model.CaptureTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCaptureCoordinatorTest {
    @Test
    fun staleRunningCaptureCanBeInterruptedBeforeNewLease() {
        val repository = PosixCaptureRepository("/tmp/lynx-coordinator-${kotlin.time.Clock.System.now().toEpochMilliseconds()}")
        var number = 0
        val coordinator = NativeCaptureCoordinator(repository, idProvider = { "capture-${++number}" })
        val target = CaptureTarget("android", "emulator-5554", "dev.lynx.dummyapp")
        val stale = coordinator.start("attach-old", target)

        coordinator.interrupt(stale.id)
        val replacement = coordinator.start("attach-new", target)

        assertEquals(CaptureState.INTERRUPTED, repository.session(stale.id)?.state)
        assertEquals("capture-2", replacement.id)
    }
    @Test
    fun repeatedStartForSameTargetReturnsOriginalCapture() {
        val repository = PosixCaptureRepository("/tmp/lynx-coordinator-${kotlin.time.Clock.System.now().toEpochMilliseconds()}")
        val coordinator = NativeCaptureCoordinator(repository, idProvider = { "capture-fixed" })
        val target = CaptureTarget("android", "emulator-5554", "dev.lynx.dummyapp")

        val first = coordinator.start("attach-1", target)
        val second = coordinator.start("attach-1", target)

        assertEquals(first.id, second.id)
    }

    @Test
    fun competingTargetIsRejectedWithoutReplacingActiveCapture() {
        val repository = PosixCaptureRepository("/tmp/lynx-coordinator-${kotlin.time.Clock.System.now().toEpochMilliseconds()}")
        val coordinator = NativeCaptureCoordinator(repository, idProvider = { "capture-fixed" })
        coordinator.start("attach-1", CaptureTarget("android", "emulator-5554", "dev.lynx.dummyapp"))

        val error = assertFailsWith<IllegalStateException> {
            coordinator.start("attach-2", CaptureTarget("ios", "udid", "dev.lynx.dummyapp"))
        }
        assertEquals("CAPTURE_ACTIVE", error.message)
    }
}
