package com.embabel.agent.tool.postgres.blackboard

/** The agent's input: a question in plain English. This is what the chat agent passes as a tool call. */
data class SqlQuestion(val question: String)