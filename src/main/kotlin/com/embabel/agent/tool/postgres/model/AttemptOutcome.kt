package com.embabel.agent.tool.postgres.model

/** Internal result of validating + executing one candidate (not a blackboard type). */
data class AttemptOutcome(val sql: String, val success: Boolean, val rendered: String, val error: String?)