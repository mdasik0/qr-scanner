# How to use

Guide for the **QR Scan** Android app (v0.7.0+) and for anyone building an API that works with it.

---

## Install the app

After install you should see **Native app v0.7.0** and a backend login screen.

1. Uninstall any old **QR Scan** app and any home-screen shortcut that opens Chrome.
2. On the same Wi‑Fi as the PC running qr-bridge, open `http://<PC-LAN-IP>:5055/install`.
3. Download and install the APK.
4. Open **QR Scan** from the app drawer — not from the browser.
5. Sign in with the **API host** (example `http://192.168.x.x:5011`), email/username, and password.

That host must implement the API below.

### Build the APK yourself

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

### Browser fallback

Phone → `http://<PC-LAN-IP>:5055/scan` → Chrome **Add to Home screen**.

That page talks to qr-bridge, not the native app login flow.

---

## How to make an API for this app

Implement these three endpoints. The user types only the host in the app (example: `http://192.168.1.10:5011`). The app always appends `/api/v1`.

### Auth

All scan routes need a Bearer token from login.

`Authorization: Bearer <accessToken>`  
`Content-Type: application/json`

If login or a later call returns **401** / “Unauthorized” / a message containing `token`, the app clears the session and shows the login screen again.

### 1. Login

`POST /api/v1/login`

Request:

```json
{
  "identifier": "wearhouse@espresso.com",
  "password": "secret"
}
```

`identifier` is email or username.

Success:

```json
{
  "success": true,
  "message": "Logged in",
  "data": {
    "accessToken": "eyJhbGciOi..."
  }
}
```

The app only reads `data.accessToken`. If that string is missing, login fails and `message` is shown.

### 2. Scanner mode — send + show

`POST /api/v1/qr-scans`

Use this when the user picks **Use as scanner**. Persist the scan, broadcast it to a website, or both — then **return a table** so the phone can draw it.

Request:

```json
{
  "raw_payload": "BTH-2026-12-3",
  "device_id": "a1b2c3d4e5f6g7h8",
  "device_label": "SM-A546E",
  "format": "QR_CODE",
  "meta": {
    "app_version": "0.7.0",
    "sdk": 34
  }
}
```

| Field | Required | Notes |
|---|---|---|
| `raw_payload` | yes | Exact text from the QR |
| `device_id` | yes | Android `ANDROID_ID` |
| `device_label` | no | Phone model |
| `format` | no | Always `QR_CODE` from this app |
| `meta` | no | App version + Android SDK |

Success — **show format** (required). HTTP **2xx**. `data` **must** be an object with `columns` (non-empty array) and `rows` (array). `title` is optional.

```json
{
  "success": true,
  "message": "QR scan accepted",
  "data": {
    "title": "Milk",
    "columns": ["Field", "Value"],
    "rows": [
      ["Item", "Milk"],
      ["Qty", "500 g"],
      ["Batch", "BTH-2026-12-3"]
    ]
  }
}
```

Rows may also be objects keyed by column name:

```json
{
  "success": true,
  "data": {
    "title": "Batch BTH-2026-12-3",
    "columns": ["Item", "Qty", "Expiry"],
    "rows": [
      { "Item": "Milk", "Qty": "500 g", "Expiry": "2026-12-01" }
    ]
  }
}
```

If the request succeeds but `data` is not this shape, the scan still counts as sent. The camera stays on and the app shows a yellow format guide instead of a table.

### 3. See data mode — show only

`POST /api/v1/qr-scans/lookup`

Same request body as `/qr-scans`. Same **show format** response.

Use this when the user picks **See data**. Look the QR up and return a table. Do not require a live website listener.

### Errors

Any non-2xx response. The app shows `message` if present:

```json
{
  "success": false,
  "message": "Batch not found"
}
```

Do not return HTML. If the body looks like a website (`<!DOCTYPE` / `<html`), the app tells the user they pointed at the website, not the API.

### Show format rules

The phone **only** draws a table when all of these are true:

1. Root JSON has `data` as an **object** (not a list, not the raw QR string).
2. `data.columns` is a non-empty array of header names.
3. `data.rows` is an array. Each row is either:
   - an array of cells, or
   - an object whose keys match `columns`.

`data.title` is the headline. If it is blank, the app uses root `message`, then `"Result"`.

Do not return:

- A flat object (`{ "item": "Milk", "qty": 1 }`)
- A bare list
- Only `{ "raw_payload": "..." }`
- The raw scan record (`id`, `device_id`, `scanned_at`, …) without `title` / `columns` / `rows`

Those answers are accepted as HTTP success, but the app will not draw them.

### URL the user types

| They type | App calls |
|---|---|
| `http://192.168.1.10:5011` | `http://192.168.1.10:5011/api/v1/...` |
| `http://192.168.1.10:5011/api/v1` | same — `/api/v1` is stripped, then added back |
| `http://host:3011` | rewritten to port **5011** |

Local HTTP is allowed. Use the PC LAN IP on a real phone, not `localhost`. Emulator: `http://10.0.2.2:5011`.

### Minimal backend sketch

```text
POST /api/v1/login
  → { data: { accessToken } }

POST /api/v1/qr-scans          (Bearer)
  → { data: { title, columns, rows } }

POST /api/v1/qr-scans/lookup   (Bearer)
  → { data: { title, columns, rows } }
```
