package com.embabel.agent.tool.postgres.model

/** A column within a [TableInfo]. */
data class ColumnInfo(
    val name: String,
    val type: String,
    val nullable: Boolean,
)