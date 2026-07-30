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
import org.partiql.spi.catalog.Catalog
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Namespace
import org.partiql.spi.catalog.Path
import org.partiql.spi.catalog.Session
import org.partiql.spi.errors.PErrorListener
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineResolverTest {

    @Test
    fun `qualified resolution preserves catalog namespace and routine matching modes`() {
        val catalog = TestRoutineCatalog.builder("Demo")
            .function(
                "com.example.provider.mixedRoutine",
                Name.of("NameSpace", "MixedRoutine"),
                scalar("MixedRoutine", PType.integer()),
            )
            .build()
        val env = env(session("Demo", catalog))

        val regular = env.selectRoutine(
            Identifier.regular("dEmO", "namespace", "MIXEDROUTINE"),
            arity = 1,
        )
        assertScalar(regular, "Demo", Name.of("NameSpace", "MixedRoutine"))

        val quotedNamespace = env.selectRoutine(
            Identifier.of(
                Identifier.Simple.regular("demo"),
                Identifier.Simple.delimited("NameSpace"),
                Identifier.Simple.regular("mixedroutine"),
            ),
            arity = 1,
        )
        assertIs<RoutineSelection.Scalar>(quotedNamespace)

        val quotedCatalogAndRoutine = env.selectRoutine(
            Identifier.of(
                Identifier.Simple.delimited("Demo"),
                Identifier.Simple.regular("namespace"),
                Identifier.Simple.delimited("MixedRoutine"),
            ),
            arity = 1,
        )
        assertIs<RoutineSelection.Scalar>(quotedCatalogAndRoutine)

        assertIs<RoutineSelection.NotFound>(
            env.selectRoutine(
                Identifier.of(
                    Identifier.Simple.delimited("demo"),
                    Identifier.Simple.regular("namespace"),
                    Identifier.Simple.regular("mixedroutine"),
                ),
                arity = 1,
            )
        )
        assertIs<RoutineSelection.NotFound>(
            env.selectRoutine(
                Identifier.of(
                    Identifier.Simple.regular("demo"),
                    Identifier.Simple.delimited("namespace"),
                    Identifier.Simple.regular("mixedroutine"),
                ),
                arity = 1,
            )
        )
        assertIs<RoutineSelection.NotFound>(
            env.selectRoutine(
                Identifier.of(
                    Identifier.Simple.regular("demo"),
                    Identifier.Simple.regular("namespace"),
                    Identifier.Simple.delimited("mixedroutine"),
                ),
                arity = 1,
            )
        )
    }

    @Test
    fun `unquoted catalog ambiguity is reported while quoted catalog is exact`() {
        val lower = TestRoutineCatalog.builder("demo")
            .function("com.example.provider.lower", Name.of("echo"), scalar("echo", PType.integer()))
            .build()
        val upper = TestRoutineCatalog.builder("DEMO")
            .function("com.example.provider.upper", Name.of("echo"), scalar("echo", PType.integer()))
            .build()
        val env = env(session("demo", lower, upper))

        val ambiguous = assertIs<RoutineSelection.Ambiguous>(
            env.selectRoutine(Identifier.regular("DeMo", "echo"), arity = 1)
        )
        assertEquals(setOf("demo", "DEMO"), ambiguous.candidates.toSet())

        val exact = assertIs<RoutineSelection.Scalar>(
            env.selectRoutine(
                Identifier.of(
                    Identifier.Simple.delimited("DEMO"),
                    Identifier.Simple.regular("echo"),
                ),
                arity = 1,
            )
        )
        assertEquals("DEMO", exact.match.catalog)
    }

    @Test
    fun `unquoted routine ambiguity is reported while quoted routine is exact`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(
                "com.example.provider.lowerRoutine",
                Name.of("Echo"),
                scalar("Echo", PType.integer()),
            )
            .function(
                "com.example.provider.upperRoutine",
                Name.of("ECHO"),
                scalar("ECHO", PType.integer()),
            )
            .build()
        val env = env(session("demo", catalog))

        val ambiguous = assertIs<RoutineSelection.Ambiguous>(
            env.selectRoutine(Identifier.regular("echo"), arity = 1)
        )
        assertEquals(2, ambiguous.candidates.size)

        val exact = assertIs<RoutineSelection.Scalar>(
            env.selectRoutine(Identifier.delimited("Echo"), arity = 1)
        )
        assertEquals("com.example.provider.lowerRoutine", exact.match.routine?.providerId?.value)
        assertEquals(Name.of("Echo"), exact.match.canonicalName)
    }

    @Test
    fun `qualified names resolve from catalog root and never fall back to path`() {
        val current = TestRoutineCatalog.builder("current")
            .function(
                "com.example.provider.path",
                Name.of("ns", "echo"),
                scalar("echo", PType.integer()),
            )
            .build()
        val demo = TestRoutineCatalog.builder("demo")
            .function(
                "com.example.provider.root",
                Name.of("echo"),
                scalar("echo", PType.integer()),
            )
            .function(
                "com.example.provider.deep",
                Name.of("deep", "echo"),
                scalar("echo", PType.integer()),
            )
            .build()
        val base = session("current", current, demo)
        val env = env(withPath(base, Namespace.of("current", "ns")))

        assertScalar(
            env.selectRoutine(Identifier.regular("demo", "echo"), arity = 1),
            "demo",
            Name.of("echo"),
        )
        assertScalar(
            env.selectRoutine(Identifier.regular("demo", "deep", "echo"), arity = 1),
            "demo",
            Name.of("deep", "echo"),
        )
        assertIs<RoutineSelection.NotFound>(
            env.selectRoutine(Identifier.regular("ns", "echo"), arity = 1)
        )
    }

    @Test
    fun `unqualified resolution uses complete ordered path and matching arity`() {
        val first = TestRoutineCatalog.builder("first")
            .function(
                "com.example.provider.first",
                Name.of("one", "echo"),
                scalar("echo", PType.integer(), PType.integer()),
            )
            .build()
        val second = TestRoutineCatalog.builder("second")
            .function(
                "com.example.provider.second",
                Name.of("two", "echo"),
                scalar("echo", PType.integer()),
            )
            .build()
        val base = session("first", first, second)
        val env = env(
            withPath(
                base,
                Namespace.of("first", "one"),
                Namespace.of("second", "two"),
            )
        )

        val selected = assertIs<RoutineSelection.Scalar>(
            env.selectRoutine(Identifier.regular("ECHO"), arity = 1)
        )
        assertEquals("second", selected.match.catalog)
        assertEquals(Name.of("two", "echo"), selected.match.canonicalName)
        assertEquals(1, first.functionResolutionCount)
        assertEquals(1, second.functionResolutionCount)
    }

    @Test
    fun `routine kind selection is joint at one location and respects path precedence`() {
        val collision = TestRoutineCatalog.builder("collision")
            .function(
                "com.example.provider.scalar",
                Name.of("choose"),
                scalar("choose", PType.integer()),
            )
            .aggregation(
                "com.example.provider.aggregate",
                Name.of("choose"),
                aggregate("choose", PType.integer()),
            )
            .build()
        val collisionEnv = env(session("collision", collision))
        val ambiguous = assertIs<RoutineSelection.Ambiguous>(
            collisionEnv.selectRoutine(Identifier.regular("choose"), arity = 1)
        )
        assertTrue(ambiguous.candidates.any { it.startsWith("scalar ") })
        assertTrue(ambiguous.candidates.any { it.startsWith("aggregate ") })

        val scalarCatalog = TestRoutineCatalog.builder("scalar")
            .function(
                "com.example.provider.scalar",
                Name.of("choose"),
                scalar("choose", PType.integer()),
            )
            .build()
        val aggregateCatalog = TestRoutineCatalog.builder("aggregate")
            .aggregation(
                "com.example.provider.aggregate",
                Name.of("choose"),
                aggregate("choose", PType.integer()),
            )
            .build()
        val base = session("scalar", scalarCatalog, aggregateCatalog)

        val scalarFirst = env(
            withPath(base, Namespace.of("scalar"), Namespace.of("aggregate"))
        )
        assertIs<RoutineSelection.Scalar>(
            scalarFirst.selectRoutine(Identifier.regular("choose"), arity = 1)
        )
        assertEquals(0, aggregateCatalog.functionResolutionCount)
        assertEquals(0, aggregateCatalog.aggregationResolutionCount)

        val aggregateFirst = env(
            withPath(base, Namespace.of("aggregate"), Namespace.of("scalar"))
        )
        assertIs<RoutineSelection.Aggregate>(
            aggregateFirst.selectRoutine(Identifier.regular("choose"), arity = 1)
        )
    }

    @Test
    fun `count call shape separates scalar and aggregate arity`() {
        assertEquals(
            RoutineCallShape(scalarArity = 0, aggregateArity = 1),
            RoutineCallShape.from(Identifier.regular("COUNT"), arity = 0),
        )
        assertEquals(
            RoutineCallShape(scalarArity = 0, aggregateArity = 0),
            RoutineCallShape.from(Identifier.delimited("COUNT"), arity = 0),
        )

        val catalog = TestRoutineCatalog.builder("demo")
            .function(
                "com.example.provider.scalarCount",
                Name.of("count"),
                scalar("count", PType.integer()),
            )
            .aggregation(
                "com.example.provider.aggregateCount",
                Name.of("count"),
                aggregate("count", PType.integer()),
            )
            .build()
        val selected = env(session("demo", catalog)).selectRoutine(
            Identifier.regular("count"),
            RoutineCallShape.from(Identifier.regular("count"), arity = 0),
        )
        val aggregate = assertIs<RoutineSelection.Aggregate>(selected)
        assertEquals("com.example.provider.aggregateCount", aggregate.match.routine?.providerId?.value)
    }

    @Test
    fun `cache key preserves quoting mode and selected binding`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(
                "com.example.provider.echo",
                Name.of("echo"),
                scalar("echo", PType.integer()),
            )
            .build()
        val env = env(session("demo", catalog))

        assertIs<RoutineSelection.Scalar>(
            env.selectRoutine(Identifier.regular("ECHO"), arity = 1)
        )
        assertIs<RoutineSelection.Scalar>(
            env.selectRoutine(Identifier.regular("ECHO"), arity = 1)
        )
        assertEquals(1, catalog.functionResolutionCount)
        assertEquals(1, catalog.aggregationResolutionCount)

        assertIs<RoutineSelection.NotFound>(
            env.selectRoutine(Identifier.delimited("ECHO"), arity = 1)
        )
        assertEquals(2, catalog.functionResolutionCount)
        assertEquals(2, catalog.aggregationResolutionCount)
    }

    @Test
    fun `legacy lookup is unqualified lowercased and has no identity`() {
        val overload = scalar("echo", PType.integer())
        val legacy = LegacyCatalog("legacy", overload)
        val base = session("legacy", legacy)
        val env = env(withPath(base, Namespace.of("legacy", "ignored")))

        val selected = assertIs<RoutineSelection.Scalar>(
            env.selectRoutine(Identifier.regular("EcHo"), arity = 1)
        )
        assertEquals(listOf("echo"), legacy.requests)
        assertNull(selected.match.routine)

        assertIs<RoutineSelection.NotFound>(
            env.selectRoutine(Identifier.regular("legacy", "echo"), arity = 1)
        )
        assertEquals(listOf("echo"), legacy.requests)
    }

    private fun assertScalar(selection: RoutineSelection, catalog: String, name: Name) {
        val scalar = assertIs<RoutineSelection.Scalar>(selection)
        assertEquals(catalog, scalar.match.catalog)
        assertEquals(name, scalar.match.canonicalName)
    }

    private fun env(session: Session): Env = Env(session, PErrorListener.abortOnError())

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

    private class LegacyCatalog(
        private val name: String,
        private val overload: FnOverload,
    ) : Catalog {
        internal val requests = mutableListOf<String>()

        override fun getName(): String = name

        override fun getFunctions(session: Session, name: String): Collection<FnOverload> {
            requests += name
            return when (name) {
                overload.signature.name -> listOf(overload)
                else -> emptyList()
            }
        }
    }
}
