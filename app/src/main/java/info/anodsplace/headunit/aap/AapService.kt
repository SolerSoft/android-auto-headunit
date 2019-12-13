package info.anodsplace.headunit.aap

import android.app.*
import android.content.*
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat.PRIORITY_MIN
import info.anodsplace.headunit.App
import info.anodsplace.headunit.R
import info.anodsplace.headunit.aap.protocol.messages.NightModeEvent
import info.anodsplace.headunit.connection.AccessoryConnection
import info.anodsplace.headunit.connection.SocketAccessoryConnection
import info.anodsplace.headunit.connection.UsbAccessoryConnection
import info.anodsplace.headunit.connection.UsbReceiver
import info.anodsplace.headunit.contract.ConnectedIntent
import info.anodsplace.headunit.location.GpsLocationService
import info.anodsplace.headunit.utils.*
import info.anodsplace.headunit.contract.DisconnectIntent
import info.anodsplace.headunit.contract.LocationUpdateIntent


class AapService : Service(), UsbReceiver.Listener, AccessoryConnection.Listener {

    private lateinit var uiModeManager: UiModeManager
    private var accessoryConnection: AccessoryConnection? = null
    private lateinit var usbReceiver: UsbReceiver
    private lateinit var nightModeReceiver: BroadcastReceiver

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_NONE)
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    private fun startForeground() {

        // Determine channel ID based on SDK version
        // See: https://stackoverflow.com/questions/47531742/startforeground-fail-after-upgrade-to-android-8-1
        val channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannel("HeadUnitService", "HeadUnit Service")
                } else {
                    // If earlier version channel ID is not used
                    // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                    App.defaultChannel
                }


        val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setTicker("HeadUnit is running")
                .setWhen(System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentTitle("HeadUnit is running")
                .setContentText("...")
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, AapProjectionActivity.intent(this), PendingIntent.FLAG_UPDATE_CURRENT))
                .setPriority(NotificationManager.IMPORTANCE_HIGH)
                .build()

        startForeground(1, notification)
    }


    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        uiModeManager.nightMode = UiModeManager.MODE_NIGHT_AUTO

        usbReceiver = UsbReceiver(this)
        nightModeReceiver = NightModeReceiver(Settings(this), uiModeManager)

        val nightModeFilter = IntentFilter()
        nightModeFilter.addAction(Intent.ACTION_TIME_TICK)
        nightModeFilter.addAction(LocationUpdateIntent.action)
        registerReceiver(nightModeReceiver, nightModeFilter)
        registerReceiver(usbReceiver, UsbReceiver.createFilter())
    }

    override fun onDestroy() {
        super.onDestroy()
        onDisconnect()
        unregisterReceiver(nightModeReceiver)
        unregisterReceiver(usbReceiver)
        uiModeManager.disableCarMode(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        accessoryConnection = connectionFactory(intent, this)
        if (accessoryConnection == null) {
            AppLog.e { "Cannot create connection $intent" }
            stopSelf()
            return START_NOT_STICKY
        }

        uiModeManager.enableCarMode(0)

        startService(GpsLocationService.intent(this))

        // Overload creates notification and sets ID
        startForeground()

        accessoryConnection!!.connect(this)

        return START_STICKY
    }

    override fun onConnectionResult(success: Boolean) {
        if (success) {
            reset()
            if (App.provide(this).transport.start(accessoryConnection!!)) {
                sendBroadcast(ConnectedIntent())
            }
        } else {
            AppLog.e { "Cannot connect to device" }
            Toast.makeText(this, "Cannot connect to the device", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun onDisconnect() {
        sendBroadcast(DisconnectIntent())
        reset()
        accessoryConnection?.disconnect()
        accessoryConnection = null
    }

    private fun reset() {
        App.provide(this).resetTransport()
        App.provide(this).audioDecoder.stop()
        App.provide(this).videoDecoder.stop("AapService::reset")
    }

    override fun onUsbDetach(device: UsbDevice) {
        if (accessoryConnection is UsbAccessoryConnection) {
            if ((accessoryConnection as UsbAccessoryConnection).isDeviceRunning(device)) {
                stopSelf()
            }
        }
    }

    override fun onUsbAttach(device: UsbDevice) {

    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {

    }

    private class NightModeReceiver(private val settings: Settings, private val modeManager: UiModeManager) : BroadcastReceiver() {
        private var nightMode = NightMode(settings, false)
        private var initialized = false
        private var lastValue = false

        override fun onReceive(context: Context, intent: Intent) {
            if (!nightMode.hasGPSLocation && intent.action == LocationUpdateIntent.action) nightMode = NightMode(settings, true)

            val isCurrent = nightMode.current
            if (!initialized || lastValue != isCurrent) {
                lastValue = isCurrent
                AppLog.i { nightMode.toString() }
                initialized = App.provide(context).transport.send(NightModeEvent(isCurrent))
                if (initialized) modeManager.nightMode = if (isCurrent) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            }
        }
    }

    companion object {
        private const val TYPE_USB = 1
        private const val TYPE_WIFI = 2
        private const val EXTRA_CONNECTION_TYPE = "extra_connection_type"
        private const val EXTRA_IP = "extra_ip"

        fun createIntent(device: UsbDevice, context: Context): Intent {
            val intent = Intent(context, AapService::class.java)
            intent.putExtra(UsbManager.EXTRA_DEVICE, device)
            intent.putExtra(EXTRA_CONNECTION_TYPE, TYPE_USB)
            return intent
        }

        fun createIntent(ip: String, context: Context): Intent {
            val intent = Intent(context, AapService::class.java)
            intent.putExtra(EXTRA_IP, ip)
            intent.putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI)
            return intent
        }

        private fun connectionFactory(intent: Intent?, context: Context): AccessoryConnection? {
            val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0

            if (connectionType == TYPE_USB) {
                val device = intent.usbDevice
                if (device == null) {
                    AppLog.e { "No device in $intent" }
                    return null
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                return UsbAccessoryConnection(usbManager, device)
            } else if (connectionType == TYPE_WIFI) {
                val ip = intent?.getStringExtra(EXTRA_IP) ?: ""
                return SocketAccessoryConnection(ip)
            }

            return null
        }
    }
}
