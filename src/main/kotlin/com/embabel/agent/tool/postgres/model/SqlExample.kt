package com.embabel.agent.tool.postgres.model

/**
 * A worked example pairing a natural-language question with the SQL that answers it.
 * Surfaced into the generation prompt as a few-shot, and into the sub-agent's goal notes.
 */
data class SqlExample(
    val question: String,
    val sql: String,
)