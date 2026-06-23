package com.embabel.agent.tool.postgres.blackboard

/** The agent's terminal output. */
data class SqlAnswer(
    val question: String,
    val sql: String,
    val answer: String,
    val succeeded: Boolean,
)