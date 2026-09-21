package dev.lynx.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class NetworkCaptureSettings(
    val listenHost: String = "0.0.0.0",
    val listenPort: Int = 0,
    /** Zero retains complete bodies for the session. */
    val maxBodyBytes: Long = 0,
    val captureBodies: Boolean = true,
) {
    init {
        require(listenPort in 0..65_535)
        require(maxBodyBytes >= 0)
    }
}

@Serializable
data class NetworkFilter(
    val method: String? = null,
    val status: Int? = null,
    val urlSubstring: String? = null,
    val sinceEpochMillis: Long? = null,
    val limit: Int? = null,
) {
    init {
        require(status == null || status in 100..599)
        require(sinceEpochMillis == null || sinceEpochMillis >= 0)
        require(limit == null || limit > 0)
    }
}

@Serializable
data class NetworkCapabilities(
    val source: EvidenceSource = EvidenceSource.NETWORK,
    val httpsMitm: Boolean,
    val maxBodyBytes: Long = 0,
    val limitations: List<String> = emptyList(),
    val supportedProtocols: List<String> = emptyList(),
    val proxyEndpoint: String? = null,
    val proxyStatus: String? = null,
    val bypassLikely: Boolean? = null,
    val caFingerprint: String? = null,
    val caCertificatePath: String? = null,
    val caTrustStatus: String? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("command")
sealed interface NetworkCommand {
    @Serializable
    @SerialName("start")
    data class Start(val settings: NetworkCaptureSettings = NetworkCaptureSettings()) : NetworkCommand

    @Serializable
    @SerialName("stop")
    data object Stop : NetworkCommand

    @Serializable
    @SerialName("list")
    data class List(val filter: NetworkFilter = NetworkFilter()) : NetworkCommand

    @Serializable
    @SerialName("get")
    data class Get(val requestId: RequestId) : NetworkCommand

    @Serializable
    @SerialName("doctor")
    data object Doctor : NetworkCommand
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface NetworkCommandResult {
    @Serializable
    @SerialName("network_started")
    data class Started(
        val endpoint: String,
        val capabilities: NetworkCapabilities,
    ) : NetworkCommandResult

    @Serializable
    @SerialName("network_stopped")
    data object Stopped : NetworkCommandResult

    @Serializable
    @SerialName("network_list")
    data class Exchanges(val exchanges: List<NetworkExchange>) : NetworkCommandResult

    @Serializable
    @SerialName("network_get")
    data class Exchange(val exchange: NetworkExchange) : NetworkCommandResult

    @Serializable
    @SerialName("network_doctor")
    data class Diagnostics(val capabilities: NetworkCapabilities) : NetworkCommandResult
}
