package com.embabel.agent.tool.postgres

import com.embabel.agent.tool.postgres.model.ColumnInfo
import com.embabel.agent.tool.postgres.model.SqlSchema
import com.embabel.agent.tool.postgres.model.TableInfo
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Reads table/column metadata from `information_schema` once (lazily) and caches it.
 *
 * Restricting to [SqlReferenceConfig.allowedSchemas] is itself a guardrail: tables outside the
 * allow-list are never described to the model, so it cannot reference them.
 */
class SchemaIntrospector(
    private val dataSource: DataSource,
    private val allowedSchemas: List<String>,
) {
    private val logger = LoggerFactory.getLogger(SchemaIntrospector::class.java)

    private val cached: SqlSchema by lazy { introspect() }

    /** The cached schema; introspection happens on first call. */
    fun schema(): SqlSchema = cached

    private fun introspect(): SqlSchema {
        val placeholders = allowedSchemas.joinToString(",") { "?" }
        val sql = """
            SELECT table_schema, table_name, column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema IN ($placeholders)
            ORDER BY table_schema, table_name, ordinal_position
        """.trimIndent()

        data class Key(val schema: String, val table: String)
        val byTable = linkedMapOf<Key, MutableList<ColumnInfo>>()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                allowedSchemas.forEachIndexed { i, s -> ps.setString(i + 1, s) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val key = Key(rs.getString("table_schema"), rs.getString("table_name"))
                        byTable.getOrPut(key) { mutableListOf() }.add(
                            ColumnInfo(
                                name = rs.getString("column_name"),
                                type = rs.getString("data_type"),
                                nullable = rs.getString("is_nullable").equals("YES", ignoreCase = true),
                            ),
                        )
                    }
                }
            }
        }

        val tables = byTable.map { (key, cols) -> TableInfo(key.schema, key.table, cols) }
        logger.info("Introspected {} table(s) from schema(s) {}", tables.size, allowedSchemas)
        return SqlSchema(tables)
    }
}