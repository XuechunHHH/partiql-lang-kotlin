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
import org.junit.jupiter.api.Assertions.assertNull
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

class MountedRoutinesValidationTest {

    @Test
    fun rejectsEmptySourceSubtree() {
        val error = validationError {
            mountSubtree(loaded(), Namespace.empty(), Namespace.empty())
        }

        val issue = error.issues.single()
        assertEquals("Routine mount validation failed with 1 issue(s).", error.message)
        assertEquals(RoutineMountValidationReason.EMPTY_SOURCE_SUBTREE, issue.reason)
        assertEquals(0, issue.requestIndex)
        assertEquals(Namespace.empty(), issue.sourceNamespace)
        assertEquals(Namespace.empty(), issue.targetNamespace)
        assertNull(issue.sourceName)
        assertNull(issue.routineId)
        assertEquals("Mount request 0 has an empty source subtree.", issue.message)
    }

    @Test
    fun collectsEmptySourceAndTargetSegmentsWithoutExpansion() {
        val provider = loaded()
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(
                    provider,
                    Name.of("inventory", ""),
                    Namespace.of("target", ""),
                )
                .mountSubtree(
                    provider,
                    Namespace.of("inventory", ""),
                    Namespace.empty(),
                )
                .mountRoutine(
                    provider,
                    Name.of("inventory", "missing"),
                    Namespace.of("target", ""),
                )
                .build()
        }

        assertEquals(
            listOf(
                RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT,
                RoutineMountValidationReason.EMPTY_TARGET_SEGMENT,
                RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT,
                RoutineMountValidationReason.EMPTY_TARGET_SEGMENT,
            ),
            error.issues.map { it.reason },
        )
        assertEquals(listOf(0, 0, 1, 2), error.issues.map { it.requestIndex })
        assertEquals(
            "Mount request 0 source contains an empty segment.",
            error.issues[0].message,
        )
        assertEquals(
            "Mount request 0 target namespace contains an empty segment.",
            error.issues[1].message,
        )
        assertEquals(Name.of("inventory", ""), error.issues[0].sourceName)
        assertEquals(Namespace.of("inventory", ""), error.issues[2].sourceNamespace)
    }

    @Test
    fun exactSourceSelectionIsCaseSensitive() {
        val routine = scalarRoutine(
            "provider.tokenize",
            Name.of("Inventory", "Tokenize"),
        )
        val error = validationError {
            mountRoutine(
                loaded(functions = listOf(routine)),
                Name.of("inventory", "Tokenize"),
                Namespace.empty(),
            )
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.UNKNOWN_ROUTINE, issue.reason)
        assertEquals(Name.of("inventory", "Tokenize"), issue.sourceName)
        assertEquals(
            "Mount request 0 references unknown routine \"inventory\".\"Tokenize\".",
            issue.message,
        )
    }

    @Test
    fun subtreeSourceSelectionIsCaseSensitive() {
        val routine = scalarRoutine(
            "provider.tokenize",
            Name.of("Inventory", "privacy", "tokenize"),
        )
        val error = validationError {
            mountSubtree(
                loaded(functions = listOf(routine)),
                Namespace.of("inventory", "privacy"),
                Namespace.empty(),
            )
        }

        assertEquals(
            RoutineMountValidationReason.UNKNOWN_SUBTREE,
            error.issues.single().reason,
        )
    }

    @Test
    fun subtreeWithoutStrictDescendantsIsUnknown() {
        val routine = scalarRoutine(
            "provider.privacy",
            Name.of("inventory", "privacy"),
        )
        val error = validationError {
            mountSubtree(
                loaded(functions = listOf(routine)),
                Namespace.of("inventory", "privacy"),
                Namespace.empty(),
            )
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.UNKNOWN_SUBTREE, issue.reason)
        assertEquals(Namespace.of("inventory", "privacy"), issue.sourceNamespace)
        assertEquals(
            "Mount request 0 references unknown or empty subtree \"inventory\".\"privacy\".",
            issue.message,
        )
    }

    @Test
    fun rejectsDuplicateExactMountsRegardlessOfTarget() {
        val routine = scalarRoutine(
            "provider.tokenize",
            Name.of("inventory", "tokenize"),
        )
        val provider = loaded(functions = listOf(routine))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, routine.sourceName, Namespace.empty())
                .mountRoutine(provider, routine.sourceName, Namespace.of("other"))
                .build()
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT, issue.reason)
        assertEquals(1, issue.requestIndex)
        assertEquals(0, issue.conflictingRequestIndex)
        assertEquals(RoutineId("provider.tokenize"), issue.routineId)
        assertNull(issue.conflictingRoutineId)
        assertEquals(routine.sourceName, issue.selectedSourceName)
        assertNotSame(routine.sourceName, issue.selectedSourceName)
        assertEquals(Name.of("other", "tokenize"), issue.targetName)
        assertEquals(routine.sourceName, issue.conflictingSelectedSourceName)
        assertEquals(Name.of("tokenize"), issue.conflictingTargetName)
        assertEquals(
            "Mount request 1 candidate \"inventory\".\"tokenize\" selects routine provider.tokenize, first selected by " +
                "request 0 candidate \"inventory\".\"tokenize\".",
            issue.message,
        )
    }

    @Test
    fun exactAndSubtreeOverlapIsDuplicateMount() {
        val routine = scalarRoutine(
            "provider.tokenize",
            Name.of("inventory", "privacy", "tokenize"),
        )
        val provider = loaded(functions = listOf(routine))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, routine.sourceName, Namespace.empty())
                .mountSubtree(
                    provider,
                    Namespace.of("inventory", "privacy"),
                    Namespace.of("security"),
                )
                .build()
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT, issue.reason)
        assertEquals(Name.of("security", "tokenize"), issue.targetName)
        assertEquals(Name.of("tokenize"), issue.conflictingTargetName)
    }

    @Test
    fun overlappingSubtreesAreDuplicateMounts() {
        val routine = scalarRoutine(
            "provider.tokenize",
            Name.of("inventory", "privacy", "tokenize"),
        )
        val provider = loaded(functions = listOf(routine))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountSubtree(
                    provider,
                    Namespace.of("inventory", "privacy"),
                    Namespace.empty(),
                )
                .mountSubtree(provider, Namespace.of("inventory"), Namespace.empty())
                .build()
        }

        assertEquals(
            RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT,
            error.issues.single().reason,
        )
    }

    @Test
    fun scalarAndAggregateTargetCollisionIsRejected() {
        val scalar = scalarRoutine(
            "provider.scalar",
            Name.of("inventory", "scalar", "shared"),
        )
        val aggregate = aggregateRoutine(
            "provider.aggregate",
            Name.of("inventory", "aggregate", "shared"),
        )
        val provider = loaded(functions = listOf(scalar), aggregations = listOf(aggregate))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, scalar.sourceName, Namespace.empty())
                .mountRoutine(provider, aggregate.sourceName, Namespace.empty())
                .build()
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.TARGET_NAME_COLLISION, issue.reason)
        assertEquals(RoutineId("provider.aggregate"), issue.routineId)
        assertEquals(RoutineId("provider.scalar"), issue.conflictingRoutineId)
        assertEquals(aggregate.sourceName, issue.selectedSourceName)
        assertEquals(scalar.sourceName, issue.conflictingSelectedSourceName)
        assertEquals(Name.of("shared"), issue.targetName)
        assertEquals(Name.of("shared"), issue.conflictingTargetName)
        assertEquals(
            "Mount request 1 candidate \"inventory\".\"aggregate\".\"shared\" target \"shared\" collides with request 0 " +
                "candidate \"inventory\".\"scalar\".\"shared\" target \"shared\" under regular identifier matching.",
            issue.message,
        )
    }

    @Test
    fun caseEquivalentTargetsAreRejected() {
        val upper = scalarRoutine(
            "provider.upper",
            Name.of("inventory", "Tokenize"),
        )
        val lower = scalarRoutine(
            "provider.lower",
            Name.of("inventory", "tokenize"),
        )
        val provider = loaded(functions = listOf(upper, lower))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, upper.sourceName, Namespace.empty())
                .mountRoutine(provider, lower.sourceName, Namespace.empty())
                .build()
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.TARGET_NAME_COLLISION, issue.reason)
        assertEquals(Name.of("tokenize"), issue.targetName)
        assertEquals(Name.of("Tokenize"), issue.conflictingTargetName)
    }

    @Test
    fun collisionInsideOneSubtreeIdentifiesBothCandidates() {
        val upper = scalarRoutine(
            "provider.upper",
            Name.of("inventory", "Tokenize"),
        )
        val lower = scalarRoutine(
            "provider.lower",
            Name.of("inventory", "tokenize"),
        )
        val error = validationError {
            mountSubtree(
                loaded(functions = listOf(lower, upper)),
                Namespace.of("inventory"),
                Namespace.empty(),
            )
        }

        val issue = error.issues.single()
        assertEquals(RoutineMountValidationReason.TARGET_NAME_COLLISION, issue.reason)
        assertEquals(0, issue.requestIndex)
        assertEquals(0, issue.conflictingRequestIndex)
        assertEquals(lower.sourceName, issue.selectedSourceName)
        assertEquals(upper.sourceName, issue.conflictingSelectedSourceName)
        assertEquals(Name.of("tokenize"), issue.targetName)
        assertEquals(Name.of("Tokenize"), issue.conflictingTargetName)
    }

    @Test
    fun rejectedTargetStillBecomesFirstSelectionForDuplicateDetection() {
        val first = scalarRoutine(
            "provider.first",
            Name.of("inventory", "first", "shared"),
        )
        val second = scalarRoutine(
            "provider.second",
            Name.of("inventory", "second", "shared"),
        )
        val provider = loaded(functions = listOf(first, second))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, first.sourceName, Namespace.empty())
                .mountRoutine(provider, second.sourceName, Namespace.empty())
                .mountRoutine(provider, second.sourceName, Namespace.of("other"))
                .build()
        }

        assertEquals(
            listOf(
                RoutineMountValidationReason.TARGET_NAME_COLLISION,
                RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT,
            ),
            error.issues.map { it.reason },
        )
        assertEquals(1, error.issues[1].conflictingRequestIndex)
        assertEquals(second.sourceName, error.issues[1].conflictingSelectedSourceName)
        assertEquals(Name.of("shared"), error.issues[1].conflictingTargetName)
    }

    @Test
    fun sameIdFromDifferentProvidersIsDuplicateMount() {
        val first = scalarRoutine(
            "provider.shared",
            Name.of("first", "one"),
        )
        val second = scalarRoutine(
            "provider.shared",
            Name.of("second", "two"),
        )
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(
                    loaded(functions = listOf(first)),
                    first.sourceName,
                    Namespace.empty(),
                )
                .mountRoutine(
                    loaded(functions = listOf(second)),
                    second.sourceName,
                    Namespace.empty(),
                )
                .build()
        }

        assertEquals(
            RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT,
            error.issues.single().reason,
        )
    }

    @Test
    fun collectsSemanticIssuesInRequestOrder() {
        val first = scalarRoutine(
            "provider.first",
            Name.of("inventory", "first", "shared"),
        )
        val second = scalarRoutine(
            "provider.second",
            Name.of("inventory", "second", "shared"),
        )
        val provider = loaded(functions = listOf(first, second))
        val error = assertThrows<RoutineMountValidationException> {
            MountedRoutines.builder()
                .mountRoutine(provider, Name.of("inventory", "missing"), Namespace.empty())
                .mountRoutine(provider, first.sourceName, Namespace.empty())
                .mountRoutine(provider, second.sourceName, Namespace.empty())
                .mountRoutine(provider, first.sourceName, Namespace.of("again"))
                .build()
        }

        assertEquals(
            listOf(
                RoutineMountValidationReason.UNKNOWN_ROUTINE,
                RoutineMountValidationReason.TARGET_NAME_COLLISION,
                RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT,
            ),
            error.issues.map { it.reason },
        )
        assertEquals(listOf(0, 2, 3), error.issues.map { it.requestIndex })
    }

    @Test
    fun exceptionIssuesAreJavaUnmodifiable() {
        val error = validationError {
            mountSubtree(loaded(), Namespace.empty(), Namespace.empty())
        }

        assertThrows<UnsupportedOperationException> {
            (error.issues as MutableList<RoutineMountValidationIssue>).add(error.issues.single())
        }
    }

    private fun validationError(configure: MountedRoutines.Builder.() -> Unit): RoutineMountValidationException =
        assertThrows {
            MountedRoutines.builder().apply(configure).build()
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
