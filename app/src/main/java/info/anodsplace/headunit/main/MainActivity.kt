package info.anodsplace.headunit.main

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import info.anodsplace.headunit.App
import info.anodsplace.headunit.R
import info.anodsplace.headunit.aap.AapProjectionActivity
import info.anodsplace.headunit.aap.AapService
import info.anodsplace.headunit.utils.AppLog
import info.anodsplace.headunit.utils.NetworkUtils
import info.anodsplace.headunit.utils.hideSystemUI
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException

class MainActivity : FragmentActivity() {
    var keyListener: KeyListener? = null

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent): Boolean
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, SettingsFragment())
                .commit()

        ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
        ), permissionRequestCode)

        self_button.setOnClickListener {
            /*
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.main_content, NetworkListFragment())
                    .commit()
             */

            AppLog.i {"Starting service in Wifi mode"}
            startService(AapService.createIntent("127.0.0.1", this))
            // startService(AapService.createIntent("192.168.1.173", this))
        }

        project_button.setOnClickListener {
            if (App.provide(this).transport.isAlive) {
                val aapIntent = AapProjectionActivity.intent(this);
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        window.decorView.hideSystemUI()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i { "onKeyDown: $keyCode "}

        return keyListener?.onKeyEvent(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i { "onKeyUp: $keyCode" }

        return keyListener?.onKeyEvent(event) ?: super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val permissionRequestCode = 97
    }
}
