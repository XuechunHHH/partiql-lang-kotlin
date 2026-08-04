/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.partiql.planner.internal

import org.junit.jupiter.api.Test
import org.partiql.parser.PartiQLParser
import org.partiql.plan.Action
import org.partiql.plan.Operator
import org.partiql.plan.Plan
import org.partiql.plan.RoutineRef
import org.partiql.plan.rel.RelAggregate
import org.partiql.plan.rel.RelProject
import org.partiql.plan.rex.RexCall
import org.partiql.plan.rex.RexDispatch
import org.partiql.plan.rex.RexError
import org.partiql.plan.rex.RexSelect
import org.partiql.plan.rex.RexStruct
import org.partiql.planner.PartiQLPlanner
import org.partiql.planner.util.PErrorCollector
import org.partiql.spi.Context
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Namespace
import org.partiql.spi.catalog.Session
import org.partiql.spi.catalog.Table
import org.partiql.spi.errors.PError
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutineResolutionIntegrationTest {

    private val parser = PartiQLParser.standard()
    private val planner = PartiQLPlanner.standard()

    @Test
    fun `qualified and unqualified scalar calls preserve the same canonical identity`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(
                Name.of("one", "two", "echo"),
                scalar("echo", PType.integer()),
            )
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .path(Namespace.of("demo", "one", "two"))
            .build()
        val expected = RoutineRef("demo", Name.of("one", "two", "echo"))

        assertEquals(expected, plan("echo(1)", session).singleCall().routineRef)
        assertEquals(expected, plan("demo.one.two.echo(1)", session).singleCall().routineRef)
    }

    @Test
    fun `aggregate resolution falls through type mismatch and preserves distinct and identity`() {
        val first = TestRoutineCatalog.builder("first")
            .aggregation(Name.of("stats", "total"), aggregate("total", PType.string()))
            .build()
        val second = TestRoutineCatalog.builder("second")
            .aggregation(Name.of("total"), aggregate("total", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("first")
            .catalogs(first, second)
            .path(Namespace.of("first", "stats"), Namespace.of("second"))
            .build()

        val measure = plan(
            "SELECT total(DISTINCT x) AS result FROM << 1 >> AS x",
            session,
        ).singleAggregate().measures.single()

        assertTrue(measure.isDistinct)
        assertEquals(RoutineRef("second", Name.of("total")), measure.routineRef)
    }

    @Test
    fun `dynamic scalar dispatch preserves one binding identity`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(
                Name.of("math", "choose"),
                scalar("choose", PType.integer()),
                scalar("choose", PType.string()),
            )
            .table(Table.empty("payload", PType.dynamic()))
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .path(Namespace.of("demo", "math"))
            .build()

        val dispatch = plan(
            "SELECT VALUE choose(x) FROM payload AS x",
            session,
        ).singleDispatch()

        assertEquals(2, dispatch.functions.size)
        assertEquals(RoutineRef("demo", Name.of("math", "choose")), dispatch.routineRef)
    }

    @Test
    fun `count star selects aggregate arity one and injects one argument`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .aggregation(Name.of("stats", "count"), aggregate("count", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .path(Namespace.of("demo", "stats"))
            .build()

        val measure = plan(
            "SELECT COUNT(*) AS result FROM << 1 >> AS x",
            session,
        ).singleAggregate().measures.single()

        assertEquals(RoutineRef("demo", Name.of("stats", "count")), measure.routineRef)
        assertEquals(1, measure.args.size)
        assertEquals(PType.integer(), measure.args.single().type.pType)
    }

    @Test
    fun `regular catalog ambiguity reports function ambiguous`() {
        val lower = TestRoutineCatalog.builder("demo")
            .function(Name.of("math", "echo"), scalar("echo", PType.integer()))
            .build()
        val upper = TestRoutineCatalog.builder("DEMO")
            .function(Name.of("math", "echo"), scalar("echo", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(lower, upper)
            .build()

        val planned = plan("dEmO.math.echo(1)", session)

        planned.assertSingleProblem(PError.FUNCTION_AMBIGUOUS)
        assertTrue(planned.plan.operators().any { it is RexError })
    }

    @Test
    fun `multiple exact scalar bindings report function ambiguous`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(Name.of("math", "echo"), scalar("echo", PType.integer()))
            .function(Name.of("MATH", "ECHO"), scalar("ECHO", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .build()

        val planned = plan("demo.math.echo(1)", session)

        planned.assertSingleProblem(PError.FUNCTION_AMBIGUOUS)
        assertTrue(planned.plan.operators().any { it is RexError })
    }

    @Test
    fun `qualified lookup does not fall back to an unqualified path entry`() {
        val qualifiedCatalog = TestRoutineCatalog.builder("qualified").build()
        val fallbackCatalog = TestRoutineCatalog.builder("fallback")
            .function(Name.of("math", "echo"), scalar("echo", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("qualified")
            .catalogs(qualifiedCatalog, fallbackCatalog)
            .path(Namespace.of("fallback", "math"))
            .build()

        val planned = plan("qualified.math.echo(1)", session)

        planned.assertSingleProblem(PError.FUNCTION_NOT_FOUND)
        assertTrue(planned.plan.operators().any { it is RexError })
    }

    @Test
    fun `scalar and aggregate collision reports function ambiguous`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(Name.of("choose"), scalar("choose", PType.integer()))
            .aggregation(Name.of("choose"), aggregate("choose", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .build()

        val planned = plan("choose(1)", session)

        planned.assertSingleProblem(PError.FUNCTION_AMBIGUOUS)
        assertTrue(planned.plan.operators().any { it is RexError })
    }

    @Test
    fun `multiple exact aggregate bindings report function ambiguous without internal error`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .aggregation(
                Name.of("stats", "custom_total"),
                aggregate("custom_total", PType.integer()),
            )
            .aggregation(
                Name.of("stats", "CUSTOM_TOTAL"),
                aggregate("CUSTOM_TOTAL", PType.integer()),
            )
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .path(Namespace.of("demo", "stats"))
            .build()

        val planned = plan(
            "SELECT custom_total(x) AS result FROM << 1 >> AS x",
            session,
        )

        planned.assertSingleProblem(PError.FUNCTION_AMBIGUOUS)
        assertTrue(planned.plan.operators().any { it is RexError })
    }

    @Test
    fun `aggregate type mismatch reports semantic error without internal error`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .aggregation(
                Name.of("custom_total"),
                aggregate("custom_total", PType.string()),
            )
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .build()

        val planned = plan(
            "SELECT custom_total(x) AS result FROM << 1 >> AS x",
            session,
        )

        planned.assertSingleProblem(PError.FUNCTION_TYPE_MISMATCH)
        assertTrue(planned.plan.operators().any { it is RexError })
    }

    @Test
    fun `scalar type mismatch retains overload candidates`() {
        val candidate = scalar("custom_echo", PType.string())
        val catalog = TestRoutineCatalog.builder("demo")
            .function(Name.of("custom_echo"), candidate)
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .build()

        val planned = plan("custom_echo(1)", session)

        planned.assertSingleProblem(PError.FUNCTION_TYPE_MISMATCH)
        assertEquals(
            listOf(candidate),
            planned.errors.problems.single().getListOrNull("CANDIDATES", FnOverload::class.java),
        )
        assertTrue(planned.plan.operators().any { it is RexError })
    }

    private fun plan(query: String, session: Session): Planned {
        val statement = parser.parse(query).statements.single()
        val errors = PErrorCollector()
        val result = planner.plan(statement, session, Context.of(errors))
        return Planned(result.plan, errors)
    }

    private fun scalar(name: String, vararg parameters: PType): FnOverload {
        val builder = FnOverload.Builder(name)
            .returns(PType.integer())
            .body { args -> args.firstOrNull() ?: Datum.integer(0) }
        parameters.forEach(builder::addParameter)
        return builder.build()
    }

    private fun aggregate(name: String, vararg parameters: PType): AggOverload {
        val builder = AggOverload.Builder(name).returns(PType.integer())
        parameters.forEach(builder::addParameter)
        return builder.build()
    }

    private data class Planned(
        val plan: Plan,
        val errors: PErrorCollector,
    ) {
        fun singleCall(): RexCall {
            assertTrue(errors.errors.isEmpty())
            return plan.operators().filterIsInstance<RexCall>().single()
        }

        fun singleDispatch(): RexDispatch {
            assertTrue(errors.errors.isEmpty())
            val operators = plan.operators()
            return operators.filterIsInstance<RexDispatch>().singleOrNull()
                ?: error("Expected one dispatch in ${operators.map { it::class.simpleName }}")
        }

        fun singleAggregate(): RelAggregate {
            assertTrue(errors.errors.isEmpty())
            return plan.operators().filterIsInstance<RelAggregate>().single()
        }

        fun assertSingleProblem(code: Int) {
            val error = assertNotNull(
                errors.problems.singleOrNull(),
                "Expected one problem in ${plan.operators().map { it::class.simpleName }}",
            )
            assertEquals(code, error.code())
            assertTrue(errors.problems.none { it.code() == PError.INTERNAL_ERROR })
        }
    }
}

private fun Plan.operators(): List<Operator> {
    val result = mutableListOf<Operator>()

    fun collect(operator: Operator) {
        result += operator
        when (operator) {
            is RelAggregate -> {
                operator.measures.flatMap { it.args }.forEach(::collect)
                operator.groups.forEach(::collect)
            }
            is RelProject -> operator.projections.forEach(::collect)
            is RexSelect -> collect(operator.constructor)
            is RexStruct -> operator.fields.forEach {
                collect(it.key)
                collect(it.value)
            }
        }
        operator.operands.forEach { operand ->
            operand.forEach(::collect)
        }
    }

    val query = assertIs<Action.Query>(action)
    collect(query.rex)
    return result
}
