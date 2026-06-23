package com.embabel.agent.tool.postgres.blackboard

/** A first-pass SQL candidate from the model, carried with the context needed to repair it. */
data class SqlCandidate(val question: String, val ddl: String, val sql: String)