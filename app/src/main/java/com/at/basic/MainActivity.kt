package com.at.basic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import androidx.core.app.ActivityCompat
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
    private lateinit var unitToggleButton: Button

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

    // Real-time value tracking
    private var realTimeSpeedOverride: Int? = null
    private var realTimeRpmOverride: Int? = null

    // Flags to prevent auto-updates when user is editing
    private var isUserEditingOdometer = false
    private var isUserEditingEngineHours = false
    private var isUserEditingRpm = false
    private var isUserEditingVin = false

    // Unit system tracking
    private var isMetricSystem = true // true = KMph/KM, false = MPH/Miles

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
                setBluetoothDeviceName(this, "ECM emitter")
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
        unitToggleButton = findViewById(R.id.unitToggleButton)

        // Dashboard
        dashSpeedText = findViewById(R.id.dashSpeedText)
        dashRpmText = findViewById(R.id.dashRpmText)
        dashEngineHoursText = findViewById(R.id.dashEngineHoursText)
        dashOdometerText = findViewById(R.id.dashOdometerText)
        dashEngineStateText = findViewById(R.id.dashEngineStateText)
        dashVinText = findViewById(R.id.dashVinText)

        // Initialize frequency controls
        updateFrequencyLabel()

        // Initialize unit toggle button
        updateUnitDisplay()
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
        updateSpeedDisplay()

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

        unitToggleButton.setOnClickListener {
            toggleUnits()
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
                realTimeSpeedOverride = currentSpeed
                updateSpeedDisplay()
                updateDashboard()
            } else {
                realTimeSpeedOverride = null
            }
        }

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Always allow real-time speed changes
                    currentSpeed = progress
                    realTimeSpeedOverride = progress
                    updateSpeedDisplay()

                    // Calculate corresponding RPM based on speed
                    calculateRpmFromSpeed(progress)

                    updateDashboard()

                    Log.d(TAG, "Real-time speed change: $progress ${getSpeedUnit()}, calculated RPM: $currentRPM")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Real-time RPM editing with TextWatcher
        rpmEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isUserEditingRpm) return
                val newRpm = s.toString().toIntOrNull()
                if (newRpm != null && newRpm >= 0) {
                    realTimeRpmOverride = newRpm
                    currentRPM = newRpm
                    updateDashboard()
                    Log.d(TAG, "Real-time RPM change: $newRpm")
                }
            }
        })

        rpmEditText.setOnFocusChangeListener { _, hasFocus ->
            isUserEditingRpm = hasFocus
            if (!hasFocus) {
                // Final validation when focus is lost
                val newRpm = rpmEditText.text.toString().toIntOrNull()
                if (newRpm != null && newRpm >= 0) {
                    realTimeRpmOverride = newRpm
                    currentRPM = newRpm
                    updateDashboard()
                }
            }
        }

        // Real-time VIN editing with TextWatcher
        vinEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isUserEditingVin) return
                val newVin = s.toString()
                if (newVin.isNotEmpty()) {
                    vin = newVin
                    userVin = newVin
                    updateDashboard()
                    Log.d(TAG, "Real-time VIN change: $newVin")
                }
            }
        })

        vinEditText.setOnFocusChangeListener { _, hasFocus ->
            isUserEditingVin = hasFocus
        }

        // Real-time Engine Hours editing with TextWatcher
        engineHoursEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isUserEditingEngineHours) return
                val newEngineHours = s.toString().toDoubleOrNull()
                if (newEngineHours != null && newEngineHours >= 0) {
                    engineHours = newEngineHours
                    updateDashboard()
                    Log.d(TAG, "Real-time Engine Hours change: $newEngineHours")
                }
            }
        })

        engineHoursEditText.setOnFocusChangeListener { _, hasFocus ->
            isUserEditingEngineHours = hasFocus
        }

        // Real-time Odometer editing with TextWatcher
        odometerEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isUserEditingOdometer) return
                val newOdometer = s.toString().toDoubleOrNull()
                if (newOdometer != null && newOdometer >= 0) {
                    odometer = newOdometer
                    updateDashboard()
                    Log.d(TAG, "Real-time Odometer change: $newOdometer")
                }
            }
        })

        odometerEditText.setOnFocusChangeListener { _, hasFocus ->
            isUserEditingOdometer = hasFocus
        }
    }

    private fun toggleUnits() {
        isMetricSystem = !isMetricSystem

        // Convert current speed
        if (isMetricSystem) {
            // Converting from MPH to KMph
            currentSpeed = (currentSpeed * 1.60934).toInt()
            speedSeekBar.progress = currentSpeed

            // Convert odometer from miles to km
            if (!isUserEditingOdometer) {
                odometer *= 1.60934
                odometerEditText.setText(odometer.toInt().toString())
            }

            Toast.makeText(this, "Switched to Metric (KMph/KM)", Toast.LENGTH_SHORT).show()
        } else {
            // Converting from KMph to MPH
            currentSpeed = (currentSpeed / 1.60934).toInt()
            speedSeekBar.progress = currentSpeed

            // Convert odometer from km to miles
            if (!isUserEditingOdometer) {
                odometer /= 1.60934
                odometerEditText.setText(odometer.toInt().toString())
            }

            Toast.makeText(this, "Switched to Imperial (MPH/Miles)", Toast.LENGTH_SHORT).show()
        }

        realTimeSpeedOverride = currentSpeed
        updateUnitDisplay()
        updateSpeedDisplay()
        updateDashboard()

        Log.d(TAG, "Unit system changed to: ${if (isMetricSystem) "Metric" else "Imperial"}")
    }

    private fun updateUnitDisplay() {
        unitToggleButton.text = if (isMetricSystem) "📏 Metric (KMph)" else "📏 Imperial (MPH)"

        // Update speed seekbar max based on unit system
        speedSeekBar.max = if (isMetricSystem) 200 else 124 // 200 kmph = ~124 mph
    }

    private fun updateSpeedDisplay() {
        val unit = if (isMetricSystem) "km/h" else "mph"
        speedValueText.text = "$currentSpeed $unit"
    }

    private fun getSpeedUnit(): String {
        return if (isMetricSystem) "km/h" else "mph"
    }

    private fun getDistanceUnit(): String {
        return if (isMetricSystem) "km" else "miles"
    }

    private fun calculateRpmFromSpeed(speed: Int) {
        when {
            speed == 0 -> {
                // Engine off or idle
                currentRPM = 0
                realTimeRpmOverride = 0
                isEngineRunning = false
            }
            speed > 0 && speed <= 10 -> {
                // Idle/low speed
                currentRPM = (700..900).random()
                realTimeRpmOverride = currentRPM
                isEngineRunning = true
            }
            speed > 10 -> {
                // Calculate RPM based on speed with realistic gear simulation
                val baseRPM = when {
                    speed <= 30 -> (speed * 80) + 800  // 1st gear
                    speed <= 60 -> (speed * 45) + 1200 // 2nd gear
                    speed <= 90 -> (speed * 30) + 1500 // 3rd gear
                    speed <= 120 -> (speed * 25) + 1800 // 4th gear
                    else -> (speed * 20) + 2000 // 5th gear
                }
                currentRPM = (baseRPM + random.nextInt(200) - 100).coerceIn(800, 6000)
                realTimeRpmOverride = currentRPM
                isEngineRunning = true
            }
        }

        // Update RPM field to show calculated value
        runOnUiThread {
            rpmEditText.setText(currentRPM.toString())
        }
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
                realTimeSpeedOverride = currentSpeed
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
                updateSpeedDisplay()
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
            "=== ECM Emitter Mode Started ===\nDevice is discoverable as 'ECM Emitter'\nWaiting for client to connect...\nWill start emitting ECM data once connected.\n\n✅ REAL-TIME EDITING ENABLED!\nYou can now edit speed, RPM, VIN, and other values while transmitting!"
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

        // Clear real-time overrides and editing flags
        realTimeSpeedOverride = null
        realTimeRpmOverride = null
        isUserEditingOdometer = false
        isUserEditingEngineHours = false
        isUserEditingRpm = false
        isUserEditingVin = false

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

        // Enable input fields for real-time editing when transmitting
        val enableRealTimeEditing = isAutoSending // Allow editing when emitting ECM data
        vinEditText.isEnabled = !isActive || enableRealTimeEditing
        rpmEditText.isEnabled = !isActive || enableRealTimeEditing
        engineHoursEditText.isEnabled = !isActive || enableRealTimeEditing
        odometerEditText.isEnabled = !isActive || enableRealTimeEditing
        speedDynamicCheckBox.isEnabled = !isActive || enableRealTimeEditing
        speedSeekBar.isEnabled = (!isActive && !isSpeedDynamic) || (enableRealTimeEditing && !isSpeedDynamic)
        unitToggleButton.isEnabled = !isActive || enableRealTimeEditing
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
                            val speedValue = keyValue[1].replace("km/h", "").replace("mph", "").toIntOrNull() ?: 0
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

                        "Units" -> {
                            isMetricSystem = keyValue[1] == "Metric"
                            updateUnitDisplay()
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
            statusTextView.text = "Status: ECM Emitter - Sending data to $deviceName (LIVE EDITING ENABLED)"
            autoSendHandler.post(autoSendRunnable)
            Toast.makeText(
                this,
                "Client connected! ECM data emission started. You can edit values in real-time!",
                Toast.LENGTH_LONG
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
            statusTextView.text = "Status: ECM Emitter - Ready to Accept Client Connections (LIVE EDITING READY)"
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
        return "1HGCM82633A004352"
    }

    fun generateECMData(): String {
        val currentTime = System.currentTimeMillis()
        val timeDeltaSeconds = (currentTime - lastUpdateTime) / 1000.0
        lastUpdateTime = currentTime

        // Check for real-time overrides first
        realTimeSpeedOverride?.let { overrideSpeed ->
            currentSpeed = overrideSpeed
            isEngineRunning = overrideSpeed > 0
            if (isEngineRunning && engineStartTime == 0L) {
                engineStartTime = currentTime
            } else if (!isEngineRunning) {
                engineStartTime = 0L
            }
        }

        realTimeRpmOverride?.let { overrideRpm ->
            currentRPM = overrideRpm
        }

        // Use dynamic speed or manual speed based on checkbox (only if no real-time override)
        if (isSpeedDynamic && realTimeSpeedOverride == null) {
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

                // Update the seekbar to reflect dynamic changes
                runOnUiThread {
                    speedSeekBar.progress = currentSpeed
                    updateSpeedDisplay()
                }
            } else {
                currentSpeed = 0
                runOnUiThread {
                    speedSeekBar.progress = 0
                    updateSpeedDisplay()
                }
            }
        }

        // Generate realistic RPM based on engine state and speed (only if no real-time override)
        if (realTimeRpmOverride == null) {
            if (isEngineRunning) {
                when {
                    currentSpeed == 0 -> {
                        // Idle RPM with some variation
                        currentRPM = (700..900).random() + random.nextInt(100)
                    }

                    currentSpeed > 0 -> {
                        // RPM calculation based on speed with gear simulation
                        val baseRPM = when {
                            currentSpeed <= 30 -> (currentSpeed * 80) + 800  // 1st gear
                            currentSpeed <= 60 -> (currentSpeed * 45) + 1200 // 2nd gear
                            currentSpeed <= 90 -> (currentSpeed * 30) + 1500 // 3rd gear
                            currentSpeed <= 120 -> (currentSpeed * 25) + 1800 // 4th gear
                            else -> (currentSpeed * 20) + 2000 // 5th gear
                        }
                        currentRPM = (baseRPM + random.nextInt(300) - 150).coerceIn(800, 6000)
                    }
                }
            } else {
                currentRPM = 0
            }

            // Update RPM field to show calculated value (only if user is not editing)
            if (!isUserEditingRpm) {
                runOnUiThread {
                    rpmEditText.setText(currentRPM.toString())
                }
            }
        }

        // Update odometer based on speed and time
        if (currentSpeed > 0 && timeDeltaSeconds > 0) {
            val distanceKm = (currentSpeed * timeDeltaSeconds) / 3600.0 // Convert to distance units
            val distanceToAdd = if (isMetricSystem) distanceKm else distanceKm / 1.60934 // Convert to miles if imperial
            odometer += distanceToAdd

            // Update odometer field to show new value (only if user is not editing)
            if (!isUserEditingOdometer) {
                runOnUiThread {
                    odometerEditText.setText(odometer.toInt().toString())
                }
            }
        }

        // Update engine hours
        if (isEngineRunning && timeDeltaSeconds > 0) {
            engineHours += timeDeltaSeconds / 3600.0 // Convert seconds to hours

            // Update engine hours field to show new value (only if user is not editing)
            if (!isUserEditingEngineHours) {
                runOnUiThread {
                    engineHoursEditText.setText(String.format("%.1f", engineHours))
                }
            }
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
        },State:$engineState,Fuel:$fuelLevel%,Temp:$coolantTemp°C,Speed:${currentSpeed}${getSpeedUnit()},Throttle:$throttlePos%,Units:${if (isMetricSystem) "Metric" else "Imperial"}"
    }

    fun setBluetoothDeviceName(context: Context, newName: String): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            // Bluetooth is not supported on this device
            return false
        }

        // Check for necessary permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // You'll need to request BLUETOOTH_CONNECT permission at runtime for Android 12+
                // This is a simplified example; handle permission requests appropriately in your app
                return false
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // You'll need to request BLUETOOTH and BLUETOOTH_ADMIN permissions for pre-Android 12
                return false
            }
        }

        // Bluetooth must be enabled to set its name
        if (!bluetoothAdapter.isEnabled) {
            // Optionally, you can request to enable Bluetooth here:
            // val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return false
        }

        // Set the new Bluetooth name
        // Note: The `setName` method is now hidden (@hide) in recent Android versions and may not be directly accessible
        // or may not work reliably across all devices or future Android releases.
        // Official public APIs for changing the *local* Bluetooth name programmatically are limited for security/privacy reasons.
        // The name is typically set by the user in system settings.
        try {
            // This is a common way it was done, but its reliability is not guaranteed.
            val success = bluetoothAdapter.setName(newName)
            return success
        } catch (e: SecurityException) {
            // Handle cases where permission is missing despite checks (should not happen if checks are correct)
            e.printStackTrace()
            return false
        } catch (e: NoSuchMethodError) {
            // Handle cases where the setName method is not available/accessible
            e.printStackTrace()
            // Log or inform the user that the name cannot be changed programmatically on this device.
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}