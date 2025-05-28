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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Random

class MainActivity : AppCompatActivity(), BluetoothCommunicationListener {

    private val REQUEST_ENABLE_BT = 1
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private lateinit var statusTextView: TextView
    private lateinit var messagesTextView: TextView
    private lateinit var sendButton: Button
    private lateinit var messageEditText: EditText
    private lateinit var serverButton: Button
    private lateinit var clientButton: Button

    // New UI elements
    private lateinit var frequencySpinner: Spinner
    private lateinit var toggleAutoSendButton: Button

    private lateinit var bluetoothService: BluetoothService

    // Handler for automatic data sending
    private val autoSendHandler = Handler(Looper.getMainLooper())
    private var isAutoSending = false
    private var currentSendFrequencyMillis = 1000L // Default to 1 second (1000 ms)

    // Runnable for automatic data sending
    private val autoSendRunnable = object : Runnable {
        override fun run() {
            if (bluetoothService.currentState == BluetoothService.STATE_CONNECTED) {
                val ecmData = generateECMData()
                bluetoothService.write(ecmData.toByteArray())
                messagesTextView.append("\nMe (Auto): $ecmData")
            } else {
                Toast.makeText(this@MainActivity, "Not connected to send auto data.", Toast.LENGTH_SHORT).show()
                stopAutoSending() // Stop if disconnected
            }
            if (isAutoSending) {
                autoSendHandler.postDelayed(this, currentSendFrequencyMillis)
            }
        }
    }


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

        // Initialize new UI elements
        frequencySpinner = findViewById(R.id.frequencySpinner)
        toggleAutoSendButton = findViewById(R.id.toggleAutoSendButton)

        bluetoothService = BluetoothService(mainHandler) // Pass the mainHandler to the BluetoothService

        requestPermissions()

        // Setup frequency spinner
        ArrayAdapter.createFromResource(
            this,
            R.array.send_frequencies,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            frequencySpinner.adapter = adapter
        }

        frequencySpinner.setSelection(0) // Default to 1 Second

        // Set listener for frequency spinner to update currentSendFrequencyMillis
        frequencySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentSendFrequencyMillis = when (position) {
                    0 -> 1000L // 1 Second
                    1 -> 5000L // 5 Seconds
                    2 -> 10000L // 10 Seconds
                    else -> 1000L // Default
                }
                Toast.makeText(this@MainActivity, "Send frequency set to ${parent?.getItemAtPosition(position)}", Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                // Do nothing
            }
        }


        serverButton.setOnClickListener {
            if (bluetoothAdapter?.isEnabled == true) {
                bluetoothService.start() // Start the server
                stopAutoSending() // Stop auto-sending if switching roles
            } else {
                Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            }
        }

        clientButton.setOnClickListener {
            if (bluetoothAdapter?.isEnabled == true) {
                @SuppressLint("MissingPermission") // Permissions handled via requestBluetoothPermissions
                val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
                if (!pairedDevices.isNullOrEmpty()) {
                    val deviceToConnect = pairedDevices.first() // Connect to the first paired device
                    bluetoothService.connect(deviceToConnect)
                    stopAutoSending() // Stop auto-sending if connecting as client
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

        // Auto-send toggle button listener
        toggleAutoSendButton.setOnClickListener {
            if (isAutoSending) {
                stopAutoSending()
            } else {
                startAutoSending()
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
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE) // For server mode
        }
        // Location permissions are crucial for BLE scanning, but also for Classic BT discovery on some versions
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)


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
        // Consider if you always want to start listening on resume or only on button press
        // For this use case, starting as a server allows immediate connection if desired
        // If you only want it to be explicitly started by a button, you can remove this.
        if (bluetoothService.currentState == BluetoothService.STATE_NONE) {
            bluetoothService.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService.stop()
        stopAutoSending() // Clean up handler callbacks
    }

    // region BluetoothCommunicationListener Callbacks (These are now guaranteed to be on the UI thread)
    override fun onMessageReceived(message: String) {
        messagesTextView.append("\nOther: $message")
    }

    override fun onConnected(deviceName: String, deviceAddress: String) {
        statusTextView.text = "Connected to: $deviceName ($deviceAddress)"
        Toast.makeText(this, "Bluetooth Connected to $deviceName!", Toast.LENGTH_SHORT).show()
        // Optionally, start auto-sending immediately after connection
        // startAutoSending()
    }

    override fun onConnectionFailed(error: String) {
        statusTextView.text = "Connection Failed: $error"
        Toast.makeText(this, "Connection Failed: $error", Toast.LENGTH_LONG).show()
        stopAutoSending() // Stop auto-sending on connection failure
    }

    override fun onDisconnected() {
        statusTextView.text = "Disconnected."
        Toast.makeText(this, "Bluetooth Disconnected.", Toast.LENGTH_SHORT).show()
        stopAutoSending() // Stop auto-sending on disconnection
    }

    override fun onConnecting(deviceName: String, deviceAddress: String) {
        statusTextView.text = "Connecting to: $deviceName ($deviceAddress)..."
    }

    override fun onListenStarted() {
        statusTextView.text = "Status: Listening for connections (Server)"
    }
    // endregion

    // region Automatic Data Sending Logic

    private fun startAutoSending() {
        if (bluetoothService.currentState == BluetoothService.STATE_CONNECTED) {
            if (!isAutoSending) {
                isAutoSending = true
                toggleAutoSendButton.text = "Stop Auto Send"
                autoSendHandler.post(autoSendRunnable)
                Toast.makeText(this, "Auto sending started.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Not connected to start auto sending.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAutoSending() {
        if (isAutoSending) {
            isAutoSending = false
            toggleAutoSendButton.text = "Start Auto Send"
            autoSendHandler.removeCallbacks(autoSendRunnable)
            Toast.makeText(this, "Auto sending stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Generates simulated ECM data.
     * In a real application, this would come from sensors or a data source.
     */
    private fun generateECMData(): String {
        val random = Random()
        val vin = "VIN123456789ABCDEF"
        val odometer = random.nextInt(100000) // 0 - 99999 km
        val rpm = random.nextInt(4000) + 500 // 500 - 4499 RPM
        val engineHours = String.format("%.2f", random.nextDouble() * 1000) // 0.00 - 999.99 hours
        val engineState = if (random.nextBoolean()) "RUNNING" else "OFF"
        val fuelLevel = random.nextInt(101) // 0-100%

        return "VIN:$vin,ODO:$odometer,RPM:$rpm,EH:$engineHours,State:$engineState,Fuel:$fuelLevel%"
    }
    // endregion
}