package com.github.thiagokokada.omronsyncer.sync

sealed class SyncSetupException : IllegalStateException()

class NoBluetoothAdapterException : SyncSetupException()

class NoBondedBluetoothDevicesException : SyncSetupException()

class NoSelectedMonitorException : SyncSetupException()

class SelectedMonitorNotFoundException : SyncSetupException()

class MonitorNotBondedException : SyncSetupException()

class NoMeasurementsForSelectedUserException : SyncSetupException()
