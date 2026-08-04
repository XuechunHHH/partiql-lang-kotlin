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
 * A structured provider-validation diagnostic.
 *
 * [isAggregate] identifies the scalar or aggregate inventory. [sourceName] identifies the invalid definition when
 * available. Duplicate definitions also populate [conflictingSourceName]. [callback] identifies a failed provider
 * callback. Signature diagnostics populate [signatureName] or [parameterTypes]. Fields not used by [reason] are null.
 */
public class RoutineProviderValidationIssue private constructor(
    public val reason: RoutineProviderValidationReason,
    public val isAggregate: Boolean,
    sourceName: Name? = null,
    conflictingSourceName: Name? = null,
    public val signatureName: String? = null,
    parameterTypes: Collection<PType>? = null,
) {
    public val sourceName: Name? = sourceName?.let { Name.of(it.toList()) }
    public val conflictingSourceName: Name? = conflictingSourceName?.let { Name.of(it.toList()) }
    public val parameterTypes: List<PType>? =
        parameterTypes?.let { Collections.unmodifiableList(ArrayList(it)) }
    public val callback: String? = when {
        reason != RoutineProviderValidationReason.PROVIDER_ACCESS_FAILED -> null
        isAggregate -> "getAggregations"
        else -> "getFunctions"
    }

    init {
        validateFields()
    }

    public val message: String = when (reason) {
        RoutineProviderValidationReason.PROVIDER_ACCESS_FAILED ->
            "Provider callback $callback failed."
        RoutineProviderValidationReason.EMPTY_SOURCE_SEGMENT ->
            "Source name $sourceName contains an empty segment."
        RoutineProviderValidationReason.DUPLICATE_SOURCE_NAME ->
            "Source name $sourceName is declared more than once in the $inventoryName inventory."
        RoutineProviderValidationReason.EMPTY_OVERLOADS ->
            "Routine definition $sourceName has no overloads."
        RoutineProviderValidationReason.SIGNATURE_NAME_MISMATCH ->
            "Routine definition $sourceName overload name $signatureName does not equal source leaf ${sourceName?.getName()}."
        RoutineProviderValidationReason.DUPLICATE_OVERLOAD_SIGNATURE ->
            "Routine definition $sourceName contains a duplicate overload signature."
    }

    private fun validateFields() {
        when (reason) {
            RoutineProviderValidationReason.PROVIDER_ACCESS_FAILED -> {
                requireNull(sourceName, conflictingSourceName, signatureName, parameterTypes)
            }
            RoutineProviderValidationReason.EMPTY_SOURCE_SEGMENT,
            RoutineProviderValidationReason.EMPTY_OVERLOADS,
            -> {
                requireNotNull(sourceName)
                requireNull(conflictingSourceName, signatureName, parameterTypes)
            }
            RoutineProviderValidationReason.DUPLICATE_SOURCE_NAME -> {
                requireNotNull(sourceName)
                requireNotNull(conflictingSourceName)
                requireNull(signatureName, parameterTypes)
            }
            RoutineProviderValidationReason.SIGNATURE_NAME_MISMATCH -> {
                requireNotNull(sourceName)
                requireNotNull(signatureName)
                requireNull(conflictingSourceName, parameterTypes)
            }
            RoutineProviderValidationReason.DUPLICATE_OVERLOAD_SIGNATURE -> {
                requireNotNull(sourceName)
                requireNotNull(parameterTypes)
                requireNull(conflictingSourceName, signatureName)
            }
        }
    }

    private fun requireNull(vararg fields: Any?) {
        require(fields.all { it == null })
    }

    private val inventoryName: String
        get() = if (isAggregate) "aggregate" else "scalar"

    internal companion object {

        @JvmSynthetic
        internal fun create(
            reason: RoutineProviderValidationReason,
            isAggregate: Boolean,
            sourceName: Name? = null,
            conflictingSourceName: Name? = null,
            signatureName: String? = null,
            parameterTypes: Collection<PType>? = null,
        ): RoutineProviderValidationIssue =
            RoutineProviderValidationIssue(
                reason = reason,
                isAggregate = isAggregate,
                sourceName = sourceName,
                conflictingSourceName = conflictingSourceName,
                signatureName = signatureName,
                parameterTypes = parameterTypes,
            )
    }
}
