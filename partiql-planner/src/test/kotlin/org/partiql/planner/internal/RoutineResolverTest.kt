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
import org.partiql.planner.internal.ir.Rex
import org.partiql.planner.internal.ir.SetQuantifier
import org.partiql.planner.internal.typer.CompilerType
import org.partiql.spi.catalog.Catalog
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Namespace
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.catalog.RoutineCatalog
import org.partiql.spi.catalog.Session
import org.partiql.spi.catalog.Table
import org.partiql.spi.errors.PErrorListener
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoutineResolverTest {

    @Test
    fun `qualified lookup preserves matching mode for every identifier part`() {
        val catalog = TestRoutineCatalog.builder("Demo")
            .function(
                Name.of("NameSpace", "MixedRoutine"),
                scalar("MixedRoutine", PType.integer()),
            )
            .build()
        val env = env(session("Demo", catalog))

        assertEquals(
            RoutineClassification.Scalar,
            env.classifyRoutine(
                Identifier.regular("dEmO", "namespace", "MIXEDROUTINE"),
                RoutineCallShape(1, 1),
            ),
        )
        assertEquals(
            RoutineClassification.Scalar,
            env.classifyRoutine(
                Identifier.delimited("Demo", "NameSpace", "MixedRoutine"),
                RoutineCallShape(1, 1),
            ),
        )
        assertEquals(
            RoutineClassification.NotFound,
            env.classifyRoutine(
                Identifier.of(
                    Identifier.Simple.delimited("demo"),
                    Identifier.Simple.regular("namespace"),
                    Identifier.Simple.regular("mixedroutine"),
                ),
                RoutineCallShape(1, 1),
            ),
        )
        assertEquals(
            RoutineClassification.NotFound,
            env.classifyRoutine(
                Identifier.of(
                    Identifier.Simple.regular("demo"),
                    Identifier.Simple.delimited("namespace"),
                    Identifier.Simple.regular("mixedroutine"),
                ),
                RoutineCallShape(1, 1),
            ),
        )
        assertEquals(
            RoutineClassification.NotFound,
            env.classifyRoutine(
                Identifier.of(
                    Identifier.Simple.regular("demo"),
                    Identifier.Simple.regular("namespace"),
                    Identifier.Simple.delimited("mixedroutine"),
                ),
                RoutineCallShape(1, 1),
            ),
        )
    }

    @Test
    fun `unqualified scalar resolution falls through type mismatch without merging locations`() {
        val first = TestRoutineCatalog.builder("first")
            .function(
                Name.of("ns", "choose"),
                scalar("choose", PType.string()),
            )
            .build()
        val second = TestRoutineCatalog.builder("second")
            .function(
                Name.of("choose"),
                scalar("choose", PType.integer()),
            )
            .build()
        val session = Session.builder()
            .catalog("first")
            .catalogs(first, second)
            .path(
                Namespace.of("first", "ns"),
                Namespace.of("second"),
            )
            .build()

        val resolution = assertIs<ScalarRoutineResolution.Success>(
            env(session).resolveFn(Identifier.regular("CHOOSE"), listOf(integerArg())),
        )
        val call = assertIs<Rex.Op.Call.Static>(resolution.rex.op)

        assertEquals("second", call.routine?.catalog)
        assertEquals(Name.of("choose"), call.routine?.name)
        assertEquals(1, first.functionResolutionCount)
        assertEquals(1, second.functionResolutionCount)
    }

    @Test
    fun `path catalog and namespace levels use exact canonical case`() {
        val catalog = TestRoutineCatalog.builder("Demo")
            .function(Name.of("Math", "echo"), scalar("echo", PType.integer()))
            .build()
        val exactPath = Session.builder()
            .catalog("Demo")
            .catalogs(catalog)
            .path(Namespace.of("Demo", "Math"))
            .build()
        val wrongNamespaceCase = Session.builder()
            .catalog("Demo")
            .catalogs(catalog)
            .path(Namespace.of("Demo", "math"))
            .build()
        val wrongCatalogCase = Session.builder()
            .catalog("Demo")
            .catalogs(catalog)
            .path(Namespace.of("demo", "Math"))
            .build()

        assertEquals(
            RoutineClassification.Scalar,
            env(exactPath).classifyRoutine(Identifier.regular("ECHO"), RoutineCallShape(1, 1)),
        )
        assertEquals(
            RoutineClassification.NotFound,
            env(wrongNamespaceCase).classifyRoutine(Identifier.regular("echo"), RoutineCallShape(1, 1)),
        )
        assertEquals(
            RoutineClassification.NotFound,
            env(wrongCatalogCase).classifyRoutine(Identifier.regular("echo"), RoutineCallShape(1, 1)),
        )
    }

    @Test
    fun `earlier coercible scalar wins over later exact scalar`() {
        val first = TestRoutineCatalog.builder("first")
            .function(Name.of("choose"), scalar("choose", PType.bigint()))
            .build()
        val second = TestRoutineCatalog.builder("second")
            .function(Name.of("choose"), scalar("choose", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("first")
            .catalogs(first, second)
            .path(Namespace.of("first"), Namespace.of("second"))
            .build()

        val resolution = assertIs<ScalarRoutineResolution.Success>(
            env(session).resolveFn(Identifier.regular("choose"), listOf(integerArg())),
        )
        val call = assertIs<Rex.Op.Call.Static>(resolution.rex.op)

        assertEquals("first", call.routine?.catalog)
    }

    @Test
    fun `earlier viable scalar wins before later binding ambiguity`() {
        val first = TestRoutineCatalog.builder("first")
            .function(Name.of("choose"), scalar("choose", PType.integer()))
            .build()
        val second = TestRoutineCatalog.builder("second")
            .function(Name.of("choose"), scalar("choose", PType.integer()))
            .function(Name.of("CHOOSE"), scalar("CHOOSE", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("first")
            .catalogs(first, second)
            .path(Namespace.of("first"), Namespace.of("second"))
            .build()

        val resolution = assertIs<ScalarRoutineResolution.Success>(
            env(session).resolveFn(Identifier.regular("choose"), listOf(integerArg())),
        )
        val call = assertIs<Rex.Op.Call.Static>(resolution.rex.op)

        assertEquals("first", call.routine?.catalog)
    }

    @Test
    fun `earlier viable aggregate wins before later binding ambiguity`() {
        val first = TestRoutineCatalog.builder("first")
            .aggregation(Name.of("total"), aggregate("total", PType.integer()))
            .build()
        val second = TestRoutineCatalog.builder("second")
            .aggregation(Name.of("total"), aggregate("total", PType.integer()))
            .aggregation(Name.of("TOTAL"), aggregate("TOTAL", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("first")
            .catalogs(first, second)
            .path(Namespace.of("first"), Namespace.of("second"))
            .build()

        val resolution = assertIs<AggregateRoutineResolution.Success>(
            env(session).resolveAgg(
                Identifier.regular("total"),
                RoutineCallShape(1, 1),
                SetQuantifier.ALL,
                listOf(integerArg()),
            ),
        )

        assertEquals("first", resolution.call.routine?.catalog)
    }

    @Test
    fun `empty and missing path entries are skipped while duplicates remain ordered locations`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(Name.of("echo"), scalar("echo", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .path(
                Namespace.empty(),
                Namespace.of("missing"),
                Namespace.of("demo"),
                Namespace.of("demo"),
            )
            .build()

        val resolution = assertIs<ScalarRoutineResolution.Success>(
            env(session).resolveFn(Identifier.regular("echo"), listOf(integerArg())),
        )
        val call = assertIs<Rex.Op.Call.Static>(resolution.rex.op)

        assertEquals("demo", call.routine?.catalog)
        assertEquals(1, catalog.functionResolutionCount)
        assertEquals(2, catalog.aggregationResolutionCount)
    }

    @Test
    fun `same-name bindings with disjoint arities are not ambiguous`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(Name.of("echo"), scalar("echo", PType.integer()))
            .function(Name.of("ECHO"), scalar("ECHO", PType.integer(), PType.integer()))
            .build()

        val resolution = assertIs<ScalarRoutineResolution.Success>(
            env(session("demo", catalog)).resolveFn(Identifier.regular("echo"), listOf(integerArg())),
        )
        val call = assertIs<Rex.Op.Call.Static>(resolution.rex.op)

        assertEquals(Name.of("echo"), call.routine?.name)
    }

    @Test
    fun `scalar and aggregate candidates at different path entries are ambiguous`() {
        val scalar = TestRoutineCatalog.builder("scalar")
            .function(Name.of("choose"), scalar("choose", PType.integer()))
            .build()
        val aggregate = TestRoutineCatalog.builder("aggregate")
            .aggregation(Name.of("choose"), aggregate("choose", PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("scalar")
            .catalogs(scalar, aggregate)
            .path(Namespace.of("scalar"), Namespace.of("aggregate"))
            .build()

        val classification = assertIs<RoutineClassification.Ambiguous>(
            env(session).classifyRoutine(Identifier.regular("choose"), RoutineCallShape(1, 1)),
        )

        assertEquals(2, classification.candidates.size)
    }

    @Test
    fun `count star uses separate scalar and aggregate arities`() {
        val shape = RoutineCallShape.from(Identifier.regular("COUNT"), arity = 0)
        val catalog = TestRoutineCatalog.builder("demo")
            .function(Name.of("count"), scalar("count", PType.integer()))
            .aggregation(Name.of("count"), aggregate("count", PType.integer()))
            .build()

        assertEquals(RoutineCallShape(scalarArity = 0, aggregateArity = 1), shape)
        assertEquals(
            RoutineClassification.Aggregate,
            env(session("demo", catalog)).classifyRoutine(Identifier.regular("count"), shape),
        )
    }

    @Test
    fun `classification and typed resolution reuse cached exact lookups`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(Name.of("echo"), scalar("echo", PType.integer()))
            .build()
        val env = env(session("demo", catalog))
        val identifier = Identifier.regular("echo")

        assertEquals(
            RoutineClassification.Scalar,
            env.classifyRoutine(identifier, RoutineCallShape(1, 1)),
        )
        assertIs<ScalarRoutineResolution.Success>(env.resolveFn(identifier, listOf(integerArg())))
        assertEquals(1, catalog.functionResolutionCount)
        assertEquals(1, catalog.aggregationResolutionCount)
    }

    @Test
    fun `legacy lookup ignores namespace tail lowercases name and has no identity`() {
        val legacy = LegacyCatalog("legacy", scalar("echo", PType.integer()))
        val session = Session.builder()
            .catalog("legacy")
            .catalogs(legacy)
            .path(Namespace.of("legacy", "ignored"))
            .build()

        val resolution = assertIs<ScalarRoutineResolution.Success>(
            env(session).resolveFn(Identifier.regular("EcHo"), listOf(integerArg())),
        )
        val call = assertIs<Rex.Op.Call.Static>(resolution.rex.op)

        assertEquals(listOf("echo"), legacy.requests)
        assertNull(call.routine)
    }

    @Test
    fun `legacy aggregate lookup lowercases name and has no identity`() {
        val requests = mutableListOf<String>()
        val overload = aggregate("total", PType.integer())
        val legacy = object : Catalog {
            override fun getName(): String = "legacy"

            override fun getAggregations(session: Session, name: String): Collection<AggOverload> {
                requests += name
                return if (name == overload.signature.name) listOf(overload) else emptyList()
            }
        }
        val session = Session.builder()
            .catalog("legacy")
            .catalogs(legacy)
            .build()

        val resolution = assertIs<AggregateRoutineResolution.Success>(
            env(session).resolveAgg(
                Identifier.regular("ToTaL"),
                RoutineCallShape(1, 1),
                SetQuantifier.ALL,
                listOf(integerArg()),
            ),
        )

        assertEquals(listOf("total"), requests)
        assertNull(resolution.call.routine)
    }

    @Test
    fun `legacy scalar success does not probe later scalar catalogs`() {
        val first = LegacyCatalog("first", scalar("echo", PType.integer()))
        val later = object : Catalog {
            override fun getName(): String = "later"

            override fun getFunctions(session: Session, name: String): Collection<FnOverload> =
                error("Later scalar catalog must not be queried")
        }
        val session = Session.builder()
            .catalog("first")
            .catalogs(first, later)
            .path(Namespace.of("first"), Namespace.of("later"))
            .build()

        assertIs<ScalarRoutineResolution.Success>(
            env(session).resolveFn(Identifier.regular("echo"), listOf(integerArg())),
        )
    }

    @Test
    fun `legacy aggregate success does not probe later aggregate catalogs`() {
        val overload = aggregate("total", PType.integer())
        val first = object : Catalog {
            override fun getName(): String = "first"

            override fun getAggregations(session: Session, name: String): Collection<AggOverload> =
                if (name == overload.signature.name) listOf(overload) else emptyList()
        }
        val later = object : Catalog {
            override fun getName(): String = "later"

            override fun getAggregations(session: Session, name: String): Collection<AggOverload> =
                error("Later aggregate catalog must not be queried")
        }
        val session = Session.builder()
            .catalog("first")
            .catalogs(first, later)
            .path(Namespace.of("first"), Namespace.of("later"))
            .build()

        assertIs<AggregateRoutineResolution.Success>(
            env(session).resolveAgg(
                Identifier.regular("total"),
                RoutineCallShape(1, 1),
                SetQuantifier.ALL,
                listOf(integerArg()),
            ),
        )
    }

    @Test
    fun `exact lookup is authoritative for an adopting catalog`() {
        val legacyOverload = scalar("echo", PType.integer())
        val catalog = object : RoutineCatalog {
            override fun getName(): String = "demo"

            override fun getFunctions(session: Session, name: String): Collection<FnOverload> =
                listOf(legacyOverload)

            override fun resolveFunctions(
                session: Session,
                identifier: Identifier,
            ): Collection<RoutineBinding<FnOverload>> = emptyList()

            override fun resolveAggregations(
                session: Session,
                identifier: Identifier,
            ): Collection<RoutineBinding<AggOverload>> = emptyList()
        }

        assertEquals(
            ScalarRoutineResolution.NotFound,
            env(session("demo", catalog)).resolveFn(Identifier.regular("echo"), listOf(integerArg())),
        )
    }

    @Test
    fun `legacy binding with wrong arity falls through to the next path entry`() {
        val first = LegacyCatalog(
            "first",
            scalar("echo", PType.integer(), PType.integer()),
        )
        val second = LegacyCatalog("second", scalar("echo", PType.integer()))
        val session = Session.builder()
            .catalog("first")
            .catalogs(first, second)
            .path(Namespace.of("first"), Namespace.of("second"))
            .build()

        val resolution = assertIs<ScalarRoutineResolution.Success>(
            env(session).resolveFn(Identifier.regular("echo"), listOf(integerArg())),
        )
        val call = assertIs<Rex.Op.Call.Static>(resolution.rex.op)

        assertEquals(listOf("echo"), first.requests)
        assertEquals(listOf("echo"), second.requests)
        assertNull(call.routine)
    }

    @Test
    fun `routine path remains independent from table namespace resolution`() {
        val catalog = TestRoutineCatalog.builder("demo")
            .function(Name.of("routines", "echo"), scalar("echo", PType.integer()))
            .table(Table.empty(Name.of("tables", "payload"), PType.integer()))
            .build()
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .namespace("tables")
            .path(Namespace.of("demo", "routines"))
            .build()
        val env = env(session)

        assertNotNull(env.resolveTable(Identifier.regular("payload")))
        assertIs<ScalarRoutineResolution.Success>(
            env.resolveFn(Identifier.regular("echo"), listOf(integerArg())),
        )
    }

    private fun env(session: Session): Env = Env(session, PErrorListener.abortOnError())

    private fun session(current: String, vararg catalogs: Catalog): Session =
        Session.builder()
            .catalog(current)
            .catalogs(*catalogs)
            .build()

    private fun integerArg(): Rex =
        Rex(CompilerType(PType.integer()), Rex.Op.Lit(Datum.integer(1)))

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
