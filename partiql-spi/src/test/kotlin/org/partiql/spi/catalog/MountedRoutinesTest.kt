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
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.function.LoadedRoutineProvider
import org.partiql.spi.function.ProvidedRoutine
import org.partiql.spi.function.RoutineId
import org.partiql.spi.function.RoutineProvider
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MountedRoutinesTest {

    @Test
    fun emptyBuilderCreatesEmptyComponent() {
        val mounted = MountedRoutines.builder().build()
        val functions = mounted.resolveFunctions(Identifier.regular("missing"))
        val aggregations = mounted.resolveAggregations(Identifier.regular("missing"))

        assertTrue(functions.isEmpty())
        assertTrue(aggregations.isEmpty())
        assertThrows<UnsupportedOperationException> {
            (functions as MutableCollection<RoutineBinding<FnOverload>>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (aggregations as MutableCollection<RoutineBinding<AggOverload>>).clear()
        }
    }

    @Test
    fun mountsExactScalarAndAggregateAtRootAndNestedTargets() {
        val scalar = scalarRoutine("provider.scalar", Name.of("inventory", "scalar"))
        val aggregate = aggregateRoutine("provider.total", Name.of("inventory", "total"))
        val mounted = MountedRoutines.builder()
            .mountRoutine(loaded(functions = listOf(scalar)), scalar.sourceName, Namespace.empty())
            .mountRoutine(
                loaded(aggregations = listOf(aggregate)),
                aggregate.sourceName,
                Namespace.of("metrics"),
            )
            .build()

        val scalarBinding = mounted.resolveFunctions(Identifier.regular("scalar")).single()
        val aggregateBinding = mounted.resolveAggregations(Identifier.regular("metrics", "total")).single()

        assertEquals(RoutineId("provider.scalar"), scalarBinding.providerId)
        assertEquals(Name.of("scalar"), scalarBinding.canonicalName)
        assertEquals(RoutineId("provider.total"), aggregateBinding.providerId)
        assertEquals(Name.of("metrics", "total"), aggregateBinding.canonicalName)
        assertTrue(mounted.resolveAggregations(Identifier.regular("scalar")).isEmpty())
        assertTrue(mounted.resolveFunctions(Identifier.regular("metrics", "total")).isEmpty())
    }

    @Test
    fun expandsSubtreeIntoRootAndNestedTargets() {
        val tokenize = scalarRoutine(
            "provider.tokenize",
            Name.of("inventory", "privacy", "tokenize"),
        )
        val record = scalarRoutine(
            "provider.record",
            Name.of("inventory", "privacy", "audit", "record"),
        )
        val unmounted = scalarRoutine(
            "provider.score",
            Name.of("inventory", "analytics", "score"),
        )
        val provider = loaded(functions = listOf(tokenize, record, unmounted))

        val root = MountedRoutines.builder()
            .mountSubtree(provider, Namespace.of("inventory", "privacy"), Namespace.empty())
            .build()
        val nested = MountedRoutines.builder()
            .mountSubtree(
                provider,
                Namespace.of("inventory", "privacy"),
                Namespace.of("security"),
            )
            .build()

        assertEquals(
            Name.of("tokenize"),
            root.resolveFunctions(Identifier.regular("tokenize")).single().canonicalName,
        )
        assertEquals(
            Name.of("audit", "record"),
            root.resolveFunctions(Identifier.regular("audit", "record")).single().canonicalName,
        )
        assertTrue(root.resolveFunctions(Identifier.regular("score")).isEmpty())
        assertEquals(
            Name.of("security", "tokenize"),
            nested.resolveFunctions(Identifier.regular("security", "tokenize")).single().canonicalName,
        )
        assertEquals(
            Name.of("security", "audit", "record"),
            nested.resolveFunctions(Identifier.regular("security", "audit", "record")).single().canonicalName,
        )
    }

    @Test
    fun subtreeSelectsOnlyStrictDescendants() {
        val exact = scalarRoutine(
            "provider.privacy",
            Name.of("inventory", "privacy"),
        )
        val child = scalarRoutine(
            "provider.child",
            Name.of("inventory", "privacy", "child"),
        )
        val mounted = MountedRoutines.builder()
            .mountSubtree(
                loaded(functions = listOf(exact, child)),
                Namespace.of("inventory", "privacy"),
                Namespace.empty(),
            )
            .build()

        assertTrue(mounted.resolveFunctions(Identifier.regular("privacy")).isEmpty())
        assertEquals(
            RoutineId("provider.child"),
            mounted.resolveFunctions(Identifier.regular("child")).single().providerId,
        )
    }

    @Test
    fun lookupHonorsQuotingAndPreservesTargetCase() {
        val routine = scalarRoutine(
            "provider.tokenize",
            Name.of("inventory", "Tokenize"),
        )
        val mounted = MountedRoutines.builder()
            .mountRoutine(
                loaded(functions = listOf(routine)),
                routine.sourceName,
                Namespace.of("Privacy"),
            )
            .build()

        assertEquals(
            Name.of("Privacy", "Tokenize"),
            mounted.resolveFunctions(Identifier.regular("privacy", "tokenize")).single().canonicalName,
        )
        assertEquals(
            1,
            mounted.resolveFunctions(Identifier.delimited("Privacy", "Tokenize")).size,
        )
        assertEquals(
            1,
            mounted.resolveFunctions(
                Identifier.of(
                    Identifier.Simple.regular("privacy"),
                    Identifier.Simple.delimited("Tokenize"),
                ),
            ).size,
        )
        assertEquals(
            1,
            mounted.resolveFunctions(
                Identifier.of(
                    Identifier.Simple.delimited("Privacy"),
                    Identifier.Simple.regular("tokenize"),
                ),
            ).size,
        )
        assertTrue(mounted.resolveFunctions(Identifier.delimited("privacy", "Tokenize")).isEmpty())
        assertTrue(mounted.resolveFunctions(Identifier.delimited("Privacy", "tokenize")).isEmpty())
        assertTrue(mounted.resolveFunctions(Identifier.regular("tokenize")).isEmpty())
    }

    @Test
    fun sameProviderCanBeMountedIndependentlyForDifferentCatalogs() {
        val routine = scalarRoutine(
            "provider.tokenize",
            Name.of("inventory", "tokenize"),
        )
        val provider = loaded(functions = listOf(routine))
        val root = MountedRoutines.builder()
            .mountRoutine(provider, routine.sourceName, Namespace.empty())
            .build()
        val nested = MountedRoutines.builder()
            .mountRoutine(provider, routine.sourceName, Namespace.of("privacy"))
            .build()

        val rootBinding = root.resolveFunctions(Identifier.regular("tokenize")).single()
        val nestedBinding = nested.resolveFunctions(Identifier.regular("privacy", "tokenize")).single()

        assertEquals(rootBinding.providerId, nestedBinding.providerId)
        assertEquals(Name.of("tokenize"), rootBinding.canonicalName)
        assertEquals(Name.of("privacy", "tokenize"), nestedBinding.canonicalName)
    }

    @Test
    fun successfulBuildIsIsolatedFromLaterBuilderChanges() {
        val first = scalarRoutine("provider.first", Name.of("inventory", "first"))
        val second = scalarRoutine("provider.second", Name.of("inventory", "second"))
        val provider = loaded(functions = listOf(first, second))
        val builder = MountedRoutines.builder()
            .mountRoutine(provider, first.sourceName, Namespace.empty())
        val firstBuild = builder.build()

        builder.mountRoutine(provider, second.sourceName, Namespace.empty())
        val secondBuild = builder.build()

        assertEquals(1, firstBuild.resolveFunctions(Identifier.regular("first")).size)
        assertTrue(firstBuild.resolveFunctions(Identifier.regular("second")).isEmpty())
        assertEquals(1, secondBuild.resolveFunctions(Identifier.regular("first")).size)
        assertEquals(1, secondBuild.resolveFunctions(Identifier.regular("second")).size)
    }

    @Test
    fun lookupReturnsFreshUnmodifiableResultsAndBindings() {
        val routine = scalarRoutine("provider.scalar", Name.of("inventory", "scalar"))
        val mounted = MountedRoutines.builder()
            .mountRoutine(loaded(functions = listOf(routine)), routine.sourceName, Namespace.empty())
            .build()

        val first = mounted.resolveFunctions(Identifier.regular("scalar"))
        val second = mounted.resolveFunctions(Identifier.regular("scalar"))

        assertNotSame(first, second)
        assertSame(first.single(), second.single())
        assertThrows<UnsupportedOperationException> {
            (first as MutableCollection<RoutineBinding<FnOverload>>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (first.single().overloads as MutableCollection<FnOverload>).clear()
        }
    }

    @Test
    fun lookupIsStableAcrossConcurrentReaders() {
        val routine = scalarRoutine("provider.scalar", Name.of("inventory", "scalar"))
        val mounted = MountedRoutines.builder()
            .mountRoutine(loaded(functions = listOf(routine)), routine.sourceName, Namespace.empty())
            .build()
        val executor = Executors.newFixedThreadPool(4)

        try {
            val results = (1..100).map {
                executor.submit<Boolean> {
                    mounted.resolveFunctions(Identifier.regular("SCALAR")).single().providerId ==
                        RoutineId("provider.scalar")
                }
            }

            assertTrue(results.all { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun loaded(
        functions: Collection<ProvidedRoutine<FnOverload>> = emptyList(),
        aggregations: Collection<ProvidedRoutine<AggOverload>> = emptyList(),
    ): LoadedRoutineProvider =
        LoadedRoutineProvider.load(
            object : RoutineProvider {
                override fun getFunctions(): Collection<ProvidedRoutine<FnOverload>> = functions

                override fun getAggregations(): Collection<ProvidedRoutine<AggOverload>> = aggregations
            },
        )

    private fun scalarRoutine(id: String, sourceName: Name): ProvidedRoutine<FnOverload> =
        ProvidedRoutine(
            RoutineId(id),
            sourceName,
            listOf(
                FnOverload.Builder(sourceName.getName())
                    .returns(PType.dynamic())
                    .body { Datum.missing() }
                    .build(),
            ),
        )

    private fun aggregateRoutine(id: String, sourceName: Name): ProvidedRoutine<AggOverload> =
        ProvidedRoutine(
            RoutineId(id),
            sourceName,
            listOf(
                AggOverload.Builder(sourceName.getName())
                    .returns(PType.dynamic())
                    .build(),
            ),
        )
}
