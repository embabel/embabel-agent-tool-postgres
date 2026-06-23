package com.embabel.agent.tool.postgres

import com.embabel.agent.tool.postgres.model.SqlReferenceConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Deterministic tests of [NlToSqlAgent.tryExecute] — the guard + read-only execute path that the
 * agent's repair loop is built on — exercised without a live LLM. The full LLM-driven generate +
 * repair loop is validated end-to-end against a real model in the bpm-guide app.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NlToSqlAgentLogicTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var agent: NlToSqlAgent

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
                st.execute("CREATE TABLE public.widget (id int primary key, name text not null)")
                st.execute("INSERT INTO public.widget(id, name) VALUES (1, 'alpha'), (2, 'bravo')")
            }
        }
        agent = NlToSqlAgent(dataSource, SqlReferenceConfig(maxRepairAttempts = 2))
    }

    @AfterAll
    fun tearDown() {
        (dataSource as? HikariDataSource)?.close()
        postgres.stop()
    }

    @Test
    fun `tryExecute succeeds on a valid select`() {
        val outcome = agent.tryExecute("SELECT name FROM public.widget ORDER BY id")
        assertTrue(outcome.success)
        assertTrue(outcome.rendered.contains("alpha"), outcome.rendered)
        assertTrue(outcome.error == null)
    }

    @Test
    fun `tryExecute rejects a write at the guard`() {
        val outcome = agent.tryExecute("UPDATE public.widget SET name = 'x'")
        assertFalse(outcome.success)
        assertTrue(outcome.error!!.contains("guard", ignoreCase = true), outcome.error!!)
    }

    @Test
    fun `tryExecute reports an execution error (bad column) for repair`() {
        val outcome = agent.tryExecute("SELECT nope FROM public.widget")
        assertFalse(outcome.success)
        assertTrue(outcome.error!!.contains("Execution failed", ignoreCase = true), outcome.error!!)
    }

    @Test
    fun `a corrected query succeeds on the next attempt`() {
        assertFalse(agent.tryExecute("SELECT nope FROM public.widget").success)
        // The model would repair to a valid query; the corrected SQL then succeeds.
        assertTrue(agent.tryExecute("SELECT name FROM public.widget").success)
    }
}
