package com.github.thiagokokada.omronsyncer.omron

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.sync.MissingBluetoothPermissionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class OmronSyncClient(
    private val context: Context,
) {

    suspend fun sync(
        device: BluetoothDevice,
        model: OmronDeviceDefinition,
    ): SyncResult = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        fun log(message: String) {
            diagnostics += "${timestampText()} - $message"
        }

        val deviceLabel = try {
            "${device.name ?: "Unknown"} (${device.address})"
        } catch (_: SecurityException) {
            "Unknown device"
        }

        log("Model: ${model.modelCode} (${model.marketedName})")
        log("Selected device: $deviceLabel")

        val session = GattSession(context, device, model, ::log)
        try {
            log("Connecting to GATT...")
            session.connect()
            log("Requesting MTU $MTU...")
            session.requestMtu(MTU)
            log("Discovering services...")
            session.discoverServices()
            log("Enabling RX notifications...")
            session.enableNotifications()

            session.startTransmission()

            val measurements = buildList {
                model.userLayouts.forEach { layout ->
                    addAll(readUser(session, model, layout))
                }
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
        model: OmronDeviceDefinition,
        userLayout: OmronUserLayout,
    ): List<Measurement> {
        val measurements = buildList {
            repeat(userLayout.recordCount) { recordIndex ->
                val recordAddress = userLayout.startAddress + (recordIndex * model.recordSizeBytes)
                val recordBytes = session.readRecord(recordAddress, model.recordSizeBytes)
                OmronRecordParser.parseMeasurement(model, userLayout.user, recordBytes)?.let(::add)
            }
        }

        session.logUserSummary(userLayout.user, measurements)
        return measurements
    }

    private class GattSession(
        private val context: Context,
        private val device: BluetoothDevice,
        private val model: OmronDeviceDefinition,
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

                    newState == BluetoothGatt.STATE_CONNECTED -> deferred?.complete(Unit)
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

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                log("RX: ${value.toHexString()}")
                notificationChannel.trySendBlocking(value)
            }
        }

        suspend fun connect() {
            if (gatt != null) {
                return
            }

            connectDeferred = CompletableDeferred()
            gatt = try {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (_: SecurityException) {
                connectDeferred = null
                throw MissingBluetoothPermissionException()
            }

            connectDeferred?.await()
            connectDeferred = null
        }

        suspend fun requestMtu(mtu: Int) {
            val gatt = requireGatt()
            mtuDeferred = CompletableDeferred()
            val requested = try {
                gatt.requestMtu(mtu)
            } catch (_: SecurityException) {
                mtuDeferred = null
                throw MissingBluetoothPermissionException()
            }
            if (!requested) {
                mtuDeferred = null
                throw IllegalStateException("Failed to request MTU.")
            }
            mtuDeferred?.await()
            mtuDeferred = null
        }

        suspend fun discoverServices() {
            val gatt = requireGatt()
            servicesDeferred = CompletableDeferred()
            val started = try {
                gatt.discoverServices()
            } catch (_: SecurityException) {
                servicesDeferred = null
                throw MissingBluetoothPermissionException()
            }
            if (!started) {
                servicesDeferred = null
                throw IllegalStateException("Failed to start service discovery.")
            }
            servicesDeferred?.await()
            servicesDeferred = null
        }

        suspend fun enableNotifications() {
            val gatt = requireGatt()
            val characteristic = requireCharacteristic(model.serviceUuid, model.rxUuid)
            val notificationsEnabled = try {
                gatt.setCharacteristicNotification(characteristic, true)
            } catch (_: SecurityException) {
                throw MissingBluetoothPermissionException()
            }
            if (!notificationsEnabled) {
                throw IllegalStateException("Failed to enable notifications.")
            }

            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                ?: throw IllegalStateException("Notification descriptor not found.")

            descriptorDeferred = CompletableDeferred()

            val writeStatus = try {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                )
            } catch (_: SecurityException) {
                descriptorDeferred = null
                throw MissingBluetoothPermissionException()
            }
            if (writeStatus != BluetoothStatusCodes.SUCCESS) {
                descriptorDeferred = null
                throw IllegalStateException(
                    "Failed to write notification descriptor: ${describeBluetoothStatusCode(writeStatus)}",
                )
            }

            descriptorDeferred?.await()
            descriptorDeferred = null
        }

        suspend fun startTransmission() {
            sendCommand(
                command = START_TRANSMISSION_COMMAND,
                expectedPacketType = RESPONSE_START,
            )
        }

        suspend fun endTransmission() {
            val response = sendCommand(
                command = END_TRANSMISSION_COMMAND,
                expectedPacketType = RESPONSE_END,
            )
            if (response.data.firstOrNull()?.toInt() != 0) {
                throw IllegalStateException(
                    "Device reported endTransmission error: ${response.data.first().toUByte().toInt()}",
                )
            }
        }

        suspend fun readRecord(address: Int, recordSize: Int): ByteArray {
            val response = sendCommand(
                command = buildReadCommand(address, recordSize),
                expectedPacketType = RESPONSE_READ,
                expectedAddress = address,
            )
            require(response.data.size == recordSize) {
                "Read response size mismatch: expected=$recordSize actual=${response.data.size}"
            }
            return response.data
        }

        fun logUserSummary(user: Int, measurements: List<Measurement>) {
            val latestMeasurement = measurements.maxByOrNull { it.recordedAt }
            if (latestMeasurement == null) {
                log("User $user: no valid measurements parsed.")
                return
            }
            log(
                "User $user: parsed ${measurements.size} measurements, latest=" +
                    latestMeasurement.recordedAt,
            )
        }

        fun close() {
            log("Closing GATT session.")
            notificationChannel.close()
            try {
                gatt?.close()
            } catch (_: SecurityException) {
                // Ignore close failures after permission loss.
            }
            gatt = null
        }

        private suspend fun sendCommand(
            command: ByteArray,
            expectedPacketType: Int? = null,
            expectedAddress: Int? = null,
        ): OmronResponse {
            var lastError: Exception? = null
            repeat(COMMAND_RETRY_COUNT) { retryIndex ->
                val attempt = retryIndex + 1
                try {
                    while (notificationChannel.tryReceive().isSuccess) {
                        // Discard stale packets before a new request.
                    }

                    val txCharacteristic = requireCharacteristic(model.serviceUuid, model.txUuid)

                    log("TX[$attempt]: ${command.toHexString()}")

                    val writeStatus = try {
                        requireGatt().writeCharacteristic(
                            txCharacteristic,
                            command,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                        )
                    } catch (_: SecurityException) {
                        throw MissingBluetoothPermissionException()
                    }
                    if (writeStatus != BluetoothStatusCodes.SUCCESS) {
                        throw IllegalStateException(
                            "Characteristic write failed: ${describeBluetoothStatusCode(writeStatus)}",
                        )
                    }

                    val response = receiveExpectedResponse(
                        attempt = attempt,
                        expectedPacketType = expectedPacketType,
                        expectedAddress = expectedAddress,
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

        private suspend fun receiveExpectedResponse(
            attempt: Int,
            expectedPacketType: Int?,
            expectedAddress: Int?,
        ): OmronResponse = withTimeout(RESPONSE_TIMEOUT_MS) {
            awaitMatchingResponse(
                attempt = attempt,
                expectedPacketType = expectedPacketType,
                expectedAddress = expectedAddress,
            )
        }

        private suspend fun awaitMatchingResponse(
            attempt: Int,
            expectedPacketType: Int?,
            expectedAddress: Int?,
        ): OmronResponse {
            while (true) {
                val payload = notificationChannel.receive()
                val response = parseResponse(payload)
                log(
                    "Packet[$attempt]: type=0x${response.packetType.toString(16)} " +
                        "address=0x${response.address.toString(16)} bytes=${response.data.size}",
                )
                if (expectedPacketType != null && response.packetType != expectedPacketType) {
                    log(
                        "Ignoring packet[$attempt]: expected type=0x" +
                            expectedPacketType.toString(16) +
                            " actual=0x${response.packetType.toString(16)}",
                    )
                    continue
                }
                if (expectedAddress != null && response.address != expectedAddress) {
                    log(
                        "Ignoring packet[$attempt]: expected address=0x" +
                            expectedAddress.toString(16) +
                            " actual=0x${response.address.toString(16)}",
                    )
                    continue
                }
                return response
            }
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
            return try {
                requireGatt().getService(serviceUuid)
            } catch (_: SecurityException) {
                throw MissingBluetoothPermissionException()
            }
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

        private class OmronResponse(
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
        const val MTU = 185
        const val RESPONSE_TIMEOUT_MS = 5_000L
        const val COMMAND_RETRY_COUNT = 3
        const val COMMAND_RETRY_DELAY_MS = 400L

        const val RESPONSE_START = 0x8000
        const val RESPONSE_READ = 0x8100
        const val RESPONSE_END = 0x8F00
        const val BLUETOOTH_STATUS_ERROR_DEVICE_NOT_CONNECTED = 4
        val DIAGNOSTIC_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

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

        fun timestampText(): String = DIAGNOSTIC_TIME_FORMATTER.format(Instant.now())

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

        fun describeBluetoothStatusCode(status: Int): String = when (status) {
            BluetoothStatusCodes.SUCCESS -> "SUCCESS"
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION ->
                "MISSING_BLUETOOTH_CONNECT_PERMISSION"
            BLUETOOTH_STATUS_ERROR_DEVICE_NOT_CONNECTED -> "DEVICE_NOT_CONNECTED"
            BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> "PROFILE_SERVICE_NOT_BOUND"
            BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED -> "GATT_WRITE_NOT_ALLOWED"
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> "GATT_WRITE_REQUEST_BUSY"
            BluetoothStatusCodes.ERROR_UNKNOWN -> "UNKNOWN"
            else -> "STATUS_$status"
        }
    }
}
