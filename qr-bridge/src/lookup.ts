const BATCH_ID_PATTERN = /BTH-\d{4}-\d+-\d+/i;

export type LookupTable = {
  title: string;
  columns: string[];
  rows: string[][];
};

function normalizeScan(raw: string): string {
  let value = raw.trim();
  if (!value) return "";

  if (value.includes("%")) {
    try {
      value = decodeURIComponent(value.replace(/\+/g, " "));
    } catch {
      // keep original when encoding is malformed
    }
  }

  value = value.replace(/^[\x00-\x1F\x7F]+/, "").trim();
  value = value.replace(/sub_batch_id\s*:\s*/gi, "");
  value = value.replace(/^["']+|["']+$/g, "").replace(/["']/g, "").trim();
  return value;
}

export function parseBatchId(raw: string): string | null {
  const value = normalizeScan(raw);
  if (!value) return null;

  const batchMatch = value.match(BATCH_ID_PATTERN);
  if (batchMatch) return batchMatch[0];

  if (/^BTH-/i.test(value)) {
    const lastDash = value.lastIndexOf("-");
    if (lastDash > 0) return value.slice(0, lastDash);
  }

  return null;
}

function cellValue(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function flattenObject(
  value: Record<string, unknown>,
  prefix = ""
): [string, string][] {
  const out: [string, string][] = [];
  for (const [key, nested] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (nested !== null && typeof nested === "object" && !Array.isArray(nested)) {
      out.push(...flattenObject(nested as Record<string, unknown>, path));
    } else {
      out.push([path, cellValue(nested)]);
    }
  }
  return out;
}

function tableFromRecords(records: Record<string, unknown>[]): LookupTable | null {
  if (records.length === 0) return null;
  const columns: string[] = [];
  for (const row of records) {
    for (const key of Object.keys(row)) {
      if (!columns.includes(key)) columns.push(key);
    }
  }
  return {
    title: "Scan result",
    columns,
    rows: records.map((row) => columns.map((column) => cellValue(row[column]))),
  };
}

function tryParseJson(raw: string): unknown {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    return null;
  }
}

/**
 * Turn any QR payload into a table the phone can render.
 * Production APIs may return their own columns/rows; this is the
 * local processor used by qr-bridge.
 */
export function processQrPayload(raw: string): LookupTable {
  const parsedJson = tryParseJson(raw);

  if (parsedJson && typeof parsedJson === "object" && !Array.isArray(parsedJson)) {
    const obj = parsedJson as Record<string, unknown>;
    if (Array.isArray(obj.columns) && Array.isArray(obj.rows)) {
      return {
        title: typeof obj.title === "string" ? obj.title : "Scan result",
        columns: obj.columns.map((column) => cellValue(column)),
        rows: obj.rows.map((row) => {
          if (Array.isArray(row)) return row.map((cell) => cellValue(cell));
          if (row && typeof row === "object") {
            return (obj.columns as unknown[]).map((column) =>
              cellValue((row as Record<string, unknown>)[String(column)])
            );
          }
          return [cellValue(row)];
        }),
      };
    }
    if (Array.isArray(obj.records)) {
      const records = obj.records.filter(
        (row): row is Record<string, unknown> =>
          Boolean(row) && typeof row === "object" && !Array.isArray(row)
      );
      const table = tableFromRecords(records);
      if (table) return table;
    }
  }

  if (Array.isArray(parsedJson)) {
    const records = parsedJson.filter(
      (row): row is Record<string, unknown> =>
        Boolean(row) && typeof row === "object" && !Array.isArray(row)
    );
    const table = tableFromRecords(records);
    if (table) return table;
  }

  const rows: [string, string][] = [["Raw payload", raw]];
  const batchId = parseBatchId(raw);
  if (batchId) {
    rows.push(["Batch ID", batchId]);
    rows.push(["Kind", "sub-batch / item batch"]);
  }

  if (parsedJson && typeof parsedJson === "object" && !Array.isArray(parsedJson)) {
    rows.push(...flattenObject(parsedJson as Record<string, unknown>));
  } else {
    try {
      const url = new URL(raw);
      rows.push(["Type", "URL"]);
      rows.push(["Host", url.host]);
      rows.push(["Path", url.pathname]);
      if (url.search) rows.push(["Query", url.search.slice(1)]);
    } catch {
      if (!batchId) rows.push(["Type", "text"]);
    }
  }

  rows.push(["Processed at", new Date().toISOString()]);

  return {
    title: batchId ? `Batch ${batchId}` : "Scan result",
    columns: ["Field", "Value"],
    rows: rows.map(([field, value]) => [field, value]),
  };
}
