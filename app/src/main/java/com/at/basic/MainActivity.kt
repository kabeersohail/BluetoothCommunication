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
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Random

class MainActivity : AppCompatActivity(), BluetoothCommunicationListener {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private lateinit var statusTextView: TextView
    private lateinit var messagesTextView: TextView
    private lateinit var receiveButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var frequencySeekBar: SeekBar
    private lateinit var frequencyLabel: TextView

    private lateinit var bluetoothService: BluetoothService

    private val autoSendHandler = Handler(Looper.getMainLooper())
    private var isAutoSending = false
    private var isReceiving = false
    private var sendFrequencyMillis = 1000L // Default 1 second
    private var messageCount = 0

    // Runnable for automatic data sending
    private val autoSendRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "autoSendRunnable: run() called. isAutoSending: $isAutoSending, BluetoothService state: ${bluetoothService.currentState}")
            if (bluetoothService.currentState == BluetoothService.STATE_CONNECTED && isAutoSending) {
                val ecmData = generateECMData()
                Log.d(TAG, "autoSendRunnable: Generated ECM data: $ecmData")
                bluetoothService.write(ecmData.toByteArray())
                messageCount++
                messagesTextView.append("\n[${messageCount}] Me (Sent): $ecmData")

                // Auto-scroll to bottom
                runOnUiThread {
                    val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
                    scrollView.post { scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
                }
            } else {
                Log.w(TAG, "autoSendRunnable: Not sending data. Not connected or auto-sending stopped. Current state: ${bluetoothService.currentState}")
                stopAutoSending()
            }
            if (isAutoSending) {
                Log.d(TAG, "autoSendRunnable: Rescheduling in ${sendFrequencyMillis}ms.")
                autoSendHandler.postDelayed(this, sendFrequencyMillis)
            } else {
                Log.d(TAG, "autoSendRunnable: isAutoSending is false, not rescheduling.")
            }
        }
    }

    private val mainHandler: Handler = @SuppressLint("HandlerLeak")
    object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            Log.d(TAG, "mainHandler: handleMessage() called for what: ${msg.what}")
            when (msg.what) {
                MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    val readMessage = String(readBuf, 0, msg.arg1)
                    Log.d(TAG, "mainHandler: MESSAGE_READ received. Message: '$readMessage'")
                    onMessageReceived(readMessage)
                }
                MESSAGE_CONNECTED -> {
                    val deviceName = msg.obj as String
                    val deviceAddress = if (msg.data != null) msg.data.getString("device_address") else ""
                    Log.d(TAG, "mainHandler: MESSAGE_CONNECTED received. Device: $deviceName ($deviceAddress)")
                    onConnected(deviceName, deviceAddress ?: "")
                }
                MESSAGE_CONNECTION_FAILED -> {
                    val error = msg.obj as String
                    Log.e(TAG, "mainHandler: MESSAGE_CONNECTION_FAILED received. Error: $error")
                    onConnectionFailed(error)
                }
                MESSAGE_DISCONNECTED -> {
                    Log.d(TAG, "mainHandler: MESSAGE_DISCONNECTED received.")
                    onDisconnected()
                }
                MESSAGE_CONNECTING -> {
                    val deviceName = msg.obj as String
                    val deviceAddress = if (msg.data != null) msg.data.getString("device_address") else ""
                    Log.d(TAG, "mainHandler: MESSAGE_CONNECTING received. Device: $deviceName ($deviceAddress)")
                    onConnecting(deviceName, deviceAddress ?: "")
                }
                MESSAGE_LISTEN_STARTED -> {
                    Log.d(TAG, "mainHandler: MESSAGE_LISTEN_STARTED received.")
                    onListenStarted()
                }
            }
        }
    }

    // Activity Result Launchers
    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        Log.d(TAG, "requestBluetoothPermissions: Permissions result. Granted: $granted")
        if (granted) {
            Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
            checkBluetoothEnabled()
        } else {
            Toast.makeText(this, "Bluetooth permissions denied. App functionality limited.", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d(TAG, "enableBluetoothLauncher: Bluetooth enable request result. Result code: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth not enabled. App functionality limited.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate() called.")

        initializeViews()
        bluetoothService = BluetoothService(mainHandler)
        requestPermissions()
        setupEventListeners()
    }

    private fun initializeViews() {
        statusTextView = findViewById(R.id.statusTextView)
        messagesTextView = findViewById(R.id.messagesTextView)
        receiveButton = findViewById(R.id.receiveButton)
        sendButton = findViewById(R.id.sendButton)
        stopButton = findViewById(R.id.stopButton)
        frequencySeekBar = findViewById(R.id.frequencySeekBar)
        frequencyLabel = findViewById(R.id.frequencyLabel)

        // Initialize frequency controls
        updateFrequencyLabel()
    }

    private fun setupEventListeners() {
        receiveButton.setOnClickListener {
            Log.d(TAG, "receiveButton clicked.")
            startReceiveMode()
        }

        sendButton.setOnClickListener {
            Log.d(TAG, "sendButton clicked.")
            startSendMode()
        }

        stopButton.setOnClickListener {
            Log.d(TAG, "stopButton clicked.")
            stopAllOperations()
        }

        frequencySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Map progress (0-100) to frequency (100ms - 5000ms)
                sendFrequencyMillis = (100 + (progress * 49)).toLong()
                updateFrequencyLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateFrequencyLabel() {
        val frequency = 1000.0 / sendFrequencyMillis
        frequencyLabel.text = "Send Frequency: ${String.format("%.1f", frequency)} Hz (${sendFrequencyMillis}ms)"
    }

    private fun startReceiveMode() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            return
        }

        stopAllOperations()
        isReceiving = true
        messageCount = 0

        bluetoothService.start()
        statusTextView.text = "Status: Waiting to Receive..."
        messagesTextView.text = "=== Receive Mode Started ===\n"
        updateButtonStates()
    }

    private fun startSendMode() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            return
        }

        stopAllOperations()
        messageCount = 0
        messagesTextView.text = "=== Send Mode Started ===\n"

        @SuppressLint("MissingPermission")
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        if (!pairedDevices.isNullOrEmpty()) {
            isAutoSending = true
            val deviceToConnect = pairedDevices.first()
            bluetoothService.connect(deviceToConnect)
            statusTextView.text = "Status: Attempting to Send..."
            updateButtonStates()
        } else {
            Toast.makeText(this, "No paired devices found. Pair devices first.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAllOperations() {
        isAutoSending = false
        isReceiving = false
        stopAutoSending()
        bluetoothService.stop()
        statusTextView.text = "Status: Stopped"
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val isActive = isAutoSending || isReceiving
        receiveButton.isEnabled = !isActive
        sendButton.isEnabled = !isActive
        stopButton.isEnabled = isActive
        frequencySeekBar.isEnabled = !isActive
    }

    private fun requestPermissions() {
        Log.d(TAG, "requestPermissions() called.")
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
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

    @SuppressLint("MissingPermission")
    private fun checkBluetoothEnabled() {
        Log.d(TAG, "checkBluetoothEnabled() called.")
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Device doesn't support Bluetooth", Toast.LENGTH_LONG).show()
            finish()
        } else if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called.")
        stopAllOperations()
    }

    // BluetoothCommunicationListener Callbacks
    override fun onMessageReceived(message: String) {
        Log.d(TAG, "onMessageReceived() called. Message: '$message'")
        messageCount++
        messagesTextView.append("\n[${messageCount}] Other (Received): $message")

        // Auto-scroll to bottom
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
        scrollView.post { scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
    }

    override fun onConnected(deviceName: String, deviceAddress: String) {
        Log.d(TAG, "onConnected() called. Device: $deviceName")
        if (isAutoSending) {
            statusTextView.text = "Status: Sending to $deviceName"
            autoSendHandler.post(autoSendRunnable)
        } else if (isReceiving) {
            statusTextView.text = "Status: Receiving from $deviceName"
        }
        Toast.makeText(this, "Connected to $deviceName!", Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionFailed(error: String) {
        Log.e(TAG, "onConnectionFailed() called. Error: $error")
        statusTextView.text = "Connection Failed: $error"
        Toast.makeText(this, "Connection Failed: $error", Toast.LENGTH_LONG).show()
        stopAllOperations()
    }

    override fun onDisconnected() {
        Log.d(TAG, "onDisconnected() called.")
        statusTextView.text = "Disconnected."
        Toast.makeText(this, "Bluetooth Disconnected.", Toast.LENGTH_SHORT).show()
        stopAllOperations()
    }

    override fun onConnecting(deviceName: String, deviceAddress: String) {
        Log.d(TAG, "onConnecting() called. Device: $deviceName")
        statusTextView.text = "Status: Connecting to $deviceName..."
    }

    override fun onListenStarted() {
        Log.d(TAG, "onListenStarted() called.")
        if (isReceiving) {
            statusTextView.text = "Status: Listening for connections (Ready to Receive)"
        }
    }

    // Auto-sending logic
    private fun stopAutoSending() {
        Log.d(TAG, "stopAutoSending() called. Current isAutoSending: $isAutoSending")
        if (isAutoSending) {
            isAutoSending = false
            autoSendHandler.removeCallbacks(autoSendRunnable)
            Log.d(TAG, "stopAutoSending: Auto-sending stopped, callbacks removed.")
        }
    }

    /**
     * Enhanced ECM data generator with more realistic values
     */
    private fun generateECMData(): String {
        val random = Random()
        val timestamp = System.currentTimeMillis()

        // Generate more realistic VIN
        val vinChars = "123456789ABCDEFGHJKLMNPRSTUVWXYZ" // Excluding I, O, Q
        val vin = (1..17).map { vinChars[random.nextInt(vinChars.length)] }.joinToString("")

        val odometer = random.nextInt(500000) + 10000 // 10,000 - 509,999 km
        val rpm = when {
            random.nextDouble() < 0.3 -> 0 // 30% chance engine is off
            random.nextDouble() < 0.7 -> random.nextInt(1000) + 600 // Idle range
            else -> random.nextInt(3000) + 1500 // Driving range
        }

        val engineHours = String.format("%.1f", random.nextDouble() * 5000 + 100)
        val engineState = if (rpm > 0) "RUNNING" else "OFF"
        val fuelLevel = random.nextInt(101) // 0-100%
        val coolantTemp = if (engineState == "RUNNING") {
            random.nextInt(40) + 80 // 80-119°C when running
        } else {
            random.nextInt(30) + 20 // 20-49°C when off
        }

        val speed = if (rpm > 1200) random.nextInt(80) else 0 // km/h
        val throttlePos = if (rpm > 800) random.nextInt(100) else 0 // %

        return "TS:$timestamp,VIN:$vin,ODO:$odometer,RPM:$rpm,EH:$engineHours,State:$engineState,Fuel:$fuelLevel%,Temp:$coolantTemp°C,Speed:${speed}km/h,Throttle:$throttlePos%"
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}