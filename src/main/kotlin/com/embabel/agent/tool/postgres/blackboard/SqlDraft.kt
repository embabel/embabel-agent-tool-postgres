package com.embabel.agent.tool.postgres.blackboard

/** Structured LLM output — a single SQL statement. Keeping it structured avoids markdown fences. */
data class SqlDraft(val sql: String)