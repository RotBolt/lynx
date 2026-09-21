package dev.lynx.network

import dev.lynx.adb.CommandRunner
import dev.lynx.adb.ProcessCommandRunner
import java.nio.file.Path
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID

data class CertificateInstallResult(
    val status: String,
    val target: String,
    val certificatePath: String,
    val message: String,
)

/** Stages a CA on Android and opens the platform installer; user approval remains explicit. */
class AndroidCertificateInstaller(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val adbExecutable: String = dev.lynx.adb.AdbExecutableResolver.resolve(),
) {
    fun install(deviceSerial: String, certificatePath: Path): CertificateInstallResult {
        val remote = "/sdcard/Download/lynx-ca.crt"
        val push = runner.run(listOf(adbExecutable, "-s", deviceSerial, "push", certificatePath.toString(), remote))
        if (push.exitCode != 0) return CertificateInstallResult("failed", deviceSerial, certificatePath.toString(), push.stderr.ifBlank { "Unable to stage certificate" })
        // Android 14+ no longer resolves the implicit INSTALL action from an
        // adb shell. Target the platform certificate installer explicitly and
        // mark this as a user CA so the Settings flow can complete it.
        val launch = runner.run(
            listOf(
                adbExecutable, "-s", deviceSerial, "shell", "am", "start",
                "-n", "com.android.certinstaller/.CertInstallerMain",
                "-a", "android.credentials.INSTALL",
                "-t", "application/x-x509-ca-cert",
                "-d", "file://$remote",
                // This is a CA-only PEM. Android distinguishes the CA
                // installer path from the user/client-credential path.
                "--es", "certificate_install_usage", "ca",
            ),
        )
        return if (launch.exitCode == 0) {
            CertificateInstallResult("awaiting_user_confirmation", deviceSerial, certificatePath.toString(), "Approve the CA in Android Settings, then enable it for the debug app.")
        } else CertificateInstallResult("staged", deviceSerial, certificatePath.toString(), "Certificate staged at $remote; open Settings > Security > Install a certificate.")
    }
}

/** Uses Apple's supported simulator keychain command; physical devices require a profile and user approval. */
class AppleCertificateInstaller(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val xcrunExecutable: String = "xcrun",
) {
    fun installSimulator(udid: String, certificatePath: Path): CertificateInstallResult {
        val result = runner.run(listOf(xcrunExecutable, "simctl", "keychain", udid, "add-root-cert", certificatePath.toString()))
        return if (result.exitCode == 0) CertificateInstallResult("awaiting_user_confirmation", udid, certificatePath.toString(), "Certificate added to the simulator keychain; enable it in Certificate Trust Settings if prompted.")
        else CertificateInstallResult("failed", udid, certificatePath.toString(), result.stderr.ifBlank { "Unable to add simulator root certificate" })
    }

    fun physicalDeviceInstructions(certificatePath: Path): CertificateInstallResult {
        val profilePath = certificatePath.resolveSibling("lynx-ca.mobileconfig")
        return runCatching {
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(certificatePath))
            val encoded = Base64.getEncoder().encodeToString(certificate.encoded)
            val uuid = UUID.randomUUID().toString().uppercase()
            val profile = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>PayloadContent</key><array><dict>
<key>PayloadCertificateFileName</key><string>Lynx Local CA.cer</string>
<key>PayloadContent</key><data>$encoded</data>
<key>PayloadDisplayName</key><string>Lynx Local CA</string>
<key>PayloadIdentifier</key><string>dev.lynx.ca.$uuid</string>
<key>PayloadOrganization</key><string>Lynx</string>
<key>PayloadType</key><string>com.apple.security.root</string>
<key>PayloadUUID</key><string>$uuid</string>
<key>PayloadVersion</key><integer>1</integer>
</dict></array>
<key>PayloadDisplayName</key><string>Lynx Local CA</string>
<key>PayloadIdentifier</key><string>dev.lynx.profile.$uuid</string>
<key>PayloadOrganization</key><string>Lynx</string>
<key>PayloadRemovalDisallowed</key><false/>
<key>PayloadType</key><string>Configuration</string>
<key>PayloadUUID</key><string>$uuid</string>
<key>PayloadVersion</key><integer>1</integer>
</dict></plist>
"""
            Files.writeString(profilePath, profile)
            CertificateInstallResult(
                "awaiting_user_confirmation",
                "physical-device",
                profilePath.toString(),
                "Install this configuration profile on the iOS device, then enable full trust in Settings > General > About > Certificate Trust Settings. Profile contains CA from ${certificatePath}.",
            )
        }.getOrElse { error ->
            CertificateInstallResult("failed", "physical-device", certificatePath.toString(), error.message ?: "Unable to create iOS configuration profile")
        }
    }
}
