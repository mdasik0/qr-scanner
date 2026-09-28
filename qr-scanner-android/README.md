# QR Scanner Android

Native APK. API contract: [`../README.md`](../README.md)

## Run on your phone from VS Code / Cursor

This is **not** Flutter-style hot reload. The app still compiles, but it installs over USB so you never download an APK.

1. On the phone: **Settings → About phone → tap Build number 7 times**.
2. **Settings → Developer options → USB debugging** on.
3. Plug the phone into this PC with a data cable. Unlock it and tap **Allow**.
4. In VS Code / Cursor: **Terminal → Run Task → Run QR Scan on phone**.

Or from a terminal:

```bat
cd qr-scanner-android
run-on-phone.bat
```

After you change Kotlin/XML, run the same task again. Gradle only rebuilds what changed (usually much faster than a full APK download).

Check the cable is a data cable, not charge-only. To confirm the phone is seen:

**Terminal → Run Task → List Android phones**

Wireless: plug in once, then `adb tcpip 5555`, unplug, `adb connect PHONE-IP:5555`, and run the task again.

Closest thing to live edits is **Android Studio → Apply Changes**. VS Code cannot do that for this CameraX app.

## Quick APK build (for sharing)

```bat
build-apk.bat
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
