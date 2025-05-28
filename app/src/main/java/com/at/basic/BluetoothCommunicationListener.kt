package com.at.basic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// Interface for communication back to UI/Activity
interface BluetoothCommunicationListener {
    fun onMessageReceived(message: String)
    fun onConnected(deviceName: String, deviceAddress: String)
    fun onConnectionFailed(error: String)
    fun onDisconnected()
    fun onConnecting(deviceName: String, deviceAddress: String)
    fun onListenStarted() // Added for server state
}

// Handler message types
const val MESSAGE_READ = 1
const val MESSAGE_CONNECTED = 2
const val MESSAGE_CONNECTION_FAILED = 3
const val MESSAGE_DISCONNECTED = 4
const val MESSAGE_CONNECTING = 5
const val MESSAGE_LISTEN_STARTED = 6

class BluetoothService(
    private val handler: Handler // Handler connected to the main looper
) {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @Volatile
    var currentState: Int = STATE_NONE

    companion object {
        const val STATE_NONE = 0        // We're doing nothing
        const val STATE_LISTEN = 1      // Now listening for incoming connections
        const val STATE_CONNECTING = 2  // Now initiating an outgoing connection
        const val STATE_CONNECTED = 3   // Now connected to a remote device

        private const val TAG = "BluetoothService"
    }

    @Synchronized
    fun start() {
        Log.d(TAG, "start() called. Current state: $currentState")

        // Cancel any thread currently running a connection
        connectedThread?.cancel()
        connectedThread = null
        Log.d(TAG, "start(): connectedThread cancelled and nulled.")

        // Cancel any thread currently attempting to connect
        connectThread?.cancel()
        connectThread = null
        Log.d(TAG, "start(): connectThread cancelled and nulled.")

        // Start the accept thread to listen for a BluetoothServerSocket
        if (acceptThread == null) {
            acceptThread = AcceptThread()
            acceptThread?.start()
            Log.d(TAG, "start(): AcceptThread created and started.")
            updateState(STATE_LISTEN)

            // Notify UI that listening has started
            handler.obtainMessage(MESSAGE_LISTEN_STARTED).sendToTarget()
        } else {
            Log.d(TAG, "start(): AcceptThread already running, not restarting.")
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission") // Permissions handled in MainActivity
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect() called for device: ${device.name} (${device.address}). Current state: $currentState")

        // Cancel any thread currently running a connection
        connectedThread?.cancel()
        connectedThread = null
        Log.d(TAG, "connect(): connectedThread cancelled and nulled.")

        // Cancel any thread attempting to make a connection
        if (currentState == STATE_CONNECTING) {
            connectThread?.cancel()
            connectThread = null
            Log.d(TAG, "connect(): Existing connectThread cancelled and nulled.")
        }

        // Notify UI that we're starting to connect
        val deviceName = device.name ?: "Unknown Device"
        val msg = handler.obtainMessage(MESSAGE_CONNECTING, deviceName)
        val bundle = Bundle()
        bundle.putString("device_address", device.address)
        msg.data = bundle
        msg.sendToTarget()

        // Start the thread to connect with the given device
        connectThread = ConnectThread(device)
        connectThread?.start()
        Log.d(TAG, "connect(): ConnectThread created and started for $deviceName.")
        updateState(STATE_CONNECTING)
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        val deviceName = device.name ?: "Unknown Device"
        Log.d(TAG, "connected() called. Socket established with $deviceName.")

        // Cancel the thread that completed the connection
        connectThread?.cancel()
        connectThread = null
        Log.d(TAG, "connected(): connectThread cancelled and nulled after successful connection.")

        // Cancel any accept thread because we only want one connection
        acceptThread?.cancel()
        acceptThread = null
        Log.d(TAG, "connected(): acceptThread cancelled and nulled (single connection mode).")

        // Start the thread to manage the connection and perform transmissions
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
        Log.d(TAG, "connected(): ConnectedThread created and started for data transfer.")

        // Send the name and address of the connected device back to the UI Activity
        val msg = handler.obtainMessage(MESSAGE_CONNECTED, deviceName)
        val bundle = Bundle()
        bundle.putString("device_address", device.address)
        msg.data = bundle
        msg.sendToTarget()
        Log.d(TAG, "connected(): MESSAGE_CONNECTED sent to handler for device: $deviceName.")

        updateState(STATE_CONNECTED)
    }

    @Synchronized
    fun stop() {
        Log.d(TAG, "stop() called. Current state: $currentState")

        connectedThread?.cancel()
        connectedThread = null
        Log.d(TAG, "stop(): connectedThread cancelled and nulled.")

        connectThread?.cancel()
        connectThread = null
        Log.d(TAG, "stop(): connectThread cancelled and nulled.")

        acceptThread?.cancel()
        acceptThread = null
        Log.d(TAG, "stop(): acceptThread cancelled and nulled.")

        updateState(STATE_NONE)
        Log.d(TAG, "stop(): Service stopped. State set to STATE_NONE.")
    }

    fun write(out: ByteArray) {
        // Create temporary object
        var r: ConnectedThread?
        // Synchronize a copy of the ConnectedThread
        synchronized(this) {
            if (currentState != STATE_CONNECTED) {
                Log.w(TAG, "write(): Attempted to write when not connected. Current state: $currentState. Data not sent.")
                return // Not connected, so can't write
            }
            r = connectedThread
            Log.d(TAG, "write(): Current state is STATE_CONNECTED. Attempting to write ${out.size} bytes.")
        }
        // Perform the write unsynchronized
        r?.write(out)
    }

    private fun updateState(state: Int) {
        val oldState = currentState
        currentState = state
        Log.d(TAG, "updateState(): Changing state from $oldState to $currentState")
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(Constants.ECM_SERVICE_NAME, Constants.MY_UUID)
        }

        @Volatile
        private var isRunning = true

        override fun run() {
            Log.d(TAG, "AcceptThread: BEGIN mAcceptThread $this - Service: ${Constants.ECM_SERVICE_NAME}")
            name = "AcceptThread-ECM"
            var socket: BluetoothSocket? = null

            // Listen to the server socket if not connected
            while (isRunning && currentState != STATE_CONNECTED) {
                try {
                    Log.d(TAG, "AcceptThread: ECM Service listening on UUID: ${Constants.MY_UUID}")
                    Log.d(TAG, "AcceptThread: Server socket listening... (currentState: $currentState)")

                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket?.accept()

                    val remoteName = socket?.remoteDevice?.name ?: "Unknown Device"
                    val remoteAddress = socket?.remoteDevice?.address ?: "Unknown Address"
                    Log.d(TAG, "AcceptThread: Accepted connection from $remoteName ($remoteAddress)")

                } catch (e: IOException) {
                    Log.e(TAG, "AcceptThread: Socket's accept() method failed", e)
                    connectionFailed("ECM Server accept failed: ${e.message}")
                    isRunning = false
                }

                // If a connection was accepted
                socket?.let {
                    synchronized(this@BluetoothService) {
                        when (currentState) {
                            STATE_LISTEN, STATE_CONNECTING -> {
                                Log.d(TAG, "AcceptThread: Connection accepted, starting connected thread.")
                                connected(it, it.remoteDevice)
                            }
                            STATE_NONE, STATE_CONNECTED -> {
                                Log.w(TAG, "AcceptThread: Connection rejected - state is $currentState. Closing socket.")
                                try {
                                    it.close()
                                } catch (e: IOException) {
                                    Log.e(TAG, "AcceptThread: Could not close unwanted socket", e)
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }

            Log.d(TAG, "AcceptThread: END mAcceptThread loop. isRunning: $isRunning, currentState: $currentState")
            try {
                mmServerSocket?.close()
                Log.d(TAG, "AcceptThread: ECM ServerSocket closed.")
            } catch (e: IOException) {
                Log.e(TAG, "AcceptThread: Error closing ECM server socket.", e)
            }
        }

        fun cancel() {
            Log.d(TAG, "AcceptThread: cancel() called for ECM service")
            isRunning = false
            try {
                mmServerSocket?.close()
                Log.d(TAG, "AcceptThread: ECM ServerSocket explicitly closed by cancel().")
            } catch (e: IOException) {
                Log.e(TAG, "AcceptThread: Could not close the ECM accept socket during cancel()", e)
            }
        }
    }

    /**
     * This thread runs while attempting to establish an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    @SuppressLint("MissingPermission")
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(Constants.MY_UUID)
        }

        override fun run() {
            val deviceName = device.name ?: "Unknown Device"
            Log.d(TAG, "ConnectThread: BEGIN mConnectThread for $deviceName")
            name = "ConnectThread-$deviceName"

            // Always cancel discovery because it will slow down a connection
            bluetoothAdapter?.cancelDiscovery()
            Log.d(TAG, "ConnectThread: Bluetooth discovery cancelled.")

            // Make a connection to the BluetoothSocket
            mmSocket?.let { socket ->
                try {
                    Log.d(TAG, "ConnectThread: Attempting to connect to ECM service on $deviceName...")
                    socket.connect()
                    Log.d(TAG, "ConnectThread: Successfully connected to ECM service on $deviceName")
                } catch (e: IOException) {
                    Log.e(TAG, "ConnectThread: Could not connect to ECM service on $deviceName", e)
                    try {
                        socket.close()
                        Log.d(TAG, "ConnectThread: Closed client socket after connection failure.")
                    } catch (e2: IOException) {
                        Log.e(TAG, "ConnectThread: Could not close client socket after connection failure", e2)
                    }
                    connectionFailed("Could not connect to ECM Emitter '$deviceName': ${e.message}")
                    return
                }

                // Reset the ConnectThread because we're done
                synchronized(this@BluetoothService) {
                    connectThread = null
                    Log.d(TAG, "ConnectThread: connectThread nulled after connection attempt.")
                }

                // Start the connected thread
                connected(socket, device)
            } ?: run {
                Log.e(TAG, "ConnectThread: mmSocket was null for $deviceName. Cannot connect.")
                connectionFailed("Could not create socket for ECM Emitter '$deviceName'")
            }
            Log.d(TAG, "ConnectThread: END mConnectThread for $deviceName")
        }

        fun cancel() {
            Log.d(TAG, "ConnectThread: cancel() called")
            try {
                mmSocket?.close()
                Log.d(TAG, "ConnectThread: Client socket explicitly closed by cancel().")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectThread: Could not close the client socket during cancel()", e)
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {

        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)

        @SuppressLint("MissingPermission")
        override fun run() {
            val deviceName = mmSocket.remoteDevice.name ?: "Unknown Device"
            Log.d(TAG, "ConnectedThread: BEGIN mConnectedThread for ECM connection with: $deviceName")
            var numBytes: Int

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    Log.d(TAG, "ConnectedThread: Waiting to read ECM data from $deviceName...")
                    numBytes = mmInStream.read(mmBuffer)
                    Log.d(TAG, "ConnectedThread: Read $numBytes bytes of ECM data from $deviceName.")

                    // Send the obtained bytes to the UI Activity via the Handler
                    handler.obtainMessage(MESSAGE_READ, numBytes, -1, mmBuffer)
                        .sendToTarget()
                    Log.d(TAG, "ConnectedThread: ECM data MESSAGE_READ sent to handler with $numBytes bytes.")

                } catch (e: IOException) {
                    Log.e(TAG, "ConnectedThread: ECM data stream disconnected from $deviceName", e)
                    connectionLost()
                    break
                }
            }
            Log.d(TAG, "ConnectedThread: END mConnectedThread for $deviceName")
            try {
                mmSocket.close()
                Log.d(TAG, "ConnectedThread: ECM socket closed.")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: Error closing ECM socket.", e)
            }
        }

        /**
         * Write ECM data to the connected OutStream.
         */
        fun write(buffer: ByteArray) {
            try {
                Log.d(TAG, "ConnectedThread: Sending ${buffer.size} bytes of ECM data...")
                mmOutStream.write(buffer)
                mmOutStream.flush() // Ensure ECM data is sent immediately
                Log.d(TAG, "ConnectedThread: Successfully sent ${buffer.size} bytes of ECM data.")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: Error sending ECM data", e)
                connectionLost()
            }
        }

        fun cancel() {
            Log.d(TAG, "ConnectedThread: cancel() called")
            try {
                mmSocket.close()
                Log.d(TAG, "ConnectedThread: ECM connected socket explicitly closed by cancel().")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: Could not close the ECM connect socket during cancel()", e)
            }
        }
    }

    /**
     * Indicate that the connection attempt failed.
     */
    private fun connectionFailed(errorMessage: String) {
        Log.e(TAG, "connectionFailed(): ECM connection failed: $errorMessage")
        updateState(STATE_NONE)
        handler.obtainMessage(MESSAGE_CONNECTION_FAILED, errorMessage).sendToTarget()
        Log.d(TAG, "connectionFailed(): MESSAGE_CONNECTION_FAILED sent to handler.")
    }

    /**
     * Indicate that the connection was lost.
     */
    private fun connectionLost() {
        Log.w(TAG, "connectionLost(): ECM connection unexpectedly lost.")
        updateState(STATE_NONE)
        handler.obtainMessage(MESSAGE_DISCONNECTED).sendToTarget()
        Log.d(TAG, "connectionLost(): MESSAGE_DISCONNECTED sent to handler.")
    }
}