package com.sumo.qrscanner

import org.json.JSONArray
import org.json.JSONObject

data class TableResult(
    val title: String,
    val columns: List<String>,
    val rows: List<List<String>>,
) {
    companion object {
        fun fromApiBody(text: String): TableResult {
            val root = JSONObject(text)
            val fallbackTitle = root.optString("message").ifBlank { "Result" }
            val data = root.opt("data")
            return fromAny(data, fallbackTitle)
        }

        fun fromAny(value: Any?, fallbackTitle: String): TableResult {
            return when (value) {
                is JSONObject -> fromObject(value, fallbackTitle)
                is JSONArray -> fromArray(value, fallbackTitle)
                null, JSONObject.NULL -> fieldValueTable(fallbackTitle, listOf("Result" to "No data"))
                else -> fieldValueTable(fallbackTitle, listOf("Result" to cell(value)))
            }
        }

        private fun fromObject(obj: JSONObject, fallbackTitle: String): TableResult {
            val title = obj.optString("title").ifBlank { fallbackTitle }

            if (obj.has("columns") && obj.has("rows")) {
                val columns = stringList(obj.optJSONArray("columns")).ifEmpty { listOf("Value") }
                val rows = tableRows(obj.optJSONArray("rows"), columns)
                return TableResult(title, columns, rows)
            }

            if (obj.has("records") && obj.opt("records") is JSONArray) {
                val fromRecords = fromArray(obj.getJSONArray("records"), title)
                if (fromRecords.rows.isNotEmpty()) return fromRecords
            }

            val pairs = flattenObject(obj)
            return fieldValueTable(title, pairs)
        }

        private fun fromArray(array: JSONArray, fallbackTitle: String): TableResult {
            val objects = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                if (item is JSONObject) objects.add(item)
            }
            if (objects.isEmpty()) {
                val rows = (0 until array.length()).map { listOf(cell(array.opt(it))) }
                return TableResult(fallbackTitle, listOf("Value"), rows)
            }

            val columns = linkedSetOf<String>()
            for (obj in objects) {
                val keys = obj.keys()
                while (keys.hasNext()) columns.add(keys.next())
            }
            val cols = columns.toList()
            val rows = objects.map { obj -> cols.map { col -> cell(obj.opt(col)) } }
            return TableResult(fallbackTitle, cols, rows)
        }

        private fun tableRows(array: JSONArray?, columns: List<String>): List<List<String>> {
            if (array == null) return emptyList()
            val rows = mutableListOf<List<String>>()
            for (i in 0 until array.length()) {
                when (val item = array.opt(i)) {
                    is JSONArray -> rows.add((0 until item.length()).map { cell(item.opt(it)) })
                    is JSONObject -> rows.add(columns.map { col -> cell(item.opt(col)) })
                    else -> rows.add(listOf(cell(item)))
                }
            }
            return rows
        }

        private fun flattenObject(obj: JSONObject, prefix: String = ""): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                when (val nested = obj.opt(key)) {
                    is JSONObject -> out.addAll(flattenObject(nested, path))
                    else -> out.add(path to cell(nested))
                }
            }
            return out
        }

        private fun fieldValueTable(
            title: String,
            pairs: List<Pair<String, String>>,
        ): TableResult {
            return TableResult(
                title = title,
                columns = listOf("Field", "Value"),
                rows = pairs.map { listOf(it.first, it.second) },
            )
        }

        private fun stringList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { cell(array.opt(it)) }
        }

        private fun cell(value: Any?): String {
            return when (value) {
                null, JSONObject.NULL -> "—"
                is JSONArray, is JSONObject -> value.toString()
                else -> value.toString()
            }
        }
    }
}
