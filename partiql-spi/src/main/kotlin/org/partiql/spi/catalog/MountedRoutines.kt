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
import org.partiql.spi.function.RoutineDefinition
import org.partiql.spi.function.RoutineOverloadSignature
import org.partiql.spi.types.PType
import java.util.Collections

/**
 * An immutable, catalog-local lookup component containing host-selected provider routines.
 *
 * This value is not a [Catalog]: it has no catalog name, table inventory, session ownership, or path policy. A host
 * implementing [RoutineCatalog] delegates its namespace-aware routine methods and inventory to this component.
 */
public class MountedRoutines private constructor(
    private val inventory: RoutineInventory,
) {

    /**
     * Resolves scalar bindings matching the complete catalog-local [identifier].
     */
    public fun resolveFunctions(identifier: Identifier): Collection<RoutineBinding<FnOverload>> =
        resolve(identifier, inventory.functions)

    /**
     * Resolves aggregate bindings matching the complete catalog-local [identifier].
     */
    public fun resolveAggregations(identifier: Identifier): Collection<RoutineBinding<AggOverload>> =
        resolve(identifier, inventory.aggregations)

    /**
     * Returns the complete immutable catalog-local routine inventory.
     */
    public fun getRoutineInventory(): RoutineInventory = inventory

    public companion object {

        /**
         * Creates a builder for one independently validated catalog-local routine component.
         */
        @JvmStatic
        public fun builder(): Builder = Builder()

        private fun materialize(requests: List<MountRequest>): MountedRoutines {
            val issues = mutableListOf<RoutineMountValidationIssue>()
            val selections = mutableListOf<Selection>()

            finalRequests(requests).forEach { request ->
                val targetName = request.targetName()
                val hasInvalidSource = request.source.any(String::isEmpty)
                val hasInvalidTarget = request.targetNamespace.any(String::isEmpty)
                if (hasInvalidSource) {
                    issues += request.issue(
                        RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT,
                        targetName,
                    )
                }
                if (hasInvalidTarget) {
                    issues += request.issue(
                        RoutineMountValidationReason.EMPTY_TARGET_SEGMENT,
                        targetName,
                    )
                }
                if (hasInvalidSource || hasInvalidTarget) {
                    return@forEach
                }

                val function = request.provider.functions.firstOrNull { it.sourceName == request.source }
                val aggregation = request.provider.aggregations.firstOrNull { it.sourceName == request.source }
                if (function == null && aggregation == null) {
                    issues += request.issue(
                        RoutineMountValidationReason.UNKNOWN_ROUTINE,
                        targetName,
                    )
                } else {
                    selections += Selection(request, targetName, function, aggregation)
                }
            }

            issues += targetCollisionIssues(selections)
            val functions = bindings(
                selections = selections,
                isAggregate = false,
                definition = Selection::function,
                signature = FnOverload::getSignature,
                issues = issues,
            )
            val aggregations = bindings(
                selections = selections,
                isAggregate = true,
                definition = Selection::aggregation,
                signature = AggOverload::getSignature,
                issues = issues,
            )

            if (issues.isNotEmpty()) {
                throw RoutineMountValidationException.create(issues.sortedWith(ISSUE_COMPARATOR))
            }
            return MountedRoutines(RoutineInventory(functions, aggregations))
        }

        private fun finalRequests(requests: List<MountRequest>): List<MountRequest> {
            val requestBySource = mutableMapOf<SourceKey, MountRequest>()
            requests.forEach { request ->
                requestBySource[SourceKey(request.provider, request.source)] = request
            }
            return requestBySource.values.sortedBy(MountRequest::index)
        }

        private fun targetCollisionIssues(selections: List<Selection>): List<RoutineMountValidationIssue> {
            val firstByRegularTarget = mutableListOf<Selection>()
            val issues = mutableListOf<RoutineMountValidationIssue>()
            selections.forEach { selection ->
                val conflicting = firstByRegularTarget.firstOrNull {
                    regularEquivalent(it.targetName, selection.targetName)
                }
                when {
                    conflicting == null -> firstByRegularTarget += selection
                    conflicting.targetName != selection.targetName ->
                        issues += RoutineMountValidationIssue.create(
                            reason = RoutineMountValidationReason.TARGET_NAME_COLLISION,
                            requestIndex = selection.request.index,
                            conflictingRequestIndex = conflicting.request.index,
                            sourceName = selection.request.source,
                            conflictingSourceName = conflicting.request.source,
                            targetName = selection.targetName,
                            conflictingTargetName = conflicting.targetName,
                        )
                }
            }
            return issues
        }

        private fun <T> bindings(
            selections: List<Selection>,
            isAggregate: Boolean,
            definition: (Selection) -> RoutineDefinition<T>?,
            signature: (T) -> RoutineOverloadSignature,
            issues: MutableList<RoutineMountValidationIssue>,
        ): List<RoutineBinding<T>> {
            val contributionsByTarget = linkedMapOf<Name, MutableList<Contribution<T>>>()
            selections.forEach { selection ->
                definition(selection)?.overloads?.forEach { overload ->
                    contributionsByTarget
                        .getOrPut(selection.targetName) { mutableListOf() }
                        .add(Contribution(selection, overload))
                }
            }

            return contributionsByTarget.map { (targetName, contributions) ->
                val firstBySignature = mutableMapOf<List<PType>, Contribution<T>>()
                val accepted = mutableListOf<Contribution<T>>()
                contributions.forEach { contribution ->
                    val parameterTypes = signature(contribution.overload).parameterTypes.toList()
                    val conflicting = firstBySignature[parameterTypes]
                    if (conflicting == null) {
                        firstBySignature[parameterTypes] = contribution
                        accepted += contribution
                    } else {
                        issues += duplicateSignatureIssue(
                            contribution,
                            conflicting,
                            targetName,
                            isAggregate,
                            parameterTypes,
                        )
                    }
                }
                val overloads = accepted
                    .sortedWith(
                        Comparator { first, second ->
                            compareParameterTypes(
                                signature(first.overload).parameterTypes,
                                signature(second.overload).parameterTypes,
                            )
                        },
                    )
                    .map(Contribution<T>::overload)
                RoutineBinding(targetName, overloads)
            }
        }

        private fun <T> duplicateSignatureIssue(
            contribution: Contribution<T>,
            conflicting: Contribution<T>,
            targetName: Name,
            isAggregate: Boolean,
            parameterTypes: List<PType>,
        ): RoutineMountValidationIssue =
            RoutineMountValidationIssue.create(
                reason = RoutineMountValidationReason.DUPLICATE_TARGET_SIGNATURE,
                requestIndex = contribution.selection.request.index,
                conflictingRequestIndex = conflicting.selection.request.index,
                sourceName = contribution.selection.request.source,
                conflictingSourceName = conflicting.selection.request.source,
                targetName = targetName,
                conflictingTargetName = conflicting.selection.targetName,
                isAggregate = isAggregate,
                parameterTypes = parameterTypes,
            )

        private fun MountRequest.issue(
            reason: RoutineMountValidationReason,
            targetName: Name,
        ): RoutineMountValidationIssue =
            RoutineMountValidationIssue.create(
                reason = reason,
                requestIndex = index,
                sourceName = source,
                targetName = targetName,
            )

        private fun MountRequest.targetName(): Name =
            Name.of(targetNamespace.toList() + source.getName())

        private fun regularEquivalent(first: Name, second: Name): Boolean {
            val firstParts = first.toList()
            val secondParts = second.toList()
            return firstParts.size == secondParts.size &&
                firstParts.indices.all { firstParts[it].equals(secondParts[it], ignoreCase = true) }
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
            return Collections.unmodifiableList(ArrayList(matches))
        }

        private fun compareNames(first: Name, second: Name): Int {
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

        private fun compareParameterTypes(first: List<PType>?, second: List<PType>?): Int {
            if (first == null || second == null) {
                return when {
                    first == null && second == null -> 0
                    first == null -> -1
                    else -> 1
                }
            }
            for (index in 0 until minOf(first.size, second.size)) {
                val codeComparison = first[index].code().compareTo(second[index].code())
                if (codeComparison != 0) {
                    return codeComparison
                }
                val representationComparison = first[index].toString().compareTo(second[index].toString())
                if (representationComparison != 0) {
                    return representationComparison
                }
            }
            return first.size.compareTo(second.size)
        }

        private val ISSUE_COMPARATOR: Comparator<RoutineMountValidationIssue> =
            Comparator { first, second ->
                var result = first.requestIndex.compareTo(second.requestIndex)
                if (result == 0) {
                    result = compareNames(first.targetName, second.targetName)
                }
                if (result == 0) {
                    result = compareParameterTypes(first.parameterTypes, second.parameterTypes)
                }
                if (result == 0) {
                    result = first.reason.ordinal.compareTo(second.reason.ordinal)
                }
                result
            }
    }

    /**
     * Collects exact mounts. Semantic failures are accumulated and reported by [build].
     */
    public class Builder internal constructor() {
        private val requests: MutableList<MountRequest> = mutableListOf()

        /**
         * Mounts one exact provider source while preserving its routine leaf.
         *
         * A later request for the same [provider] snapshot and exact [source] replaces the earlier request.
         */
        public fun mountRoutine(
            provider: LoadedRoutineProvider,
            source: Name,
            targetNamespace: Namespace,
        ): Builder {
            requests += MountRequest(
                index = requests.size,
                provider = provider,
                source = Name.of(source.toList()),
                targetNamespace = Namespace.of(targetNamespace.toList()),
            )
            return this
        }

        /**
         * Applies same-source replacement, validates the final state, and creates an immutable lookup component.
         *
         * @throws RoutineMountValidationException if the final mount configuration is invalid
         */
        public fun build(): MountedRoutines = materialize(requests.toList())
    }

    private class MountRequest(
        val index: Int,
        val provider: LoadedRoutineProvider,
        val source: Name,
        val targetNamespace: Namespace,
    )

    private class SourceKey(
        private val provider: LoadedRoutineProvider,
        private val source: Name,
    ) {
        override fun equals(other: Any?): Boolean =
            other is SourceKey && provider === other.provider && source == other.source

        override fun hashCode(): Int = 31 * java.lang.System.identityHashCode(provider) + source.hashCode()
    }

    private class Selection(
        val request: MountRequest,
        val targetName: Name,
        val function: RoutineDefinition<FnOverload>?,
        val aggregation: RoutineDefinition<AggOverload>?,
    )

    private class Contribution<T>(
        val selection: Selection,
        val overload: T,
    )
}
