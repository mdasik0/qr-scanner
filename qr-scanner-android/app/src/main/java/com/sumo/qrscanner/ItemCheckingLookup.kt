package com.sumo.qrscanner

import org.json.JSONArray
import org.json.JSONObject

object ItemCheckingLookup {
    private val BATCH_ID = Regex("BTH-\\d{4}-\\d+-\\d+", RegexOption.IGNORE_CASE)

    fun run(api: QrApiClient, baseUrl: String, token: String, raw: String): TableResult {
        val batchId = parseBatchId(raw)
            ?: return fieldTable(
                "Scan result",
                listOf("Raw payload" to raw, "Result" to "Could not read a batch ID from that scan."),
            )

        val transferJson = api.getJson(
            baseUrl,
            token,
            "/stock-transfers/by-batch/${java.net.URLEncoder.encode(batchId, "UTF-8")}",
        )
        val transferLines = api.dataObject(transferJson)?.optJSONArray("lines") ?: JSONArray()

        val grnJson = api.getJson(
            baseUrl,
            token,
            "/grn-items",
            mapOf("batch_id" to batchId, "limit" to "100", "page" to "1"),
        )
        val grnRows = api.dataArray(grnJson)

        return if (transferLines.length() > 0) {
            buildTransferTable(api, baseUrl, token, batchId, transferLines)
        } else {
            buildGrnTable(api, baseUrl, token, batchId, grnRows)
        }
    }

    private fun buildTransferTable(
        api: QrApiClient,
        baseUrl: String,
        token: String,
        batchId: String,
        lines: JSONArray,
    ): TableResult {
        val latest = pickLatest(lines) ?: return emptyBatch(batchId)
        val itemId = latest.optInt("item_id")
        val item = if (itemId > 0) fetchItem(api, baseUrl, token, itemId) else null
        val units = nameMap(api, baseUrl, token, "/units", "name")
        val branches = nameMap(api, baseUrl, token, "/branches", "name")
        val unitId = item?.optInt("unit_id") ?: 0
        val unit = units[unitId] ?: "—"
        val record = linkedMapOf(
            "Item" to (item?.optString("name")?.ifBlank { null } ?: "Item #$itemId"),
            "Source" to "Stock transfer",
            "Batch" to cell(latest.opt("batch_id")),
            "Requisition" to latest.opt("req_id")?.takeUnless { it == JSONObject.NULL }?.let { "REQ-$it" }.orDash(),
            "PO" to "—",
            "Supplier" to "—",
            "Delivery" to branchLabel(branches, latest.opt("delivery_branch")),
            "Ordered" to formatQty(num(latest.opt("quantity")), unit),
            "Received" to formatQty(num(latest.opt("received_quantity")), unit),
            "Price" to "—",
            "Received on" to formatDate(latest.optString("created_at")),
            "Expiry" to formatDate(latest.optString("expiry_date")),
            "Comments" to cell(latest.opt("comments")),
        )
        return fieldTable(record["Item"] ?: "Batch $batchId", record.toList())
    }

    private fun buildGrnTable(
        api: QrApiClient,
        baseUrl: String,
        token: String,
        batchId: String,
        rows: JSONArray,
    ): TableResult {
        if (rows.length() == 0) return emptyBatch(batchId)
        val units = nameMap(api, baseUrl, token, "/units", "name")
        val branches = nameMap(api, baseUrl, token, "/branches", "name")
        val suppliers = nameMap(api, baseUrl, token, "/suppliers", "supplier_name")
        val pos = nameMap(api, baseUrl, token, "/purchase-orders", "po_code")
        val records = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val itemId = row.optInt("item_id")
            val item = if (itemId > 0) fetchItem(api, baseUrl, token, itemId) else null
            val unitId = row.optInt("unit_id").takeIf { it > 0 } ?: item?.optInt("unit_id") ?: 0
            val unit = units[unitId] ?: "—"
            linkedMapOf(
                "Item" to (item?.optString("name")?.ifBlank { null } ?: "Item #$itemId"),
                "Source" to if (row.isNull("po_id")) "Direct" else "From PO",
                "Batch" to cell(row.opt("batch_id")),
                "Requisition" to row.opt("requisition_id")?.takeUnless { it == JSONObject.NULL }?.let { "REQ-$it" }.orDash(),
                "PO" to if (row.isNull("po_id")) "—" else (pos[row.optInt("po_id")] ?: "#${row.optInt("po_id")}"),
                "Supplier" to if (row.isNull("supplier_id")) "—" else (suppliers[row.optInt("supplier_id")] ?: "Supplier #${row.optInt("supplier_id")}"),
                "Delivery" to branchLabel(branches, row.opt("delivery_location")),
                "Ordered" to formatQty(num(row.opt("ordered_quantity")), unit),
                "Received" to formatQty(num(row.opt("received_quantity")), unit),
                "Price" to formatPrice(row.opt("price")),
                "Received on" to formatDate(row.optString("created_at")),
                "Expiry" to formatDate(row.optString("expiry_date")),
                "Comments" to cell(row.opt("comments")),
            )
        }
        if (records.size == 1) {
            val record = records.first()
            return fieldTable(record["Item"] ?: "Batch $batchId", record.toList())
        }
        val columns = records.first().keys.toList()
        return TableResult(
            title = "Batch $batchId",
            columns = columns,
            rows = records.map { record -> columns.map { record[it] ?: "—" } },
        )
    }

    private fun fetchItem(
        api: QrApiClient,
        baseUrl: String,
        token: String,
        id: Int,
    ): JSONObject? {
        return try {
            api.dataObject(api.getJson(baseUrl, token, "/items/$id"))
        } catch (_: Exception) {
            null
        }
    }

    private fun nameMap(
        api: QrApiClient,
        baseUrl: String,
        token: String,
        path: String,
        labelKey: String,
    ): Map<Int, String> {
        val json = api.getJson(baseUrl, token, path, mapOf("limit" to "100", "page" to "1"))
        val rows = api.dataArray(json)
        val map = mutableMapOf<Int, String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optInt("id")
            val label = row.optString(labelKey).ifBlank { row.optString("name") }
            if (id > 0) map[id] = label.ifBlank { "#$id" }
        }
        return map
    }

    private fun pickLatest(lines: JSONArray): JSONObject? {
        var best: JSONObject? = null
        for (i in 0 until lines.length()) {
            val row = lines.optJSONObject(i) ?: continue
            if (row.isNull("item_id")) continue
            if (best == null || row.optInt("id") > best.optInt("id")) best = row
        }
        return best
    }

    fun parseBatchId(raw: String): String? {
        var value = raw.trim()
        if (value.contains("%")) {
            try {
                value = java.net.URLDecoder.decode(value.replace("+", " "), "UTF-8")
            } catch (_: Exception) {
            }
        }
        value = value.replace(Regex("^[\\x00-\\x1F\\x7F]+"), "").trim()
        value = value.replace(Regex("sub_batch_id\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        value = value.trim('"', '\'', ' ')
        BATCH_ID.find(value)?.value?.let { return it }
        if (value.startsWith("BTH-", ignoreCase = true)) {
            val lastDash = value.lastIndexOf('-')
            if (lastDash > 0) return value.substring(0, lastDash)
        }
        return null
    }

    private fun emptyBatch(batchId: String) = fieldTable(
        "Batch $batchId",
        listOf(
            "Batch ID" to batchId,
            "Result" to "No GRN or stock transfer items found for this batch.",
        ),
    )

    private fun fieldTable(title: String, pairs: List<Pair<String, String>>) = TableResult(
        title = title,
        columns = listOf("Field", "Value"),
        rows = pairs.map { listOf(it.first, it.second) },
    )

    private fun branchLabel(branches: Map<Int, String>, raw: Any?): String {
        val id = num(raw).toInt()
        if (id <= 0) return "—"
        return branches[id] ?: "Branch #$id"
    }

    private fun cell(value: Any?): String {
        if (value == null || value == JSONObject.NULL || value.toString().isBlank()) return "—"
        return value.toString()
    }

    private fun String?.orDash(): String = if (this.isNullOrBlank()) "—" else this

    private fun num(value: Any?): Double {
        return when (value) {
            null, JSONObject.NULL -> 0.0
            is Number -> value.toDouble()
            else -> value.toString().toDoubleOrNull() ?: 0.0
        }
    }

    private fun formatQty(qty: Double, unit: String): String {
        if (qty <= 0) return "—"
        val label = unit.trim()
        if (label == "g/kg") {
            val grams = qty * 1000
            return if (grams >= 1000) "${trimNum(qty)} kg" else "${trimNum(grams)} g"
        }
        return "${trimNum(qty)}${if (label.isNotBlank()) " $label" else ""}"
    }

    private fun trimNum(value: Double): String = value.toBigDecimal().stripTrailingZeros().toPlainString()

    private fun formatPrice(value: Any?): String {
        if (value == null || value == JSONObject.NULL || value.toString().isBlank()) return "—"
        return "৳${"%.2f".format(num(value))}"
    }

    private fun formatDate(value: String?): String {
        if (value.isNullOrBlank()) return "—"
        return value.take(10)
    }
}
