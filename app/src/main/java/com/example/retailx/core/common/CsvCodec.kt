package com.example.retailx.core.common

object CsvCodec {
    fun encode(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.appendLine(headers.joinToString(",") { escapeField(it) })
        for (row in rows) {
            sb.appendLine(row.joinToString(",") { escapeField(it) })
        }
        return sb.toString()
    }

    fun escapeField(value: String): String {
        val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        return if (needsQuote) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    fun decode(csvText: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        val text = csvText.replace("\r\n", "\n").replace("\r", "\n")

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(currentField.toString().trim())
                        currentField.clear()
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString().trim())
                        currentField.clear()
                        if (currentRow.any { it.isNotEmpty() }) {
                            rows.add(currentRow.toList())
                        }
                        currentRow.clear()
                    }
                    else -> currentField.append(c)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString().trim())
            if (currentRow.any { it.isNotEmpty() }) {
                rows.add(currentRow.toList())
            }
        }
        return rows
    }
}
