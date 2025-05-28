package com.at.basic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
    var currentState: Int = STATE_NONE // Changed to var so MainActivity can read it

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

        // Start the thread to connect with the given device
        connectThread = ConnectThread(device)
        connectThread?.start()
        Log.d(TAG, "connect(): ConnectThread created and started for ${device.name}.")
        updateState(STATE_CONNECTING)
    }

    @Synchronized
    fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        Log.d(TAG, "connected() called. Socket established with ${device.name}.")

        // Cancel the thread that completed the connection
        connectThread?.cancel()
        connectThread = null
        Log.d(TAG, "connected(): connectThread cancelled and nulled after successful connection.")


        // Cancel any accept thread because we only want one connection
        // (This is important for a simple point-to-point connection)
        acceptThread?.cancel()
        acceptThread = null
        Log.d(TAG, "connected(): acceptThread cancelled and nulled (single connection mode).")


        // Start the thread to manage the connection and perform transmissions
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
        Log.d(TAG, "connected(): ConnectedThread created and started for data transfer.")


        // Send the name of the connected device back to the UI Activity
        // Note: For address, you might need to pass it explicitly if needed by UI
        handler.obtainMessage(MESSAGE_CONNECTED, -1, -1, device.name)
            .sendToTarget()
        Log.d(TAG, "connected(): MESSAGE_CONNECTED sent to handler for device: ${device.name}.")


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
        // Could send state updates to UI here if needed, but current setup uses MESSAGE_CONNECTED etc.
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(Constants.APP_NAME, Constants.MY_UUID)
        }

        @Volatile private var isRunning = true

        override fun run() {
            Log.d(TAG, "AcceptThread: BEGIN mAcceptThread $this")
            name = "AcceptThread"
            var socket: BluetoothSocket? = null

            // Listen to the server socket if not connected
            // Continue running as long as isRunning is true AND we are not yet connected
            while (isRunning && currentState != STATE_CONNECTED) {
                try {
                    Log.d(TAG, "AcceptThread: Server socket listening... (currentState: $currentState)")
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket?.accept()
                    Log.d(TAG, "AcceptThread: Accepted connection from ${socket?.remoteDevice?.name}.")
                } catch (e: IOException) {
                    Log.e(TAG, "AcceptThread: Socket's accept() method failed", e)
                    connectionFailed("Server accept failed: ${e.message}")
                    isRunning = false // Stop listening on error
                }

                // If a connection was accepted
                socket?.let {
                    synchronized(this@BluetoothService) { // Synchronize on the outer class instance
                        when (currentState) {
                            STATE_LISTEN, STATE_CONNECTING -> {
                                // Situation normal. Start the connected thread.
                                Log.d(TAG, "AcceptThread: Connection accepted, state is LISTEN or CONNECTING. Starting connected thread.")
                                connected(it, it.remoteDevice)
                            }
                            STATE_NONE, STATE_CONNECTED -> {
                                // Either not ready or already connected. Terminate new socket.
                                Log.w(TAG, "AcceptThread: Connection accepted, but state is NONE or ALREADY CONNECTED. Closing unwanted socket.")
                                try {
                                    it.close()
                                } catch (e: IOException) {
                                    Log.e(TAG, "AcceptThread: Could not close unwanted socket", e)
                                }
                            }
                            else -> {
                                Log.d(TAG, "AcceptThread: Connection accepted, unknown state: $currentState. Closing socket.")
                                try {
                                    it.close()
                                } catch (e: IOException) {
                                    Log.e(TAG, "AcceptThread: Could not close unwanted socket in unknown state", e)
                                }
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "AcceptThread: END mAcceptThread loop. isRunning: $isRunning, currentState: $currentState")
            try {
                mmServerSocket?.close()
                Log.d(TAG, "AcceptThread: ServerSocket closed at end of run.")
            } catch (e: IOException) {
                Log.e(TAG, "AcceptThread: Error closing server socket in run() end.", e)
            }
        }

        fun cancel() {
            Log.d(TAG, "AcceptThread: cancel() called for $this")
            isRunning = false // Set flag to stop the loop
            try {
                mmServerSocket?.close()
                Log.d(TAG, "AcceptThread: ServerSocket explicitly closed by cancel().")
            } catch (e: IOException) {
                Log.e(TAG, "AcceptThread: Could not close the accept socket during cancel()", e)
            }
        }
    }

    /**
     * This thread runs while attempting to establish an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    @SuppressLint("MissingPermission") // Permissions handled in MainActivity
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(Constants.MY_UUID)
        }

        override fun run() {
            Log.d(TAG, "ConnectThread: BEGIN mConnectThread for ${device.name}")
            name = "ConnectThread"

            // Always cancel discovery because it will slow down a connection
            bluetoothAdapter?.cancelDiscovery()
            Log.d(TAG, "ConnectThread: Bluetooth discovery cancelled.")


            // Make a connection to the BluetoothSocket
            mmSocket?.let { socket ->
                try {
                    Log.d(TAG, "ConnectThread: Attempting to connect to ${device.name}...")
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket.connect()
                    Log.d(TAG, "ConnectThread: Successfully connected to ${device.name}")
                } catch (e: IOException) {
                    Log.e(TAG, "ConnectThread: Could not connect client socket to ${device.name}", e)
                    try {
                        socket.close()
                        Log.d(TAG, "ConnectThread: Closed client socket after connection failure.")
                    } catch (e2: IOException) {
                        Log.e(TAG, "ConnectThread: Could not close client socket after connection failure", e2)
                    }
                    connectionFailed("Client connect failed: ${e.message}")
                    return // Exit run method on failure
                }

                // Reset the ConnectThread because we're done
                synchronized(this@BluetoothService) {
                    connectThread = null
                    Log.d(TAG, "ConnectThread: connectThread nulled after successful connection (or failure).")
                }

                // Start the connected thread
                connected(socket, device)
            } ?: run {
                Log.e(TAG, "ConnectThread: mmSocket was null. Cannot connect.")
                connectionFailed("Client socket creation failed.")
            }
            Log.d(TAG, "ConnectThread: END mConnectThread for ${device.name}")
        }

        fun cancel() {
            Log.d(TAG, "ConnectThread: cancel() called for $this")
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

        override fun run() {
            Log.d(TAG, "ConnectedThread: BEGIN mConnectedThread for socket: ${mmSocket.remoteDevice.name}")
            var numBytes: Int // bytes returned from read()

            // Keep listening to the InputStream while connected
            while (true) { // This loop must continue to read incoming data
                try {
                    Log.d(TAG, "ConnectedThread: Waiting to read from InputStream...")
                    // Read from the InputStream
                    numBytes = mmInStream.read(mmBuffer)
                    Log.d(TAG, "ConnectedThread: Read $numBytes bytes from InputStream.")


                    // Send the obtained bytes to the UI Activity via the Handler
                    handler.obtainMessage(MESSAGE_READ, numBytes, -1, mmBuffer)
                        .sendToTarget()
                    Log.d(TAG, "ConnectedThread: MESSAGE_READ sent to handler with $numBytes bytes.")


                } catch (e: IOException) {
                    Log.e(TAG, "ConnectedThread: Input stream was disconnected or error occurred", e)
                    connectionLost() // Handle disconnection
                    break // Exit the loop on disconnection
                }
            }
            Log.d(TAG, "ConnectedThread: END mConnectedThread")
            try {
                mmSocket.close()
                Log.d(TAG, "ConnectedThread: Socket closed at end of run.")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: Error closing socket at end of run.", e)
            }
        }

        /**
         * Write to the connected OutStream.
         * @param buffer The bytes to write
         */
        fun write(buffer: ByteArray) {
            try {
                Log.d(TAG, "ConnectedThread: Attempting to write ${buffer.size} bytes to OutputStream.")
                mmOutStream.write(buffer)
                // Optionally, flush to ensure data is sent immediately, though usually handled by OS
                // mmOutStream.flush()
                Log.d(TAG, "ConnectedThread: Successfully wrote ${buffer.size} bytes.")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: Error during write to output stream", e)
                connectionLost() // Handle disconnection on write error
            }
        }

        fun cancel() {
            Log.d(TAG, "ConnectedThread: cancel() called for $this")
            try {
                mmSocket.close()
                Log.d(TAG, "ConnectedThread: Connected socket explicitly closed by cancel().")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: Could not close the connect socket during cancel()", e)
            }
        }
    }

    /**
     * Indicate that the connection attempt failed.
     */
    private fun connectionFailed(errorMessage: String) {
        Log.e(TAG, "connectionFailed(): Connection failed with error: $errorMessage")
        updateState(STATE_NONE)
        // Send a failure message back to the Activity
        handler.obtainMessage(MESSAGE_CONNECTION_FAILED, errorMessage)
            .sendToTarget()
        Log.d(TAG, "connectionFailed(): MESSAGE_CONNECTION_FAILED sent to handler.")
    }

    /**
     * Indicate that the connection was lost.
     */
    private fun connectionLost() {
        Log.w(TAG, "connectionLost(): Connection unexpectedly lost.")
        updateState(STATE_NONE)
        // Send a disconnected message back to the Activity
        handler.obtainMessage(MESSAGE_DISCONNECTED).sendToTarget()
        Log.d(TAG, "connectionLost(): MESSAGE_DISCONNECTED sent to handler.")
    }
}