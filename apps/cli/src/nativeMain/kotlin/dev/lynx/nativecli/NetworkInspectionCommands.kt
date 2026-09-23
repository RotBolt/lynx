package dev.lynx.nativecli

import dev.lynx.model.NetworkCaptureSettings
import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkFilter

/** Public native network command grammar. Internal worker verbs stay in Main. */
internal object NetworkInspectionCommands {
    fun parse(args: List<String>): NetworkCommand = when (args.firstOrNull()) {
        "start" -> NetworkCommand.Start(
            NetworkCaptureSettings(
                listenHost = option(args, "--host") ?: "0.0.0.0",
                listenPort = option(args, "--port")?.toIntOrNull() ?: 0,
            ),
        )
        "stop" -> NetworkCommand.Stop
        "snapshot" -> NetworkCommand.Snapshot
        "list" -> {
            val sessionId = option(args, "--session")
            val filter = filter(args)
            if (sessionId == null) {
                if (hasFilter(args)) error("SESSION_REQUIRED")
                NetworkCommand.Sessions
            } else NetworkCommand.SessionList(sessionId, filter)
        }
        "get" -> NetworkCommand.Get(dev.lynx.model.RequestId(args.getOrNull(1) ?: error("request id is required")))
        "doctor" -> NetworkCommand.Doctor
        else -> error("Usage: lynx network [start|stop|snapshot|list|get|doctor]")
    }

    private fun filter(args: List<String>) = NetworkFilter(
        method = option(args, "--method"),
        status = option(args, "--status")?.toIntOrNull(),
        urlSubstring = option(args, "--url"),
        sinceEpochMillis = option(args, "--since")?.toLongOrNull(),
        limit = option(args, "--limit")?.toIntOrNull(),
    )

    private fun hasFilter(args: List<String>) = listOf("--method", "--status", "--url", "--since", "--limit").any { option(args, it) != null }

    private fun option(args: List<String>, name: String): String? {
        args.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf(String::isNotEmpty)?.let { return it }
        return args.windowed(2, 1).firstOrNull { it[0] == name }?.getOrNull(1)?.takeUnless { it.startsWith("--") }
    }
}
