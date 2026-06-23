package com.embabel.agent.tool.postgres.model

/** A table discovered by introspection, rendered to the model as a `CREATE TABLE`-style sketch. */
data class TableInfo(
    val schema: String,
    val name: String,
    val columns: List<ColumnInfo>,
) {
    val qualifiedName: String get() = "$schema.$name"

    fun toDdl(): String = buildString {
        append("TABLE $qualifiedName (\n")
        append(columns.joinToString(",\n") { col ->
            "  ${col.name} ${col.type}${if (col.nullable) "" else " NOT NULL"}"
        })
        append("\n)")
    }
}