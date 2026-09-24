import "dotenv/config";
import cors from "cors";
import express from "express";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Server } from "socket.io";
import { z } from "zod";
import { decodeQrFromImageBuffer } from "./decode-image.js";
import { lookupItemCheckingTable } from "./item-checking-lookup.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");

const PORT = Number(process.env.QR_BRIDGE_PORT) || 5055;
const HOST = process.env.QR_BRIDGE_HOST || "0.0.0.0";
const INGEST_KEY = process.env.QR_INGEST_KEY || "dev-qr-ingest-key";

const createSchema = z.object({
  raw_payload: z.string().trim().min(1).max(8000),
  device_id: z.string().trim().min(1).max(200),
  device_label: z.string().trim().max(200).optional().nullable(),
  format: z.string().trim().max(64).optional(),
  meta: z.record(z.unknown()).optional().nullable(),
  scanned_at: z.string().datetime().optional(),
});

export type LiveQrScan = {
  id: number;
  raw_payload: string;
  device_id: string;
  device_label: string | null;
  format: string;
  meta: Record<string, unknown> | null;
  scanned_at: string;
  created_at: string;
};

function requireIngestKey(
  req: express.Request,
  res: express.Response,
  next: express.NextFunction
) {
  const key = req.header("x-qr-ingest-key");
  if (!key || key !== INGEST_KEY) {
    res.status(401).json({
      success: false,
      message: "Invalid or missing X-QR-Ingest-Key",
    });
    return;
  }
  next();
}

const app = express();
app.use(cors({ origin: true }));
app.use(express.json({ limit: "8mb" }));

const latestApk = path.join(publicDir, "sumo-qr-scan-v070.apk");
function sendLatestApk(_req: express.Request, res: express.Response) {
  res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
  res.setHeader("Content-Type", "application/vnd.android.package-archive");
  res.sendFile(latestApk);
}
app.get("/sumo-qr-scan-v070.apk", sendLatestApk);
app.get("/sumo-qr-scan-v060.apk", sendLatestApk);
app.get("/sumo-qr-scan-v050.apk", sendLatestApk);
app.get("/sumo-qr-scan-v040.apk", sendLatestApk);
app.get("/sumo-qr-scan-v030.apk", sendLatestApk);
app.get("/sumo-qr-scan.apk", sendLatestApk);
app.get("/download-apk", sendLatestApk);

app.use(express.static(publicDir));

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "qr-bridge", mode: "ephemeral" });
});

/** Install the native APK — /scan is the old browser camera. */
app.get("/", (_req, res) => {
  res.redirect(302, "/install");
});

app.get("/install", (_req, res) => {
  res.sendFile(path.join(publicDir, "install.html"));
});

app.get("/scan", (_req, res) => {
  res.sendFile(path.join(publicDir, "scan.html"));
});

app.get("/manifest.webmanifest", (_req, res) => {
  res.type("application/manifest+json");
  res.sendFile(path.join(publicDir, "manifest.webmanifest"));
});

app.get("/scan/config.json", (_req, res) => {
  res.json({
    ingestKey: INGEST_KEY,
    apiBase: "",
  });
});

const decodeImageSchema = z.object({
  image_base64: z.string().min(32).max(10_000_000),
});

/** Decode QR from a photo (client fallback when on-device decode fails). */
app.post("/api/v1/qr-decode", requireIngestKey, async (req, res) => {
  const parsed = decodeImageSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({
      success: false,
      message: "Validation failed",
      errors: parsed.error.flatten(),
    });
    return;
  }

  try {
    const raw = parsed.data.image_base64.replace(/^data:image\/\w+;base64,/, "");
    const buffer = Buffer.from(raw, "base64");
    if (!buffer.length) {
      res.status(400).json({ success: false, message: "Empty image" });
      return;
    }
    const text = await decodeQrFromImageBuffer(buffer);
    if (!text) {
      res.status(422).json({
        success: false,
        message:
          "No QR found — if it is on a screen, use a screenshot (not a camera photo), or paste the text",
      });
      return;
    }
    res.json({ success: true, message: "QR decoded", data: { raw_payload: text } });
  } catch (err) {
    console.error("[qr-bridge] decode failed", err);
    res.status(500).json({ success: false, message: "Decode failed" });
  }
});

/**
 * Process a QR on the phone "See data" path.
 * Phone hits only this route. The bridge then calls Espresso
 * (GRN, stock transfers, items, branches, …) and returns one table.
 */
app.post("/api/v1/qr-scans/lookup", requireIngestKey, async (req, res) => {
  const parsed = createSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({
      success: false,
      message: "Validation failed",
      errors: parsed.error.flatten(),
    });
    return;
  }

  try {
    const table = await lookupItemCheckingTable(parsed.data.raw_payload);
    res.json({
      success: true,
      message: "QR processed",
      data: table,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : "Lookup failed";
    console.error("[qr-bridge] lookup failed", err);
    res.status(502).json({
      success: false,
      message,
    });
  }
});

/**
 * Broadcast a scan to Espresso clients over Socket.IO.
 * Nothing is persisted — phone → web only.
 */
app.post("/api/v1/qr-scans", requireIngestKey, (req, res) => {
  const parsed = createSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({
      success: false,
      message: "Validation failed",
      errors: parsed.error.flatten(),
    });
    return;
  }

  const now = new Date().toISOString();
  const row: LiveQrScan = {
    id: Date.now(),
    raw_payload: parsed.data.raw_payload,
    device_id: parsed.data.device_id,
    device_label: parsed.data.device_label ?? null,
    format: parsed.data.format ?? "QR_CODE",
    meta: parsed.data.meta ?? null,
    scanned_at: parsed.data.scanned_at ?? now,
    created_at: now,
  };

  io.to("qr").emit("qr:scan", row);

  res.status(202).json({
    success: true,
    message: "QR scan broadcast",
    data: row,
  });
});

/** No history — scans are ephemeral. */
app.get("/api/v1/qr-scans", requireIngestKey, (_req, res) => {
  res.json({
    success: true,
    message: "Scans are not stored (ephemeral broadcast only)",
    data: [],
  });
});

const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: true },
});

io.use((socket, next) => {
  const token =
    (socket.handshake.auth?.token as string | undefined) ||
    (socket.handshake.headers["x-qr-ingest-key"] as string | undefined);
  if (token && token !== INGEST_KEY) {
    next(new Error("unauthorized"));
    return;
  }
  next();
});

io.on("connection", (socket) => {
  socket.join("qr");
  socket.on("qr:join", () => socket.join("qr"));
});

server.listen(PORT, HOST, () => {
  console.log(`[qr-bridge] listening on http://${HOST}:${PORT}`);
  console.log(`[qr-bridge] phone scanner: http://<LAN-IP>:${PORT}/scan`);
  console.log(`[qr-bridge] mode: ephemeral (no DB) · ingest: X-QR-Ingest-Key`);
});
