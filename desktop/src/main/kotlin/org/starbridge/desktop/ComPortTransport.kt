package org.starbridge.desktop

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starbridge.core.transport.SerialTransport

/** [SerialTransport] over a computer's COM port (USB-serial cable to the hand control), 9600 8N1. */
class ComPortTransport private constructor(private val port: SerialPort) : SerialTransport {
    private val buffer = ArrayDeque<Int>()
    private val chunk = ByteArray(64)

    val name: String get() = port.systemPortName

    /**
     * The link is dead (cable pulled and plugged back, telescope power-cycled with some
     * adapters…): Windows may give the port back under the same name, but this handle is gone.
     * The connection loop closes it and opens the port again.
     */
    @Volatile var broken = false
        private set

    private fun failure(what: String): java.io.IOException {
        broken = true
        return java.io.IOException("$what ${port.systemPortName} (error ${port.lastErrorCode} en ${port.lastErrorLocation})")
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val n = port.writeBytes(data, data.size)
        if (n != data.size) throw failure("No se pudo escribir en")
    }

    override suspend fun read(timeoutMs: Long): Int? {
        buffer.removeFirstOrNull()?.let { return it }
        return withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (buffer.isEmpty() && System.nanoTime() < deadline) {
                // Semi-blocking: returns as soon as something arrives, or after READ_SLICE_MS.
                val n = port.readBytes(chunk, chunk.size)
                if (n < 0) throw failure("Se perdió el puerto")
                for (i in 0 until n) buffer.addLast(chunk[i].toInt() and 0xFF)
            }
            buffer.removeFirstOrNull()
        }
    }

    override suspend fun clearInput() {
        buffer.clear()
        withContext(Dispatchers.IO) {
            port.flushIOBuffers()
            while (port.bytesAvailable() > 0) port.readBytes(chunk, minOf(chunk.size, port.bytesAvailable()))
        }
    }

    override fun close() {
        runCatching { port.closePort() }
    }

    companion object {
        private const val READ_SLICE_MS = 100

        /** Opens [name] (e.g. "COM3") for a NexStar hand control, or throws with the reason. */
        fun open(name: String): ComPortTransport {
            val port = SerialPort.getCommPort(name)
            port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
            port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, READ_SLICE_MS, 2_000)
            if (!port.openPort()) throw java.io.IOException("No se pudo abrir $name (¿lo usa otro programa?)")
            return ComPortTransport(port)
        }

        /** Serial ports present now, with what the system says about them. */
        fun list(): List<PortInfo> = SerialPort.getCommPorts().map {
            PortInfo(it.systemPortName, "${it.descriptivePortName} ${it.portDescription}".trim(), runCatching { it.vendorID }.getOrDefault(-1))
        }
    }
}

data class PortInfo(val name: String, val description: String, val vendorId: Int)

/**
 * Which port is the telescope: the one asked for, else the only USB-serial adapter of a known
 * maker (Prolific, WCH CH34x, Silicon Labs CP210x, FTDI), else the only port there is.
 */
fun pickPort(ports: List<PortInfo>, wanted: String?): PortInfo? {
    if (wanted != null) return ports.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    val known = ports.filter { it.vendorId in USB_SERIAL_VENDORS || USB_SERIAL_NAMES.containsMatchIn(it.description) }
    return known.singleOrNull() ?: ports.singleOrNull()
}

private val USB_SERIAL_VENDORS = setOf(0x067B, 0x1A86, 0x10C4, 0x0403)
private val USB_SERIAL_NAMES = Regex("Prolific|PL2303|CH34|CP210|FTDI|USB.?Serial", RegexOption.IGNORE_CASE)
