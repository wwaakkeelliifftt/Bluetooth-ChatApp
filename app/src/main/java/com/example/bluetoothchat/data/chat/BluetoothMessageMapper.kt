package com.example.bluetoothchat.data.chat

import com.example.bluetoothchat.domain.chat.BluetoothMessage

const val DIVIDER = "!@___divide-me-full__@!"

fun BluetoothMessage.toByteArray(): ByteArray {
    return "$senderName$DIVIDER$message".toByteArray()
}

fun String.toBluetoothMessage(isFromLocalUser: Boolean): BluetoothMessage {
    val name = substringBefore(delimiter = DIVIDER)
    val message = substringAfter(delimiter = DIVIDER)
    return BluetoothMessage(message, name, isFromLocalUser)
}