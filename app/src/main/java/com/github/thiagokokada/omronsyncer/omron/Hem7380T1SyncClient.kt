package com.github.thiagokokada.omronsyncer.omron

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.github.thiagokokada.omronsyncer.model.Measurement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.UUID

class Hem7380T1SyncClient(
    private val context: Context,
) {

    suspend fun sync(device: BluetoothDevice): SyncResult = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        fun log(message: String) {
            diagnostics += message
        }

        log("Selected device: ${device.name ?: "Unknown"} (${device.address})")

        val session = GattSession(context, device, ::log)
        try {
            log("Connecting to GATT...")
            session.connect()
            log("Requesting MTU $MTU...")
            session.requestMtu(MTU)
            log("Discovering services...")
            session.discoverServices()
            log("Enabling RX notifications...")
            session.enableNotifications(SERVICE_UUID, RX_UUID)

            session.startTransmission()

            val measurements = buildList {
                addAll(readUser(session, user = 1, startAddress = USER1_START_ADDRESS))
                addAll(readUser(session, user = 2, startAddress = USER2_START_ADDRESS))
            }

            session.endTransmission()

            val sortedMeasurements = measurements.sortedByDescending { it.recordedAt }
            log("Sync completed with ${sortedMeasurements.size} parsed measurements.")
            SyncResult(
                measurements = sortedMeasurements,
                diagnostics = SyncDiagnostics(diagnostics.toList()),
            )
        } catch (error: Exception) {
            log("Sync failed: ${error.message ?: error.javaClass.simpleName}")
            throw SyncException(
                message = error.message ?: "Sync failed.",
                diagnostics = SyncDiagnostics(diagnostics.toList()),
                cause = error,
            )
        } finally {
            session.close()
        }
    }

    private suspend fun readUser(
        session: GattSession,
        user: Int,
        startAddress: Int,
    ): List<Measurement> {
        val payload = session.readContinuousEepromData(
            startAddress = startAddress,
            bytesToRead = RECORD_COUNT_PER_USER * RECORD_SIZE_BYTES,
            blockSize = EEPROM_BLOCK_SIZE,
        )

        return payload
            .asList()
            .chunked(RECORD_SIZE_BYTES)
            .mapNotNull { recordBytes ->
                parseMeasurement(user, recordBytes.toByteArray())
            }
    }

    private fun parseMeasurement(user: Int, recordBytes: ByteArray): Measurement? {
        val rawSystolic = recordBytes[0].toUByte().toInt()
        if (rawSystolic > 0xE1) {
            return null
        }

        val year = 2000 + (recordBytes[3].toInt() and 0x3F)
        val flags1 = recordBytes[4].toUByte().toInt() or (recordBytes[5].toUByte().toInt() shl 8)
        val flags2 = recordBytes[6].toUByte().toInt() or (recordBytes[7].toUByte().toInt() shl 8)

        val month = (flags1 shr 10) and 0x0F
        val day = (flags1 shr 5) and 0x1F
        val hour = flags1 and 0x1F
        val minute = (flags2 shr 6) and 0x3F
        val second = minOf(flags2 and 0x3F, 59)

        val timestamp = try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (_: DateTimeException) {
            return null
        }

        return Measurement(
            user = user,
            recordedAt = timestamp,
            systolic = rawSystolic + 25,
            diastolic = recordBytes[1].toUByte().toInt(),
            pulse = recordBytes[2].toUByte().toInt(),
            irregularHeartbeat = ((flags1 shr 14) and 0x01) == 1,
            movement = ((flags1 shr 15) and 0x01) == 1,
        )
    }

    private class GattSession(
        private val context: Context,
        private val device: BluetoothDevice,
        private val log: (String) -> Unit,
    ) {

        private val notificationChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)

        private var gatt: BluetoothGatt? = null
        private var connectDeferred: CompletableDeferred<Unit>? = null
        private var mtuDeferred: CompletableDeferred<Unit>? = null
        private var servicesDeferred: CompletableDeferred<Unit>? = null
        private var descriptorDeferred: CompletableDeferred<Unit>? = null

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val deferred = connectDeferred
                log(
                    "Connection state changed: status=$status (${describeGattStatus(status)}), " +
                        "newState=$newState",
                )
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        deferred?.completeExceptionally(
                            IllegalStateException(
                                "Bluetooth connect failed: status=$status (${describeGattStatus(status)})",
                            ),
                        )
                    }
                    newState == BluetoothGatt.STATE_CONNECTED -> {
                        deferred?.complete(Unit)
                    }
                    newState == BluetoothGatt.STATE_DISCONNECTED -> {
                        deferred?.completeExceptionally(
                            IllegalStateException("Bluetooth device disconnected."),
                        )
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val deferred = servicesDeferred ?: return
                log("Services discovered: status=$status (${describeGattStatus(status)})")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deferred.complete(Unit)
                } else {
                    deferred.completeExceptionally(
                        IllegalStateException(
                            "Service discovery failed: status=$status (${describeGattStatus(status)})",
                        ),
                    )
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val deferred = mtuDeferred ?: return
                log("MTU callback: mtu=$mtu status=$status (${describeGattStatus(status)})")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deferred.complete(Unit)
                } else {
                    deferred.completeExceptionally(
                        IllegalStateException(
                            "MTU request failed: status=$status (${describeGattStatus(status)})",
                        ),
                    )
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                val deferred = descriptorDeferred ?: return
                log("Descriptor write: status=$status (${describeGattStatus(status)})")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deferred.complete(Unit)
                } else {
                    deferred.completeExceptionally(
                        IllegalStateException(
                            "Descriptor write failed: status=$status (${describeGattStatus(status)})",
                        ),
                    )
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                characteristic.value?.let {
                    log("RX: ${it.toHexString()}")
                    notificationChannel.trySendBlocking(it)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                log("RX: ${value.toHexString()}")
                notificationChannel.trySendBlocking(value)
            }
        }

        @SuppressLint("MissingPermission")
        suspend fun connect() {
            if (gatt != null) {
                return
            }

            connectDeferred = CompletableDeferred()
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

            connectDeferred?.await()
            connectDeferred = null
        }

        @SuppressLint("MissingPermission")
        suspend fun requestMtu(mtu: Int) {
            val gatt = requireGatt()
            mtuDeferred = CompletableDeferred()
            if (!gatt.requestMtu(mtu)) {
                mtuDeferred = null
                throw IllegalStateException("Failed to request MTU.")
            }
            mtuDeferred?.await()
            mtuDeferred = null
        }

        @SuppressLint("MissingPermission")
        suspend fun discoverServices() {
            val gatt = requireGatt()
            servicesDeferred = CompletableDeferred()
            if (!gatt.discoverServices()) {
                servicesDeferred = null
                throw IllegalStateException("Failed to start service discovery.")
            }
            servicesDeferred?.await()
            servicesDeferred = null
        }

        @SuppressLint("MissingPermission")
        suspend fun enableNotifications(serviceUuid: UUID, characteristicUuid: UUID) {
            val gatt = requireGatt()
            val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                throw IllegalStateException("Failed to enable notifications.")
            }

            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                ?: throw IllegalStateException("Notification descriptor not found.")

            descriptorDeferred = CompletableDeferred()

            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!gatt.writeDescriptor(descriptor)) {
                descriptorDeferred = null
                throw IllegalStateException("Failed to write notification descriptor.")
            }

            descriptorDeferred?.await()
            descriptorDeferred = null
        }

        suspend fun startTransmission() {
            val response = sendCommand(START_TRANSMISSION_COMMAND)
            require(response.packetType == RESPONSE_START) {
                "Unexpected start response: 0x${response.packetType.toString(16)}"
            }
        }

        suspend fun endTransmission() {
            val response = sendCommand(END_TRANSMISSION_COMMAND)
            require(response.packetType == RESPONSE_END) {
                "Unexpected end response: 0x${response.packetType.toString(16)}"
            }
            if (response.data.firstOrNull()?.toInt() != 0) {
                throw IllegalStateException(
                    "Device reported endTransmission error: ${response.data.first().toUByte().toInt()}",
                )
            }
        }

        suspend fun readContinuousEepromData(
            startAddress: Int,
            bytesToRead: Int,
            blockSize: Int,
        ): ByteArray {
            val data = ArrayList<Byte>(bytesToRead)
            var currentAddress = startAddress
            var remaining = bytesToRead

            while (remaining > 0) {
                val nextBlockSize = minOf(remaining, blockSize)
                val response = sendCommand(buildReadCommand(currentAddress, nextBlockSize))
                require(response.packetType == RESPONSE_READ) {
                    "Unexpected read response: 0x${response.packetType.toString(16)}"
                }
                require(response.address == currentAddress) {
                    "Read response address mismatch: expected=0x${currentAddress.toString(16)} actual=0x${response.address.toString(16)}"
                }
                data += response.data.toList()
                currentAddress += nextBlockSize
                remaining -= nextBlockSize
            }

            return data.toByteArray()
        }

        fun close() {
            log("Closing GATT session.")
            notificationChannel.close()
            gatt?.close()
            gatt = null
        }

        @SuppressLint("MissingPermission")
        private suspend fun sendCommand(command: ByteArray): OmronResponse {
            var lastError: Exception? = null
            repeat(COMMAND_RETRY_COUNT) { retryIndex ->
                val attempt = retryIndex + 1
                try {
                    while (notificationChannel.tryReceive().isSuccess) {
                        // Discard stale packets before a new request.
                    }

                    val txCharacteristic = requireCharacteristic(SERVICE_UUID, TX_UUID)
                    @Suppress("DEPRECATION")
                    txCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    txCharacteristic.value = command

                    log("TX[$attempt]: ${command.toHexString()}")

                    @Suppress("DEPRECATION")
                    if (!requireGatt().writeCharacteristic(txCharacteristic)) {
                        throw IllegalStateException("Characteristic write failed.")
                    }

                    val payload = withTimeout(RESPONSE_TIMEOUT_MS) {
                        notificationChannel.receive()
                    }
                    val response = parseResponse(payload)
                    log(
                        "Packet[$attempt]: type=0x${response.packetType.toString(16)} " +
                            "address=0x${response.address.toString(16)} bytes=${response.data.size}",
                    )
                    return response
                } catch (error: Exception) {
                    lastError = error
                    log(
                        "Command attempt $attempt/$COMMAND_RETRY_COUNT failed: " +
                            "${error.message ?: error.javaClass.simpleName}",
                    )
                    if (attempt < COMMAND_RETRY_COUNT) {
                        delay(COMMAND_RETRY_DELAY_MS)
                    }
                }
            }
            throw IllegalStateException(
                "Command failed after $COMMAND_RETRY_COUNT attempts.",
                lastError,
            )
        }

        private fun requireCharacteristic(
            serviceUuid: UUID,
            characteristicUuid: UUID,
        ): BluetoothGattCharacteristic {
            val service = requireService(serviceUuid)
            return service.getCharacteristic(characteristicUuid)
                ?: throw IllegalStateException("Characteristic $characteristicUuid not found.")
        }

        private fun requireService(serviceUuid: UUID): BluetoothGattService {
            return requireGatt().getService(serviceUuid)
                ?: throw IllegalStateException("Service $serviceUuid not found.")
        }

        private fun requireGatt(): BluetoothGatt {
            return gatt ?: throw IllegalStateException("BluetoothGatt is not connected.")
        }

        private fun parseResponse(rawPacket: ByteArray): OmronResponse {
            require(rawPacket.size >= 8) {
                "Packet too short: ${rawPacket.size} bytes"
            }

            val checksum = rawPacket.fold(0) { acc, byte -> acc xor byte.toInt().and(0xFF) }
            require(checksum == 0) {
                "Packet checksum mismatch."
            }

            val packetType = (rawPacket[1].toUByte().toInt() shl 8) or rawPacket[2].toUByte().toInt()
            val address = (rawPacket[3].toUByte().toInt() shl 8) or rawPacket[4].toUByte().toInt()
            val declaredSize = rawPacket[5].toUByte().toInt()

            val data = when {
                packetType == RESPONSE_END -> byteArrayOf(rawPacket[6])
                declaredSize > rawPacket.size - 8 -> ByteArray(declaredSize) { 0xFF.toByte() }
                else -> rawPacket.copyOfRange(6, 6 + declaredSize)
            }

            return OmronResponse(
                packetType = packetType,
                address = address,
                data = data,
            )
        }

        private data class OmronResponse(
            val packetType: Int,
            val address: Int,
            val data: ByteArray,
        )
    }

    data class SyncResult(
        val measurements: List<Measurement>,
        val diagnostics: SyncDiagnostics,
    )

    data class SyncDiagnostics(
        val entries: List<String>,
    ) {
        fun asText(): String = entries.joinToString(separator = "\n")
    }

    class SyncException(
        message: String,
        val diagnostics: SyncDiagnostics,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    private companion object {
        const val USER1_START_ADDRESS = 0x01C4
        const val USER2_START_ADDRESS = 0x0804
        const val RECORD_COUNT_PER_USER = 100
        const val RECORD_SIZE_BYTES = 0x10
        const val EEPROM_BLOCK_SIZE = 0x38
        const val MTU = 185
        const val RESPONSE_TIMEOUT_MS = 5_000L
        const val COMMAND_RETRY_COUNT = 3
        const val COMMAND_RETRY_DELAY_MS = 400L

        const val RESPONSE_START = 0x8000
        const val RESPONSE_READ = 0x8100
        const val RESPONSE_END = 0x8F00

        val SERVICE_UUID: UUID = UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb")
        val RX_UUID: UUID = UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b")
        val TX_UUID: UUID = UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val START_TRANSMISSION_COMMAND = byteArrayOf(
            0x08,
            0x00,
            0x00,
            0x00,
            0x00,
            0x10,
            0x00,
            0x18,
        )

        val END_TRANSMISSION_COMMAND = byteArrayOf(
            0x08,
            0x0F,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x07,
        )

        fun buildReadCommand(address: Int, blockSize: Int): ByteArray {
            val payload = ByteArray(8)
            payload[0] = 0x08
            payload[1] = 0x01
            payload[2] = 0x00
            payload[3] = ((address shr 8) and 0xFF).toByte()
            payload[4] = (address and 0xFF).toByte()
            payload[5] = (blockSize and 0xFF).toByte()
            payload[6] = 0x00

            var checksum = 0
            for (index in 0..6) {
                checksum = checksum xor payload[index].toInt().and(0xFF)
            }
            payload[7] = checksum.toByte()
            return payload
        }

        fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

        fun describeGattStatus(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "READ_NOT_PERMITTED"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "WRITE_NOT_PERMITTED"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "INSUFFICIENT_AUTHENTICATION"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "REQUEST_NOT_SUPPORTED"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "INSUFFICIENT_ENCRYPTION"
            BluetoothGatt.GATT_INVALID_OFFSET -> "INVALID_OFFSET"
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "INVALID_ATTRIBUTE_LENGTH"
            BluetoothGatt.GATT_CONNECTION_CONGESTED -> "CONNECTION_CONGESTED"
            BluetoothGatt.GATT_FAILURE -> "FAILURE"
            else -> "UNKNOWN"
        }
    }
}
