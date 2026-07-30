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

package org.partiql.planner.internal.typer

import org.junit.jupiter.api.Test
import org.partiql.parser.PartiQLParser
import org.partiql.planner.internal.Env
import org.partiql.planner.internal.TestRoutineCatalog
import org.partiql.planner.internal.ir.PlanNode
import org.partiql.planner.internal.ir.Rel
import org.partiql.planner.internal.ir.Statement
import org.partiql.planner.internal.transforms.AstToPlan
import org.partiql.planner.internal.transforms.NormalizeFromSource
import org.partiql.planner.internal.transforms.NormalizeGroupBy
import org.partiql.planner.util.PErrorCollector
import org.partiql.spi.Context
import org.partiql.spi.catalog.Catalog
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Session
import org.partiql.spi.errors.PError
import org.partiql.spi.errors.PErrorListener
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.types.PType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class AggregateResolutionErrorTest {

    @Test
    fun `aggregate not found produces relational error`() {
        val unresolved = unresolvedAggregate()
        val collector = PErrorCollector()
        val result = resolve(
            unresolved,
            TestRoutineCatalog.builder("demo").build(),
            collector,
        )

        assertEquals(listOf(PError.FUNCTION_NOT_FOUND), collector.errors.map { it.code() })
        assertEquals(1, findRelErrors(result).size)
        assertTrue(collector.problems.none { it.code() == PError.INTERNAL_ERROR })
    }

    @Test
    fun `aggregate ambiguity produces relational error`() {
        val unresolved = unresolvedAggregate()
        val target = TestRoutineCatalog.builder("demo")
            .aggregation(
                "com.example.provider.first",
                Name.of("total"),
                aggregate("total"),
            )
            .aggregation(
                "com.example.provider.second",
                Name.of("total"),
                aggregate("total"),
            )
            .build()
        val collector = PErrorCollector()
        val result = resolve(unresolved, target, collector)

        val problem = collector.errors.single()
        assertEquals(PError.FUNCTION_AMBIGUOUS, problem.code())
        assertEquals(
            2,
            problem.getListOrNull("CANDIDATES", String::class.java)?.size,
        )
        assertEquals(1, findRelErrors(result).size)
        assertTrue(collector.problems.none { it.code() == PError.INTERNAL_ERROR })
    }

    @Test
    fun `scalar-only aggregate selection produces relational error`() {
        val unresolved = unresolvedAggregate()
        val target = TestRoutineCatalog.builder("demo")
            .function(
                "com.example.provider.scalar",
                Name.of("total"),
                scalar("total"),
            )
            .build()
        val collector = PErrorCollector()
        val result = resolve(unresolved, target, collector)

        assertEquals(listOf(PError.FUNCTION_NOT_FOUND), collector.errors.map { it.code() })
        assertEquals(1, findRelErrors(result).size)
        assertTrue(collector.problems.none { it.code() == PError.INTERNAL_ERROR })
    }

    private fun unresolvedAggregate(): Statement {
        val source = TestRoutineCatalog.builder("demo")
            .aggregation(
                "com.example.provider.total",
                Name.of("total"),
                aggregate("total"),
            )
            .build()
        val env = Env(session(source), PErrorListener.abortOnError())
        val parse = PartiQLParser.standard().parse(
            "SELECT VALUE demo.total(v) FROM <<1>> AS v"
        )
        assertEquals(1, parse.statements.size)
        val normalized = NormalizeGroupBy.apply(
            NormalizeFromSource.apply(parse.statements.single())
        )
        return AstToPlan.apply(normalized, env)
    }

    private fun resolve(
        statement: Statement,
        catalog: Catalog,
        collector: PErrorCollector,
    ): Statement {
        val env = Env(session(catalog), collector)
        return PlanTyper(env, Context.of(collector), emptySet()).resolve(statement)
    }

    private fun session(catalog: Catalog): Session =
        Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .build()

    private fun aggregate(name: String): AggOverload =
        AggOverload.Builder(name)
            .addParameter(PType.integer())
            .returns(PType.integer())
            .build()

    private fun scalar(name: String): FnOverload =
        FnOverload.Builder(name)
            .addParameter(PType.integer())
            .returns(PType.integer())
            .body { args -> args[0] }
            .build()

    private fun findRelErrors(node: PlanNode): List<Rel.Op.Err> {
        val current = when (node) {
            is Rel -> listOfNotNull(node.op as? Rel.Op.Err)
            else -> emptyList()
        }
        return current + node.children.flatMap(::findRelErrors)
    }
}
