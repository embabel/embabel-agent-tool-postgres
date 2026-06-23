package com.embabel.agent.tool.postgres.model

/**
 * The introspected, cached picture of the database the model is allowed to query.
 * Its [toDdl] rendering is the schema context injected into the generation prompt.
 */
data class SqlSchema(
    val tables: List<TableInfo>,
) {
    fun toDdl(): String =
        if (tables.isEmpty()) "-- (no tables visible)"
        else tables.joinToString("\n\n") { it.toDdl() }
}