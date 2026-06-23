package com.embabel.agent.tool.postgres.blackboard

/** Question paired with the (allow-list-scoped) schema the model is allowed to see. */
data class PreparedQuery(val question: String, val ddl: String)