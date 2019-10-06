package info.anodsplace.headunit.aap

import android.app.UiModeManager
import android.content.Context
import android.content.Context.UI_MODE_SERVICE
import android.media.AudioManager
import android.os.*
import android.util.SparseIntArray
import android.view.KeyEvent
import info.anodsplace.headunit.aap.protocol.Channel
import info.anodsplace.headunit.aap.protocol.messages.*
import info.anodsplace.headunit.aap.protocol.proto.Input
import info.anodsplace.headunit.aap.protocol.proto.Sensors
import info.anodsplace.headunit.connection.AccessoryConnection
import info.anodsplace.headunit.contract.ProjectionActivityRequest
import info.anodsplace.headunit.decoder.AudioDecoder
import info.anodsplace.headunit.decoder.MicRecorder
import info.anodsplace.headunit.decoder.VideoDecoder
import info.anodsplace.headunit.utils.*
import java.util.*

class AapTransport(
        audioDecoder: AudioDecoder,
        videoDecoder: VideoDecoder,
        audioManager: AudioManager,
        private val settings: Settings,
        private val context: Context)
    : Handler.Callback, MicRecorder.Listener {

    private val aapAudio: AapAudio
    private val aapVideo: AapVideo
    private val pollThread: HandlerThread = HandlerThread("AapTransport:Handler", Process.THREAD_PRIORITY_AUDIO)
    private val micRecorder: MicRecorder = MicRecorder(settings.micSampleRate, context)
    private val sessionIds = SparseIntArray(4)
    private val startedSensors = HashSet<Int>(4)
    private val ssl: AapSsl = AapSslImpl()
    private val keyCodes = settings.keyCodes.entries.associateTo(mutableMapOf()) {
        it.value to it.key
    }
    private val modeManager: UiModeManager =  context.getSystemService(UI_MODE_SERVICE) as UiModeManager
    private var connection: AccessoryConnection? = null
    private var aapRead: AapRead? = null
    private var handler: Handler? = null

    val isAlive: Boolean
        get() = pollThread.isAlive

    init {
        micRecorder.listener = this
        aapAudio = AapAudio(audioDecoder, audioManager)
        aapVideo = AapVideo(videoDecoder)
    }

    internal fun startSensor(type: Int) {
        startedSensors.add(type)
        if (type == Sensors.SensorType.NIGHT_VALUE) {
            send(NightModeEvent(false))
        }
    }

    override fun handleMessage(msg: Message): Boolean {
        if (msg.what == MSG_SEND) {
            val size = msg.arg2
            this.sendEncryptedMessage(msg.obj as ByteArray, size)
            return true
        }

        if (msg.what == MSG_POLL) {
            val ret = aapRead?.read() ?: -1
            if (handler == null) {
                return false
            }
            handler?.let {
                if (!it.hasMessages(MSG_POLL)) {
                    it.sendEmptyMessage(MSG_POLL)
                }
            }

            if (ret < 0) {
                this.quit()
            }
        }

        return true
    }

    private fun sendEncryptedMessage(data: ByteArray, length: Int) {
        // Encrypt from data[4] onwards
        val ba = ssl.encrypt(AapMessage.HEADER_SIZE, length - AapMessage.HEADER_SIZE, data) ?: return

        // Copy data[0->4] into buffer
        // TODO what is this?
        ba.data[0] = data[0]
        // TODO what is this?
        ba.data[1] = data[1]
        // Length
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)

        // Write 4 bytes of header and the encrypted data
        val size = connection!!.write(ba.data, 0, ba.limit, 250)
        AppLog.d { "Sent size: $size" }
    }

    internal fun quit() {
        micRecorder.listener = null
        pollThread.quit()
        aapRead = null
        handler = null
    }

    internal fun start(connection: AccessoryConnection): Boolean {
        AppLog.i { "Start Aap transport for $connection" }

        if (!handshake(connection)) {
            AppLog.e { "Handshake failed" }
            return false
        }

        this.connection = connection

        aapRead = AapRead.Factory.create(connection, this, micRecorder, aapAudio, aapVideo, settings, context)

        pollThread.start()
        handler = Handler(pollThread.looper, this)
        handler!!.sendEmptyMessage(MSG_POLL)
        // Create and start Transport Thread
        return true
    }

    private fun handshake(connection: AccessoryConnection): Boolean {
        val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)

        // Version request

        val versionRequest = Messages.createRawMessage(0, 3, 1, Messages.VERSION_REQUEST, Messages.VERSION_REQUEST.size) // Version Request
        var ret = connection.write(versionRequest, 0, versionRequest.size, 1000)
        if (ret < 0) {
            AppLog.e { "Version request sendEncrypted ret: $ret" }
            return false
        }

        ret = connection.read(buffer, 0, buffer.size, 1000)
        if (ret <= 0) {
            AppLog.e { "Version request read ret: $ret" }
            return false
        }
        AppLog.i { "Version response read ret: $ret" }

        // SSL
        ret = ssl.prepare()
        if (ret < 0) {
            AppLog.e { "SSL prepare failed: $ret" }
            return false
        }

        var hs_ctr = 0
        while (hs_ctr++ < 2) {
            val data = ssl.handshakeRead() ?: return false

            val bio = Messages.createRawMessage(Channel.ID_CTR, 3, 3, data.data, data.limit)
            var size = connection.write(bio, 0, bio.size, 1000)
            AppLog.i { "SSL BIO sent: $size" }
            AppLog.e { "Data was: ${bytesToHex(data.data, data.limit)}"}

            size = connection.read(buffer, 0, buffer.size, 1000)
            AppLog.i { "SSL received: $size" }
            if (size <= 0) {
                AppLog.e { "SSL receive error: $size" }
                return false
            }

            val theirCertificate = ByteArray(size - 6)
            System.arraycopy(buffer, 6, theirCertificate, 0, size - 6)
            AppLog.e { "Rxda was: ${bytesToHex(theirCertificate, theirCertificate.size)}" }
            ssl.handshakeWrite(theirCertificate)
            AppLog.i { "SSL BIO write: $ret" }
        }

        // Status = OK
        // byte ac_buf [] = {0, 3, 0, 4, 0, 4, 8, 0};
        val status = Messages.createRawMessage(0, 3, 4, byteArrayOf(8, 0), 2)
        ret = connection.write(status, 0, status.size, 1000)
        if (ret < 0) {
            AppLog.e { "Status request sendEncrypted ret: $ret" }
            return false
        }

        AppLog.i { "Status OK sent: $ret" }

        return true
    }

    fun send(keyCode: Int, isPress: Boolean) {
        val mapped = keyCodes[keyCode] ?: keyCode
        val aapKeyCode = KeyCode.convert(mapped)

        if (mapped == KeyEvent.KEYCODE_GUIDE) { // TODO what...?
            // Hack for navigation button to simulate touch
            val action = if (isPress) Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN else Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
            this.send(TouchEvent(SystemClock.elapsedRealtime(), action, 0, listOf(Triple(0, 99, 444))))
            return
        }

        if (mapped == KeyEvent.KEYCODE_N) {
            val enabled = modeManager.nightMode != UiModeManager.MODE_NIGHT_YES
            send(NightModeEvent(enabled))
            modeManager.nightMode = if (enabled) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            return
        }

        if (aapKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
            AppLog.i { "Unknown: $keyCode" }
        }

        val ts = SystemClock.elapsedRealtime()
        if (aapKeyCode == KeyEvent.KEYCODE_SOFT_LEFT|| aapKeyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            if (isPress) {
                val delta = if (aapKeyCode == KeyEvent.KEYCODE_SOFT_LEFT) -1 else 1
                send(ScrollWheelEvent(ts, delta))
            }
            return
        }

        send(KeyCodeEvent(ts, aapKeyCode, isPress))
    }

    fun send(sensor: SensorEvent): Boolean {
        return if (startedSensors.contains(sensor.sensorType)) {
            send(sensor as AapMessage)
            true
        } else {
            AppLog.e { "Sensor " + sensor.sensorType + " is not started yet" }
            false
        }
    }

    fun send(message: AapMessage) {
        if (handler == null) {
            AppLog.e { "Handler is null" }
        } else {
            AppLog.d { message.toString() }
            val msg = handler!!.obtainMessage(MSG_SEND, 0, message.size, message.data)
            handler!!.sendMessage(msg)
        }
    }

    internal fun gainVideoFocus() {
        context.sendBroadcast(ProjectionActivityRequest())
    }

    internal fun sendMediaAck(channel: Int) {
        send(MediaAck(channel, sessionIds.get(channel)))
    }

    internal fun setSessionId(channel: Int, sessionId: Int) {
        sessionIds.put(channel, sessionId)
    }

    override fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int) {
        if (mic_audio_len > 64) { // If we read at least 64 bytes of audio data
            val length = mic_audio_len + 10
            val data = ByteArray(length)
            data[0] = Channel.ID_MIC.toByte()
            data[1] = 0x0b
            Utils.putTime(2, data, SystemClock.elapsedRealtime())
            System.arraycopy(mic_buf, 0, data, 10, mic_audio_len)
            send(AapMessage(Channel.ID_MIC, 0x0b.toByte(), -1, 2, length, data))
        }
    }

    companion object {
        private const val MSG_POLL = 1
        private const val MSG_SEND = 2
    }
}

