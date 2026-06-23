package com.embabel.agent.tool.postgres.model

import java.time.Duration

/**
 * Configuration for the natural-language-to-SQL sub-agent.
 *
 * This is a plain data object — no Spring annotations — so the library stays framework-agnostic.
 * The consuming application constructs it and supplies the [javax.sql.DataSource] separately
 * (which should be backed by a **read-only** database role).
 *
 * Defaults are deliberately conservative: a small row cap and a short statement timeout, because
 * the point of this module is to demonstrate *reliable*, bounded database access.
 *
 * @param allowedSchemas the schemas whose tables are exposed to the model (others are invisible)
 * @param maxRows hard cap on rows returned; the executor truncates beyond this
 * @param statementTimeout per-query timeout enforced via `SET LOCAL statement_timeout`
 * @param fewShotExamples worked question→SQL pairs that steer generation
 * @param dialectNotes free-text dialect guidance injected into the prompt (e.g. Postgres specifics)
 * @param maxRepairAttempts how many times the agent may regenerate SQL after a validation or
 *        execution failure before giving up — this bounds the repair loop
 */
data class SqlReferenceConfig(
    val allowedSchemas: List<String> = listOf("public"),
    val maxRows: Int = 200,
    val statementTimeout: Duration = Duration.ofSeconds(5),
    val fewShotExamples: List<SqlExample> = emptyList(),
    val dialectNotes: String = "Target dialect is PostgreSQL.",
    val maxRepairAttempts: Int = 2,
) {
    init {
        require(allowedSchemas.isNotEmpty()) { "At least one allowed schema is required" }
        require(maxRows in 1..10_000) { "maxRows must be between 1 and 10000, was $maxRows" }
        require(!statementTimeout.isNegative && !statementTimeout.isZero) {
            "statementTimeout must be positive"
        }
        require(maxRepairAttempts in 0..5) { "maxRepairAttempts must be between 0 and 5, was $maxRepairAttempts" }
    }

    /** Total SQL attempts allowed: the initial generation plus [maxRepairAttempts] repairs. */
    val maxAttempts: Int get() = maxRepairAttempts + 1
}