import { espressoGet, espressoGetAllPages } from "./espresso-api.js";
import { parseBatchId, type LookupTable } from "./lookup.js";

type GrnItem = {
  id: number;
  po_id?: number | null;
  item_id: number;
  price?: number | string | null;
  ordered_quantity?: number | string | null;
  received_quantity?: number | string | null;
  unit_id?: number | null;
  comments?: string | null;
  batch_id?: string | null;
  supplier_id?: number | null;
  delivery_location?: number | null;
  requisition_id?: number | null;
  created_at?: string | null;
  expiry_date?: string | null;
};

type StockTransferLine = {
  id: number;
  item_id?: number | null;
  quantity?: number | string | null;
  received_quantity?: number | string | null;
  comments?: string | null;
  batch_id?: string | null;
  delivery_branch?: number | null;
  req_id?: number | null;
  created_at?: string | null;
  updated_at?: string | null;
  expiry_date?: string | null;
  grn_item_id?: number | null;
};

type CatalogItem = {
  id: number;
  name?: string | null;
  unit_id?: number | null;
};

type Named = { id: number; name?: string | null };
type Supplier = Named & { supplier_name?: string | null };
type PurchaseOrder = { id: number; po_code?: string | null };

export type CheckingRecord = {
  Item: string;
  Source: string;
  Batch: string;
  Requisition: string;
  PO: string;
  Supplier: string;
  Delivery: string;
  Ordered: string;
  Received: string;
  Price: string;
  "Received on": string;
  Expiry: string;
  Comments: string;
};

const COLUMNS: Array<keyof CheckingRecord> = [
  "Item",
  "Source",
  "Batch",
  "Requisition",
  "PO",
  "Supplier",
  "Delivery",
  "Ordered",
  "Received",
  "Price",
  "Received on",
  "Expiry",
  "Comments",
];

function num(value: unknown): number {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
}

function cell(value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  return String(value);
}

function formatDate(value?: string | null): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value).slice(0, 10);
  return date.toISOString().slice(0, 10);
}

function formatPrice(value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  const amount = num(value);
  return `৳${amount.toFixed(2)}`;
}

function formatQty(qty: number, unit: string): string {
  if (!(qty > 0)) return "—";
  const label = unit.trim();
  if (label === "g/kg") {
    const grams = qty * 1000;
    if (grams >= 1000) return `${trimNum(qty)} kg`;
    return `${trimNum(grams)} g`;
  }
  return `${trimNum(qty)}${label ? ` ${label}` : ""}`;
}

function trimNum(value: number): string {
  return String(Number(value.toFixed(4)));
}

function supplierName(row: Supplier): string {
  return row.supplier_name?.trim() || row.name?.trim() || "—";
}

function expiryFromGrn(
  line: StockTransferLine,
  grnById: Map<number, GrnItem>
): string | null {
  if (line.expiry_date) return line.expiry_date;
  const grnId = line.grn_item_id != null ? Number(line.grn_item_id) : 0;
  if (grnId > 0) return grnById.get(grnId)?.expiry_date ?? null;
  return null;
}

function pickLatestTransfer(
  batchId: string,
  rows: StockTransferLine[]
): StockTransferLine[] {
  if (rows.length === 0) return [];
  const byItem = rows.filter((row) => row.batch_id?.trim() === batchId);
  const pool = byItem.length > 0 ? byItem : rows;
  return [pool.reduce((best, row) => (row.id > best.id ? row : best))];
}

function recordsToTable(batchId: string, records: CheckingRecord[]): LookupTable {
  if (records.length === 0) {
    return {
      title: `Batch ${batchId}`,
      columns: ["Field", "Value"],
      rows: [
        ["Batch ID", batchId],
        ["Result", "No GRN or stock transfer items found for this batch."],
      ],
    };
  }

  if (records.length === 1) {
    const record = records[0];
    return {
      title: record.Item || `Batch ${batchId}`,
      columns: ["Field", "Value"],
      rows: COLUMNS.map((column) => [column, record[column]]),
    };
  }

  return {
    title: `Batch ${batchId}`,
    columns: [...COLUMNS],
    rows: records.map((record) => COLUMNS.map((column) => record[column])),
  };
}

/**
 * Same lookup Item Checking uses on the website:
 * parse batch → GRN + stock-transfer + catalog/branch/supplier/unit/PO.
 * Phone still calls only POST /qr-scans/lookup.
 */
export async function lookupItemCheckingTable(raw: string): Promise<LookupTable> {
  const batchId = parseBatchId(raw);
  if (!batchId) {
    return {
      title: "Scan result",
      columns: ["Field", "Value"],
      rows: [
        ["Raw payload", raw],
        ["Result", "Could not read a batch ID from that scan."],
      ],
    };
  }

  const [branches, suppliers, units, purchaseOrders, grnRows] = await Promise.all([
    espressoGetAllPages<Named>("/branches"),
    espressoGetAllPages<Supplier>("/suppliers"),
    espressoGetAllPages<Named>("/units"),
    espressoGetAllPages<PurchaseOrder>("/purchase-orders"),
    espressoGetAllPages<GrnItem>("/grn-items", { batch_id: batchId }),
  ]);

  let transferLines: StockTransferLine[] = [];
  const transfer = await espressoGet<{ lines?: StockTransferLine[] }>(
    `/stock-transfers/by-batch/${encodeURIComponent(batchId)}`
  );
  if (transfer.status !== 404) {
    transferLines = (transfer.data?.lines ?? []).filter((row) => row.item_id != null);
  }

  const latestTransfers = pickLatestTransfer(batchId, transferLines);
  const branchNames = new Map(branches.map((row) => [row.id, row.name ?? `Branch #${row.id}`]));
  const unitLabels = new Map(units.map((row) => [row.id, row.name ?? "—"]));
  const supplierNames = new Map(
    suppliers.map((row) => [row.id, supplierName(row)])
  );
  const poLabels = new Map(
    purchaseOrders.map((row) => [row.id, row.po_code?.trim() || `#${row.id}`])
  );

  if (latestTransfers.length > 0) {
    const grnById = new Map<number, GrnItem>();
    for (const row of grnRows) grnById.set(row.id, row);

    const missingIds = [
      ...new Set(
        latestTransfers
          .map((line) => Number(line.grn_item_id))
          .filter((id) => Number.isInteger(id) && id > 0 && !grnById.has(id))
      ),
    ];
    await Promise.all(
      missingIds.map(async (id) => {
        const { data, status } = await espressoGet<GrnItem>(`/grn-items/${id}`);
        if (status !== 404 && data) grnById.set(id, data);
      })
    );

    const itemIds = [
      ...new Set(
        latestTransfers.map((row) => Number(row.item_id)).filter((id) => id > 0)
      ),
    ];
    const catalog = new Map<number, CatalogItem>();
    await Promise.all(
      itemIds.map(async (id) => {
        const { data, status } = await espressoGet<CatalogItem>(`/items/${id}`);
        if (status !== 404 && data) catalog.set(id, data);
      })
    );

    const records = latestTransfers
      .filter((row): row is StockTransferLine & { item_id: number } => row.item_id != null)
      .map((row) => {
        const item = catalog.get(row.item_id);
        const unit =
          item?.unit_id != null ? (unitLabels.get(item.unit_id) ?? "—") : "—";
        return {
          Item: item?.name ?? `Item #${row.item_id}`,
          Source: "Stock transfer",
          Batch: cell(row.batch_id),
          Requisition: row.req_id ? `REQ-${row.req_id}` : "—",
          PO: "—",
          Supplier: "—",
          Delivery:
            row.delivery_branch != null
              ? (branchNames.get(row.delivery_branch) ?? `Branch #${row.delivery_branch}`)
              : "—",
          Ordered: formatQty(num(row.quantity), unit),
          Received: formatQty(num(row.received_quantity), unit),
          Price: "—",
          "Received on": formatDate(row.created_at),
          Expiry: formatDate(expiryFromGrn(row, grnById)),
          Comments: cell(row.comments),
        } satisfies CheckingRecord;
      });

    return recordsToTable(batchId, records);
  }

  const itemIds = [...new Set(grnRows.map((row) => Number(row.item_id)).filter((id) => id > 0))];
  const catalog = new Map<number, CatalogItem>();
  await Promise.all(
    itemIds.map(async (id) => {
      const { data, status } = await espressoGet<CatalogItem>(`/items/${id}`);
      if (status !== 404 && data) catalog.set(id, data);
    })
  );

  const records = grnRows.map((row) => {
    const item = catalog.get(row.item_id);
    const unitFromRow =
      row.unit_id != null ? unitLabels.get(row.unit_id) : undefined;
    const unitFromItem =
      item?.unit_id != null ? unitLabels.get(item.unit_id) : undefined;
    const unit = unitFromRow ?? unitFromItem ?? "—";
    return {
      Item: item?.name ?? `Item #${row.item_id}`,
      Source: row.po_id != null ? "From PO" : "Direct",
      Batch: cell(row.batch_id),
      Requisition: row.requisition_id ? `REQ-${row.requisition_id}` : "—",
      PO: row.po_id ? (poLabels.get(row.po_id) ?? `#${row.po_id}`) : "—",
      Supplier: row.supplier_id
        ? (supplierNames.get(row.supplier_id) ?? `Supplier #${row.supplier_id}`)
        : "—",
      Delivery:
        row.delivery_location != null
          ? (branchNames.get(row.delivery_location) ?? `Branch #${row.delivery_location}`)
          : "—",
      Ordered: formatQty(num(row.ordered_quantity), unit),
      Received: formatQty(num(row.received_quantity), unit),
      Price: formatPrice(row.price),
      "Received on": formatDate(row.created_at),
      Expiry: formatDate(row.expiry_date),
      Comments: cell(row.comments),
    } satisfies CheckingRecord;
  });

  return recordsToTable(batchId, records);
}
