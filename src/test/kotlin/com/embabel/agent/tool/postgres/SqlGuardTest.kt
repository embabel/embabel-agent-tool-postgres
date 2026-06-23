package com.embabel.agent.tool.postgres

import com.embabel.agent.tool.postgres.model.GuardResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [SqlGuard] — no database. This is the highest-value guardrail surface:
 * it proves what SQL is accepted, what is rejected, and how LIMIT is enforced.
 */
class SqlGuardTest {

    private val guard = SqlGuard(maxRows = 100)

    private fun ok(sql: String): GuardResult.Ok =
        assertInstanceOf(GuardResult.Ok::class.java, guard.validateAndLimit(sql))

    private fun rejected(sql: String): GuardResult.Rejected =
        assertInstanceOf(GuardResult.Rejected::class.java, guard.validateAndLimit(sql))

    @Test
    fun `accepts a plain select`() {
        val result = ok("SELECT id, name FROM public.processes")
        assertTrue(result.sql.contains("LIMIT 100", ignoreCase = true), "should inject LIMIT: ${result.sql}")
    }

    @Test
    fun `accepts a CTE that selects`() {
        ok("WITH recent AS (SELECT * FROM tasks) SELECT * FROM recent")
    }

    @Test
    fun `injects a limit when none is present`() {
        val result = ok("SELECT * FROM tasks")
        assertTrue(result.sql.contains("LIMIT 100", ignoreCase = true), result.sql)
    }

    @Test
    fun `clamps a limit larger than the cap`() {
        val result = ok("SELECT * FROM tasks LIMIT 5000")
        assertTrue(result.sql.contains("LIMIT 100", ignoreCase = true), result.sql)
        assertTrue(!result.sql.contains("5000"), result.sql)
    }

    @Test
    fun `leaves a smaller user limit alone`() {
        val result = ok("SELECT * FROM tasks LIMIT 10")
        assertTrue(result.sql.contains("LIMIT 10", ignoreCase = true), result.sql)
    }

    @Test
    fun `tolerates a trailing semicolon`() {
        ok("SELECT 1;")
    }

    @Test
    fun `rejects an update`() {
        val r = rejected("UPDATE tasks SET status = 'done'")
        assertTrue(r.reason.contains("SELECT", ignoreCase = true), r.reason)
    }

    @Test
    fun `rejects an insert`() {
        rejected("INSERT INTO tasks(id) VALUES (1)")
    }

    @Test
    fun `rejects a delete`() {
        rejected("DELETE FROM tasks")
    }

    @Test
    fun `rejects DDL`() {
        rejected("DROP TABLE tasks")
    }

    @Test
    fun `rejects stacked statements`() {
        val r = rejected("SELECT * FROM tasks; DROP TABLE tasks")
        assertTrue(r.reason.contains("single statement", ignoreCase = true), r.reason)
    }

    @Test
    fun `rejects garbage`() {
        rejected("not sql at all")
    }

    @Test
    fun `rejects empty input`() {
        rejected("   ")
    }
}
