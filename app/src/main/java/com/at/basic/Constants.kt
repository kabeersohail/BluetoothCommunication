package com.at.basic

import java.util.UUID

object Constants {
    const val APP_NAME = "MyBluetoothApp"
    // Using a common UUID for SPP (Serial Port Profile).
    // For your own app, consider generating a random one, but ensure consistency.
    val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}