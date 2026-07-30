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
 * A structured provider validation diagnostic.
 *
 * [routineId] and [sourceName] identify the invalid routine when applicable. Duplicate declarations also populate
 * [conflictingRoutineId] and [conflictingSourceName]. [callback] is either `getFunctions` or `getAggregations` for
 * [RoutineProviderValidationReason.PROVIDER_ACCESS_FAILED]. Signature diagnostics populate [signatureName] or
 * [parameterTypes] as appropriate. Fields not used by [reason] are null.
 */
public class RoutineProviderValidationIssue internal constructor(
    public val reason: RoutineProviderValidationReason,
    public val routineId: RoutineId? = null,
    sourceName: Name? = null,
    public val conflictingRoutineId: RoutineId? = null,
    conflictingSourceName: Name? = null,
    public val callback: String? = null,
    public val signatureName: String? = null,
    parameterTypes: Collection<PType>? = null,
) {
    public val sourceName: Name? = sourceName?.let { Name.of(it.toList()) }
    public val conflictingSourceName: Name? = conflictingSourceName?.let { Name.of(it.toList()) }
    public val parameterTypes: List<PType>? =
        parameterTypes?.let { Collections.unmodifiableList(ArrayList(it)) }

    init {
        validateFields()
    }

    public val message: String = when (reason) {
        RoutineProviderValidationReason.PROVIDER_ACCESS_FAILED ->
            "Provider callback $callback failed."
        RoutineProviderValidationReason.EMPTY_SOURCE_SEGMENT ->
            "Source name $sourceName contains an empty segment."
        RoutineProviderValidationReason.DUPLICATE_ROUTINE_ID ->
            "Routine ID $routineId is declared more than once at $conflictingSourceName and $sourceName."
        RoutineProviderValidationReason.DUPLICATE_SOURCE_NAME ->
            "Source name $sourceName is declared by routine IDs $conflictingRoutineId and $routineId."
        RoutineProviderValidationReason.EMPTY_OVERLOADS ->
            "Routine $routineId has no overloads."
        RoutineProviderValidationReason.SIGNATURE_NAME_MISMATCH ->
            "Routine $routineId overload name $signatureName does not equal source leaf ${sourceName?.getName()}."
        RoutineProviderValidationReason.DUPLICATE_OVERLOAD_SIGNATURE ->
            "Routine $routineId contains a duplicate overload signature."
    }

    private fun validateFields() {
        when (reason) {
            RoutineProviderValidationReason.PROVIDER_ACCESS_FAILED -> {
                require(callback == GET_FUNCTIONS || callback == GET_AGGREGATIONS)
                requireNull(routineId, sourceName, conflictingRoutineId, conflictingSourceName, signatureName, parameterTypes)
            }
            RoutineProviderValidationReason.EMPTY_SOURCE_SEGMENT,
            RoutineProviderValidationReason.EMPTY_OVERLOADS,
            -> {
                requireNotNull(routineId)
                requireNotNull(sourceName)
                requireNull(conflictingRoutineId, conflictingSourceName, callback, signatureName, parameterTypes)
            }
            RoutineProviderValidationReason.DUPLICATE_ROUTINE_ID,
            RoutineProviderValidationReason.DUPLICATE_SOURCE_NAME,
            -> {
                requireNotNull(routineId)
                requireNotNull(sourceName)
                requireNotNull(conflictingRoutineId)
                requireNotNull(conflictingSourceName)
                requireNull(callback, signatureName, parameterTypes)
            }
            RoutineProviderValidationReason.SIGNATURE_NAME_MISMATCH -> {
                requireNotNull(routineId)
                requireNotNull(sourceName)
                requireNotNull(signatureName)
                requireNull(conflictingRoutineId, conflictingSourceName, callback, parameterTypes)
            }
            RoutineProviderValidationReason.DUPLICATE_OVERLOAD_SIGNATURE -> {
                requireNotNull(routineId)
                requireNotNull(sourceName)
                requireNotNull(parameterTypes)
                requireNull(conflictingRoutineId, conflictingSourceName, callback, signatureName)
            }
        }
    }

    private fun requireNull(vararg fields: Any?) {
        require(fields.all { it == null })
    }

    internal companion object {
        internal const val GET_FUNCTIONS: String = "getFunctions"
        internal const val GET_AGGREGATIONS: String = "getAggregations"
    }
}
