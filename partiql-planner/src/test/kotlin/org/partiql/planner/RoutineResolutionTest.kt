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

package org.partiql.planner

import org.junit.jupiter.api.Test
import org.partiql.parser.PartiQLParser
import org.partiql.plan.Action
import org.partiql.plan.Operator
import org.partiql.plan.RoutineRef
import org.partiql.plan.rel.RelAggregate
import org.partiql.plan.rel.RelProject
import org.partiql.plan.rex.Rex
import org.partiql.plan.rex.RexCall
import org.partiql.plan.rex.RexDispatch
import org.partiql.plan.rex.RexError
import org.partiql.plan.rex.RexSelect
import org.partiql.planner.internal.TestRoutineCatalog
import org.partiql.planner.util.PErrorCollector
import org.partiql.spi.Context
import org.partiql.spi.catalog.Catalog
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Namespace
import org.partiql.spi.catalog.Path
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.catalog.RoutineCatalog
import org.partiql.spi.catalog.Session
import org.partiql.spi.catalog.Table
import org.partiql.spi.errors.PError
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.function.RoutineId
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineResolutionTest {

    @Test
    fun `system routines resolve through qualification and path`() {
        val session = Session.empty()

        val qualified = assertIs<RexCall>(plan("\$system.utcnow()", session))
        assertRoutine(
            qualified.routineRef,
            "org.partiql.builtin.utcnow",
            "\$system",
            Name.of("utcnow"),
        )

        val unqualified = assertIs<RexCall>(plan("utcnow()", session))
        assertEquals(qualified.routineRef, unqualified.routineRef)
    }

    @Test
    fun `qualified static calls carry exact catalog local identity`() {
        val rootId = "com.example.provider.rootRoutine"
        val deepId = "com.example.provider.deepRoutine"
        val catalog = TestRoutineCatalog.builder("Demo")
            .function(rootId, Name.of("RootRoutine"), scalar("RootRoutine", PType.integer()))
            .function(
                deepId,
                Name.of("NameSpace", "MixedRoutine"),
                scalar("MixedRoutine", PType.integer()),
            )
            .build()
        val session = session("Demo", catalog)

        val root = assertIs<RexCall>(plan("demo.rootroutine(1)", session))
        assertRoutine(root.routineRef, rootId, "Demo", Name.of("RootRoutine"))

        val deep = assertIs<RexCall>(
            plan("\"Demo\".\"NameSpace\".\"MixedRoutine\"(1)", session)
        )
        assertRoutine(deep.routineRef, deepId, "Demo", Name.of("NameSpace", "MixedRoutine"))
    }

    @Test
    fun `dynamic dispatch candidates carry one selected routine identity`() {
        val providerId = "com.example.provider.polymorphicRoutine"
        val catalog = TestRoutineCatalog.builder("demo")
            .function(
                providerId,
                Name.of("poly"),
                scalar("poly", PType.integer()),
                scalar("poly", PType.string()),
            )
            .table(Table.empty("source", PType.dynamic()))
            .build()

        val rex = plan(
            "SELECT VALUE demo.poly(v) FROM source AS v",
            session("demo", catalog),
        )
        val dispatch = find<RexDispatch>(rex).single()
        assertEquals(2, dispatch.functions.size)
        assertRoutine(dispatch.routineRef, providerId, "demo", Name.of("poly"))
    }

    @Test
    fun `aggregate measures carry identity and count star is normalized after selection`() {
        val totalId = "com.example.provider.total"
        val countId = "com.example.provider.aggregateCount"
        val catalog = TestRoutineCatalog.builder("demo")
            .aggregation(
                totalId,
                Name.of("metrics", "total"),
                aggregate("total", PType.integer()),
            )
            .function(
                "com.example.provider.scalarCount",
                Name.of("count"),
                scalar("count", PType.integer()),
            )
            .aggregation(
                countId,
                Name.of("count"),
                aggregate("count", PType.integer()),
            )
            .build()
        val session = session("demo", catalog)

        val totalPlan = plan(
            "SELECT VALUE demo.metrics.total(v) FROM <<1>> AS v",
            session,
        )
        val total = find<RelAggregate>(totalPlan).single().measures.single()
        assertRoutine(total.routineRef, totalId, "demo", Name.of("metrics", "total"))

        val countPlan = plan(
            "SELECT VALUE demo.count(*) FROM <<1>> AS v",
            session,
        )
        val count = find<RelAggregate>(countPlan).single().measures.single()
        assertRoutine(count.routineRef, countId, "demo", Name.of("count"))
        assertEquals(1, count.args.size)
        assertEquals(PType.integer(), count.args.single().type.pType)
    }

    @Test
    fun `aggregate classification and typing reuse one cached binding`() {
        val firstId = "com.example.provider.firstAggregate"
        val secondId = "com.example.provider.secondAggregate"
        val catalog = StatefulAggregateCatalog(
            "demo",
            RoutineBinding(
                RoutineId(firstId),
                Name.of("flip"),
                listOf(aggregate("flip", PType.integer())),
            ),
            RoutineBinding(
                RoutineId(secondId),
                Name.of("flip"),
                listOf(aggregate("flip", PType.integer())),
            ),
        )

        val rex = plan(
            "SELECT VALUE demo.flip(v) FROM <<1>> AS v",
            session("demo", catalog),
        )
        val measure = find<RelAggregate>(rex).single().measures.single()
        assertRoutine(measure.routineRef, firstId, "demo", Name.of("flip"))
        assertEquals(1, catalog.functionResolutionCount)
        assertEquals(1, catalog.aggregationResolutionCount)
    }

    @Test
    fun `selected scalar type mismatch does not resume path traversal`() {
        val firstOverload = scalar("choose", PType.bag(PType.integer()))
        val first = TestRoutineCatalog.builder("first")
            .function(
                "com.example.provider.first",
                Name.of("ns", "choose"),
                firstOverload,
            )
            .build()
        val second = TestRoutineCatalog.builder("second")
            .function(
                "com.example.provider.second",
                Name.of("choose"),
                scalar("choose", PType.integer()),
            )
            .build()
        val base = session("first", first, second)
        val collector = PErrorCollector()
        val rex = plan(
            "choose(1)",
            withPath(base, Namespace.of("first", "ns"), Namespace.of("second")),
            collector,
        )

        assertIs<RexError>(rex)
        assertEquals(listOf(PError.FUNCTION_TYPE_MISMATCH), collector.warnings.map { it.code() })
        assertEquals(
            listOf(firstOverload),
            collector.warnings.single().getListOrNull("CANDIDATES", FnOverload::class.java),
        )
        assertTrue(collector.problems.none { it.code() == PError.INTERNAL_ERROR })
        assertEquals(0, second.functionResolutionCount)
        assertEquals(0, second.aggregationResolutionCount)
    }

    @Test
    fun `catalog ambiguity reports a semantic error without internal error`() {
        val lower = TestRoutineCatalog.builder("demo")
            .function(
                "com.example.provider.lower",
                Name.of("echo"),
                scalar("echo", PType.integer()),
            )
            .build()
        val upper = TestRoutineCatalog.builder("DEMO")
            .function(
                "com.example.provider.upper",
                Name.of("echo"),
                scalar("echo", PType.integer()),
            )
            .build()
        val collector = PErrorCollector()

        val rex = plan("DeMo.echo(1)", session("demo", lower, upper), collector)

        assertIs<RexError>(rex)
        assertEquals(listOf(PError.FUNCTION_AMBIGUOUS), collector.errors.map { it.code() })
        assertTrue(collector.errors.none { it.code() == PError.INTERNAL_ERROR })
    }

    @Test
    fun `legacy scalar plans remain identity free and qualified lookup is rejected`() {
        val legacy = LegacyCatalog("legacy", scalar("echo", PType.integer()))
        val session = session("legacy", legacy)

        val call = assertIs<RexCall>(plan("EcHo(1)", session))
        assertNull(call.routineRef)

        val collector = PErrorCollector()
        val qualified = plan("legacy.echo(1)", session, collector)
        assertIs<RexError>(qualified)
        assertEquals(listOf(PError.FUNCTION_NOT_FOUND), collector.errors.map { it.code() })
        assertTrue(collector.errors.none { it.code() == PError.INTERNAL_ERROR })
    }

    private fun plan(
        query: String,
        session: Session,
        collector: PErrorCollector = PErrorCollector(),
    ): Rex {
        val parse = PartiQLParser.standard().parse(query)
        assertEquals(1, parse.statements.size)
        val result = PartiQLPlanner.standard().plan(
            parse.statements.single(),
            session,
            Context.of(collector),
        )
        assertTrue(collector.errors.none { it.code() == PError.INTERNAL_ERROR })
        return (result.plan.action as Action.Query).rex
    }

    private inline fun <reified T : Operator> find(root: Operator): List<T> {
        val matches = mutableListOf<T>()
        val pending = java.util.ArrayDeque<Operator>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val operator = pending.removeFirst()
            if (operator is T) {
                matches += operator
            }
            operator.operands.forEach { operand ->
                operand.forEach(pending::add)
            }
            when (operator) {
                is RexSelect -> pending.add(operator.constructor)
                is RelProject -> pending.addAll(operator.projections)
                is RelAggregate -> {
                    pending.addAll(operator.groups)
                    operator.measures.forEach { pending.addAll(it.args) }
                }
            }
        }
        return matches
    }

    private fun assertRoutine(
        actual: RoutineRef?,
        providerId: String,
        catalog: String,
        name: Name,
    ) {
        requireNotNull(actual)
        assertEquals(RoutineId(providerId), actual.providerId)
        assertEquals(catalog, actual.catalog)
        assertEquals(name, actual.name)
    }

    private fun session(current: String, vararg catalogs: Catalog): Session =
        Session.builder()
            .catalog(current)
            .catalogs(*catalogs)
            .build()

    private fun withPath(session: Session, vararg entries: Namespace): Session =
        object : Session by session {
            override fun getPath(): Path = Path.of(*entries)
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

    private class StatefulAggregateCatalog(
        private val name: String,
        private val first: RoutineBinding<AggOverload>,
        private val second: RoutineBinding<AggOverload>,
    ) : RoutineCatalog {
        internal var functionResolutionCount: Int = 0
            private set

        internal var aggregationResolutionCount: Int = 0
            private set

        override fun getName(): String = name

        override fun resolveFunctions(
            session: Session,
            identifier: Identifier,
        ): Collection<RoutineBinding<FnOverload>> {
            functionResolutionCount++
            return emptyList()
        }

        override fun resolveAggregations(
            session: Session,
            identifier: Identifier,
        ): Collection<RoutineBinding<AggOverload>> {
            aggregationResolutionCount++
            return when (aggregationResolutionCount) {
                1 -> listOf(first)
                else -> listOf(second)
            }
        }
    }

    private class LegacyCatalog(
        private val name: String,
        private val overload: FnOverload,
    ) : Catalog {
        override fun getName(): String = name

        override fun getFunctions(session: Session, name: String): Collection<FnOverload> =
            when (name) {
                overload.signature.name -> listOf(overload)
                else -> emptyList()
            }
    }
}
