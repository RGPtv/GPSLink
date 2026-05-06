# GPSLink

> Android app that bridges a u-blox GPS/GNSS receiver connected over USB directly into Android's mock location provider — no root required.

<br>

## Overview

GPSLink connects to a u-blox GNSS module via USB OTG, parses raw NMEA sentences in real-time, and injects position data into Android's mock location system. Any app that reads device location (Maps, navigation, survey tools, etc.) will see the u-blox fix instead of the built-in GPS.

<br>

## Features

- **Multi-constellation GNSS** — GPS, GLONASS, Galileo, BeiDou, QZSS, and SBAS (MSAS/WAAS/EGNOS/GAGAN) all parsed and displayed
- **USB auto-connect** — plugging in a u-blox device launches the app automatically via USB device attached intent
- **Mock location injection** — feeds position, altitude, speed, bearing, and accuracy into Android's `LocationManager` mock provider
- **Selectable update rate** — 1 Hz or 5 Hz, applied live via `UBX-CFG-RATE` without reconnecting
- **Satellite signal bars** — per-constellation SNR bars (GPS / GLO / GAL / BDU / QZS / SBS / GNS) updated from GSV sentences
- **Compass view** — live heading needle with cardinal direction label
- **Fix quality indicator** — color-coded: green (GPS / RTK Fixed / RTK Float), amber (DGPS/SBAS / Dead Reckoning), red (No Fix / Stale)
- **Stale fix detection** — fix label turns red automatically if no valid position is received for 10 seconds
- **Serial diagnostics** — live sentence count and bytes received shown in the UI
- **Foreground service** — runs reliably in the background with a persistent notification; survives app backgrounding

<br>

## Supported Hardware

Any u-blox receiver with USB VID `0x1546` (5446 decimal):

| Product ID | Module |
|---|---|
| 0x01A7 (423) | u-blox M7 series |
| 0x01A8 (424) | u-blox M8 series |
| 0x01A9 (425) | u-blox M9 series |
| 0x01AA (426) | u-blox M10 series |
| any 0x1546   | Generic u-blox fallback |

> **Note:** Multi-constellation (Galileo, BeiDou) requires M8 or newer. M7 supports GPS + GLONASS only and silently ignores the extra CFG-GNSS blocks.

<br>

## Requirements

- Android 8.0 (API 26) or higher
- USB OTG cable or adapter
- **Developer Options → Mock Location App** set to GPSlink (see Setup below)

<br>

## Setup

### 1. Enable Mock Locations

1. Go to **Settings → About Phone** and tap **Build Number** 7 times to enable Developer Options
2. Go to **Settings → Developer Options → Select mock location app**
3. Choose **GPSlink**

### 2. Grant Location Permission

On first launch, grant **Precise Location** permission when prompted. The app cannot inject mock locations without it.

### 3. Connect Your Receiver

Plug your u-blox module into the Android device via USB OTG. The app will either launch automatically or prompt for USB permission.

### 4. Start the Service

Tap **Start**. The status dot turns green and the connection label shows the detected device name, baud rate, and active Hz rate.

<br>

## Interface

| Element | Description |
|---|---|
| **Status dot** | Green = service active and receiving data, grey = idle |
| **Connection label** | Device name · baud rate · Hz rate |
| **Signal field** | Raw signal quality string from the module |
| **Latitude / Longitude** | Decimal degrees with N/S/E/W hemisphere |
| **Altitude** | Metres above mean sea level (from GGA) |
| **Speed** | km/h converted from NMEA knots |
| **Course** | True heading in degrees |
| **HDOP** | Horizontal dilution of precision |
| **Fix type** | GPS / DGPS/SBAS / RTK Fixed / RTK Float / Dead Reckoning / No Fix / Stale |
| **Compass** | Live rotating needle from RMC bearing field |
| **Sats in view** | Total satellites visible across all constellations |
| **Sats used** | Satellites contributing to the current fix |
| **Signal bars** | Per-constellation average SNR and satellite count |
| **Serial log** | Last raw NMEA sentence received |
| **Sentence count / bytes** | Total data received since service start |
| **Last fix** | UTC timestamp of the most recent valid position |

<br>

## Hz Rate

| Rate | UBX command | Use case |
|---|---|---|
| **1 Hz** | `UBX-CFG-RATE 1000 ms` | Default — lowest power, suitable for most use |
| **5 Hz** | `UBX-CFG-RATE 200 ms` | Faster updates for moving vehicles or survey work |

Switching rates sends a new `UBX-CFG-RATE` command immediately without restarting the service.

<br>

## GNSS Configuration

On connect, the app sends the following UBX commands to the module:

| Command | Effect |
|---|---|
| `UBX-CFG-RATE` | Sets update rate to selected Hz |
| `UBX-CFG-GNSS` | Enables GPS + GLONASS + Galileo + BeiDou (M8+ only) |
| `UBX-CFG-SBAS` | Enables SBAS ranging and correction (auto-scans all PRNs) |
| `UBX-CFG-MSG` | Enables GGA, RMC, GSV — disables GLL, GSA, VTG, GPTXT |
| `UBX-CFG-CFG` | Saves configuration to flash (persists across power cycles) |

<br>

## NMEA Sentences Parsed

| Sentence | Data extracted |
|---|---|
| `GGA` / `GNGGA` | Latitude, longitude, altitude, fix quality, satellite count, HDOP |
| `RMC` / `GNRMC` | Latitude, longitude, speed, bearing, UTC date/time |
| `GSV` / `GLGSV` / `GAGSV` / `GBGSV` | Per-satellite PRN, elevation, azimuth, SNR — all constellations |

Sentences with invalid checksums are silently discarded. Fix quality 0 (no fix) and RMC status `V` (void) are also discarded.

<br>

## Constellation Labels

| Label | System |
|---|---|
| `GPS` | US Global Positioning System |
| `GLO` | Russian GLONASS |
| `GAL` | European Galileo |
| `BDU` | Chinese BeiDou |
| `QZS` | Japanese QZSS |
| `SBS` | SBAS (MSAS / WAAS / EGNOS / GAGAN) |
| `GNS` | Multi-constellation combined (`$GN` talker) |

SBAS satellites are identified by PRN range 120–158 regardless of which talker prefix the module uses.

<br>

## Project Structure

```
app/src/main/java/com/gpslink/
├── MainActivity.java        — UI, broadcast receiver, state restore
├── UsbSerialService.java    — Foreground service, USB I/O, UBX config, mock location
├── NmeaParser.java          — NMEA sentence parser (GGA, RMC, GSV) + checksum verification
├── CompassView.java         — Custom view: rotating compass needle
└── SignalBarsView.java      — Custom view: per-constellation SNR bar chart
```

<br>

## Building

```bash
# Clone and open in Android Studio, or build from CLI:
./gradlew assembleDebug

# Install to connected device:
./gradlew installDebug
```

**Minimum requirements:**

- Android Studio Hedgehog or newer
- JDK 8+
- Android SDK 34

**Dependencies** (declared in `app/build.gradle`):

```groovy
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.github.mik3y:usb-serial-for-android:3.4.6'
```

<br>

## Permissions

| Permission | Reason |
|---|---|
| `ACCESS_FINE_LOCATION` | Required to register a mock location provider |
| `ACCESS_COARSE_LOCATION` | Fallback location access |
| `ACCESS_MOCK_LOCATION` | Inject positions into Android's location system |
| `FOREGROUND_SERVICE` | Keep the USB service alive in the background |
| `FOREGROUND_SERVICE_LOCATION` | Android 14+ foreground service type for location |
| `WAKE_LOCK` | Prevent CPU sleep during active data stream |
| `android.hardware.usb.host` | USB OTG host feature declaration |

<br>
