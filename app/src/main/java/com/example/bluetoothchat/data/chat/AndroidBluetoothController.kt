package com.example.bluetoothchat.data.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.example.bluetoothchat.domain.chat.BluetoothController
import com.example.bluetoothchat.domain.chat.BluetoothDeviceDomain
import com.example.bluetoothchat.domain.chat.BluetoothMessage
import com.example.bluetoothchat.domain.chat.ConnectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBluetoothController(
    private val context: Context
): BluetoothController {

    companion object {
        const val BT_SERVICE_UUID = "14d88ae0-20a0-474f-8fcd-66b2a759fca1"
        const val BT_SERVICE_NAME = "chat_service_name"
    }

    private var dataTransferService: BluetoothDataTransferService? = null

    private val bluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter by lazy { bluetoothManager?.adapter }

    private val _isConnected = MutableStateFlow<Boolean>(false)
    private val _scannedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    private val _errors = MutableSharedFlow<String>()

    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    override val scannedDevices: StateFlow<List<BluetoothDeviceDomain>> = _scannedDevices.asStateFlow()
    override val pairedDevices: StateFlow<List<BluetoothDeviceDomain>> = _pairedDevices.asStateFlow()
    override val  errors: SharedFlow<String> = _errors.asSharedFlow()

    private val foundDeviceReceiver = FoundDeviceReceiver { newBtDevice ->
        _scannedDevices.update { existDevicesList ->
            val newDevice = newBtDevice.toBluetoothDeviceDomain()
            if (newDevice in existDevicesList) existDevicesList else existDevicesList + newDevice
        }
    }

    private val bluetoothStateReceiver = BluetoothStateReceiver { isConnected, bluetoothDevice ->
        if (bluetoothAdapter?.bondedDevices?.contains(bluetoothDevice) == true) {
            _isConnected.update { isConnected }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                _errors.emit("Can't connect to non-paired bt-device")
            }
        }
    }

    private var currentServerSocket: BluetoothServerSocket? = null
    private var currentClientSocket: BluetoothSocket? = null

    init {
        updatePairedDevices()
        context.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        )
    }

    override fun startDiscovery() {
//        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
//            return
//        }
        context.registerReceiver(
            foundDeviceReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )
        updatePairedDevices()
        bluetoothAdapter?.startDiscovery()
    }

    override fun stopDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }
        bluetoothAdapter?.cancelDiscovery()
    }

    override fun startBluetoothServer(): Flow<ConnectionResult> {
        return flow<ConnectionResult> {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                throw SecurityException("No BLUETOOTH_CONNECT permission")
            }

            currentServerSocket = bluetoothAdapter
                ?.listenUsingRfcommWithServiceRecord(
                    BT_SERVICE_NAME,
                    UUID.fromString(BT_SERVICE_UUID)
                )

            var shouldLoop = true
            while (shouldLoop) {
                currentClientSocket = try {
                    currentServerSocket?.accept()               // blocking thread action
                } catch (e: IOException) {
                    shouldLoop = false
                    null
                }

                emit(ConnectionResult.ConnectionEstablished)

                currentClientSocket?.let { btSocket ->
                    currentServerSocket?.close()
                    /**    server_socket need only for start/accept connection, after
                     *     client_socket will keep connect between devices       */

                    val service = BluetoothDataTransferService(btSocket)
                    dataTransferService = service

                    emitAll(service.listenForIncomingMessages().map {
                        ConnectionResult.TransferSucceeded(message = it)
                    })
                }
            }

        }.onCompletion { closeConnection() }
            .flowOn(Dispatchers.IO)
    }

    override fun connectToDevice(device: BluetoothDeviceDomain): Flow<ConnectionResult> = flow {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            throw SecurityException("No BLUETOOTH_CONNECT permission")
        }

//        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.macAddress)
//        currentClientSocket = bluetoothDevice
//            ?.createRfcommSocketToServiceRecord(UUID.fromString(BT_SERVICE_UUID))

        currentClientSocket = bluetoothAdapter
            ?.getRemoteDevice(device.macAddress)
            ?.createRfcommSocketToServiceRecord(UUID.fromString(BT_SERVICE_UUID))

        stopDiscovery()                                      //  don't need observe other bt-devices

        currentClientSocket?.let { socket ->
            try {
                socket.connect()                                      //      blocking thread action
                emit(ConnectionResult.ConnectionEstablished)

                BluetoothDataTransferService(socket = socket).also { btDTS ->
                    dataTransferService = btDTS
                    emitAll(
                        btDTS.listenForIncomingMessages().map { btMessage ->
                            ConnectionResult.TransferSucceeded(btMessage) }
                    )
                }
            } catch (e: IOException) {
                socket.close()
                currentClientSocket = null
                emit(ConnectionResult.Error("Connection was interrupted. Error message: ${e.message}"))
            }
        }
    }.onCompletion { closeConnection() }
        .flowOn(Dispatchers.IO)

    override suspend fun trySendMessage(message: String): BluetoothMessage? {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return null
        if (dataTransferService == null) return null

        val bluetoothMessage = BluetoothMessage(
            message = message,
            senderName = bluetoothAdapter?.name ?: "Unknown_name",
            isFromLocalUser = true
        )
        dataTransferService?.sendMessage(bluetoothMessage.toByteArray())

        return bluetoothMessage
    }

    override fun closeConnection() {
        currentClientSocket?.close()
        currentServerSocket?.close()
        currentClientSocket = null
        currentServerSocket = null
    }

    override fun release() {
        context.unregisterReceiver(foundDeviceReceiver)
        context.unregisterReceiver(bluetoothStateReceiver)
        closeConnection()
    }

    private fun updatePairedDevices() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return
        }
        bluetoothAdapter
            ?.bondedDevices
            ?.map { btDevice -> btDevice.toBluetoothDeviceDomain() }
            ?.also { devices ->
                _pairedDevices.update { devices }
            }
    }

    private fun hasPermission(permission: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else
            true                   //  API 29 case, where bluetooth permissions granted as default
    }
}