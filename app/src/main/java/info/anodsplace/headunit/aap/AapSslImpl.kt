package info.anodsplace.headunit.aap

import info.anodsplace.headunit.aap.protocol.messages.Messages.DEF_BUFFER_LENGTH
import info.anodsplace.headunit.utils.AppLog
import info.anodsplace.headunit.utils.bytesToHex
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

object AapSslImpl: AapSsl {
    private val sslContext: SSLContext = SSLContext.getInstance("TLSv1.2")
    private var sslEngine: SSLEngine? = null
    private var txBuffer: ByteBuffer? = null
    private var rxBuffer: ByteBuffer? = null

    init {
        sslContext.init(arrayOf(SingleKeyKeyManager), arrayOf(NoCheckTrustManager), null)
    }

    override fun prepare() {
        val newSslEngine = sslContext.createSSLEngine()
        newSslEngine.useClientMode = true

        val session = newSslEngine.session
        val appBufferMax = session.applicationBufferSize
        val netBufferMax = session.packetBufferSize

        AppLog.v { "appBufferMax: $appBufferMax"}
        AppLog.v { "netBufferMax: $netBufferMax"}

        sslEngine = newSslEngine
        txBuffer = ByteBuffer.allocateDirect(netBufferMax)
        rxBuffer = ByteBuffer.allocateDirect(DEF_BUFFER_LENGTH.coerceAtLeast(appBufferMax + 50))
    }

    private fun runDelegatedTasks(result: SSLEngineResult, engine: SSLEngine) {
        if (result.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = engine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = engine.delegatedTask
            }
            val hsStatus = engine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    override fun handshakeRead(): ByteArray {
        require(sslEngine != null) { "SSL Engine not initialized - prepare() was not called" }
        txBuffer!!.clear()
        val result = sslEngine!!.wrap(emptyArray(), txBuffer)
        runDelegatedTasks(result, sslEngine!!)
        val resultBuffer = ByteArray(result.bytesProduced())
        txBuffer!!.flip()
        txBuffer!!.get(resultBuffer)
        return resultBuffer
    }

    override fun handshakeWrite(handshakeData: ByteArray) {
        require(sslEngine != null) { "SSL Engine not initialized - prepare() was not called" }
        rxBuffer!!.clear()
        val data = ByteBuffer.wrap(handshakeData)
        val result = sslEngine!!.unwrap(data, rxBuffer)
        runDelegatedTasks(result, sslEngine!!)
    }

    override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArray {
        require(sslEngine != null) { "SSL Engine not initialized - prepare() was not called" }
        rxBuffer!!.clear()
        val encrypted = ByteBuffer.wrap(buffer, start, length)
        val result = sslEngine!!.unwrap(encrypted, rxBuffer)
        runDelegatedTasks(result, sslEngine!!)
        val resultBuffer = ByteArray(result.bytesProduced())
        rxBuffer!!.flip()
        rxBuffer!!.get(resultBuffer)
        return resultBuffer
    }

    override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArray {
        require(sslEngine != null) { "SSL Engine not initialized - prepare() was not called" }
        txBuffer!!.clear()
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        val result = sslEngine!!.wrap(byteBuffer, txBuffer)
        runDelegatedTasks(result, sslEngine!!)
        val resultBuffer = ByteArray(result.bytesProduced() + offset)
        txBuffer!!.flip()
        txBuffer!!.get(resultBuffer, offset, result.bytesProduced())
        return resultBuffer
    }
}
