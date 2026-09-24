import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const dataDir = path.join(__dirname, "..", "data");
const dbPath = path.join(dataDir, "qr-scans.json");

export type QrScan = {
  id: number;
  raw_payload: string;
  device_id: string;
  device_label: string | null;
  format: string;
  meta: Record<string, unknown> | null;
  scanned_at: string;
  created_at: string;
};

type Store = { nextId: number; scans: QrScan[] };

function ensureStore(): Store {
  if (!fs.existsSync(dataDir)) {
    fs.mkdirSync(dataDir, { recursive: true });
  }
  if (!fs.existsSync(dbPath)) {
    const empty: Store = { nextId: 1, scans: [] };
    fs.writeFileSync(dbPath, JSON.stringify(empty, null, 2), "utf8");
    return empty;
  }
  const raw = fs.readFileSync(dbPath, "utf8");
  return JSON.parse(raw) as Store;
}

function saveStore(store: Store) {
  fs.writeFileSync(dbPath, JSON.stringify(store, null, 2), "utf8");
}

export function insertScan(input: {
  raw_payload: string;
  device_id: string;
  device_label?: string | null;
  format?: string;
  meta?: Record<string, unknown> | null;
  scanned_at?: string;
}): QrScan {
  const store = ensureStore();
  const now = new Date().toISOString();
  const row: QrScan = {
    id: store.nextId++,
    raw_payload: input.raw_payload,
    device_id: input.device_id,
    device_label: input.device_label ?? null,
    format: input.format ?? "QR_CODE",
    meta: input.meta ?? null,
    scanned_at: input.scanned_at ?? now,
    created_at: now,
  };
  store.scans.unshift(row);
  // keep last 5000 locally
  if (store.scans.length > 5000) {
    store.scans.length = 5000;
  }
  saveStore(store);
  return row;
}

export function listScans(options: {
  limit?: number;
  device_id?: string;
}): QrScan[] {
  const store = ensureStore();
  const limit = Math.min(Math.max(options.limit ?? 50, 1), 200);
  let rows = store.scans;
  if (options.device_id) {
    rows = rows.filter((s) => s.device_id === options.device_id);
  }
  return rows.slice(0, limit);
}

export function getScanById(id: number): QrScan | null {
  const store = ensureStore();
  return store.scans.find((s) => s.id === id) ?? null;
}
