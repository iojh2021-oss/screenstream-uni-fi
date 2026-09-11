package com.screenstream.android

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var serverInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            status.text = "Screen capture permission was not granted."
            return@registerForActivityResult
        }

        val server = serverInput.text.toString().trim()
        val room = roomInput.text.toString().trim()
        if (server.isBlank() || room.isBlank()) {
            status.text = "Enter the signaling server and room code first."
            return@registerForActivityResult
        }

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            putExtra(ScreenCaptureService.EXTRA_SERVER_URL, server)
            putExtra(ScreenCaptureService.EXTRA_ROOM_CODE, room)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            status.text = "Starting screen sharing…"
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } catch (e: Exception) {
            status.text = "Could not start screen sharing: ${e.message ?: "unknown error"}"
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private val serviceStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != ScreenCaptureService.ACTION_STATUS) return
            status.text = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS).orEmpty()
            val running = intent.getBooleanExtra(ScreenCaptureService.EXTRA_RUNNING, false)
            startButton.isEnabled = !running
            stopButton.isEnabled = running
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ScreenStream"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        serverInput = EditText(this).apply {
            hint = "wss://screenstream-92-114-51-178.sslip.io/ws"
            setSingleLine(true)
            setText("wss://screenstream-92-114-51-178.sslip.io/ws")
        }
        roomInput = EditText(this).apply {
            hint = "Room code"
            setSingleLine(true)
        }
        startButton = Button(this).apply {
            text = "Start screen sharing"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
        stopButton = Button(this).apply {
            text = "Stop screen sharing"
            isEnabled = false
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                status.text = "Screen sharing stopped."
                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }
        status = TextView(this).apply { text = "Ready" }

        root.addView(serverInput)
        root.addView(roomInput)
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(status)
        setContentView(root)

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            serviceStatusReceiver,
            android.content.IntentFilter(ScreenCaptureService.ACTION_STATUS),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(serviceStatusReceiver) }
        super.onDestroy()
    }
}
