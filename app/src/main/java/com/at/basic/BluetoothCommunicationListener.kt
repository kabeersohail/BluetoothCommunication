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

    // This is the current connection state.
    // Consider using an enum for more complex states.
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
        Log.d(TAG, "start")

        // Cancel any thread currently running a connection
        connectedThread?.cancel()
        connectedThread = null

        // Cancel any thread currently attempting to connect
        connectThread?.cancel()
        connectThread = null

        // Start the accept thread to listen for a BluetoothServerSocket
        if (acceptThread == null) {
            acceptThread = AcceptThread()
            acceptThread?.start()
            updateState(STATE_LISTEN)
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission") // Permissions handled in MainActivity
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect to: $device")

        // Cancel any thread currently running a connection
        connectedThread?.cancel()
        connectedThread = null

        // Cancel any thread attempting to make a connection
        if (currentState == STATE_CONNECTING) {
            connectThread?.cancel()
            connectThread = null
        }

        // Start the thread to connect with the given device
        connectThread = ConnectThread(device)
        connectThread?.start()
        updateState(STATE_CONNECTING)
    }

    @Synchronized
    fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        Log.d(TAG, "connected")

        // Cancel the thread that completed the connection
        connectThread?.cancel()
        connectThread = null

        // Cancel any accept thread because we only want one connection
        acceptThread?.cancel()
        acceptThread = null

        // Start the thread to manage the connection and perform transmissions
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()

        // Send the name of the connected device back to the UI Activity
        handler.obtainMessage(MESSAGE_CONNECTED, -1, -1, device.name)
            .sendToTarget()

        updateState(STATE_CONNECTED)
    }

    @Synchronized
    fun stop() {
        Log.d(TAG, "stop")

        connectedThread?.cancel()
        connectedThread = null

        connectThread?.cancel()
        connectThread = null

        acceptThread?.cancel()
        acceptThread = null

        updateState(STATE_NONE)
    }

    fun write(out: ByteArray) {
        // Create temporary object
        var r: ConnectedThread?
        // Synchronize a copy of the ConnectedThread
        synchronized(this) {
            if (currentState != STATE_CONNECTED) {
                Log.w(TAG, "Attempted to write when not connected. Current state: $currentState")
                return // Not connected, so can't write
            }
            r = connectedThread
        }
        // Perform the write unsynchronized
        r?.write(out)
    }

    private fun updateState(state: Int) {
        currentState = state
        // Could send state updates to UI here if needed
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
            while (isRunning && currentState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket?.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Socket's accept() method failed", e)
                    connectionFailed("Server accept failed: ${e.message}")
                    isRunning = false // Stop listening on error
                }

                // If a connection was accepted
                socket?.let {
                    synchronized(this@BluetoothService) { // Synchronize on the outer class instance
                        when (currentState) {
                            STATE_LISTEN, STATE_CONNECTING -> {
                                // Situation normal. Start the connected thread.
                                connected(it, it.remoteDevice)
                            }
                            STATE_NONE, STATE_CONNECTED -> {
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    it.close()
                                } catch (e: IOException) {
                                    Log.e(TAG, "Could not close unwanted socket", e)
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }
            Log.d(TAG, "END mAcceptThread")
        }

        fun cancel() {
            Log.d(TAG, "AcceptThread: cancel $this")
            isRunning = false // Set flag to stop the loop
            try {
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the accept socket", e)
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

            // Make a connection to the BluetoothSocket
            mmSocket?.let { socket ->
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket.connect()
                    Log.d(TAG, "ConnectThread: Connected to ${device.name}")
                } catch (e: IOException) {
                    Log.e(TAG, "Could not connect client socket", e)
                    try {
                        socket.close()
                    } catch (e2: IOException) {
                        Log.e(TAG, "Could not close client socket after connection failure", e2)
                    }
                    connectionFailed("Client connect failed: ${e.message}")
                    return // Exit run method on failure
                }

                // Reset the ConnectThread because we're done
                synchronized(this@BluetoothService) {
                    connectThread = null
                }

                // Start the connected thread
                connected(socket, device)
            }
            Log.d(TAG, "END mConnectThread")
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
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
            Log.d(TAG, "ConnectedThread: BEGIN mConnectedThread")
            var numBytes: Int // bytes returned from read()

            // Keep listening to the InputStream while connected
            while (true) { // This loop must continue to read incoming data
                try {
                    // Read from the InputStream
                    numBytes = mmInStream.read(mmBuffer)

                    // Send the obtained bytes to the UI Activity via the Handler
                    handler.obtainMessage(MESSAGE_READ, numBytes, -1, mmBuffer)
                        .sendToTarget()

                } catch (e: IOException) {
                    Log.e(TAG, "Input stream was disconnected", e)
                    connectionLost() // Handle disconnection
                    break // Exit the loop on disconnection
                }
            }
            Log.d(TAG, "END mConnectedThread")
        }

        /**
         * Write to the connected OutStream.
         * @param buffer The bytes to write
         */
        fun write(buffer: ByteArray) {
            try {
                mmOutStream.write(buffer)
            } catch (e: IOException) {
                Log.e(TAG, "Error during write", e)
                connectionLost() // Handle disconnection on write error
            }
        }

        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    /**
     * Indicate that the connection attempt failed.
     */
    private fun connectionFailed(errorMessage: String) {
        updateState(STATE_NONE)
        // Send a failure message back to the Activity
        handler.obtainMessage(MESSAGE_CONNECTION_FAILED, errorMessage)
            .sendToTarget()
    }

    /**
     * Indicate that the connection was lost.
     */
    private fun connectionLost() {
        updateState(STATE_NONE)
        // Send a disconnected message back to the Activity
        handler.obtainMessage(MESSAGE_DISCONNECTED).sendToTarget()
    }
}