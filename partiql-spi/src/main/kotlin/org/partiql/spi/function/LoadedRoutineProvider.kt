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

package org.partiql.spi.function

import org.partiql.spi.catalog.Name
import org.partiql.spi.types.PType
import java.util.Collections

/**
 * An immutable, validated snapshot of one [RoutineProvider].
 *
 * Loading alone does not expose routines to SQL. Pass this value to a routine mount configuration owned by the host.
 */
public class LoadedRoutineProvider private constructor(
    functions: Collection<ProvidedRoutine<FnOverload>>,
    aggregations: Collection<ProvidedRoutine<AggOverload>>,
) {
    internal val functions: List<ProvidedRoutine<FnOverload>> = immutableCopy(functions)
    internal val aggregations: List<ProvidedRoutine<AggOverload>> = immutableCopy(aggregations)

    public companion object {

        /**
         * Reads each provider inventory once, captures its declaration metadata, and validates the complete snapshot.
         *
         * @throws RoutineProviderValidationException if provider access or validation fails
         */
        @JvmStatic
        public fun load(provider: RoutineProvider): LoadedRoutineProvider {
            val functions = access(RoutineProviderValidationIssue.GET_FUNCTIONS) {
                provider.getFunctions().map(::snapshotFunction)
            }
            val aggregations = access(RoutineProviderValidationIssue.GET_AGGREGATIONS) {
                provider.getAggregations().map(::snapshotAggregation)
            }
            val issues = validate(functions, aggregations)
            if (issues.isNotEmpty()) {
                throw RoutineProviderValidationException(issues)
            }
            return LoadedRoutineProvider(functions, aggregations)
        }

        private fun snapshotFunction(routine: ProvidedRoutine<FnOverload>): ProvidedRoutine<FnOverload> =
            ProvidedRoutine(
                routine.id,
                routine.sourceName,
                routine.overloads.map { overload ->
                    SnapshotFnOverload(overload, snapshot(overload.signature))
                },
            )

        private fun snapshotAggregation(routine: ProvidedRoutine<AggOverload>): ProvidedRoutine<AggOverload> =
            ProvidedRoutine(
                routine.id,
                routine.sourceName,
                routine.overloads.map { overload ->
                    SnapshotAggOverload(overload, snapshot(overload.signature))
                },
            )

        private fun snapshot(signature: RoutineOverloadSignature): RoutineOverloadSignature =
            RoutineOverloadSignature(signature.name, signature.parameterTypes)

        private fun validate(
            functions: List<ProvidedRoutine<FnOverload>>,
            aggregations: List<ProvidedRoutine<AggOverload>>,
        ): List<RoutineProviderValidationIssue> {
            val declarations =
                functions.map { routine ->
                    Declaration(
                        routine.id,
                        routine.sourceName,
                        routine.overloads.map { it.signature },
                    )
                } +
                    aggregations.map { routine ->
                        Declaration(
                            routine.id,
                            routine.sourceName,
                            routine.overloads.map { it.signature },
                        )
                    }
            val issues = mutableListOf<RoutineProviderValidationIssue>()
            val firstById = mutableMapOf<RoutineId, Declaration>()
            val firstBySourceName = mutableMapOf<Name, Declaration>()

            declarations.forEach { declaration ->
                if (declaration.sourceName.any(String::isEmpty)) {
                    issues += issue(RoutineProviderValidationReason.EMPTY_SOURCE_SEGMENT, declaration)
                }
                if (declaration.signatures.isEmpty()) {
                    issues += issue(RoutineProviderValidationReason.EMPTY_OVERLOADS, declaration)
                }

                val sameId = firstById[declaration.id]
                if (sameId == null) {
                    firstById[declaration.id] = declaration
                } else {
                    issues += issue(
                        RoutineProviderValidationReason.DUPLICATE_ROUTINE_ID,
                        declaration,
                        conflicting = sameId,
                    )
                }

                val sameSourceName = firstBySourceName[declaration.sourceName]
                if (sameSourceName == null) {
                    firstBySourceName[declaration.sourceName] = declaration
                } else {
                    issues += issue(
                        RoutineProviderValidationReason.DUPLICATE_SOURCE_NAME,
                        declaration,
                        conflicting = sameSourceName,
                    )
                }

                val signatures = mutableSetOf<List<PType>>()
                declaration.signatures.forEach { signature ->
                    if (signature.name != declaration.sourceName.getName()) {
                        issues += issue(
                            RoutineProviderValidationReason.SIGNATURE_NAME_MISMATCH,
                            declaration,
                            signature = signature,
                        )
                    }
                    if (!signatures.add(signature.parameterTypes)) {
                        issues += issue(
                            RoutineProviderValidationReason.DUPLICATE_OVERLOAD_SIGNATURE,
                            declaration,
                            signature = signature,
                        )
                    }
                }
            }
            return issues
        }

        private fun issue(
            reason: RoutineProviderValidationReason,
            declaration: Declaration,
            conflicting: Declaration? = null,
            signature: RoutineOverloadSignature? = null,
        ): RoutineProviderValidationIssue =
            RoutineProviderValidationIssue(
                reason = reason,
                routineId = declaration.id,
                sourceName = declaration.sourceName,
                conflictingRoutineId = conflicting?.id,
                conflictingSourceName = conflicting?.sourceName,
                signatureName = when (reason) {
                    RoutineProviderValidationReason.SIGNATURE_NAME_MISMATCH -> signature?.name
                    else -> null
                },
                parameterTypes = when (reason) {
                    RoutineProviderValidationReason.DUPLICATE_OVERLOAD_SIGNATURE -> signature?.parameterTypes
                    else -> null
                },
            )

        private fun <T> access(callback: String, action: () -> List<T>): List<T> =
            try {
                immutableCopy(action())
            } catch (cause: Throwable) {
                val issue = RoutineProviderValidationIssue(
                    reason = RoutineProviderValidationReason.PROVIDER_ACCESS_FAILED,
                    callback = callback,
                )
                throw RoutineProviderValidationException(listOf(issue), cause)
            }

        private fun <T> immutableCopy(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }

    private class Declaration(
        val id: RoutineId,
        val sourceName: Name,
        val signatures: List<RoutineOverloadSignature>,
    )

    private class SnapshotFnOverload(
        private val delegate: FnOverload,
        private val signature: RoutineOverloadSignature,
    ) : FnOverload() {
        override fun getSignature(): RoutineOverloadSignature = signature

        override fun getInstance(args: Array<PType>): Fn? = delegate.getInstance(args)
    }

    private class SnapshotAggOverload(
        private val delegate: AggOverload,
        private val signature: RoutineOverloadSignature,
    ) : AggOverload() {
        override fun getSignature(): RoutineOverloadSignature = signature

        override fun getInstance(args: Array<PType>): Agg? = delegate.getInstance(args)
    }
}
