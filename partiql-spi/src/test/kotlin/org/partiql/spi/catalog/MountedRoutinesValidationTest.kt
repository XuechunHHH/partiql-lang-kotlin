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
import org.junit.jupiter.api.Assertions.assertNull
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

class MountedRoutinesValidationTest {

    @Test
    fun exactSourceSelectionIsCaseSensitive() {
        val routine = functionDefinition(
            "Library.Text.Tokenize",
            function("Tokenize", PType.string()),
        )
        val source = Name.of("library", "Text", "Tokenize")
        val error = validationError {
            mountRoutine(loaded(functions = listOf(routine)), source, Namespace.of("text"))
        }

        val issue = error.issues.single()
        assertEquals("Routine mount validation failed with 1 issue(s).", error.message)
        assertEquals(RoutineMountValidationReason.UNKNOWN_ROUTINE, issue.reason)
        assertEquals(0, issue.requestIndex)
        assertEquals(source, issue.sourceName)
        assertNotSame(source, issue.sourceName)
        assertEquals(Name.of("text", "Tokenize"), issue.targetName)
        assertNull(issue.conflictingRequestIndex)
        assertNull(issue.isAggregate)
        assertNull(issue.parameterTypes)
        assertEquals(
            "Mount request 0 references unknown routine \"library\".\"Text\".\"Tokenize\".",
            issue.message,
        )
    }

    @Test
    fun reportsInvalidFinalSourceAndTargetWithoutCascadingUnknownRoutine() {
        val source = Name.of("library", "")
        val error = validationError {
            mountRoutine(
                loaded(),
                source,
                Namespace.of("target", ""),
            )
        }

        assertEquals(
            listOf(
                RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT,
                RoutineMountValidationReason.EMPTY_TARGET_SEGMENT,
            ),
            error.issues.map { it.reason },
        )
        assertEquals(listOf(0, 0), error.issues.map { it.requestIndex })
        assertEquals(source, error.issues[0].sourceName)
        assertEquals(Name.of("target", "", ""), error.issues[0].targetName)
        assertTrue(error.issues.none { it.reason == RoutineMountValidationReason.UNKNOWN_ROUTINE })
    }

    @Test
    fun overriddenInvalidTargetDoesNotParticipateInFinalValidation() {
        val routine = functionDefinition("library.text.tokenize", function("tokenize"))
        val provider = loaded(functions = listOf(routine))

        val mounted = MountedRoutines.builder()
            .mountRoutine(provider, routine.sourceName, Namespace.of("invalid", ""))
            .mountRoutine(provider, routine.sourceName, Namespace.of("text"))
            .build()

        assertTrue(mounted.resolveFunctions(Identifier.regular("invalid", "tokenize")).isEmpty())
        assertEquals(1, mounted.resolveFunctions(Identifier.regular("text", "tokenize")).size)
    }

    @Test
    fun laterRemountCanResolveAnIntermediateSignatureCollision() {
        val numeric = functionDefinition(
            "library.numeric.contains",
            function("contains", PType.integer()),
        )
        val collection = functionDefinition(
            "library.collection.contains",
            function("contains", PType.integer()),
        )
        val provider = loaded(functions = listOf(numeric, collection))

        val mounted = MountedRoutines.builder()
            .mountRoutine(provider, numeric.sourceName, Namespace.empty())
            .mountRoutine(provider, collection.sourceName, Namespace.empty())
            .mountRoutine(provider, numeric.sourceName, Namespace.of("numeric"))
            .build()

        assertEquals(1, mounted.resolveFunctions(Identifier.regular("contains")).size)
        assertEquals(1, mounted.resolveFunctions(Identifier.regular("numeric", "contains")).size)
    }

    @Test
    fun rejectsRegularEquivalentTargetsWithDifferentCanonicalCase() {
        val upper = functionDefinition(
            "library.upper.Tokenize",
            function("Tokenize", PType.string()),
        )
        val lower = functionDefinition(
            "library.lower.tokenize",
            function("tokenize", PType.integer()),
        )
        val provider = loaded(functions = listOf(upper, lower))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, upper.sourceName, Namespace.of("Text"))
                .mountRoutine(provider, lower.sourceName, Namespace.of("text"))
                .build()
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.TARGET_NAME_COLLISION, issue.reason)
        assertEquals(1, issue.requestIndex)
        assertEquals(0, issue.conflictingRequestIndex)
        assertEquals(lower.sourceName, issue.sourceName)
        assertEquals(upper.sourceName, issue.conflictingSourceName)
        assertEquals(Name.of("text", "tokenize"), issue.targetName)
        assertEquals(Name.of("Text", "Tokenize"), issue.conflictingTargetName)
        assertNull(issue.isAggregate)
        assertNull(issue.parameterTypes)
    }

    @Test
    fun rejectsDuplicateScalarSignatureIncludingReturnOnlyDifference() {
        val first = functionDefinition(
            "library.first.contains",
            function("contains", PType.integer(), returns = PType.string()),
        )
        val second = functionDefinition(
            "library.second.contains",
            function("contains", PType.integer(), returns = PType.integer()),
        )
        val provider = loaded(functions = listOf(first, second))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, first.sourceName, Namespace.of("collection"))
                .mountRoutine(provider, second.sourceName, Namespace.of("collection"))
                .build()
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.DUPLICATE_TARGET_SIGNATURE, issue.reason)
        assertEquals(1, issue.requestIndex)
        assertEquals(0, issue.conflictingRequestIndex)
        assertEquals(second.sourceName, issue.sourceName)
        assertEquals(first.sourceName, issue.conflictingSourceName)
        assertEquals(Name.of("collection", "contains"), issue.targetName)
        assertEquals(Name.of("collection", "contains"), issue.conflictingTargetName)
        assertFalse(issue.isAggregate!!)
        assertEquals(listOf(PType.integer()), issue.parameterTypes)
        assertThrows<UnsupportedOperationException> {
            (issue.parameterTypes as MutableList<PType>).add(PType.string())
        }
    }

    @Test
    fun duplicateAggregateSignatureIdentifiesAggregateKind() {
        val first = aggregationDefinition(
            "library.first.total",
            aggregation("total", PType.integer()),
        )
        val second = aggregationDefinition(
            "library.second.total",
            aggregation("total", PType.integer()),
        )
        val provider = loaded(aggregations = listOf(first, second))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, first.sourceName, Namespace.of("stats"))
                .mountRoutine(provider, second.sourceName, Namespace.of("stats"))
                .build()
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.DUPLICATE_TARGET_SIGNATURE, issue.reason)
        assertTrue(issue.isAggregate!!)
        assertEquals(listOf(PType.integer()), issue.parameterTypes)
    }

    @Test
    fun independentlyLoadedSnapshotsDoNotReplaceEachOther() {
        val definition = functionDefinition(
            "library.collection.contains",
            function("contains", PType.integer()),
        )
        val provider = object : RoutineProvider {
            override fun getFunctions(): Collection<RoutineDefinition<FnOverload>> = listOf(definition)

            override fun getAggregations(): Collection<RoutineDefinition<AggOverload>> = emptyList()
        }
        val firstSnapshot = LoadedRoutineProvider.load(provider)
        val secondSnapshot = LoadedRoutineProvider.load(provider)

        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(firstSnapshot, definition.sourceName, Namespace.empty())
                .mountRoutine(secondSnapshot, definition.sourceName, Namespace.empty())
                .build()
        }

        assertEquals(
            RoutineMountValidationReason.DUPLICATE_TARGET_SIGNATURE,
            error.issues.single().reason,
        )
    }

    @Test
    fun reportsAllFinalIssuesInDeterministicRequestOrder() {
        val upper = functionDefinition(
            "library.upper.Value",
            function("Value", PType.integer()),
        )
        val lower = functionDefinition(
            "library.lower.value",
            function("value", PType.string()),
        )
        val first = functionDefinition(
            "library.first.contains",
            function("contains", PType.integer()),
        )
        val second = functionDefinition(
            "library.second.contains",
            function("contains", PType.integer()),
        )
        val provider = loaded(functions = listOf(upper, lower, first, second))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, Name.of("library", "missing"), Namespace.empty())
                .mountRoutine(provider, upper.sourceName, Namespace.empty())
                .mountRoutine(provider, lower.sourceName, Namespace.empty())
                .mountRoutine(provider, first.sourceName, Namespace.of("collection"))
                .mountRoutine(provider, second.sourceName, Namespace.of("collection"))
                .build()
        }

        assertEquals(
            listOf(
                RoutineMountValidationReason.UNKNOWN_ROUTINE,
                RoutineMountValidationReason.TARGET_NAME_COLLISION,
                RoutineMountValidationReason.DUPLICATE_TARGET_SIGNATURE,
            ),
            error.issues.map { it.reason },
        )
        assertEquals(listOf(0, 2, 4), error.issues.map { it.requestIndex })
        assertThrows<UnsupportedOperationException> {
            (error.issues as MutableList<RoutineMountValidationIssue>).clear()
        }
    }

    private fun validationError(
        configure: MountedRoutines.Builder.() -> Unit,
    ): RoutineMountValidationException =
        assertThrows {
            MountedRoutines.builder().apply(configure).build()
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

    private fun function(
        name: String,
        vararg parameters: PType,
        returns: PType = PType.dynamic(),
    ): FnOverload {
        val builder = FnOverload.Builder(name)
            .returns(returns)
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
