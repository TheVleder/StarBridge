package org.starbridge.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.starbridge.core.protocol.Command
import org.starbridge.core.protocol.NexStarCodec
import org.starbridge.core.protocol.ResponseSpec
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** A queued motion command was discarded because a STOP arrived after it was queued. */
class DiscardedByStopException : Exception("Orden anulada por un STOP")

/**
 * Serializes all traffic to the hand control: one command at a time, each waiting for its
 * answer. Urgent commands (STOP) are taken before any queued normal command, and every
 * motion command that was queued *before* a STOP is discarded instead of being sent after it.
 */
class CommandQueue(
    private val transport: SerialTransport,
    scope: CoroutineScope,
    /** Celestron: drivers should wait up to 3.5 s for a response. */
    private val timeoutMs: Long = 3500,
) {
    private class Request(val command: Command, val result: CompletableDeferred<ByteArray>, val stopEpoch: Long)

    private val urgent = Channel<Request>(Channel.UNLIMITED)
    private val normal = Channel<Request>(Channel.UNLIMITED)

    /** Incremented by every urgent (stop) command. */
    private val stopEpoch = AtomicLong(0)

    private val worker = scope.launch {
        while (isActive) {
            // select is biased to the first clause: urgent requests win.
            val req: Request = try {
                select<Request> {
                    urgent.onReceive { it }
                    normal.onReceive { it }
                }
            } catch (e: ClosedReceiveChannelException) {
                break
            }
            // The caller gave up (cancelled): never transmit its command.
            if (req.result.isCompleted) continue
            if (isMotion(req.command) && req.stopEpoch != stopEpoch.get()) {
                req.result.completeExceptionally(DiscardedByStopException())
                continue
            }
            val started = TimeSource.Monotonic.markNow()
            try {
                val answer = process(req.command)
                record(req.command, answer, null, started.elapsedNow().inWholeMilliseconds)
                req.result.complete(answer)
            } catch (e: CancellationException) {
                req.result.completeExceptionally(java.io.IOException("Telescopio desconectado"))
                throw e
            } catch (e: Exception) {
                record(req.command, null, e.message ?: e.toString(), started.elapsedNow().inWholeMilliseconds)
                req.result.completeExceptionally(e)
            }
        }
    }

    // --- Trace for the diagnostics page -----------------------------------------------------

    /** Recent exchanges, oldest first; identical consecutive ones are folded into "(×N más)". */
    private class Log(val capacity: Int) {
        val lines = ArrayDeque<String>()
        var last: String? = null
        var repeats = 0

        fun add(exchange: String, ms: Long) {
            if (exchange == last) {
                repeats++
                return
            }
            if (repeats > 0) lines.addLast("   (×$repeats más)")
            repeats = 0
            last = exchange
            val t = java.time.LocalTime.now()
            lines.addLast("%02d:%02d:%02d.%03d  %s  %d ms".format(t.hour, t.minute, t.second, t.nano / 1_000_000, exchange, ms))
            while (lines.size > capacity) lines.removeFirst()
        }

        fun snapshot() = lines.toList() + listOfNotNull(last?.takeIf { repeats > 0 }?.let { "   (×$repeats más)" })
    }

    // Position reads are polled several times a second: they get their own short log so they
    // never push the commands that matter (GoTo, slews, stops…) out of the main one.
    private val commandLog = Log(TRACE_LINES)
    private val positionLog = Log(POSITION_TRACE_LINES)

    /** The last exchanges with the hand control: commands first, then the latest position reads. */
    fun trace(): List<String> = synchronized(commandLog) {
        commandLog.snapshot() + listOf("", "Últimas lecturas de posición:") + positionLog.snapshot()
    }

    @Volatile private var averageMs = 0.0
    @Volatile private var lastMs = 0L
    @Volatile private var commands = 0L
    @Volatile private var errors = 0L

    fun stats() = org.starbridge.core.mount.LinkStats(averageMs, lastMs, commands, errors)

    private fun record(c: Command, answer: ByteArray?, error: String?, ms: Long) {
        // Exponential average over the last ~20 commands.
        averageMs = if (commands == 0L) ms.toDouble() else averageMs + (ms - averageMs) / 20
        lastMs = ms
        commands++
        if (error != null) errors++
        val exchange = "${describe(c.bytes, command = true)} → " + (answer?.let { "'${describe(it, command = false)}'" } ?: "ERROR: $error")
        val isPositionRead = c.bytes.size == 1 && (c.bytes[0] == 'z'.code.toByte() || c.bytes[0] == 'e'.code.toByte())
        synchronized(commandLog) { (if (isPositionRead && error == null) positionLog else commandLog).add(exchange, ms) }
    }

    /** Sends [command] and returns the response payload (without the '#'). */
    suspend fun execute(command: Command, urgent: Boolean = false): ByteArray {
        if (urgent) stopEpoch.incrementAndGet()
        val req = Request(command, CompletableDeferred(), stopEpoch.get())
        (if (urgent) this.urgent else normal).send(req)
        try {
            return req.result.await()
        } catch (e: CancellationException) {
            req.result.cancel() // the worker skips it if it has not been sent yet
            throw e
        }
    }

    /** Stops the worker and fails every queued request so no caller hangs. */
    fun close() {
        worker.cancel()
        urgent.close()
        normal.close()
        for (ch in listOf(urgent, normal)) {
            while (true) {
                val r = ch.tryReceive().getOrNull() ?: break
                r.result.completeExceptionally(java.io.IOException("Telescopio desconectado"))
            }
        }
    }

    private suspend fun process(command: Command): ByteArray {
        transport.clearInput()
        transport.write(command.bytes)
        val deadline = TimeSource.Monotonic.markNow() + timeoutMs.milliseconds

        suspend fun next(): Int {
            val remaining = -deadline.elapsedNow().inWholeMilliseconds
            if (remaining <= 0) throw CommandTimeoutException("El mando no responde")
            return transport.read(remaining) ?: throw CommandTimeoutException("El mando no responde")
        }

        return when (val spec = command.response) {
            ResponseSpec.Terminated -> {
                val out = ArrayList<Byte>()
                while (true) {
                    val b = next()
                    if (b == NexStarCodec.TERMINATOR) break
                    out += b.toByte()
                }
                out.toByteArray()
            }
            is ResponseSpec.Fixed -> {
                val out = ByteArray(spec.length) { next().toByte() }
                val end = next()
                if (end != NexStarCodec.TERMINATOR) throw ProtocolException("Respuesta inesperada del mando")
                out
            }
            is ResponseSpec.PassThrough -> {
                val out = ByteArray(spec.length) { next().toByte() }
                val end = next()
                if (end != NexStarCodec.TERMINATOR) {
                    // Error flag: one extra byte was inserted; consume the real terminator.
                    next()
                    throw PassThroughException("El motor no respondió a esa orden")
                }
                out
            }
        }
    }

    companion object {
        const val TRACE_LINES = 300
        const val POSITION_TRACE_LINES = 40

        /** Readable form of a command or answer: text as is, binary bytes as numbers. */
        fun describe(bytes: ByteArray, command: Boolean): String {
            val u = bytes.map { it.toInt() and 0xFF }
            if (u.all { it in 32..126 }) return u.joinToString("") { it.toChar().toString() }
            // Commands start with a letter followed by binary data ('P', 2, 16, 36, 6, 0, 0, 0).
            return if (command && u.isNotEmpty() && u[0] in 65..122) {
                u[0].toChar() + " " + u.drop(1).joinToString(" ")
            } else {
                u.joinToString(" ")
            }
        }

        /** Commands that start movement (GoTos and non-zero slews). Stops are never discarded. */
        fun isMotion(c: Command): Boolean {
            val b = c.bytes
            if (b.isEmpty()) return false
            return when (b[0].toInt().toChar()) {
                'b', 'r', 'R', 'B' -> true
                'P' -> {
                    if (b.size < 8) return false
                    when (b[3].toInt() and 0xFF) {
                        NexStarCodec.MC_MOVE_POS, NexStarCodec.MC_MOVE_NEG -> b[4].toInt() != 0
                        NexStarCodec.MC_VAR_RATE_POS, NexStarCodec.MC_VAR_RATE_NEG -> b[4].toInt() != 0 || b[5].toInt() != 0
                        else -> false
                    }
                }
                else -> false
            }
        }
    }
}
