package com.at.basic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BluetoothCommunicationListener {

    private val REQUEST_ENABLE_BT = 1 // Not used directly with ActivityResultContracts, but good to keep for reference
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private lateinit var statusTextView: TextView
    private lateinit var messagesTextView: TextView
    private lateinit var sendButton: Button
    private lateinit var messageEditText: EditText
    private lateinit var serverButton: Button
    private lateinit var clientButton: Button

    private lateinit var bluetoothService: BluetoothService

    private val mainHandler: Handler = @SuppressLint("HandlerLeak") // Suppress warning about handler leak
    object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    val readMessage = String(readBuf, 0, msg.arg1) // msg.arg1 contains numBytes
                    onMessageReceived(readMessage)
                }
                MESSAGE_CONNECTED -> {
                    val deviceName = msg.obj as String
                    val deviceAddress = if (msg.data != null) msg.data.getString("device_address") else "" // If you added address to bundle
                    onConnected(deviceName, deviceAddress ?: "")
                }
                MESSAGE_CONNECTION_FAILED -> {
                    val error = msg.obj as String
                    onConnectionFailed(error)
                }
                MESSAGE_DISCONNECTED -> {
                    onDisconnected()
                }
                MESSAGE_CONNECTING -> {
                    val deviceName = msg.obj as String
                    val deviceAddress = if (msg.data != null) msg.data.getString("device_address") else ""
                    onConnecting(deviceName, deviceAddress ?: "")
                }
                MESSAGE_LISTEN_STARTED -> {
                    onListenStarted()
                }
            }
        }
    }


    // Activity Result Launcher for permissions
    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
            checkBluetoothEnabled()
        } else {
            Toast.makeText(this, "Bluetooth permissions denied. App functionality limited.", Toast.LENGTH_LONG).show()
        }
    }

    // Activity Result Launcher for enabling Bluetooth
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth not enabled. App functionality limited.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        messagesTextView = findViewById(R.id.messagesTextView)
        sendButton = findViewById(R.id.sendButton)
        messageEditText = findViewById(R.id.messageEditText)
        serverButton = findViewById(R.id.serverButton)
        clientButton = findViewById(R.id.clientButton)

        bluetoothService = BluetoothService(mainHandler) // Pass the mainHandler to the BluetoothService

        requestPermissions()

        serverButton.setOnClickListener {
            if (bluetoothAdapter?.isEnabled == true) {
                // statusTextView.text = "Starting as server..." // This will be set by onListenStarted callback
                bluetoothService.start() // Start the server
            } else {
                Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            }
        }

        clientButton.setOnClickListener {
            if (bluetoothAdapter?.isEnabled == true) {
                // statusTextView.text = "Connecting as client..." // This will be set by onConnecting callback
                @SuppressLint("MissingPermission") // Permissions handled via requestBluetoothPermissions
                val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
                if (!pairedDevices.isNullOrEmpty()) {
                    val deviceToConnect = pairedDevices.first() // Connect to the first paired device
                    bluetoothService.connect(deviceToConnect)
                } else {
                    Toast.makeText(this, "No paired devices found. Pair devices first.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            }
        }

        sendButton.setOnClickListener {
            val message = messageEditText.text.toString()
            if (message.isNotEmpty()) {
                bluetoothService.write(message.toByteArray())
                messagesTextView.append("\nMe: $message")
                messageEditText.text.clear()
            } else {
                Toast.makeText(this, "Please enter a message.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (neededPermissions.isNotEmpty()) {
            requestBluetoothPermissions.launch(neededPermissions)
        } else {
            checkBluetoothEnabled()
        }
    }

    @SuppressLint("MissingPermission") // Permissions handled via requestBluetoothPermissions
    private fun checkBluetoothEnabled() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Device doesn't support Bluetooth", Toast.LENGTH_LONG).show()
            finish()
        } else if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        // If the service is not started, start it
        // This ensures the service is always ready for incoming connections when app is in foreground
        bluetoothService.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService.stop()
    }

    // region BluetoothCommunicationListener Callbacks (These are now guaranteed to be on the UI thread)
    override fun onMessageReceived(message: String) {
        messagesTextView.append("\nOther: $message")
    }

    override fun onConnected(deviceName: String, deviceAddress: String) {
        statusTextView.text = "Connected to: $deviceName ($deviceAddress)"
        Toast.makeText(this, "Bluetooth Connected to $deviceName!", Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionFailed(error: String) {
        statusTextView.text = "Connection Failed: $error"
        Toast.makeText(this, "Connection Failed: $error", Toast.LENGTH_LONG).show()
    }

    override fun onDisconnected() {
        statusTextView.text = "Disconnected."
        Toast.makeText(this, "Bluetooth Disconnected.", Toast.LENGTH_SHORT).show()
    }

    override fun onConnecting(deviceName: String, deviceAddress: String) {
        statusTextView.text = "Connecting to: $deviceName ($deviceAddress)..."
    }

    override fun onListenStarted() {
        statusTextView.text = "Status: Listening for connections (Server)"
    }
    // endregion
}