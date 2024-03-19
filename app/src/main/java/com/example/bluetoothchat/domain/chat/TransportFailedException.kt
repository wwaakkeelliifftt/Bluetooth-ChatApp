package com.example.bluetoothchat.domain.chat

import java.io.IOException

class TransportFailedException: IOException("Reading incoming data failed")