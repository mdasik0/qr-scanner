# How to use?

[**Download QR Scan v0.8.2**](https://github.com/mdasik0/qr-scanner/raw/main/downloads/QR-Scan-v0.8.2.apk)

Install the APK. Enter your **API base URL** (example `https://example.com`). No login.

Then pick a mode:

- **See data** — scan a QR and show the result inside the app.
- **Use as scanner** — scan a QR and send it to your API so you can update a website or do anything else with it.

Optional **Settings** lets you pick how results look: **Table**, **Cards**, or **List**.

---

## See data

The user scans a QR. The app posts that QR to your API, then draws the object you return. Any fields — the app parses the object and shows them.

```js
app.post("/see-data", async (req, res) => {
  const qr = req.body.qr; // example: string, object, or anything

  // example
  const item = await db.findByQr(qr);

  // you MUST return an object of fields — the app shows every key
  return res.json({
    title: "Milk",
    Item: "Milk",
    Qty: "500 g",
    Batch: qr,
  });
});
```

Example request the app sends:

```json
{ "qr": "<from the scan>" }
```

Example response — a field/value object. Use any keys:

```json
{
  "title": "Milk",
  "Item": "Milk",
  "Qty": "500 g",
  "Expiry": "2026-12-01"
}
```

Example response — many rows, each row is an object (not an array):

```json
{
  "title": "Batch",
  "rows": [
    { "Item": "Milk", "Qty": "500 g" },
    { "Item": "Sugar", "Qty": "1 kg" }
  ]
}
```

`title` is the headline. Everything else is shown as fields. The app does not need a `columns` list.

If the object has no fields to draw, the camera stays on and the app shows a warning.

---

## Use as scanner

Same idea: the app posts the QR. You use it anywhere — live website, socket, database, etc.

```js
app.post("/scan", async (req, res) => {
  const qr = req.body.qr; // example: string, object, or anything

  // example
  io.emit("qr", qr);
  await db.saveScan(qr);

  return res.json({ message: "Sent" });
});
```

Example request the app sends:

```json
{ "qr": "<from the scan>" }
```

`/scan` does not have to return fields. If you return the same object format as `/see-data`, the app will also draw it.
