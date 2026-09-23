package dev.lynx.nativehost

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/** Keeps a long-lived HTTP/2 or WebSocket connection from blocking the proxy accept loop. */
@OptIn(ObsoleteWorkersApi::class)
internal class PosixNativeConnectionDispatcher {
    fun dispatch(work: () -> Unit) {
        val worker = Worker.start(name = "lynx-network-client")
        // A native worker exception aborts the detached proxy process. The connection handler
        // already materializes protocol failures, but keep the dispatcher boundary defensive so
        // malformed peer bytes or platform interop failures cannot kill future captures.
        worker.execute(TransferMode.UNSAFE, { work }) { task ->
            try { task() } catch (_: Throwable) { /* isolate one connection from the proxy */ }
        }
        worker.requestTermination()
    }
}
