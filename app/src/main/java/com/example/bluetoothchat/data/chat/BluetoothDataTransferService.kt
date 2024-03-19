package com.example.bluetoothchat.data.chat

import android.bluetooth.BluetoothSocket
import com.example.bluetoothchat.domain.chat.BluetoothMessage
import com.example.bluetoothchat.domain.chat.TransportFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

class BluetoothDataTransferService(
    private val socket: BluetoothSocket
) {
    fun listenForIncomingMessages(): Flow<BluetoothMessage> = flow {
        if (!socket.isConnected) return@flow

        val buffer = ByteArray(1024)
        while (true) {
            val byteCount = try {
                socket.inputStream.read(buffer)
            } catch (e: IOException) {
                throw TransportFailedException()
            }
            emit(
                buffer.decodeToString(endIndex = byteCount)
                    .toBluetoothMessage(false)
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun sendMessage(bytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                socket.outputStream.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
                return@withContext false
            }

            true
        }
    }
}