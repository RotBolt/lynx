package dev.lynx.adb

import java.io.IOException

class ProcessCommandRunner : BinaryCommandRunner {
    override fun run(arguments: List<String>): CommandResult {
        return try {
            val process = ProcessBuilder(arguments).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            CommandResult(process.waitFor(), stdout, stderr)
        } catch (error: IOException) {
            throw AdbException("ADB_NOT_FOUND", error.message ?: "Unable to start adb", error)
        }
    }

    override fun runBinary(arguments: List<String>): BinaryCommandResult {
        return try {
            val process = ProcessBuilder(arguments).start()
            val stdout = process.inputStream.readBytes()
            val stderr = process.errorStream.bufferedReader().readText()
            BinaryCommandResult(process.waitFor(), stdout, stderr)
        } catch (error: IOException) {
            throw AdbException("ADB_NOT_FOUND", error.message ?: "Unable to start adb", error)
        }
    }
}
