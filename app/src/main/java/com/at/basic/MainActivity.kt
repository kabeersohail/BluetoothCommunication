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

    private lateinit var bluetoothService: BluetoothService

    private val autoSendHandler = Handler(Looper.getMainLooper())
    private var isAutoSending = false
    private var isReceiving = false
    private val sendFrequencyMillis = 1000L // Hardcoded to 1 second for simplicity

    // Runnable for automatic data sending
    private val autoSendRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "autoSendRunnable: run() called. isAutoSending: $isAutoSending, BluetoothService state: ${bluetoothService.currentState}")
            if (bluetoothService.currentState == BluetoothService.STATE_CONNECTED && isAutoSending) {
                val ecmData = generateECMData()
                Log.d(TAG, "autoSendRunnable: Generated ECM data: $ecmData")
                bluetoothService.write(ecmData.toByteArray())
                messagesTextView.append("\nMe (Sent): $ecmData")
            } else {
                Log.w(TAG, "autoSendRunnable: Not sending data. Not connected or auto-sending stopped. Current state: ${bluetoothService.currentState}")
                stopAutoSending() // Ensures the loop breaks if state changes
            }
            if (isAutoSending) { // Only reschedule if still in auto-sending mode
                Log.d(TAG, "autoSendRunnable: Rescheduling in ${sendFrequencyMillis}ms.")
                autoSendHandler.postDelayed(this, sendFrequencyMillis)
            } else {
                Log.d(TAG, "autoSendRunnable: isAutoSending is false, not rescheduling.")
            }
        }
    }

    private val mainHandler: Handler = @SuppressLint("HandlerLeak") // Suppress warning about handler leak
    object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            Log.d(TAG, "mainHandler: handleMessage() called for what: ${msg.what}")
            when (msg.what) {
                MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    val readMessage = String(readBuf, 0, msg.arg1) // msg.arg1 contains numBytes
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

    // Activity Result Launcher for permissions
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

    // Activity Result Launcher for enabling Bluetooth
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

        statusTextView = findViewById(R.id.statusTextView)
        messagesTextView = findViewById(R.id.messagesTextView)
        receiveButton = findViewById(R.id.receiveButton)
        sendButton = findViewById(R.id.sendButton)

        bluetoothService = BluetoothService(mainHandler)

        requestPermissions()

        receiveButton.setOnClickListener {
            Log.d(TAG, "receiveButton clicked.")
            if (bluetoothAdapter?.isEnabled == true) {
                // Always ensure auto-sending is off and stop its runnable if switching roles
                isAutoSending = false
                stopAutoSending()
                Log.d(TAG, "receiveButton: Ensuring auto-sending is off. isAutoSending: $isAutoSending.")

                isReceiving = true // Set the role flag for receiving
                Log.d(TAG, "receiveButton: Setting isReceiving=true.")

                bluetoothService.stop() // Stop any previous connection/sending
                bluetoothService.start() // Start as server (listening)
                statusTextView.text = "Status: Waiting to Receive..."
                messagesTextView.text = "" // Clear old messages for a fresh session
            } else {
                Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "receiveButton: Bluetooth is not enabled.")
            }
        }

        sendButton.setOnClickListener {
            Log.d(TAG, "sendButton clicked.")
            if (bluetoothAdapter?.isEnabled == true) {
                // Always ensure receiving is off and stop any previous auto-sending attempts
                isReceiving = false
                stopAutoSending() // This will set isAutoSending = false as intended for *previous* state.

                Log.d(TAG, "sendButton: Ensuring receiving is off. isReceiving: $isReceiving.")

                bluetoothService.stop() // Stop any previous connection/receiving
                messagesTextView.text = "" // Clear old messages for a fresh session

                // Now set isAutoSending to true, AFTER any previous state is cleared by stopAutoSending and bluetoothService.stop
                isAutoSending = true
                Log.d(TAG, "sendButton: Setting isAutoSending=true for current action.")

                @SuppressLint("MissingPermission") // Permissions handled via requestBluetoothPermissions
                val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
                if (!pairedDevices.isNullOrEmpty()) {
                    Log.d(TAG, "sendButton: Found paired devices. Connecting to first: ${pairedDevices.first().name}")
                    val deviceToConnect = pairedDevices.first()
                    bluetoothService.connect(deviceToConnect) // Start as client (connecting)
                    statusTextView.text = "Status: Attempting to Send..."
                } else {
                    Toast.makeText(this, "No paired devices found. Pair devices first.", Toast.LENGTH_LONG).show()
                    Log.w(TAG, "sendButton: No paired devices found. Cannot connect to send.")
                    // If no devices, we can't send, so revert isAutoSending
                    isAutoSending = false
                }
            } else {
                Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "sendButton: Bluetooth is not enabled.")
            }
        }
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
            Log.d(TAG, "requestPermissions: Launching permission request for ${neededPermissions.size} permissions.")
            requestBluetoothPermissions.launch(neededPermissions)
        } else {
            Log.d(TAG, "requestPermissions: All permissions already granted. Checking Bluetooth enabled.")
            checkBluetoothEnabled()
        }
    }

    @SuppressLint("MissingPermission") // Permissions handled via requestBluetoothPermissions
    private fun checkBluetoothEnabled() {
        Log.d(TAG, "checkBluetoothEnabled() called.")
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Device doesn't support Bluetooth", Toast.LENGTH_LONG).show()
            Log.e(TAG, "checkBluetoothEnabled: Device does not support Bluetooth.")
            finish()
        } else if (!bluetoothAdapter.isEnabled) {
            Log.d(TAG, "checkBluetoothEnabled: Bluetooth is disabled. Requesting enable.")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            Log.d(TAG, "checkBluetoothEnabled: Bluetooth is enabled.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called.")
        bluetoothService.stop()
        stopAutoSending()
    }

    // region BluetoothCommunicationListener Callbacks (Ensured to run on UI thread via mainHandler)
    override fun onMessageReceived(message: String) {
        Log.d(TAG, "onMessageReceived() called. Message: '$message'")
        messagesTextView.append("\nOther (Received): $message")
    }

    override fun onConnected(deviceName: String, deviceAddress: String) {
        Log.d(TAG, "onConnected() called. Device: $deviceName. isAutoSending: $isAutoSending, isReceiving: $isReceiving")
        if (isAutoSending) {
            statusTextView.text = "Status: Sending to $deviceName"
            Log.d(TAG, "onConnected: isAutoSending is TRUE. Starting auto-sending as sender.")
            // Removed the redundant check inside startAutoSending()
            // This is the direct call to post the runnable now.
            autoSendHandler.post(autoSendRunnable)
        } else if (isReceiving) {
            statusTextView.text = "Status: Receiving from $deviceName"
            Log.d(TAG, "onConnected: isReceiving is TRUE. Ready to receive as receiver.")
        }
        Toast.makeText(this, "Connected to $deviceName!", Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionFailed(error: String) {
        Log.e(TAG, "onConnectionFailed() called. Error: $error")
        statusTextView.text = "Connection Failed: $error"
        Toast.makeText(this, "Connection Failed: $error", Toast.LENGTH_LONG).show()
        // Reset all relevant flags and stop any ongoing processes
        stopAutoSending() // This also sets isAutoSending = false
        isReceiving = false
        Log.d(TAG, "onConnectionFailed: Flags reset: isAutoSending=$isAutoSending, isReceiving=$isReceiving")
    }

    override fun onDisconnected() {
        Log.d(TAG, "onDisconnected() called.")
        statusTextView.text = "Disconnected."
        Toast.makeText(this, "Bluetooth Disconnected.", Toast.LENGTH_SHORT).show()
        // Reset all relevant flags and stop any ongoing processes
        stopAutoSending() // This also sets isAutoSending = false
        isReceiving = false
        Log.d(TAG, "onDisconnected: Flags reset: isAutoSending=$isAutoSending, isReceiving=$isReceiving")
    }

    override fun onConnecting(deviceName: String, deviceAddress: String) {
        Log.d(TAG, "onConnecting() called. Device: $deviceName")
        statusTextView.text = "Status: Connecting to $deviceName..."
    }

    override fun onListenStarted() {
        Log.d(TAG, "onListenStarted() called.")
        // Only update status if we are in explicit receive mode
        if (isReceiving) {
            statusTextView.text = "Status: Listening for connections (Ready to Receive)"
            Log.d(TAG, "onListenStarted: Set to Ready to Receive status.")
        }
    }
    // endregion

    // region Automatic Data Sending Logic

    // Removed the outer 'if (!isAutoSending)' check
    private fun startAutoSending() {
        Log.d(TAG, "startAutoSending() called. Directly posting autoSendRunnable.")
        // The isAutoSending flag is set in the button click listener,
        // and checked within the runnable itself for rescheduling.
        // This method's sole purpose now is to kick off the *first* runnable post.
        autoSendHandler.post(autoSendRunnable)
    }

    private fun stopAutoSending() {
        Log.d(TAG, "stopAutoSending() called. Current isAutoSending: $isAutoSending")
        if (isAutoSending) {
            isAutoSending = false // Set flag to false
            autoSendHandler.removeCallbacks(autoSendRunnable) // Remove any pending callbacks
            Log.d(TAG, "stopAutoSending: Auto-sending stopped, callbacks removed.")
        } else {
            Log.d(TAG, "stopAutoSending: Auto-sending was not active.")
        }
    }

    /**
     * Generates simulated ECM data.
     */
    private fun generateECMData(): String {
        val random = Random()
        val vinChars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val vin = (1..17).map { vinChars[random.nextInt(vinChars.length)] }.joinToString("")

        val odometer = random.nextInt(1000000) // 0 - 999,999 km
        val rpm = random.nextInt(4500) + 500 // 500 - 4999 RPM
        val engineHours = String.format("%.2f", random.nextDouble() * 2000) // 0.00 - 1999.99 hours
        val engineState = if (random.nextBoolean()) "RUNNING" else "OFF"
        val fuelLevel = random.nextInt(101) // 0-100%
        val coolantTemp = random.nextInt(80) + 70 // 70-149 Celsius

        return "VIN:$vin,ODO:$odometer,RPM:$rpm,EH:$engineHours,State:$engineState,Fuel:$fuelLevel%,Temp:$coolantTemp°C"
    }

    companion object {
        private const val TAG = "MainActivity" // Tag for MainActivity logs
    }
    // endregion
}