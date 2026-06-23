package com.embabel.agent.tool.postgres

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.tool.postgres.blackboard.PreparedQuery
import com.embabel.agent.tool.postgres.blackboard.SqlAnswer
import com.embabel.agent.tool.postgres.blackboard.SqlCandidate
import com.embabel.agent.tool.postgres.blackboard.SqlDraft
import com.embabel.agent.tool.postgres.blackboard.SqlQuestion
import com.embabel.agent.tool.postgres.model.AttemptOutcome
import com.embabel.agent.tool.postgres.model.GuardResult
import com.embabel.agent.tool.postgres.model.SqlReferenceConfig
import com.embabel.common.ai.model.LlmOptions
import org.slf4j.LoggerFactory
import java.sql.SQLException
import javax.sql.DataSource

/**
 * A multi-step natural-language-to-SQL agent.
 *
 * The plan is a linear GOAP chain — `prepareQuery → generateSql → resolve` — which keeps planning
 * trivial and robust. The bounded **repair loop** lives inside [resolve]: validate → execute, and on
 * a guard rejection or execution error, feed the error back to the model for a corrected query, up
 * to [SqlReferenceConfig.maxAttempts]. (An earlier design modelled repair as a separate GOAP action
 * that cycled back to execution; that cycle defeats the forward planner, so the loop is encapsulated
 * here instead.)
 *
 * Reliability comes from [SqlGuard] (SELECT-only, LIMIT) and [SqlExecutor] (read-only, statement
 * timeout, row cap) — not from trusting the model. Expose it to a chat agent as a tool:
 * ```
 * promptRunner.withTool(Subagent.ofClass(NlToSqlAgent::class).consuming(SqlQuestion::class))
 * ```
 */
@Agent(description = "Answer a natural-language question by writing and running read-only SQL against the database")
class NlToSqlAgent(
    private val dataSource: DataSource,
    private val config: SqlReferenceConfig,
) {
    private val logger = LoggerFactory.getLogger(NlToSqlAgent::class.java)

    private val introspector = SchemaIntrospector(dataSource, config.allowedSchemas)
    private val guard = SqlGuard(config.maxRows)
    private val executor = SqlExecutor(dataSource, config)

    /** temperature 0 for deterministic SQL generation. */
    private val deterministic = LlmOptions().withTemperature(0.0)

    // understand + selectSchema: scope the question to the allowed schema's DDL.
    @Action
    fun prepareQuery(question: SqlQuestion): PreparedQuery =
        PreparedQuery(question.question, introspector.schema().toDdl())

    // generate: the model writes a first SELECT given the schema + few-shots.
    @Action
    fun generateSql(prepared: PreparedQuery, ctx: OperationContext): SqlCandidate {
        val draft = ctx.ai().withLlm(deterministic)
            .createObject(generationPrompt(prepared), SqlDraft::class.java)
        logger.info("Generated SQL: {}", draft.sql)
        return SqlCandidate(prepared.question, prepared.ddl, draft.sql)
    }

    // validate + execute, with a bounded repair loop. Terminal action: produces the agent's answer.
    @AchievesGoal(description = "Answered the question by running a validated, read-only SQL query")
    @Action
    fun resolve(candidate: SqlCandidate, ctx: OperationContext): SqlAnswer {
        var sql = candidate.sql
        var lastError: String? = null
        for (attempt in 1..config.maxAttempts) {
            val outcome = tryExecute(sql)
            if (outcome.success) {
                return SqlAnswer(candidate.question, outcome.sql, outcome.rendered, succeeded = true)
            }
            lastError = outcome.error
            logger.info("Attempt {} failed: {}", attempt, lastError)
            if (attempt < config.maxAttempts) {
                sql = repair(candidate, sql, lastError ?: "unknown error", ctx)
            }
        }
        return SqlAnswer(
            candidate.question,
            sql,
            "Unable to answer after ${config.maxAttempts} attempt(s). Last error: $lastError",
            succeeded = false,
        )
    }

    /**
     * Guard then execute a single candidate SQL. No LLM — deterministic and unit-testable.
     * A guard rejection or a SQL error becomes a failure outcome whose message can be fed back to
     * the model for repair.
     */
    fun tryExecute(sql: String): AttemptOutcome =
        when (val guarded = guard.validateAndLimit(sql)) {
            is GuardResult.Rejected -> AttemptOutcome(sql, false, "", "Rejected by guard: ${guarded.reason}")
            is GuardResult.Ok -> try {
                AttemptOutcome(guarded.sql, true, executor.execute(guarded.sql).toText(), null)
            } catch (e: SQLException) {
                AttemptOutcome(sql, false, "", "Execution failed: ${e.message}")
            }
        }

    private fun repair(candidate: SqlCandidate, badSql: String, error: String, ctx: OperationContext): String {
        val draft = ctx.ai().withLlm(deterministic)
            .createObject(repairPrompt(candidate, badSql, error), SqlDraft::class.java)
        logger.info("Repaired SQL: {}", draft.sql)
        return draft.sql
    }

    // ───────────────────────────── prompts ─────────────────────────────

    private fun fewShots(): String =
        if (config.fewShotExamples.isEmpty()) ""
        else "\nExamples:\n" + config.fewShotExamples.joinToString("\n") {
            "Q: ${it.question}\nSQL: ${it.sql}"
        } + "\n"

    private fun generationPrompt(prepared: PreparedQuery): String = """
        You are a careful analyst. Write a SINGLE read-only SQL SELECT that answers the question.
        ${config.dialectNotes}
        Use only the tables and columns in this schema:

        ${prepared.ddl}
        ${fewShots()}
        Rules:
        - SELECT only. Never write INSERT/UPDATE/DELETE/DDL.
        - Reference only columns that exist in the schema above.
        - Do not include a trailing semicolon or markdown fences.

        Question: ${prepared.question}
    """.trimIndent()

    private fun repairPrompt(candidate: SqlCandidate, badSql: String, error: String): String = """
        The previous SQL failed. Fix it. Return a corrected single read-only SELECT.
        ${config.dialectNotes}
        Schema:

        ${candidate.ddl}

        Failed SQL:
        $badSql

        Error:
        $error

        Question: ${candidate.question}
    """.trimIndent()
}
