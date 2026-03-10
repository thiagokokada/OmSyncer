package com.github.thiagokokada.omronsyncer.omron

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.sync.MissingBluetoothPermissionException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.error.GattError
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val captureBuilder = SyncCaptureBuilder(
            model = model,
            deviceName = try {
                device.name
            } catch (_: SecurityException) {
                null
            },
            deviceAddress = device.address,
        )

        log("Model: ${model.modelCode} (${model.marketedName})")
        log("Selected device: $deviceLabel")

        val session = OmronBleSession(context, model, ::log, captureBuilder)
        try {
            log("Connecting with Nordic BLE Library...")
            session.connect(device)
            session.bootstrapPairingKeyIfNeeded()

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
                capture = captureBuilder.build(),
            )
        } catch (error: Exception) {
            log("Sync failed: ${error.message ?: error.javaClass.simpleName}")
            throw SyncException(
                message = error.message ?: "Sync failed.",
                diagnostics = SyncDiagnostics(diagnostics.toList()),
                capture = captureBuilder.build(),
                cause = error,
            )
        } finally {
            session.close()
        }
    }

    private suspend fun readUser(
        session: OmronBleSession,
        model: OmronDeviceDefinition,
        userLayout: OmronUserLayout,
    ): List<Measurement> {
        val measurements = buildList {
            repeat(userLayout.recordCount) { recordIndex ->
                val recordAddress = userLayout.startAddress + (recordIndex * model.recordSizeBytes)
                val recordBytes = session.readRecord(recordAddress, model.recordSizeBytes)
                val parsedMeasurement = OmronRecordParser.parseMeasurement(model, userLayout.user, recordBytes)
                session.captureRecord(
                    user = userLayout.user,
                    address = recordAddress,
                    recordBytes = recordBytes,
                    measurement = parsedMeasurement,
                )
                parsedMeasurement?.let(::add)
            }
        }

        session.logUserSummary(userLayout.user, measurements)
        return measurements
    }

    private class OmronBleSession(
        context: Context,
        private val model: OmronDeviceDefinition,
        private val log: (String) -> Unit,
        private val captureBuilder: SyncCaptureBuilder,
    ) {

        private val notificationChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        private val pairingChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        private val manager = OmronBleManager(
            context = context,
            model = model,
            sessionLog = log,
            onPacketReceived = { payload ->
                captureBuilder.addPacket(SyncPacketDirection.RX, payload)
            },
        )

        suspend fun connect(device: BluetoothDevice) {
            manager.onNotification = { payload ->
                notificationChannel.trySend(payload)
            }
            manager.onPairingNotification = { payload ->
                pairingChannel.trySend(payload)
            }
            manager.connectTo(device)
        }

        suspend fun bootstrapPairingKeyIfNeeded() {
            val pairingCommand = model.pairingBootstrapCommandHex?.hexToByteArray() ?: return
            if (!manager.hasPairingBootstrapCharacteristic()) {
                log("Omron pairing-key bootstrap skipped: pairing characteristic unavailable.")
                return
            }

            while (pairingChannel.tryReceive().isSuccess) {
                // Discard stale packets before a bootstrap attempt.
            }

            log("Attempting Omron pairing-key bootstrap.")
            try {
                manager.enablePairingUpdates()
                log("TX[PK]: ${pairingCommand.toHexString()}")
                manager.writePairingCommand(pairingCommand)
                val response = withTimeoutOrNull(PAIRING_RESPONSE_TIMEOUT_MS) {
                    pairingChannel.receive()
                }
                if (response == null) {
                    log("Omron pairing-key bootstrap produced no response; continuing.")
                } else {
                    log("RX[PK]: ${response.toHexString()}")
                }
            } catch (error: Exception) {
                log(
                    "Omron pairing-key bootstrap failed: " +
                        "${error.message ?: error.javaClass.simpleName}",
                )
            } finally {
                runCatching {
                    manager.disablePairingUpdates()
                }.onFailure { error ->
                    log(
                        "Failed to disable Omron pairing-key updates: " +
                            "${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
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

        fun captureRecord(
            user: Int,
            address: Int,
            recordBytes: ByteArray,
            measurement: Measurement?,
        ) {
            captureBuilder.addRecord(
                user = user,
                address = address,
                recordBytes = recordBytes,
                measurement = measurement,
            )
        }

        suspend fun close() {
            log("Closing GATT session.")
            notificationChannel.close()
            pairingChannel.close()
            manager.closeConnection()
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

                    log("TX[$attempt]: ${command.toHexString()}")
                    captureBuilder.addPacket(SyncPacketDirection.TX, command)
                    manager.writeCommand(command)

                    return awaitMatchingResponse(
                        attempt = attempt,
                        expectedPacketType = expectedPacketType,
                        expectedAddress = expectedAddress,
                    )
                } catch (error: Exception) {
                    val normalizedError = normalizeCommandError(error)
                    lastError = normalizedError
                    log(
                        "Command attempt $attempt/$COMMAND_RETRY_COUNT failed: " +
                            "${normalizedError.message ?: normalizedError.javaClass.simpleName}",
                    )
                    if (attempt < COMMAND_RETRY_COUNT) {
                        delay(COMMAND_RETRY_DELAY_MS)
                    }
                }
            }

            throw IllegalStateException("Command failed after $COMMAND_RETRY_COUNT attempts.", lastError)
        }

        private suspend fun awaitMatchingResponse(
            attempt: Int,
            expectedPacketType: Int?,
            expectedAddress: Int?,
        ): OmronResponse {
            while (true) {
                val payload = withTimeout(RESPONSE_TIMEOUT_MS) {
                    notificationChannel.receive()
                }
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

        private fun normalizeCommandError(error: Exception): Exception {
            return when (error) {
                is TimeoutCancellationException -> CommandTimeoutException(error)
                is RequestFailedException if error.status == FailCallback.REASON_TIMEOUT ->
                    CommandTimeoutException(error)

                else -> error
            }
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

    private class OmronBleManager(
        context: Context,
        private val model: OmronDeviceDefinition,
        private val sessionLog: (String) -> Unit,
        private val onPacketReceived: (ByteArray) -> Unit,
    ) : BleManager(context) {

        private var txCharacteristic: BluetoothGattCharacteristic? = null
        private var rxCharacteristic: BluetoothGattCharacteristic? = null
        private var rxContinuationCharacteristic: BluetoothGattCharacteristic? = null
        private var pairingBootstrapCharacteristic: BluetoothGattCharacteristic? = null
        private val packetAssembler = OmronPacketAssembler()
        var onNotification: ((ByteArray) -> Unit)? = null
        var onPairingNotification: ((ByteArray) -> Unit)? = null

        init {
            setConnectionObserver(
                object : ConnectionObserver {
                    override fun onDeviceConnecting(device: BluetoothDevice) {
                        sessionLog("Connection starting.")
                    }

                    override fun onDeviceConnected(device: BluetoothDevice) {
                        sessionLog("Connected to device.")
                    }

                    override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                        sessionLog(
                            "Connection failed: reason=$reason (${describeDisconnectionReason(reason)})",
                        )
                    }

                    override fun onDeviceReady(device: BluetoothDevice) {
                        sessionLog("Device ready for Omron commands.")
                    }

                    override fun onDeviceDisconnecting(device: BluetoothDevice) {
                        sessionLog("Disconnecting from device.")
                    }

                    override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                        sessionLog(
                            "Disconnected from device: reason=$reason " +
                                "(${describeDisconnectionReason(reason)})",
                        )
                    }
                },
            )
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            sessionLog("Services discovered.")
            val service = gatt.getService(model.serviceUuid)
            txCharacteristic = service?.getCharacteristic(model.txUuid)
            rxCharacteristic = service?.getCharacteristic(model.rxUuid)
            rxContinuationCharacteristic = model.rxContinuationUuid?.let(service::getCharacteristic)
            pairingBootstrapCharacteristic = model.pairingBootstrapUuid?.let(service::getCharacteristic)
            val supported = txCharacteristic?.supportsWrite() == true &&
                rxCharacteristic?.supportsUpdates() == true
            if (!supported) {
                sessionLog("Required Omron service or characteristics are missing.")
            } else if (model.rxContinuationUuid != null && rxContinuationCharacteristic == null) {
                sessionLog("Optional Omron RX continuation characteristic is missing.")
            }
            if (model.pairingBootstrapUuid != null && pairingBootstrapCharacteristic == null) {
                sessionLog("Optional Omron pairing-key characteristic is missing.")
            }
            return supported
        }

        override fun initialize() {
            sessionLog("Requesting MTU $MTU...")
            requestMtu(MTU)
                .with { _, mtu ->
                    sessionLog("MTU ready: $mtu")
                }
                .fail { _, status ->
                    sessionLog("MTU request failed: ${describeRequestFailure(status)}")
                }
                .enqueue()

            registerUpdateCallback(
                characteristic = requireRxCharacteristic(),
                label = "RX primary",
                startsPacket = true,
            )
            enableUpdates(
                characteristic = requireRxCharacteristic(),
                label = "RX primary",
            ).enqueue()

            rxContinuationCharacteristic?.let { continuationCharacteristic ->
                registerUpdateCallback(
                    characteristic = continuationCharacteristic,
                    label = "RX continuation",
                    startsPacket = false,
                )
                enableUpdates(
                    characteristic = continuationCharacteristic,
                    label = "RX continuation",
                ).enqueue()
            }

            pairingBootstrapCharacteristic?.let { pairingCharacteristic ->
                registerPairingCallback(pairingCharacteristic)
            }
        }

        override fun onServicesInvalidated() {
            txCharacteristic = null
            rxCharacteristic = null
            rxContinuationCharacteristic = null
            pairingBootstrapCharacteristic = null
        }

        override fun shouldClearCacheWhenDisconnected(): Boolean {
            return model.clearGattCacheOnDisconnect
        }

        override fun log(priority: Int, message: String) {
            if (priority >= Log.WARN) {
                sessionLog("Nordic[$priority]: $message")
            }
        }

        suspend fun connectTo(device: BluetoothDevice) {
            try {
                connect(device)
                    .retry(CONNECTION_RETRY_COUNT, CONNECTION_RETRY_DELAY_MS)
                    .timeout(CONNECTION_TIMEOUT_MS)
                    .suspend()
            } catch (_: SecurityException) {
                throw MissingBluetoothPermissionException()
            }
        }

        suspend fun writeCommand(command: ByteArray) {
            try {
                writeCharacteristic(
                    requireTxCharacteristic(),
                    command,
                    requireTxCharacteristic().bestWriteType(),
                ).suspend()
            } catch (_: SecurityException) {
                throw MissingBluetoothPermissionException()
            }
        }

        suspend fun closeConnection() {
            if (isConnected) {
                runCatching {
                    disconnect().suspend()
                }.onFailure { error ->
                    sessionLog(
                        "Disconnect request failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
            close()
        }

        private fun requireTxCharacteristic(): BluetoothGattCharacteristic {
            return txCharacteristic ?: throw IllegalStateException("TX characteristic not found.")
        }

        private fun requireRxCharacteristic(): BluetoothGattCharacteristic {
            return rxCharacteristic ?: throw IllegalStateException("RX characteristic not found.")
        }

        fun hasPairingBootstrapCharacteristic(): Boolean = pairingBootstrapCharacteristic != null

        suspend fun enablePairingUpdates() {
            val characteristic = pairingBootstrapCharacteristic ?: return
            enableUpdates(
                characteristic = characteristic,
                label = "Pairing bootstrap",
            ).suspend()
        }

        suspend fun disablePairingUpdates() {
            val characteristic = pairingBootstrapCharacteristic ?: return
            disableUpdates(
                characteristic = characteristic,
                label = "Pairing bootstrap",
            ).suspend()
        }

        suspend fun writePairingCommand(command: ByteArray) {
            val characteristic = pairingBootstrapCharacteristic
                ?: throw IllegalStateException("Pairing characteristic not found.")
            try {
                writeCharacteristic(
                    characteristic,
                    command,
                    characteristic.bestWriteType(),
                ).suspend()
            } catch (_: SecurityException) {
                throw MissingBluetoothPermissionException()
            }
        }

        private fun registerUpdateCallback(
            characteristic: BluetoothGattCharacteristic,
            label: String,
            startsPacket: Boolean,
        ) {
            setNotificationCallback(characteristic)
                .setHandler(null)
                .with { _, data ->
                    val fragment = data.value ?: ByteArray(0)
                    if (fragment.isEmpty()) {
                        sessionLog("$label fragment was empty.")
                        return@with
                    }

                    sessionLog("$label fragment: ${fragment.toHexString()}")
                    val packets = packetAssembler.appendFragment(
                        fragment = fragment,
                        startsPacket = startsPacket,
                    )
                    packets.forEach { packet ->
                        sessionLog("RX packet: ${packet.toHexString()}")
                        onPacketReceived(packet)
                        onNotification?.invoke(packet)
                    }
                }
        }

        private fun registerPairingCallback(characteristic: BluetoothGattCharacteristic) {
            setNotificationCallback(characteristic)
                .setHandler(null)
                .with { _, data ->
                    val fragment = data.value ?: ByteArray(0)
                    if (fragment.isEmpty()) {
                        sessionLog("Pairing bootstrap fragment was empty.")
                        return@with
                    }
                    sessionLog("Pairing bootstrap fragment: ${fragment.toHexString()}")
                    onPairingNotification?.invoke(fragment)
                }
        }

        private fun enableUpdates(
            characteristic: BluetoothGattCharacteristic,
            label: String,
        ) = when {
            characteristic.isNotifiable() ->
                enableNotifications(characteristic)
                    .done {
                        sessionLog("$label notifications enabled.")
                    }
                    .fail { _, status ->
                        sessionLog("$label notifications failed: ${describeRequestFailure(status)}")
                    }

            characteristic.isIndicatable() ->
                enableIndications(characteristic)
                    .done {
                        sessionLog("$label indications enabled.")
                    }
                    .fail { _, status ->
                        sessionLog("$label indications failed: ${describeRequestFailure(status)}")
                    }

            else ->
                enableNotifications(characteristic)
                    .fail { _, status ->
                        sessionLog("$label enable attempt failed: ${describeRequestFailure(status)}")
                    }
        }

        private fun disableUpdates(
            characteristic: BluetoothGattCharacteristic,
            label: String,
        ) = when {
            characteristic.isNotifiable() ->
                disableNotifications(characteristic)
                    .done {
                        sessionLog("$label notifications disabled.")
                    }
                    .fail { _, status ->
                        sessionLog("$label notification disable failed: ${describeRequestFailure(status)}")
                    }

            characteristic.isIndicatable() ->
                disableIndications(characteristic)
                    .done {
                        sessionLog("$label indications disabled.")
                    }
                    .fail { _, status ->
                        sessionLog("$label indication disable failed: ${describeRequestFailure(status)}")
                    }

            else ->
                disableNotifications(characteristic)
                    .fail { _, status ->
                        sessionLog("$label disable attempt failed: ${describeRequestFailure(status)}")
                    }
        }
    }

    data class SyncResult(
        val measurements: List<Measurement>,
        val diagnostics: SyncDiagnostics,
        val capture: SyncCapture,
    )

    data class SyncDiagnostics(
        val entries: List<String>,
    ) {
        fun asText(): String = entries.joinToString(separator = "\n")
    }

    class SyncException(
        message: String,
        val diagnostics: SyncDiagnostics,
        val capture: SyncCapture,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    class CommandTimeoutException(cause: Throwable) :
        IllegalStateException("Timed out waiting for response.", cause)

    private class SyncCaptureBuilder(
        private val model: OmronDeviceDefinition,
        private val deviceName: String?,
        private val deviceAddress: String?,
    ) {
        private val packets = mutableListOf<SyncPacketCapture>()
        private val records = mutableListOf<SyncRecordCapture>()

        fun addPacket(direction: SyncPacketDirection, payload: ByteArray) {
            packets += SyncPacketCapture(
                direction = direction,
                hex = payload.toHexString(),
            )
        }

        fun addRecord(
            user: Int,
            address: Int,
            recordBytes: ByteArray,
            measurement: Measurement?,
        ) {
            records += SyncRecordCapture(
                user = user,
                address = address,
                hex = recordBytes.toHexString(),
                measurement = measurement?.let {
                    CapturedMeasurement(
                        recordedAt = it.recordedAt,
                        systolic = it.systolic,
                        diastolic = it.diastolic,
                        pulse = it.pulse,
                        irregularHeartbeat = it.irregularHeartbeat,
                        movement = it.movement,
                    )
                },
            )
        }

        fun build(): SyncCapture {
            return SyncCapture(
                modelId = model.id,
                modelCode = model.modelCode,
                deviceName = deviceName,
                deviceAddress = deviceAddress,
                packets = packets.toList(),
                records = records.toList(),
            )
        }
    }

    private companion object {
        const val MTU = 185
        const val RESPONSE_TIMEOUT_MS = 5_000L
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val COMMAND_RETRY_COUNT = 3
        const val COMMAND_RETRY_DELAY_MS = 400L
        const val CONNECTION_RETRY_COUNT = 3
        const val CONNECTION_RETRY_DELAY_MS = 250
        const val PAIRING_RESPONSE_TIMEOUT_MS = 1_000L

        const val RESPONSE_START = 0x8000
        const val RESPONSE_READ = 0x8100
        const val RESPONSE_END = 0x8F00
        val DIAGNOSTIC_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

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

        fun describeRequestFailure(status: Int): String = when (status) {
            FailCallback.REASON_DEVICE_DISCONNECTED -> "DEVICE_DISCONNECTED"
            FailCallback.REASON_DEVICE_NOT_SUPPORTED -> "DEVICE_NOT_SUPPORTED"
            FailCallback.REASON_NULL_ATTRIBUTE -> "NULL_ATTRIBUTE"
            FailCallback.REASON_REQUEST_FAILED -> "REQUEST_FAILED"
            FailCallback.REASON_TIMEOUT -> "TIMEOUT"
            FailCallback.REASON_VALIDATION -> "VALIDATION"
            FailCallback.REASON_CANCELLED -> "CANCELLED"
            FailCallback.REASON_NOT_ENABLED -> "NOT_ENABLED"
            FailCallback.REASON_UNSUPPORTED_CONFIGURATION -> "UNSUPPORTED_CONFIGURATION"
            FailCallback.REASON_BLUETOOTH_DISABLED -> "BLUETOOTH_DISABLED"
            in Int.MIN_VALUE..-101 -> "REASON_$status"
            in -100..-1 -> "REASON_$status"
            else -> "${GattError.parse(status)} ($status)"
        }

        fun describeDisconnectionReason(reason: Int): String = when (reason) {
            ConnectionObserver.REASON_SUCCESS -> "SUCCESS"
            ConnectionObserver.REASON_TERMINATE_LOCAL_HOST -> "TERMINATE_LOCAL_HOST"
            ConnectionObserver.REASON_TERMINATE_PEER_USER -> "TERMINATE_PEER_USER"
            ConnectionObserver.REASON_LINK_LOSS -> "LINK_LOSS"
            ConnectionObserver.REASON_NOT_SUPPORTED -> "NOT_SUPPORTED"
            ConnectionObserver.REASON_CANCELLED -> "CANCELLED"
            ConnectionObserver.REASON_TIMEOUT -> "TIMEOUT"
            ConnectionObserver.REASON_UNSUPPORTED_CONFIGURATION -> "UNSUPPORTED_CONFIGURATION"
            ConnectionObserver.REASON_UNKNOWN -> "UNKNOWN"
            else -> "REASON_$reason"
        }
    }
}

internal class OmronPacketAssembler {
    private val bufferedBytes = mutableListOf<Byte>()
    private var expectedPacketSize: Int? = null

    fun appendFragment(
        fragment: ByteArray,
        startsPacket: Boolean,
    ): List<ByteArray> {
        if (fragment.isEmpty()) {
            return emptyList()
        }

        if (startsPacket) {
            bufferedBytes.clear()
            expectedPacketSize = fragment.first().toUByte().toInt()
        } else if (expectedPacketSize == null) {
            return emptyList()
        }

        bufferedBytes += fragment.toList()
        return drainCompletedPackets()
    }

    private fun drainCompletedPackets(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()

        while (true) {
            val packetSize = expectedPacketSize ?: break
            if (bufferedBytes.size < packetSize) {
                break
            }

            val packet = ByteArray(packetSize) { index -> bufferedBytes[index] }
            packets += packet
            bufferedBytes.subList(0, packetSize).clear()
            expectedPacketSize = bufferedBytes.firstOrNull()?.toUByte()?.toInt()
        }

        return packets
    }
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) {
        "Hex text must have an even number of characters."
    }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun BluetoothGattCharacteristic.supportsWrite(): Boolean {
    return isWritable() || isWritableWithoutResponse()
}

private fun BluetoothGattCharacteristic.supportsUpdates(): Boolean {
    return isNotifiable() || isIndicatable()
}

private fun BluetoothGattCharacteristic.isWritable(): Boolean {
    return properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
}

private fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean {
    return properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
}

private fun BluetoothGattCharacteristic.isNotifiable(): Boolean {
    return properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
}

private fun BluetoothGattCharacteristic.isIndicatable(): Boolean {
    return properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
}

private fun BluetoothGattCharacteristic.bestWriteType(): Int {
    return when {
        isWritableWithoutResponse() -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else -> error("TX characteristic is not writable.")
    }
}
