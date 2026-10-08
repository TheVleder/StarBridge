package org.starbridge.core.transport

/** Raw byte link to the hand control (USB serial on Android, simulator in tests). */
interface SerialTransport {
    suspend fun write(data: ByteArray)

    /** Reads one byte (0..255), or null if nothing arrived within [timeoutMs]. */
    suspend fun read(timeoutMs: Long): Int?

    /** Discards any pending input (stale answers). */
    suspend fun clearInput()

    fun close()
}

class ProtocolException(message: String) : Exception(message)

class CommandTimeoutException(message: String) : Exception(message)

/** The hand control could not reach the addressed device (pass-through error flag). */
class PassThroughException(message: String) : Exception(message)
