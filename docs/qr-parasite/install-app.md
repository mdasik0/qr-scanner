# Install QR Scan

Native Android APK. After install you should see **Native app v0.7.0** and a backend login screen.

## From a phone

1. Uninstall any old **QR Scan** app and any home-screen shortcut that opens Chrome.
2. On the same Wi‑Fi as the PC running qr-bridge, open `http://<PC-LAN-IP>:5055/install`.
3. Download and install the APK.
4. Open **QR Scan** from the app drawer — not from the browser.
5. Sign in with the **API host** (example `http://192.168.x.x:5011`), email/username, and password.

That host must implement the contract in [doc.md](./doc.md).

## Build the APK yourself

From `qr-scanner-android/`:

```bat
build-apk.bat
```

```bash
chmod +x build-apk.sh && ./build-apk.sh
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

First time: open that folder in Android Studio once so the SDK path and Gradle wrapper exist.

`local.properties` (created/updated by the build script):

```properties
sdk.dir=C:\\Users\\You\\AppData\\Local\\Android\\Sdk
qr.api.base.url=http://192.168.x.x:5011
```

Use the PC LAN IP, not `localhost`. Emulator: `http://10.0.2.2:5011`.

## Browser fallback (no Android Studio)

Phone → `http://<PC-LAN-IP>:5055/scan` → Chrome **Add to Home screen**.

That page talks to qr-bridge, not the native app login flow.
