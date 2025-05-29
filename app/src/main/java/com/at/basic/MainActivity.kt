package com.at.basic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Random

class MainActivity : AppCompatActivity(), BluetoothCommunicationListener {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // UI Components
    private lateinit var statusTextView: TextView
    private lateinit var messagesTextView: TextView
    private lateinit var receiveButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var scanButton: Button
    private lateinit var frequencySeekBar: SeekBar
    private lateinit var frequencyLabel: TextView
    private lateinit var toggleScannerButton: Button
    private lateinit var deviceScannerLayout: LinearLayout
    private lateinit var deviceListView: ListView

    // Input Fields
    private lateinit var vinEditText: EditText
    private lateinit var rpmEditText: EditText
    private lateinit var engineHoursEditText: EditText
    private lateinit var odometerEditText: EditText
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedDynamicCheckBox: CheckBox
    private lateinit var speedValueText: TextView

    // Dashboard Display
    private lateinit var dashSpeedText: TextView
    private lateinit var dashRpmText: TextView
    private lateinit var dashEngineHoursText: TextView
    private lateinit var dashOdometerText: TextView
    private lateinit var dashEngineStateText: TextView
    private lateinit var dashVinText: TextView

    private lateinit var bluetoothService: BluetoothService

    private val autoSendHandler = Handler(Looper.getMainLooper())
    private var isAutoSending = false
    private var isReceiving = false
    private var isScanning = false
    private var sendFrequencyMillis = 1000L // Default 1 second
    private var messageCount = 0

    // Device discovery
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private var deviceSelectionDialog: AlertDialog? = null

    // User-configured initial values
    private var userVin: String = ""
    private var userInitialRpm: Int = 0
    private var userInitialSpeed: Int = 0
    private var userInitialEngineHours: Double = 0.0
    private var userInitialOdometer: Double = 0.0
    private var isSpeedDynamic: Boolean = true

    // Runnable for automatic data sending
    private val autoSendRunnable = object : Runnable {
        override fun run() {
            Log.d(
                TAG,
                "autoSendRunnable: run() called. isAutoSending: $isAutoSending, BluetoothService state: ${bluetoothService.currentState}"
            )
            if (bluetoothService.currentState == BluetoothService.STATE_CONNECTED && isAutoSending) {
                val ecmData = generateECMData()
                Log.d(TAG, "autoSendRunnable: Generated ECM data: $ecmData")
                bluetoothService.write(ecmData.toByteArray())
                messageCount++
                messagesTextView.append("\n[${messageCount}] ECM Emitted: $ecmData")

                // Update dashboard
                updateDashboard()

                // Auto-scroll to bottom
                runOnUiThread {
                    val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
                    scrollView.post { scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
                }
            } else {
                Log.w(
                    TAG,
                    "autoSendRunnable: Not sending data. Not connected or auto-sending stopped. Current state: ${bluetoothService.currentState}"
                )
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

    // BroadcastReceiver for device discovery
    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let {
                        Log.d(TAG, "Device found: ${it.name ?: "Unknown"} - ${it.address}")
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                            val deviceName = it.name ?: "Unknown Device"
                            val deviceInfo = "$deviceName\n${it.address}"
                            deviceListAdapter.add(deviceInfo)
                            deviceListAdapter.notifyDataSetChanged()
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Device discovery finished")
                    isScanning = false
                    updateButtonStates()
                    statusTextView.text =
                        "Status: Discovery completed (${discoveredDevices.size} devices found)"
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.d(TAG, "Device discovery started")
                    statusTextView.text = "Status: Scanning for devices..."
                }
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
                    val deviceAddress =
                        if (msg.data != null) msg.data.getString("device_address") else ""
                    Log.d(
                        TAG,
                        "mainHandler: MESSAGE_CONNECTED received. Device: $deviceName ($deviceAddress)"
                    )
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
                    val deviceAddress =
                        if (msg.data != null) msg.data.getString("device_address") else ""
                    Log.d(
                        TAG,
                        "mainHandler: MESSAGE_CONNECTING received. Device: $deviceName ($deviceAddress)"
                    )
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
    private val requestBluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            Log.d(TAG, "requestBluetoothPermissions: Permissions result. Granted: $granted")
            if (granted) {
                Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
                checkBluetoothEnabled()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth permissions denied. App functionality limited.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(
                TAG,
                "enableBluetoothLauncher: Bluetooth enable request result. Result code: ${result.resultCode}"
            )
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth not enabled. App functionality limited.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val requestDiscoverabilityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(
                TAG,
                "requestDiscoverabilityLauncher: Discoverability result. Result code: ${result.resultCode}"
            )
            if (result.resultCode > 0) {
                Toast.makeText(
                    this,
                    "Device is now discoverable as 'ECM Emitter' for ${result.resultCode} seconds",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "Device discoverability was denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate() called.")

        initializeViews()
        bluetoothService = BluetoothService(mainHandler)
        setupDeviceDiscovery()
        requestPermissions()
        setupEventListeners()
        registerReceiver()
        initializeUserValues()
    }

    private fun initializeViews() {
        // Status and controls
        statusTextView = findViewById(R.id.statusTextView)
        messagesTextView = findViewById(R.id.messagesTextView)
        receiveButton = findViewById(R.id.receiveButton)
        sendButton = findViewById(R.id.sendButton)
        stopButton = findViewById(R.id.stopButton)
        scanButton = findViewById(R.id.scanButton)
        frequencySeekBar = findViewById(R.id.frequencySeekBar)
        frequencyLabel = findViewById(R.id.frequencyLabel)
        toggleScannerButton = findViewById(R.id.toggleScannerButton)
        deviceScannerLayout = findViewById(R.id.deviceScannerLayout)
        deviceListView = findViewById(R.id.deviceListView)

        // Input fields
        vinEditText = findViewById(R.id.vinEditText)
        rpmEditText = findViewById(R.id.rpmEditText)
        engineHoursEditText = findViewById(R.id.engineHoursEditText)
        odometerEditText = findViewById(R.id.odometerEditText)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        speedDynamicCheckBox = findViewById(R.id.speedDynamicCheckBox)
        speedValueText = findViewById(R.id.speedValueText)

        // Dashboard
        dashSpeedText = findViewById(R.id.dashSpeedText)
        dashRpmText = findViewById(R.id.dashRpmText)
        dashEngineHoursText = findViewById(R.id.dashEngineHoursText)
        dashOdometerText = findViewById(R.id.dashOdometerText)
        dashEngineStateText = findViewById(R.id.dashEngineStateText)
        dashVinText = findViewById(R.id.dashVinText)

        // Initialize frequency controls
        updateFrequencyLabel()
    }

    private fun initializeUserValues() {
        // Set default values
        userVin = generateVIN()
        userInitialRpm = 800
        userInitialSpeed = 0
        userInitialEngineHours = 1500.0
        userInitialOdometer = 125000.0

        // Update UI with defaults
        vinEditText.setText(userVin)
        rpmEditText.setText(userInitialRpm.toString())
        engineHoursEditText.setText(userInitialEngineHours.toString())
        odometerEditText.setText(userInitialOdometer.toInt().toString())
        speedSeekBar.progress = userInitialSpeed
        speedValueText.text = "$userInitialSpeed km/h"

        // Initialize runtime values
        vin = userVin
        currentRPM = userInitialRpm
        currentSpeed = userInitialSpeed
        engineHours = userInitialEngineHours
        odometer = userInitialOdometer

        updateDashboard()
    }

    private fun setupDeviceDiscovery() {
        deviceListAdapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        deviceListView.adapter = deviceListAdapter

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = discoveredDevices[position]
            connectToDevice(selectedDevice)
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        }
        registerReceiver(receiver, filter)
    }

    private fun setupEventListeners() {
        receiveButton.setOnClickListener {
            Log.d(TAG, "receiveButton clicked.")
            readUserInputValues()
            startReceiveMode()
        }

        sendButton.setOnClickListener {
            Log.d(TAG, "sendButton clicked.")
            showScanDialog()
        }

        scanButton.setOnClickListener {
            Log.d(TAG, "scanButton clicked.")
            startDeviceDiscovery()
        }

        stopButton.setOnClickListener {
            Log.d(TAG, "stopButton clicked.")
            stopAllOperations()
        }

        toggleScannerButton.setOnClickListener {
            toggleDeviceScanner()
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

        speedDynamicCheckBox.setOnCheckedChangeListener { _, isChecked ->
            isSpeedDynamic = isChecked
            speedSeekBar.isEnabled = !isChecked
            if (!isChecked) {
                currentSpeed = speedSeekBar.progress
                speedValueText.text = "$currentSpeed km/h"
            }
        }

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isSpeedDynamic) {
                    currentSpeed = progress
                    speedValueText.text = "$currentSpeed km/h"
                    updateDashboard()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun readUserInputValues() {
        try {
            userVin = vinEditText.text.toString().takeIf { it.isNotEmpty() } ?: generateVIN()
            userInitialRpm = rpmEditText.text.toString().toIntOrNull() ?: 800
            userInitialEngineHours = engineHoursEditText.text.toString().toDoubleOrNull() ?: 1500.0
            userInitialOdometer = odometerEditText.text.toString().toDoubleOrNull() ?: 125000.0

            // Reset runtime values with user input
            vin = userVin
            currentRPM = userInitialRpm
            engineHours = userInitialEngineHours
            odometer = userInitialOdometer

            if (!isSpeedDynamic) {
                currentSpeed = speedSeekBar.progress
            }

            updateDashboard()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid input values. Using defaults.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDeviceScanner() {
        if (deviceScannerLayout.visibility == View.GONE) {
            deviceScannerLayout.visibility = View.VISIBLE
            toggleScannerButton.text = "🔼 Hide Device Scanner"
        } else {
            deviceScannerLayout.visibility = View.GONE
            toggleScannerButton.text = "🔍 Show Device Scanner"
        }
    }

    private fun updateDashboard() {
        runOnUiThread {
            dashSpeedText.text = currentSpeed.toString()
            dashRpmText.text = currentRPM.toString()
            dashEngineHoursText.text = String.format("%.1f", engineHours)
            dashOdometerText.text = odometer.toInt().toString()
            dashEngineStateText.text = if (isEngineRunning) "RUNNING" else "OFF"
            dashEngineStateText.setTextColor(
                if (isEngineRunning)
                    ContextCompat.getColor(this, android.R.color.holo_green_light)
                else
                    ContextCompat.getColor(this, android.R.color.holo_red_light)
            )
            dashVinText.text = vin

            if (!isSpeedDynamic) {
                speedValueText.text = "$currentSpeed km/h"
            }
        }
    }

    private fun updateFrequencyLabel() {
        val frequency = 1000.0 / sendFrequencyMillis
        frequencyLabel.text =
            "Data Frequency: ${String.format("%.1f", frequency)} Hz (${sendFrequencyMillis}ms)"
    }

    @SuppressLint("MissingPermission")
    private fun startReceiveMode() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            return
        }

        stopAllOperations()
        isReceiving = false  // Server doesn't "receive" - it emits!
        isAutoSending = true  // Server auto-sends ECM data
        messageCount = 0

        // Make device discoverable as "ECM Emitter"
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // 5 minutes
        }
        requestDiscoverabilityLauncher.launch(discoverableIntent)

        bluetoothService.start()
        statusTextView.text = "Status: ECM Emitter - Waiting for Client Connection..."
        messagesTextView.text =
            "=== ECM Emitter Mode Started ===\nDevice is discoverable as 'ECM Emitter'\nWaiting for client to connect...\nWill start emitting ECM data once connected.\n"
        updateButtonStates()
    }

    private fun showScanDialog() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            return
        }

        // Clear previous discoveries
        discoveredDevices.clear()
        deviceListAdapter.clear()

        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val listView = ListView(this).apply {
            adapter = deviceListAdapter
        }

        deviceSelectionDialog = AlertDialog.Builder(this)
            .setTitle("Select ECM Emitter Device")
            .setView(listView)
            .setPositiveButton("Scan") { _, _ ->
                startDeviceDiscovery()
            }
            .setNegativeButton("Cancel", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = discoveredDevices[position]
            deviceSelectionDialog?.dismiss()
            connectToDevice(selectedDevice)
        }

        deviceSelectionDialog?.show()

        // Auto-start scanning
        startDeviceDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun startDeviceDiscovery() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isScanning) {
            bluetoothAdapter?.cancelDiscovery()
        }

        discoveredDevices.clear()
        deviceListAdapter.clear()
        isScanning = true
        updateButtonStates()

        Log.d(TAG, "Starting device discovery...")
        val discoveryStarted = bluetoothAdapter?.startDiscovery() ?: false

        if (discoveryStarted) {
            statusTextView.text = "Status: Scanning for ECM Emitter devices..."
            Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to start device discovery", Toast.LENGTH_SHORT).show()
            isScanning = false
            updateButtonStates()
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        stopAllOperations()
        messageCount = 0
        messagesTextView.text =
            "=== Client Mode Started ===\nConnecting to ECM Emitter: ${device.name ?: "Unknown Device"}...\nWaiting to receive ECM data...\n"

        isAutoSending = false  // Client doesn't send, it receives
        isReceiving = true     // Client receives ECM data
        bluetoothService.connect(device)
        statusTextView.text =
            "Status: Connecting to ECM Emitter: ${device.name ?: "Unknown Device"}..."
        updateButtonStates()
    }

    private fun stopAllOperations() {
        isAutoSending = false
        isReceiving = false
        stopAutoSending()

        // Stop discovery if running
        if (isScanning) {
            bluetoothAdapter?.cancelDiscovery()
            isScanning = false
        }

        bluetoothService.stop()
        statusTextView.text = "Status: Stopped"
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val isActive = isAutoSending || isReceiving || isScanning
        receiveButton.isEnabled = !isActive
        sendButton.isEnabled = !isActive
        scanButton.isEnabled = !isActive && !isAutoSending  // Can't scan when emitting
        stopButton.isEnabled = isActive
        frequencySeekBar.isEnabled = !isActive

        // Disable input fields when active
        vinEditText.isEnabled = !isActive
        rpmEditText.isEnabled = !isActive
        engineHoursEditText.isEnabled = !isActive
        odometerEditText.isEnabled = !isActive
        speedDynamicCheckBox.isEnabled = !isActive
        speedSeekBar.isEnabled = !isActive && !isSpeedDynamic
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
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        stopAllOperations()
    }

    // BluetoothCommunicationListener Callbacks
    override fun onMessageReceived(message: String) {
        Log.d(TAG, "onMessageReceived() called. Message: '$message'")
        messageCount++
        messagesTextView.append("\n[${messageCount}] ECM Data Received: $message")

        // Parse and update dashboard with received data
        parseReceivedData(message)

        // Auto-scroll to bottom
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
        scrollView.post { scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
    }

    private fun parseReceivedData(data: String) {
        try {
            val parts = data.split(",")
            parts.forEach { part ->
                val keyValue = part.split(":")
                if (keyValue.size == 2) {
                    when (keyValue[0]) {
                        "VIN" -> {
                            vin = keyValue[1]
                            dashVinText.text = vin
                        }

                        "RPM" -> {
                            currentRPM = keyValue[1].toIntOrNull() ?: 0
                            dashRpmText.text = currentRPM.toString()
                        }

                        "Speed" -> {
                            val speedValue = keyValue[1].replace("km/h", "").toIntOrNull() ?: 0
                            currentSpeed = speedValue
                            dashSpeedText.text = currentSpeed.toString()
                        }

                        "ODO" -> {
                            odometer = keyValue[1].toDoubleOrNull() ?: 0.0
                            dashOdometerText.text = odometer.toInt().toString()
                        }

                        "EH" -> {
                            engineHours = keyValue[1].toDoubleOrNull() ?: 0.0
                            dashEngineHoursText.text = String.format("%.1f", engineHours)
                        }

                        "State" -> {
                            isEngineRunning = keyValue[1] == "RUNNING"
                            dashEngineStateText.text = if (isEngineRunning) "RUNNING" else "OFF"
                            dashEngineStateText.setTextColor(
                                if (isEngineRunning)
                                    ContextCompat.getColor(this, android.R.color.holo_green_light)
                                else
                                    ContextCompat.getColor(this, android.R.color.holo_red_light)
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing received data: $data", e)
        }
    }

    override fun onConnected(deviceName: String, deviceAddress: String) {
        Log.d(TAG, "onConnected() called. Device: $deviceName")
        if (isAutoSending) {
            // Server mode - start emitting ECM data
            statusTextView.text = "Status: ECM Emitter - Sending data to $deviceName"
            autoSendHandler.post(autoSendRunnable)
            Toast.makeText(
                this,
                "Client connected! Starting ECM data emission...",
                Toast.LENGTH_SHORT
            ).show()
        } else if (isReceiving) {
            // Client mode - ready to receive ECM data
            statusTextView.text = "Status: Client - Receiving ECM data from $deviceName"
            Toast.makeText(this, "Connected to ECM Emitter! Receiving data...", Toast.LENGTH_SHORT)
                .show()
        }
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
        if (isAutoSending) {
            statusTextView.text = "Status: ECM Emitter - Client connecting..."
        } else {
            statusTextView.text = "Status: Connecting to ECM Emitter: $deviceName..."
        }
    }

    override fun onListenStarted() {
        Log.d(TAG, "onListenStarted() called.")
        if (isAutoSending) {  // Changed from isReceiving to isAutoSending
            statusTextView.text = "Status: ECM Emitter - Ready to Accept Client Connections"
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

    private val random = Random()

    // Persistent values that should remain consistent or change gradually
    private var vin: String = generateVIN()
    private var odometer: Double =
        (random.nextInt(400000) + 50000).toDouble() // Start with realistic mileage
    private var engineHours: Double =
        odometer / 60.0 + random.nextDouble() * 1000 // Roughly correlated with odometer
    private var currentSpeed: Int = 0
    private var currentRPM: Int = 0
    private var lastUpdateTime: Long = System.currentTimeMillis()

    // Engine state tracking
    private var isEngineRunning: Boolean = false
    private var engineStartTime: Long = 0

    private fun generateVIN(): String {
        val vinChars = "123456789ABCDEFGHJKLMNPRSTUVWXYZ" // Excluding I, O, Q
        return (1..17).map { vinChars[random.nextInt(vinChars.length)] }.joinToString("")
    }

    fun generateECMData(): String {
        val currentTime = System.currentTimeMillis()
        val timeDeltaSeconds = (currentTime - lastUpdateTime) / 1000.0
        lastUpdateTime = currentTime

        // Use dynamic speed or manual speed based on checkbox
        if (isSpeedDynamic) {
            // Simulate engine state changes (randomly start/stop engine)
            if (!isEngineRunning && random.nextDouble() < 0.05) { // 5% chance to start
                isEngineRunning = true
                engineStartTime = currentTime
            } else if (isEngineRunning && random.nextDouble() < 0.02) { // 2% chance to stop
                isEngineRunning = false
                currentSpeed = 0
            }

            // Generate realistic speed changes (gradual acceleration/deceleration)
            if (isEngineRunning) {
                val speedChange = when {
                    random.nextDouble() < 0.3 -> 0 // 30% chance no change
                    random.nextDouble() < 0.6 -> random.nextInt(5) + 1 // Accelerate
                    else -> -(random.nextInt(5) + 1) // Decelerate
                }
                currentSpeed = (currentSpeed + speedChange).coerceIn(0, 120)
            } else {
                currentSpeed = 0
            }
        } else {
            // Use manual speed from seekbar
            isEngineRunning = currentSpeed > 0
            if (isEngineRunning && engineStartTime == 0L) {
                engineStartTime = currentTime
            }
        }

        // Generate realistic RPM based on engine state and speed
        if (isEngineRunning) {
            when {
                currentSpeed == 0 -> {
                    // Idle RPM with some variation
                    currentRPM = (700..900).random() + random.nextInt(100)
                }

                currentSpeed > 0 -> {
                    // RPM roughly correlates with speed, but add gear simulation
                    val baseRPM = (currentSpeed * 35) + (600..800).random()
                    currentRPM = (baseRPM + random.nextInt(300) - 150).coerceIn(800, 4000)
                }
            }
        } else {
            currentRPM = 0
        }

        // Update odometer based on speed and time
        if (currentSpeed > 0 && timeDeltaSeconds > 0) {
            val distanceKm = (currentSpeed * timeDeltaSeconds) / 3600.0 // Convert to km
            odometer += distanceKm
        }

        // Update engine hours
        if (isEngineRunning && timeDeltaSeconds > 0) {
            engineHours += timeDeltaSeconds / 3600.0 // Convert seconds to hours
        }

        // Generate other realistic values
        val engineState = if (isEngineRunning) "RUNNING" else "OFF"

        val fuelLevel = random.nextInt(101) // This can vary independently

        val coolantTemp = if (isEngineRunning) {
            // Temperature gradually increases when running
            val runningTime = (currentTime - engineStartTime) / 1000.0
            val baseTemp = 85 + (runningTime / 60.0 * 2).coerceAtMost(25.0) // Warm up over time
            (baseTemp + random.nextInt(10) - 5).toInt().coerceIn(80, 115)
        } else {
            random.nextInt(25) + 20 // Ambient temperature when off
        }

        val throttlePos = when {
            !isEngineRunning -> 0
            currentSpeed == 0 -> random.nextInt(5) // Minimal throttle at idle
            else -> {
                // Throttle roughly correlates with acceleration and speed
                val baseThrottle = (currentSpeed * 0.8).toInt()
                (baseThrottle + random.nextInt(20) - 10).coerceIn(5, 85)
            }
        }

        return "TS:$currentTime,VIN:$vin,ODO:${odometer.toInt()},RPM:$currentRPM,EH:${
            String.format(
                "%.1f",
                engineHours
            )
        },State:$engineState,Fuel:$fuelLevel%,Temp:$coolantTemp°C,Speed:${currentSpeed}km/h,Throttle:$throttlePos%"
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}