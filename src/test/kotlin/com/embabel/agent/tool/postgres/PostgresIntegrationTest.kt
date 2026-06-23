package com.embabel.agent.tool.postgres

import com.embabel.agent.tool.postgres.model.SqlReferenceConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/**
 * Integration tests for [SchemaIntrospector] and [SqlExecutor] against a real Postgres,
 * proving the reliability guardrails the demo teaches: schema scoping, row caps, read-only
 * enforcement, and statement timeouts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresIntegrationTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        })
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE public.widget (id int primary key, name text not null, qty int)")
                st.execute(
                    """
                    INSERT INTO public.widget(id, name, qty) VALUES
                      (1, 'alpha', 10), (2, 'bravo', 20), (3, 'charlie', 30),
                      (4, 'delta', 40), (5, 'echo', 50)
                    """.trimIndent(),
                )
            }
        }
    }

    @AfterAll
    fun tearDown() {
        (dataSource as? HikariDataSource)?.close()
        postgres.stop()
    }

    @Test
    fun `introspects only the allowed schema`() {
        val schema = SchemaIntrospector(dataSource, listOf("public")).schema()
        val widget = schema.tables.single { it.name == "widget" }
        assertEquals(setOf("id", "name", "qty"), widget.columns.map { it.name }.toSet())
        val name = widget.columns.single { it.name == "name" }
        assertFalse(name.nullable, "name is NOT NULL")
        assertTrue(schema.toDdl().contains("TABLE public.widget"), schema.toDdl())
        // pg_catalog / information_schema tables must not leak in.
        assertTrue(schema.tables.all { it.schema == "public" })
    }

    @Test
    fun `executes a select and renders rows`() {
        val executor = SqlExecutor(dataSource, SqlReferenceConfig())
        val result = executor.execute("SELECT name, qty FROM public.widget ORDER BY id")
        assertEquals(listOf("name", "qty"), result.columns)
        assertEquals(5, result.rows.size)
        assertFalse(result.truncated)
        assertEquals("alpha", result.rows.first()[0])
    }

    @Test
    fun `caps rows and reports truncation`() {
        val executor = SqlExecutor(dataSource, SqlReferenceConfig(maxRows = 2))
        val result = executor.execute("SELECT id FROM public.widget ORDER BY id")
        assertEquals(2, result.rows.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `read-only connection blocks a write that slips past the guard`() {
        val executor = SqlExecutor(dataSource, SqlReferenceConfig())
        // The executor sets the connection read-only; a write must fail even though SqlGuard
        // is not in the loop here. This is the defense-in-depth layer.
        assertThrows(SQLException::class.java) {
            executor.execute("UPDATE public.widget SET qty = 0")
        }
    }

    @Test
    fun `statement timeout kills a runaway query`() {
        val executor = SqlExecutor(
            dataSource,
            SqlReferenceConfig(statementTimeout = Duration.ofMillis(200)),
        )
        assertThrows(SQLException::class.java) {
            executor.execute("SELECT pg_sleep(3)")
        }
    }
}
