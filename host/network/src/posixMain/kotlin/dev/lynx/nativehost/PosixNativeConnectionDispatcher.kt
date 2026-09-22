package dev.lynx.nativehost

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/** Keeps a long-lived HTTP/2 or WebSocket connection from blocking the proxy accept loop. */
@OptIn(ObsoleteWorkersApi::class)
internal class PosixNativeConnectionDispatcher {
    fun dispatch(work: () -> Unit) {
        val worker = Worker.start(name = "lynx-network-client")
        worker.execute(TransferMode.UNSAFE, { work }) { task -> task() }
        worker.requestTermination()
    }
}
