# UbloxBridge

Reads u-blox 7 GPS over USB Serial and mocks both GPS and Network location providers on Android.

## Build Instructions

### Option A — GitHub Actions (Recommended, no Android Studio needed)
1. Create a GitHub account at github.com
2. Create a new repository called "UbloxBridge"
3. Upload all these files keeping the folder structure
4. Go to Actions tab → "Build APK" → Run workflow
5. Download the APK from the artifacts when done

### Option B — Android Studio
1. Open Android Studio
2. File → Open → select this UbloxBridge folder
3. Wait for Gradle sync to finish
4. Build → Build Bundle(s)/APK(s) → Build APK(s)
5. APK will be at: app/build/outputs/apk/debug/app-debug.apk

## Setup on Phone
1. Install APK (enable "Install from unknown sources" if needed)
2. Go to Settings → Developer Options → Select mock location app → UbloxBridge
3. Plug in u-blox 7 via USB OTG
4. Open UbloxBridge → tap START
5. Grant location permission when asked
6. Open Google Maps — blue dot will follow u-blox position

## Notes
- Do NOT run Fake GPS (Lexa) at the same time
- The notification shows live coordinates when working
- Auto-reconnects if USB is unplugged and re-plugged
