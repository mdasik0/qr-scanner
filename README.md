# How to use?

[**Download QR Scan v0.8.0**](https://github.com/mdasik0/qr-scanner/raw/main/downloads/QR-Scan-v0.8.0.apk)

Install the APK. Enter your **API base URL** (example `http://192.168.1.10:5011`). No login.

Then pick a mode:

- **See data** — scan a QR and show the result inside the app.
- **Use as scanner** — scan a QR and send it to your API so you can update a website or do anything else with it.

---

## See data

The user scans a QR. The app posts that QR to your API, then draws whatever you return.

```js
app.post("/see-data", async (req, res) => {
  const qr = req.body.qr; // text from the QR

  // do whatever you need with the QR
  const item = await db.findByQr(qr);

  // you MUST return this format — this is the only shape the app can draw
  return res.json({
    title: "Milk",
    columns: ["Field", "Value"],
    rows: [
      ["Item", "Milk"],
      ["Qty", "500 g"],
      ["Batch", qr],
    ],
  });
});
```

Example request the app sends:

```json
{ "qr": "BTH-2026-12-3" }
```

Example response the app can show:

```json
{
  "title": "Milk",
  "columns": ["Field", "Value"],
  "rows": [
    ["Item", "Milk"],
    ["Qty", "500 g"]
  ]
}
```

`title` is the headline. `columns` are the table headers. `rows` is a list of rows. Each row is a list of cells, same length as `columns`.

If this format is missing, the camera stays on and the app shows a warning instead of a table.

---

## Use as scanner

Same idea: the app posts the QR. You use it anywhere — live website, socket, database, etc.

```js
app.post("/scan", async (req, res) => {
  const qr = req.body.qr; // text from the QR

  // do whatever with the QR — same as pushing it into a website
  io.emit("qr", qr);
  await db.saveScan(qr);

  return res.json({ message: "Sent" });
});
```

Example request the app sends:

```json
{ "qr": "BTH-2026-12-3" }
```

`/scan` does not have to return a table. If you do return the same `{ title, columns, rows }` format as `/see-data`, the app will also draw it.
