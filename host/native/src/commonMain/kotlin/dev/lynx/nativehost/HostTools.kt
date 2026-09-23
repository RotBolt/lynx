package dev.lynx.nativehost

enum class HostTool { ADB, XCRUN, SIMCTL, SQLITE3, OPENSSL }

enum class ToolStatus { AVAILABLE, MISSING, UNUSABLE, NOT_APPLICABLE }

data class ResolvedTool(
    val tool: HostTool,
    val status: ToolStatus,
    val path: String?,
    val version: String?,
    val reason: String?,
)

fun interface HostToolResolver {
    fun resolve(tool: HostTool): ResolvedTool
}

/** Candidate ordering is host-specific; result handling stays shared and testable. */
data class ToolCandidate(val path: String, val source: String, val explicit: Boolean = false)

data class ToolProbe(val status: ToolStatus, val version: String? = null, val reason: String? = null) {
    companion object {
        fun available(version: String?) = ToolProbe(ToolStatus.AVAILABLE, version)
        fun missing(reason: String = "not found") = ToolProbe(ToolStatus.MISSING, reason = reason)
        fun unusable(reason: String) = ToolProbe(ToolStatus.UNUSABLE, reason = reason)
    }
}

class CandidateHostToolResolver(
    private val candidates: (HostTool) -> List<ToolCandidate>,
    private val probe: (String, HostTool) -> ToolProbe,
) : HostToolResolver {
    override fun resolve(tool: HostTool): ResolvedTool {
        val attempted = mutableListOf<String>()
        for (candidate in candidates(tool)) {
            val result = probe(candidate.path, tool)
            if (result.status == ToolStatus.AVAILABLE) {
                return ResolvedTool(tool, ToolStatus.AVAILABLE, candidate.path, result.version, null)
            }
            if (candidate.explicit) {
                return ResolvedTool(tool, ToolStatus.UNUSABLE, null, null, "${candidate.source}=${candidate.path}: ${result.reason ?: "unusable"}")
            }
            attempted += "${candidate.source}: ${result.reason ?: result.status.name.lowercase()}"
        }
        val status = if (attempted.any { it.contains("not executable") }) ToolStatus.MISSING else ToolStatus.MISSING
        return ResolvedTool(tool, status, null, null, attempted.joinToString("; ").ifBlank { "no candidates configured" })
    }
}
