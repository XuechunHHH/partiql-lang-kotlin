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

package org.partiql.spi.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.function.LoadedRoutineProvider
import org.partiql.spi.function.RoutineDefinition
import org.partiql.spi.function.RoutineProvider
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MountedRoutinesTest {

    @Test
    fun emptyBuilderCreatesEmptyImmutableComponent() {
        val mounted = MountedRoutines.builder().build()
        val functions = mounted.resolveFunctions(Identifier.regular("missing"))
        val aggregations = mounted.resolveAggregations(Identifier.regular("missing"))

        assertTrue(functions.isEmpty())
        assertTrue(aggregations.isEmpty())
        assertTrue(mounted.getRoutineInventory().functions.isEmpty())
        assertTrue(mounted.getRoutineInventory().aggregations.isEmpty())
        assertThrows<UnsupportedOperationException> {
            (functions as MutableCollection<RoutineBinding<FnOverload>>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (aggregations as MutableCollection<RoutineBinding<AggOverload>>).clear()
        }
    }

    @Test
    fun mountsExactScalarAndAggregateAtRootAndNestedNamespaces() {
        val pow = functionDefinition(
            "library.math.pow",
            function("pow", PType.integer(), PType.integer()),
            function("pow", PType.bigint(), PType.bigint()),
        )
        val total = aggregationDefinition(
            "library.stats.total",
            aggregation("total", PType.integer()),
        )
        val hidden = functionDefinition(
            "library.text.hidden",
            function("hidden", PType.string()),
        )
        val provider = loaded(functions = listOf(pow, hidden), aggregations = listOf(total))

        val mounted = MountedRoutines.builder()
            .mountRoutine(provider, pow.sourceName, Namespace.of("math"))
            .mountRoutine(provider, total.sourceName, Namespace.empty())
            .build()

        val powBinding = mounted.resolveFunctions(Identifier.regular("math", "pow")).single()
        val totalBinding = mounted.resolveAggregations(Identifier.regular("total")).single()

        assertEquals(Name.of("math", "pow"), powBinding.canonicalName)
        assertEquals(2, powBinding.overloads.size)
        assertEquals(Name.of("total"), totalBinding.canonicalName)
        assertTrue(mounted.resolveFunctions(Identifier.regular("hidden")).isEmpty())
        assertTrue(mounted.resolveAggregations(Identifier.regular("math", "pow")).isEmpty())
        assertEquals(
            listOf(Name.of("math", "pow")),
            mounted.getRoutineInventory().functions.map { it.canonicalName },
        )
        assertEquals(
            listOf(Name.of("total")),
            mounted.getRoutineInventory().aggregations.map { it.canonicalName },
        )
    }

    @Test
    fun targetNamespaceNeverActsAsAMountLevelCatalog() {
        val routine = functionDefinition(
            "library.math.pow",
            function("pow", PType.integer(), PType.integer()),
        )

        val mounted = MountedRoutines.builder()
            .mountRoutine(
                loaded(functions = listOf(routine)),
                routine.sourceName,
                Namespace.of("example"),
            )
            .build()

        assertEquals(
            Name.of("example", "pow"),
            mounted.getRoutineInventory().functions.single().canonicalName,
        )
    }

    @Test
    fun oneMountIncludesScalarAndAggregateDefinitionsAtTheSameSource() {
        val source = "library.stats.score"
        val provider = loaded(
            functions = listOf(functionDefinition(source, function("score", PType.integer()))),
            aggregations = listOf(aggregationDefinition(source, aggregation("score", PType.integer()))),
        )

        val mounted = MountedRoutines.builder()
            .mountRoutine(provider, Name.of(source.split(".")), Namespace.of("analytics"))
            .build()

        assertEquals(
            Name.of("analytics", "score"),
            mounted.resolveFunctions(Identifier.regular("analytics", "score")).single().canonicalName,
        )
        assertEquals(
            Name.of("analytics", "score"),
            mounted.resolveAggregations(Identifier.regular("analytics", "score")).single().canonicalName,
        )
    }

    @Test
    fun lookupHonorsQuotingCaseAndCompleteIdentifierDepth() {
        val routine = functionDefinition(
            "Library.Text.Tokenize",
            function("Tokenize", PType.string()),
        )
        val mounted = MountedRoutines.builder()
            .mountRoutine(
                loaded(functions = listOf(routine)),
                routine.sourceName,
                Namespace.of("Text"),
            )
            .build()

        val regular = mounted.resolveFunctions(Identifier.regular("text", "tokenize")).single()
        val exact = mounted.resolveFunctions(Identifier.delimited("Text", "Tokenize")).single()
        val mixed = mounted.resolveFunctions(
            Identifier.of(
                Identifier.Simple.regular("text"),
                Identifier.Simple.delimited("Tokenize"),
            ),
        ).single()

        assertEquals(Name.of("Text", "Tokenize"), regular.canonicalName)
        assertSame(regular, exact)
        assertSame(regular, mixed)
        assertTrue(mounted.resolveFunctions(Identifier.delimited("text", "Tokenize")).isEmpty())
        assertTrue(mounted.resolveFunctions(Identifier.delimited("Text", "tokenize")).isEmpty())
        assertTrue(mounted.resolveFunctions(Identifier.regular("Tokenize")).isEmpty())
        assertTrue(mounted.resolveFunctions(Identifier.regular("prefix", "Text", "Tokenize")).isEmpty())
    }

    @Test
    fun distinctSourcesComposeOverloadsAtOneExactTargetDeterministically() {
        val integer = functionDefinition(
            "library.numeric.contains",
            function("contains", PType.integer()),
        )
        val string = functionDefinition(
            "library.text.contains",
            function("contains", PType.string()),
        )
        val provider = loaded(functions = listOf(integer, string))

        val firstOrder = MountedRoutines.builder()
            .mountRoutine(provider, string.sourceName, Namespace.of("collection"))
            .mountRoutine(provider, integer.sourceName, Namespace.of("collection"))
            .build()
        val secondOrder = MountedRoutines.builder()
            .mountRoutine(provider, integer.sourceName, Namespace.of("collection"))
            .mountRoutine(provider, string.sourceName, Namespace.of("collection"))
            .build()

        val firstSignatures = firstOrder.getRoutineInventory()
            .functions
            .single()
            .overloads
            .map { it.signature.parameterTypes }
        val secondSignatures = secondOrder.getRoutineInventory()
            .functions
            .single()
            .overloads
            .map { it.signature.parameterTypes }

        assertEquals(Name.of("collection", "contains"), firstOrder.getRoutineInventory().functions.single().canonicalName)
        assertEquals(firstSignatures, secondSignatures)
        assertEquals(
            setOf(listOf(PType.integer()), listOf(PType.string())),
            firstSignatures.toSet(),
        )
    }

    @Test
    fun remountReplacesTheOldTargetAndBuiltValuesRemainIsolated() {
        val first = functionDefinition("library.first", function("first"))
        val second = functionDefinition("library.second", function("second"))
        val provider = loaded(functions = listOf(first, second))
        val builder = MountedRoutines.builder()
            .mountRoutine(provider, first.sourceName, Namespace.of("old"))
        val oldBuild = builder.build()

        builder
            .mountRoutine(provider, first.sourceName, Namespace.of("new"))
            .mountRoutine(provider, second.sourceName, Namespace.empty())
        val newBuild = builder.build()

        assertEquals(1, oldBuild.resolveFunctions(Identifier.regular("old", "first")).size)
        assertTrue(oldBuild.resolveFunctions(Identifier.regular("new", "first")).isEmpty())
        assertTrue(oldBuild.resolveFunctions(Identifier.regular("second")).isEmpty())
        assertTrue(newBuild.resolveFunctions(Identifier.regular("old", "first")).isEmpty())
        assertEquals(1, newBuild.resolveFunctions(Identifier.regular("new", "first")).size)
        assertEquals(1, newBuild.resolveFunctions(Identifier.regular("second")).size)
    }

    @Test
    fun inventoryAndLookupResultsAreDeeplyImmutable() {
        val routine = functionDefinition(
            "library.math.pow",
            function("pow", PType.integer(), PType.integer()),
        )
        val mounted = MountedRoutines.builder()
            .mountRoutine(loaded(functions = listOf(routine)), routine.sourceName, Namespace.of("math"))
            .build()
        val inventory = mounted.getRoutineInventory()
        val firstLookup = mounted.resolveFunctions(Identifier.regular("math", "pow"))
        val secondLookup = mounted.resolveFunctions(Identifier.regular("math", "pow"))

        assertNotSame(firstLookup, secondLookup)
        assertSame(firstLookup.single(), secondLookup.single())
        assertThrows<UnsupportedOperationException> {
            (firstLookup as MutableCollection<RoutineBinding<FnOverload>>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (inventory.functions as MutableList<RoutineBinding<FnOverload>>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (inventory.functions.single().overloads as MutableList<FnOverload>).clear()
        }
    }

    @Test
    fun lookupIsStableAcrossConcurrentReaders() {
        val routine = functionDefinition(
            "library.math.pow",
            function("pow", PType.integer(), PType.integer()),
        )
        val mounted = MountedRoutines.builder()
            .mountRoutine(loaded(functions = listOf(routine)), routine.sourceName, Namespace.of("math"))
            .build()
        val executor = Executors.newFixedThreadPool(4)

        try {
            val results = (1..100).map {
                executor.submit<Boolean> {
                    mounted.resolveFunctions(Identifier.regular("MATH", "POW"))
                        .single()
                        .canonicalName == Name.of("math", "pow")
                }
            }
            assertTrue(results.all { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun firstReleaseExposesNoSubtreeMountApi() {
        assertFalse(MountedRoutines.Builder::class.java.methods.any { it.name == "mountSubtree" })
    }

    private fun loaded(
        functions: Collection<RoutineDefinition<FnOverload>> = emptyList(),
        aggregations: Collection<RoutineDefinition<AggOverload>> = emptyList(),
    ): LoadedRoutineProvider =
        LoadedRoutineProvider.load(
            object : RoutineProvider {
                override fun getFunctions(): Collection<RoutineDefinition<FnOverload>> = functions

                override fun getAggregations(): Collection<RoutineDefinition<AggOverload>> = aggregations
            },
        )

    private fun functionDefinition(
        sourceName: String,
        vararg overloads: FnOverload,
    ): RoutineDefinition<FnOverload> =
        RoutineDefinition(Name.of(sourceName.split(".")), overloads.toList())

    private fun aggregationDefinition(
        sourceName: String,
        vararg overloads: AggOverload,
    ): RoutineDefinition<AggOverload> =
        RoutineDefinition(Name.of(sourceName.split(".")), overloads.toList())

    private fun function(name: String, vararg parameters: PType): FnOverload {
        val builder = FnOverload.Builder(name)
            .returns(PType.dynamic())
            .body { Datum.missing() }
        parameters.forEach(builder::addParameter)
        return builder.build()
    }

    private fun aggregation(name: String, vararg parameters: PType): AggOverload {
        val builder = AggOverload.Builder(name).returns(PType.dynamic())
        parameters.forEach(builder::addParameter)
        return builder.build()
    }
}
