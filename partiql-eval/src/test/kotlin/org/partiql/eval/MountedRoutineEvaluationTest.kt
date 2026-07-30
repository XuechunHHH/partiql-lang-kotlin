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

package org.partiql.eval

import org.junit.jupiter.api.Test
import org.partiql.eval.compiler.PartiQLCompiler
import org.partiql.parser.PartiQLParser
import org.partiql.planner.PartiQLPlanner
import org.partiql.spi.Context
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.MountedRoutines
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Namespace
import org.partiql.spi.catalog.Path
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.catalog.RoutineCatalog
import org.partiql.spi.catalog.Session
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
import kotlin.test.assertTrue

class MountedRoutineEvaluationTest {

    private val session: Session = createSession()

    @Test
    fun `executes fully qualified mounted scalar`() {
        assertDatumEquals(
            Datum.integer(101),
            execute("alpha.tools.scalarMarker(1)", session),
        )
    }

    @Test
    fun `executes path resolved mounted scalar`() {
        assertDatumEquals(
            Datum.integer(101),
            execute("scalarMarker(1)", session),
        )
    }

    @Test
    fun `executes fully qualified mounted aggregate`() {
        assertDatumEquals(
            Datum.bagVararg(Datum.integer(3)),
            execute(
                "SELECT VALUE alpha.metrics.tally(v) FROM <<1, 2, 3>> AS v",
                session,
            ),
        )
    }

    @Test
    fun `executes path resolved mounted aggregate`() {
        assertDatumEquals(
            Datum.bagVararg(Datum.integer(3)),
            execute(
                "SELECT VALUE tally(v) FROM <<1, 2, 3>> AS v",
                session,
            ),
        )
    }

    private fun createSession(): Session {
        val provider = LoadedRoutineProvider.load(
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
        val mounted = MountedRoutines.builder()
            .mountRoutine(provider, SCALAR_SOURCE, Namespace.of("Tools"))
            .mountRoutine(provider, AGGREGATE_SOURCE, Namespace.of("Metrics"))
            .build()
        val base = Session.builder()
            .catalog("alpha")
            .catalogs(HostRoutineCatalog("alpha", mounted))
            .build()
        return object : Session by base {
            override fun getPath(): Path =
                Path.of(
                    Namespace.of("alpha", "Tools"),
                    Namespace.of("alpha", "Metrics"),
                )
        }
    }

    private fun execute(query: String, session: Session): Datum {
        val parse = PartiQLParser.standard().parse(query)
        assertEquals(1, parse.statements.size)
        val collector = PErrorCollector()
        val plan = PartiQLPlanner.standard().plan(
            parse.statements.single(),
            session,
            Context.of(collector),
        ).plan
        assertTrue(
            collector.problems.isEmpty(),
            "Planning failed: ${collector.problems}",
        )
        return PartiQLCompiler.standard()
            .prepare(plan, Mode.STRICT())
            .execute()
    }

    private fun assertDatumEquals(expected: Datum, actual: Datum) {
        assertEquals(0, Datum.comparator().compare(expected, actual))
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
        val SCALAR_SOURCE = Name.of("example", "inventory", "scalarMarker")
        val AGGREGATE_SOURCE = Name.of("example", "inventory", "tally")
    }
}
