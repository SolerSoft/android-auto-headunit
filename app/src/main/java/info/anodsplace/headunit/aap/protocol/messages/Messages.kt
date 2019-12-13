package info.anodsplace.headunit.aap.protocol.messages

import info.anodsplace.headunit.aap.Utils


/**
 * @author algavris
 * *
 * @date 08/06/2016.
 */

object Messages {
    const val DEF_BUFFER_LENGTH = 131080
    var VERSION_REQUEST = byteArrayOf(0, 1, 0, 2)

    fun createRawMessage(chan: Int, flags: Int, type: Int, data: ByteArray): ByteArray {
        val size = data.size
        val total = 6 + size
        val buffer = ByteArray(total)

        buffer[0] = chan.toByte()
        buffer[1] = flags.toByte()
        Utils.intToBytes(size + 2, 2, buffer)
        Utils.intToBytes(type, 4, buffer)

        System.arraycopy(data, 0, buffer, 6, size)
        return buffer
    }
}
