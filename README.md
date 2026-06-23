# embabel-agent-tool-postgres

A reusable [Embabel](https://embabel.com) tool: a **multi-step natural-language-to-SQL sub-agent**
for **PostgreSQL** with read-only guardrails, intended to be exposed to a chat agent as a `Subagent` tool.

This is a **teaching reference**, not production code. It demonstrates how to make database access
*reliable* inside an agent framework:

- the NL→SQL work is a GOAP `@Agent` (`understand → selectSchema → generateSql → validate →
  execute → repairOnError → format`), so the repair-on-error loop is first-class;
- generation is fenced by **guardrails**, not trust: a read-only `DataSource`, JSqlParser
  SELECT-only validation, injected `LIMIT`, a `statement_timeout`, and a row cap.

It targets PostgreSQL specifically: the `statement_timeout` and `information_schema` introspection
are Postgres-flavoured, so it is not portable to other JDBC databases as-is.

The library is deliberately lean: it takes a `javax.sql.DataSource` and an `SqlReferenceConfig`,
and carries no JDBC driver, connection pool, or Spring wiring of its own — the consuming
application supplies those.

## Use

```kotlin
// In the consuming Embabel app, expose the sub-agent to the chat agent's PromptRunner:
promptRunner.withTool(Subagent.ofClass(NlToSqlAgent::class).consuming(SqlQuestion::class))
```

## Build

```
mvn install
```

Requires access to the Embabel artifact repositories (configured in `pom.xml`).
