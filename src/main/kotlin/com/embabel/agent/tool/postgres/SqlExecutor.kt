package com.embabel.agent.tool.postgres

import com.embabel.agent.tool.postgres.model.QueryResult
import com.embabel.agent.tool.postgres.model.SqlReferenceConfig
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Executes a validated SELECT against a read-only connection, with a statement timeout and a row cap.
 *
 * Reliability mechanics (the teaching payload), all enforced here regardless of what the model wrote:
 *  - `connection.isReadOnly = true` and a non-committing transaction — a write cannot succeed even
 *    if a data-modifying statement somehow got past [SqlGuard];
 *  - `SET LOCAL statement_timeout` — a runaway query is killed by the database;
 *  - `maxRows` cap on the statement — output is bounded.
 *
 * The `statement_timeout` syntax is PostgreSQL-specific (this module targets Postgres); the rest is
 * plain JDBC.
 */
class SqlExecutor(
    private val dataSource: DataSource,
    private val config: SqlReferenceConfig,
) {
    private val logger = LoggerFactory.getLogger(SqlExecutor::class.java)

    fun execute(sql: String): QueryResult {
        dataSource.connection.use { conn ->
            conn.isReadOnly = true
            conn.autoCommit = false // required so SET LOCAL applies for the duration of the query
            try {
                conn.createStatement().use { st ->
                    st.execute("SET LOCAL statement_timeout = ${config.statementTimeout.toMillis()}")
                }
                conn.prepareStatement(sql).use { ps ->
                    // Fetch one extra row so we can report truncation accurately.
                    ps.maxRows = config.maxRows + 1
                    ps.executeQuery().use { rs -> return map(rs) }
                }
            } finally {
                // Nothing to commit on a read-only connection; roll back to release the transaction.
                runCatching { conn.rollback() }.onFailure {
                    logger.debug("Rollback after read-only query failed: {}", it.message)
                }
            }
        }
    }

    private fun map(rs: ResultSet): QueryResult {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
        val rows = mutableListOf<List<String?>>()
        var truncated = false
        while (rs.next()) {
            if (rows.size >= config.maxRows) {
                truncated = true
                break
            }
            rows.add((1..meta.columnCount).map { rs.getObject(it)?.toString() })
        }
        return QueryResult(columns, rows, truncated)
    }
}
