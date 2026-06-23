package com.embabel.agent.tool.postgres.model

/**
 * The result of a query, already bounded by the row cap.
 *
 * @param columns column labels, in order
 * @param rows row values rendered as nullable strings (demo-grade formatting)
 * @param truncated true if more rows existed than [SqlReferenceConfig.maxRows] allowed
 */
data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncated: Boolean,
) {
    /** A compact text rendering suitable for handing back to the model / chat. */
    fun toText(): String {
        if (columns.isEmpty()) return "(no columns)"
        val header = columns.joinToString(" | ")
        val separator = columns.joinToString("-+-") { "-".repeat(it.length.coerceAtLeast(3)) }
        val body = rows.joinToString("\n") { row ->
            row.joinToString(" | ") { it ?: "NULL" }
        }
        val footer = buildString {
            append("\n(${rows.size} row${if (rows.size == 1) "" else "s"}")
            if (truncated) append(", truncated")
            append(")")
        }
        return "$header\n$separator\n$body$footer"
    }
}