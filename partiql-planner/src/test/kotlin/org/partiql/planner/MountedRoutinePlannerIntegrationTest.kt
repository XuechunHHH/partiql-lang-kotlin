/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://aws.amazon.com/apache2.0
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
import org.partiql.plan.rex.RexError
import org.partiql.plan.rex.RexSelect
import org.partiql.planner.util.PErrorCollector
import org.partiql.spi.Context
import org.partiql.spi.catalog.Catalog
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.MountedRoutines
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Namespace
import org.partiql.spi.catalog.Path
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.catalog.RoutineCatalog
import org.partiql.spi.catalog.Session
import org.partiql.spi.errors.PError
import org.partiql.spi.function.Accumulator
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.function.LoadedRoutineProvider
import org.partiql.spi.function.ProvidedRoutine
import org.partiql.spi.function.RoutineId
import org.partiql.spi.function.RoutineProvider
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MountedRoutinePlannerIntegrationTest {

    @Test
    fun `qualified and path calls preserve mounted scalar and aggregate identity`() {
        val provider = loadProvider()
        val mounted = MountedRoutines.builder()
            .mountRoutine(provider, SCALAR_SOURCE, Namespace.of("Tools"))
            .mountRoutine(provider, AGGREGATE_SOURCE, Namespace.of("Metrics"))
            .build()
        val catalog = HostRoutineCatalog("alpha", mounted)
        val session = withPath(
            session("alpha", catalog),
            Namespace.of("alpha", "Tools"),
            Namespace.of("alpha", "Metrics"),
        )

        val qualifiedScalar = assertIs<RexCall>(
            plan("alpha.tools.scalarMarker(1)", session),
        )
        val pathScalar = assertIs<RexCall>(plan("scalarMarker(1)", session))
        val scalarRef = RoutineRef(
            SCALAR_ID,
            "alpha",
            Name.of("Tools", "scalarMarker"),
        )
        assertEquals(scalarRef, qualifiedScalar.routineRef)
        assertEquals(scalarRef, pathScalar.routineRef)

        val qualifiedAggregate = aggregateMeasure(
            plan(
                "SELECT VALUE alpha.metrics.tally(v) FROM <<1, 2>> AS v",
                session,
            ),
        )
        val pathAggregate = aggregateMeasure(
            plan(
                "SELECT VALUE tally(v) FROM <<1, 2>> AS v",
                session,
            ),
        )
        val aggregateRef = RoutineRef(
            AGGREGATE_ID,
            "alpha",
            Name.of("Metrics", "tally"),
        )
        assertEquals(aggregateRef, qualifiedAggregate.routineRef)
        assertEquals(aggregateRef, pathAggregate.routineRef)
    }

    @Test
    fun `independent catalog mounts retain provider identity and change SQL identity`() {
        val provider = loadProvider()
        val alpha = HostRoutineCatalog(
            "alpha",
            MountedRoutines.builder()
                .mountRoutine(provider, SCALAR_SOURCE, Namespace.of("Tools"))
                .build(),
        )
        val beta = HostRoutineCatalog(
            "beta",
            MountedRoutines.builder()
                .mountRoutine(provider, SCALAR_SOURCE, Namespace.of("Utilities"))
                .build(),
        )
        val session = session("alpha", alpha, beta)

        val alphaRef = assertIs<RexCall>(
            plan("alpha.tools.scalarMarker(1)", session),
        ).routineRef
        val betaRef = assertIs<RexCall>(
            plan("beta.utilities.scalarMarker(1)", session),
        ).routineRef

        requireNotNull(alphaRef)
        requireNotNull(betaRef)
        assertEquals(SCALAR_ID, alphaRef.providerId)
        assertEquals(SCALAR_ID, betaRef.providerId)
        assertEquals("alpha", alphaRef.catalog)
        assertEquals("beta", betaRef.catalog)
        assertEquals(Name.of("Tools", "scalarMarker"), alphaRef.name)
        assertEquals(Name.of("Utilities", "scalarMarker"), betaRef.name)
    }

    @Test
    fun `loaded but unmounted routine remains invisible`() {
        val provider = loadProvider()
        val catalog = HostRoutineCatalog(
            "alpha",
            MountedRoutines.builder()
                .mountRoutine(provider, SCALAR_SOURCE, Namespace.of("Tools"))
                .build(),
        )
        val collector = PErrorCollector()

        val result = plan("alpha.notMounted(1)", session("alpha", catalog), collector)

        assertIs<RexError>(result)
        assertEquals(listOf(PError.FUNCTION_NOT_FOUND), collector.errors.map { it.code() })
        assertTrue(collector.problems.none { it.code() == PError.INTERNAL_ERROR })
    }

    private fun loadProvider(): LoadedRoutineProvider =
        LoadedRoutineProvider.load(
            object : RoutineProvider {
                override fun getFunctions(): Collection<ProvidedRoutine<FnOverload>> =
                    listOf(
                        ProvidedRoutine(
                            SCALAR_ID,
                            SCALAR_SOURCE,
                            listOf(
                                FnOverload.Builder(SCALAR_SOURCE.getName())
                                    .addParameter(PType.integer())
                                    .returns(PType.integer())
                                    .body { Datum.integer(101) }
                                    .build(),
                            ),
                        ),
                        ProvidedRoutine(
                            UNMOUNTED_ID,
                            UNMOUNTED_SOURCE,
                            listOf(
                                FnOverload.Builder(UNMOUNTED_SOURCE.getName())
                                    .addParameter(PType.integer())
                                    .returns(PType.integer())
                                    .body { Datum.integer(-1) }
                                    .build(),
                            ),
                        ),
                    )

                override fun getAggregations(): Collection<ProvidedRoutine<AggOverload>> =
                    listOf(
                        ProvidedRoutine(
                            AGGREGATE_ID,
                            AGGREGATE_SOURCE,
                            listOf(
                                AggOverload.Builder(AGGREGATE_SOURCE.getName())
                                    .addParameter(PType.integer())
                                    .returns(PType.integer())
                                    .body { CountingAccumulator() }
                                    .build(),
                            ),
                        ),
                    )
            },
        )

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
        assertTrue(collector.problems.none { it.code() == PError.INTERNAL_ERROR })
        return (result.plan.action as Action.Query).rex
    }

    private fun aggregateMeasure(root: Rex): RelAggregate.Measure =
        find<RelAggregate>(root).single().measures.single()

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

    private fun session(current: String, vararg catalogs: Catalog): Session =
        Session.builder()
            .catalog(current)
            .catalogs(*catalogs)
            .build()

    private fun withPath(session: Session, vararg entries: Namespace): Session =
        object : Session by session {
            override fun getPath(): Path = Path.of(*entries)
        }

    private class HostRoutineCatalog(
        private val name: String,
        private val mounted: MountedRoutines,
    ) : RoutineCatalog {
        override fun getName(): String = name

        override fun resolveFunctions(
            session: Session,
            identifier: Identifier,
        ): Collection<RoutineBinding<FnOverload>> = mounted.resolveFunctions(identifier)

        override fun resolveAggregations(
            session: Session,
            identifier: Identifier,
        ): Collection<RoutineBinding<AggOverload>> = mounted.resolveAggregations(identifier)
    }

    private class CountingAccumulator : Accumulator {
        private var count: Int = 0

        override fun next(args: Array<out Datum>?) {
            count++
        }

        override fun value(): Datum = Datum.integer(count)
    }

    private companion object {
        val SCALAR_ID = RoutineId("example.provider.scalar-marker")
        val AGGREGATE_ID = RoutineId("example.provider.tally")
        val UNMOUNTED_ID = RoutineId("example.provider.not-mounted")
        val SCALAR_SOURCE = Name.of("example", "inventory", "scalarMarker")
        val AGGREGATE_SOURCE = Name.of("example", "inventory", "tally")
        val UNMOUNTED_SOURCE = Name.of("example", "inventory", "notMounted")
    }
}
