# QR Scanner Android

Native APK for Sumo QR → Espresso Item Checking (ephemeral Socket.IO via qr-bridge).

## Quick build

```bat
build-apk.bat
```

```bash
chmod +x build-apk.sh && ./build-apk.sh
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

First time: open this folder in **Android Studio** once so the SDK path + Gradle wrapper exist.

## Config

`local.properties` (created/updated by the build script):

```properties
sdk.dir=C:\\Users\\You\\AppData\\Local\\Android\\Sdk
qr.bridge.base.url=http://192.168.x.x:5055
qr.ingest.key=dev-qr-ingest-key
```

Use your PC LAN IP — not `localhost`. Emulator: `http://10.0.2.2:5055`.

## No Android Studio?

Install the web scanner as an app instead:

Phone → `http://<PC-LAN-IP>:5055/scan` → Chrome **Add to Home screen**

How to use / API contract: [`../README.md`](../README.md)
