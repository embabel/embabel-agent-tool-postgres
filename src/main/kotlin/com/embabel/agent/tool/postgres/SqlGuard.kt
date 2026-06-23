package com.embabel.agent.tool.postgres

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.statement.select.Limit
import com.embabel.agent.tool.postgres.model.GuardResult
import org.slf4j.LoggerFactory

/**
 * Statically validates model-generated SQL before it ever reaches the database.
 *
 * This is the first layer of defense; the read-only database role enforced by [SqlExecutor] is the
 * second. Neither is trusted alone.
 *
 * Rules enforced:
 *  - exactly one statement (no stacked `;`-separated statements);
 *  - that statement must be a `SELECT` (rejects INSERT/UPDATE/DELETE/DDL/etc.);
 *  - a `LIMIT` is injected (or clamped down) to [SqlReferenceConfig.maxRows].
 *
 * Note on data-modifying CTEs (e.g. `WITH x AS (DELETE ... RETURNING ...) SELECT ...`): JSqlParser
 * reports the top-level statement as a `SELECT`, so this guard alone would pass it. That case is
 * caught by the read-only role / read-only transaction in [SqlExecutor] — defense in depth.
 */
class SqlGuard(
    private val maxRows: Int,
) {
    private val logger = LoggerFactory.getLogger(SqlGuard::class.java)

    fun validateAndLimit(rawSql: String): GuardResult {
        val sql = rawSql.trim().removeSuffix(";").trim()
        if (sql.isEmpty()) return GuardResult.Rejected("Empty SQL.")

        // Reject stacked statements up front.
        val statements = try {
            CCJSqlParserUtil.parseStatements(sql).statements
        } catch (e: Exception) {
            logger.debug("Failed to parse SQL: {}", e.message)
            return GuardResult.Rejected("SQL did not parse: ${e.message}")
        }
        if (statements.size != 1) {
            return GuardResult.Rejected(
                "Only a single statement is allowed; found ${statements.size}.",
            )
        }

        val statement = statements.first()
        if (statement !is Select) {
            return GuardResult.Rejected(
                "Only SELECT statements are allowed; got ${statement.javaClass.simpleName}.",
            )
        }

        return GuardResult.Ok(enforceLimit(statement))
    }

    private fun enforceLimit(select: Select): String {
        when (val body = select.selectBody) {
            is PlainSelect -> body.limit = clampedLimit(body.limit)
            is SetOperationList -> body.limit = clampedLimit(body.limit)
            else -> logger.debug("Unrecognized select body {}, leaving LIMIT untouched", body.javaClass)
        }
        return select.toString()
    }

    /**
     * Returns a LIMIT no greater than [maxRows]: injects one if absent, clamps it if too large,
     * and leaves a smaller user-supplied limit alone.
     */
    private fun clampedLimit(existing: Limit?): Limit {
        val existingRowCount = (existing?.rowCount as? LongValue)?.value
        val target = when {
            existingRowCount == null -> maxRows.toLong()
            existingRowCount > maxRows -> maxRows.toLong()
            else -> existingRowCount
        }
        return Limit().apply { rowCount = LongValue(target) }
    }
}
