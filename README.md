# OmSyncer

OmSyncer is an Android app for syncing measurements from supported Omron blood pressure monitors over Bluetooth Low Energy, storing them locally, exporting them as CSV, and optionally writing them to Health Connect.

The project is based on reverse engineering work from [`omblepy`](https://github.com/userx14/omblepy) and [`UBPM`](https://codeberg.org/LazyT/ubpm).

## Features

- Sync measurements from a bonded Omron monitor over BLE
- Store measurements locally with duplicate suppression
- Export stored measurements to CSV
- Export blood pressure and pulse to Health Connect
- Optional auto-export to Health Connect after a successful sync
- Optional Health Connect export filtering by monitor user
- Optional periodic background sync using WorkManager
- BLE sync diagnostics with a dedicated log screen and log export

## Supported devices

### Verified

- `HEM-7380T1`
- `HEM-6232T` (Omron RS7 Intelli IT / Gold Wireless Wrist `BP4350`; word-swapped record format ported from [`omblepy`](https://github.com/userx14/omblepy))

### Experimental

- `HEM-7155T (V2)`
- `HEM-7155T (V3)`
- `HEM-7146T`

Experimental here means the protocol/configuration was added from UBPM data, but it has not been verified on real hardware in this repository yet.

## Build

Standard Android Studio workflows should work:

```sh
./gradlew :app:assembleDebug
```

## Testing

Unit tests:

```sh
./gradlew :app:testDebugUnitTest
```

Instrumentation tests:

```sh
./gradlew :app:connectedDebugAndroidTest
```
