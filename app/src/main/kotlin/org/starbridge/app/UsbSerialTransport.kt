package org.starbridge.app

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starbridge.core.transport.SerialTransport

/** [SerialTransport] over usb-serial-for-android (PL2303 / FTDI / CP210x / CH34x). */
class UsbSerialTransport(private val port: UsbSerialPort) : SerialTransport {
    private val buffer = ArrayDeque<Int>()
    private val chunk = ByteArray(maxOf(64, port.readEndpoint?.maxPacketSize ?: 64))

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override suspend fun read(timeoutMs: Long): Int? {
        buffer.removeFirstOrNull()?.let { return it }
        return withContext(Dispatchers.IO) {
            val n = port.read(chunk, timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
            for (i in 0 until n) buffer.addLast(chunk[i].toInt() and 0xFF)
            buffer.removeFirstOrNull()
        }
    }

    override suspend fun clearInput() {
        buffer.clear()
        withContext(Dispatchers.IO) {
            // Not every chip supports purging; drain whatever is pending instead.
            runCatching { port.purgeHwBuffers(false, true) }
            while (port.read(chunk, 1) > 0) Unit
        }
    }

    override fun close() {
        runCatching { port.close() }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 2000
    }
}
