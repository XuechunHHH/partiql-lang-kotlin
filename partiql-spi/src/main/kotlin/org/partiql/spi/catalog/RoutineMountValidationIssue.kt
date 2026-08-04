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

import org.partiql.spi.types.PType
import java.util.Collections

/**
 * A structured routine-mount validation diagnostic.
 *
 * [requestIndex], [sourceName], and [targetName] identify the surviving mount request. Conflict diagnostics also
 * identify the earlier request and its source and target. [isAggregate] and [parameterTypes] are populated only for a
 * duplicate target signature. Fields not used by [reason] are null.
 */
public class RoutineMountValidationIssue private constructor(
    public val reason: RoutineMountValidationReason,
    public val requestIndex: Int,
    sourceName: Name,
    targetName: Name,
    public val conflictingRequestIndex: Int? = null,
    conflictingSourceName: Name? = null,
    conflictingTargetName: Name? = null,
    public val isAggregate: Boolean? = null,
    parameterTypes: Collection<PType>? = null,
) {
    public val sourceName: Name = Name.of(sourceName.toList())
    public val targetName: Name = Name.of(targetName.toList())
    public val conflictingSourceName: Name? = conflictingSourceName?.let { Name.of(it.toList()) }
    public val conflictingTargetName: Name? = conflictingTargetName?.let { Name.of(it.toList()) }
    public val parameterTypes: List<PType>? =
        parameterTypes?.let { Collections.unmodifiableList(ArrayList(it)) }

    init {
        validateFields()
    }

    public val message: String = when (reason) {
        RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT ->
            "Mount request $requestIndex source $sourceName contains an empty segment."
        RoutineMountValidationReason.EMPTY_TARGET_SEGMENT ->
            "Mount request $requestIndex target $targetName contains an empty segment."
        RoutineMountValidationReason.UNKNOWN_ROUTINE ->
            "Mount request $requestIndex references unknown routine $sourceName."
        RoutineMountValidationReason.TARGET_NAME_COLLISION ->
            "Mount request $requestIndex source $sourceName target $targetName collides with request " +
                "$conflictingRequestIndex source $conflictingSourceName target $conflictingTargetName under regular " +
                "identifier matching."
        RoutineMountValidationReason.DUPLICATE_TARGET_SIGNATURE ->
            "Mount request $requestIndex source $sourceName contributes duplicate $kindName signature " +
                "$parameterTypes to target $targetName; request $conflictingRequestIndex source " +
                "$conflictingSourceName contributed it first."
    }

    private fun validateFields() {
        require(requestIndex >= 0)
        when (reason) {
            RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT,
            RoutineMountValidationReason.EMPTY_TARGET_SEGMENT,
            RoutineMountValidationReason.UNKNOWN_ROUTINE,
            -> requireNull(
                conflictingRequestIndex,
                conflictingSourceName,
                conflictingTargetName,
                isAggregate,
                parameterTypes,
            )
            RoutineMountValidationReason.TARGET_NAME_COLLISION -> {
                requireConflictFields()
                requireNull(isAggregate, parameterTypes)
            }
            RoutineMountValidationReason.DUPLICATE_TARGET_SIGNATURE -> {
                requireConflictFields()
                requireNotNull(isAggregate)
                requireNotNull(parameterTypes)
            }
        }
    }

    private fun requireConflictFields() {
        requireNotNull(conflictingRequestIndex)
        require(conflictingRequestIndex >= 0)
        requireNotNull(conflictingSourceName)
        requireNotNull(conflictingTargetName)
    }

    private fun requireNull(vararg fields: Any?) {
        require(fields.all { it == null })
    }

    private val kindName: String
        get() = if (isAggregate == true) "aggregate" else "scalar"

    internal companion object {

        @JvmSynthetic
        internal fun create(
            reason: RoutineMountValidationReason,
            requestIndex: Int,
            sourceName: Name,
            targetName: Name,
            conflictingRequestIndex: Int? = null,
            conflictingSourceName: Name? = null,
            conflictingTargetName: Name? = null,
            isAggregate: Boolean? = null,
            parameterTypes: Collection<PType>? = null,
        ): RoutineMountValidationIssue =
            RoutineMountValidationIssue(
                reason = reason,
                requestIndex = requestIndex,
                sourceName = sourceName,
                targetName = targetName,
                conflictingRequestIndex = conflictingRequestIndex,
                conflictingSourceName = conflictingSourceName,
                conflictingTargetName = conflictingTargetName,
                isAggregate = isAggregate,
                parameterTypes = parameterTypes,
            )
    }
}
