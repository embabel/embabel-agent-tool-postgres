package com.embabel.agent.tool.postgres.model

/**
 * Outcome of validating a candidate SQL string.
 *
 * [Ok] carries the (possibly LIMIT-injected) SQL that is safe to execute.
 * [Rejected] carries a human-readable reason that is fed straight back to the model as a tool
 * error, so the agent can repair and retry.
 */
sealed interface GuardResult {
    data class Ok(val sql: String) : GuardResult
    data class Rejected(val reason: String) : GuardResult
}