package com.agoria.app.core

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages terminal background processes for the Terminal connector.
 * Each process runs in the background with its own PID and output buffers.
 */
object TerminalManager {

    private val processes = ConcurrentHashMap<String, ProcessInfo>()
    private val pidCounter = AtomicInteger(1)

    data class ProcessInfo(
        val pid: String,
        val command: String,
        val cwd: String,
        val process: Process,
        val stdout: StringBuilder = StringBuilder(),
        val stderr: StringBuilder = StringBuilder(),
        var status: String = "running",
        val startTime: Long = System.currentTimeMillis(),
    )

    data class SyncResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean = exitCode == 0
    }

    /**
     * Run a command synchronously with a timeout (default 60s).
     */
    fun runSync(command: String, cwd: String, timeoutSeconds: Int = 60): SyncResult {
        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
        processBuilder.directory(java.io.File(cwd))
        processBuilder.redirectErrorStream(false)

        val process = processBuilder.start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val stdoutThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.forEachLine { line ->
                    stdout.append(line).append('\n')
                }
            }
        }

        val stderrThread = Thread {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                reader.forEachLine { line ->
                    stderr.append(line).append('\n')
                }
            }
        }

        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(1000)
            stderrThread.join(1000)
            return SyncResult(-1, stdout.toString(), "Process timed out after ${timeoutSeconds}s\n${stderr}")
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        val exitCode = process.exitValue()
        return SyncResult(exitCode, stdout.toString(), stderr.toString())
    }

    /**
     * Start a background process and return its PID.
     */
    fun startBackground(command: String, cwd: String): String {
        val pid = "proc_${pidCounter.getAndIncrement()}"
        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
        processBuilder.directory(java.io.File(cwd))
        processBuilder.redirectErrorStream(false)

        val process = processBuilder.start()

        val info = ProcessInfo(
            pid = pid,
            command = command,
            cwd = cwd,
            process = process,
        )
        processes[pid] = info

        // Start output readers
        Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.forEachLine { line ->
                    info.stdout.append(line).append('\n')
                }
            }
        }.start()

        Thread {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                reader.forEachLine { line ->
                    info.stderr.append(line).append('\n')
                }
            }
        }.start()

        // Monitor process completion
        Thread {
            try {
                val exitCode = process.waitFor()
                info.status = if (exitCode == 0) "completed" else "failed"
                Log.d("TerminalManager", "Process $pid exited with code $exitCode")
            } catch (e: Exception) {
                info.status = "error"
                Log.e("TerminalManager", "Process $pid error", e)
            }
        }.start()

        return pid
    }

    /**
     * List all background processes.
     */
    fun listProcesses(): List<ProcessInfo> {
        return processes.values.toList()
    }

    /**
     * Stop a background process by PID.
     */
    fun stopProcess(pid: String): Boolean {
        val info = processes.remove(pid)
        if (info == null) return false
        info.process.destroyForcibly()
        info.status = "stopped"
        return true
    }

    /**
     * Get logs for a background process.
     */
    fun getLogs(pid: String): ProcessInfo? {
        return processes[pid]
    }

    /**
     * Clean up completed/stopped processes older than maxAgeMs.
     */
    fun cleanup(maxAgeMs: Long = 3600_000) {
        val now = System.currentTimeMillis()
        processes.entries.removeIf { (_, info) ->
            info.status != "running" && (now - info.startTime) > maxAgeMs
        }
    }
}