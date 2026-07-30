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

import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.function.LoadedRoutineProvider
import org.partiql.spi.function.ProvidedRoutine
import org.partiql.spi.function.RoutineId
import java.util.Collections

/**
 * An immutable, catalog-local lookup component containing host-selected provider routines.
 *
 * This value is not a [Catalog]: it has no catalog name, table inventory, session ownership, or path policy. A host that
 * implements [RoutineCatalog] delegates both namespace-aware routine methods to one of these components. If the host combines
 * additional routine sources, it is responsible for validating collisions before catalog use. Because [RoutineCatalog] is
 * authoritative, omitted routines do not fall back to legacy bare-name lookup.
 */
public class MountedRoutines private constructor(
    functions: Collection<RoutineBinding<FnOverload>>,
    aggregations: Collection<RoutineBinding<AggOverload>>,
) {
    private val functions: List<RoutineBinding<FnOverload>> = immutableCopy(functions)
    private val aggregations: List<RoutineBinding<AggOverload>> = immutableCopy(aggregations)

    /**
     * Resolves scalar bindings matching the complete catalog-local [identifier].
     */
    public fun resolveFunctions(identifier: Identifier): Collection<RoutineBinding<FnOverload>> =
        resolve(identifier, functions)

    /**
     * Resolves aggregate bindings matching the complete catalog-local [identifier].
     */
    public fun resolveAggregations(identifier: Identifier): Collection<RoutineBinding<AggOverload>> =
        resolve(identifier, aggregations)

    public companion object {

        /**
         * Creates a builder for one independently validated catalog-local routine component.
         */
        @JvmStatic
        public fun builder(): Builder = Builder()

        private fun materialize(requests: List<MountRequest>): MountedRoutines {
            val issues = mutableListOf<RoutineMountValidationIssue>()
            val firstSelectionByRoutineId = mutableMapOf<RoutineId, Candidate<*>>()
            val accepted = mutableListOf<Candidate<*>>()

            requests.forEach requestLoop@{ request ->
                val structureIssues = request.structureIssues()
                if (structureIssues.isNotEmpty()) {
                    issues += structureIssues
                    return@requestLoop
                }

                val candidates = request.candidates()
                if (candidates.isEmpty()) {
                    issues += request.unknownIssue()
                    return@requestLoop
                }

                candidates.sortedWith(CANDIDATE_COMPARATOR).forEach candidateLoop@{ candidate ->
                    val firstSelection = firstSelectionByRoutineId[candidate.routine.id]
                    if (firstSelection != null) {
                        issues += candidate.duplicateIssue(firstSelection)
                        return@candidateLoop
                    }

                    firstSelectionByRoutineId[candidate.routine.id] = candidate
                    val conflictingTarget = accepted.firstOrNull {
                        regularEquivalent(it.targetName, candidate.targetName)
                    }
                    if (conflictingTarget != null) {
                        issues += candidate.collisionIssue(conflictingTarget)
                    } else {
                        accepted += candidate
                    }
                }
            }

            if (issues.isNotEmpty()) {
                throw RoutineMountValidationException(issues)
            }

            val functions = accepted.filterIsInstance<FunctionCandidate>().map { candidate ->
                RoutineBinding(
                    candidate.routine.id,
                    candidate.targetName,
                    candidate.routine.overloads,
                )
            }
            val aggregations = accepted.filterIsInstance<AggregationCandidate>().map { candidate ->
                RoutineBinding(
                    candidate.routine.id,
                    candidate.targetName,
                    candidate.routine.overloads,
                )
            }
            return MountedRoutines(functions, aggregations)
        }

        private fun MountRequest.structureIssues(): List<RoutineMountValidationIssue> {
            val issues = mutableListOf<RoutineMountValidationIssue>()
            when (this) {
                is ExactRequest -> {
                    if (source.any(String::isEmpty)) {
                        issues += requestIssue(RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT)
                    }
                }
                is SubtreeRequest -> {
                    if (source.isEmpty()) {
                        issues += requestIssue(RoutineMountValidationReason.EMPTY_SOURCE_SUBTREE)
                    } else if (source.any(String::isEmpty)) {
                        issues += requestIssue(RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT)
                    }
                }
            }
            if (targetNamespace.any(String::isEmpty)) {
                issues += requestIssue(RoutineMountValidationReason.EMPTY_TARGET_SEGMENT)
            }
            return issues
        }

        private fun MountRequest.candidates(): List<Candidate<*>> =
            when (this) {
                is ExactRequest -> {
                    val functions = provider.functions
                        .filter { it.sourceName == source }
                        .map { FunctionCandidate(this, it, target(source.getName())) }
                    val aggregations = provider.aggregations
                        .filter { it.sourceName == source }
                        .map { AggregationCandidate(this, it, target(source.getName())) }
                    functions + aggregations
                }
                is SubtreeRequest -> {
                    val functions = provider.functions.mapNotNull { routine ->
                        targetFor(routine)?.let { FunctionCandidate(this, routine, it) }
                    }
                    val aggregations = provider.aggregations.mapNotNull { routine ->
                        targetFor(routine)?.let { AggregationCandidate(this, routine, it) }
                    }
                    functions + aggregations
                }
            }

        private fun MountRequest.unknownIssue(): RoutineMountValidationIssue =
            when (this) {
                is ExactRequest -> requestIssue(RoutineMountValidationReason.UNKNOWN_ROUTINE)
                is SubtreeRequest -> requestIssue(RoutineMountValidationReason.UNKNOWN_SUBTREE)
            }

        private fun MountRequest.requestIssue(reason: RoutineMountValidationReason): RoutineMountValidationIssue =
            RoutineMountValidationIssue(
                reason = reason,
                requestIndex = index,
                sourceName = (this as? ExactRequest)?.source,
                sourceNamespace = (this as? SubtreeRequest)?.source,
                targetNamespace = targetNamespace,
            )

        private fun Candidate<*>.duplicateIssue(conflicting: Candidate<*>): RoutineMountValidationIssue =
            conflictIssue(RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT, conflicting)

        private fun Candidate<*>.collisionIssue(conflicting: Candidate<*>): RoutineMountValidationIssue =
            conflictIssue(RoutineMountValidationReason.TARGET_NAME_COLLISION, conflicting)

        private fun Candidate<*>.conflictIssue(
            reason: RoutineMountValidationReason,
            conflicting: Candidate<*>,
        ): RoutineMountValidationIssue =
            RoutineMountValidationIssue(
                reason = reason,
                requestIndex = request.index,
                conflictingRequestIndex = conflicting.request.index,
                routineId = routine.id,
                conflictingRoutineId = when (reason) {
                    RoutineMountValidationReason.TARGET_NAME_COLLISION -> conflicting.routine.id
                    else -> null
                },
                sourceName = (request as? ExactRequest)?.source,
                sourceNamespace = (request as? SubtreeRequest)?.source,
                targetNamespace = request.targetNamespace,
                selectedSourceName = routine.sourceName,
                targetName = targetName,
                conflictingSelectedSourceName = conflicting.routine.sourceName,
                conflictingTargetName = conflicting.targetName,
            )

        private fun <T> SubtreeRequest.targetFor(routine: ProvidedRoutine<T>): Name? {
            val sourceParts = source.toList()
            val routineParts = routine.sourceName.toList()
            if (routineParts.size <= sourceParts.size || routineParts.take(sourceParts.size) != sourceParts) {
                return null
            }
            return Name.of(targetNamespace.toList() + routineParts.drop(sourceParts.size))
        }

        private fun MountRequest.target(vararg suffix: String): Name =
            Name.of(targetNamespace.toList() + suffix)

        private fun regularEquivalent(first: Name, second: Name): Boolean {
            val firstParts = first.toList()
            val secondParts = second.toList()
            return firstParts.size == secondParts.size &&
                firstParts.indices.all { firstParts[it].equals(secondParts[it], ignoreCase = true) }
        }

        private fun compareSourceNames(first: Name, second: Name): Int {
            val firstParts = first.toList()
            val secondParts = second.toList()
            for (index in 0 until minOf(firstParts.size, secondParts.size)) {
                val comparison = firstParts[index].compareTo(secondParts[index])
                if (comparison != 0) {
                    return comparison
                }
            }
            return firstParts.size.compareTo(secondParts.size)
        }

        private fun <T> resolve(
            identifier: Identifier,
            bindings: List<RoutineBinding<T>>,
        ): Collection<RoutineBinding<T>> {
            val identifierParts = identifier.getParts()
            val matches = bindings.filter { binding ->
                val nameParts = binding.canonicalName.toList()
                identifierParts.size == nameParts.size &&
                    identifierParts.indices.all { identifierParts[it].matches(nameParts[it]) }
            }
            return immutableCopy(matches)
        }

        private fun <T> immutableCopy(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))

        private val CANDIDATE_COMPARATOR: Comparator<Candidate<*>> =
            Comparator { first, second -> compareSourceNames(first.routine.sourceName, second.routine.sourceName) }
    }

    /**
     * Collects exact and subtree mounts. Semantic failures are accumulated and reported by [build].
     */
    public class Builder internal constructor() {
        private val requests: MutableList<MountRequest> = mutableListOf()

        /**
         * Mounts one exact provider routine while preserving its source-name leaf.
         */
        public fun mountRoutine(
            provider: LoadedRoutineProvider,
            source: Name,
            targetNamespace: Namespace,
        ): Builder {
            requests += ExactRequest(
                requests.size,
                provider,
                Name.of(source.toList()),
                Namespace.of(targetNamespace.toList()),
            )
            return this
        }

        /**
         * Mounts every strict-descendant routine under [source], preserving each relative suffix.
         */
        public fun mountSubtree(
            provider: LoadedRoutineProvider,
            source: Namespace,
            targetNamespace: Namespace,
        ): Builder {
            requests += SubtreeRequest(
                requests.size,
                provider,
                Namespace.of(source.toList()),
                Namespace.of(targetNamespace.toList()),
            )
            return this
        }

        /**
         * Expands and validates every recorded request.
         *
         * @throws RoutineMountValidationException if any request is invalid
         */
        public fun build(): MountedRoutines = materialize(requests.toList())
    }

    private sealed class MountRequest(
        val index: Int,
        val provider: LoadedRoutineProvider,
        val targetNamespace: Namespace,
    )

    private class ExactRequest(
        index: Int,
        provider: LoadedRoutineProvider,
        val source: Name,
        targetNamespace: Namespace,
    ) : MountRequest(index, provider, targetNamespace)

    private class SubtreeRequest(
        index: Int,
        provider: LoadedRoutineProvider,
        val source: Namespace,
        targetNamespace: Namespace,
    ) : MountRequest(index, provider, targetNamespace)

    private sealed class Candidate<T>(
        val request: MountRequest,
        val routine: ProvidedRoutine<T>,
        val targetName: Name,
    )

    private class FunctionCandidate(
        request: MountRequest,
        routine: ProvidedRoutine<FnOverload>,
        targetName: Name,
    ) : Candidate<FnOverload>(request, routine, targetName)

    private class AggregationCandidate(
        request: MountRequest,
        routine: ProvidedRoutine<AggOverload>,
        targetName: Name,
    ) : Candidate<AggOverload>(request, routine, targetName)
}
