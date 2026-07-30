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

import org.partiql.spi.function.RoutineId

/**
 * A structured routine mount validation diagnostic.
 *
 * [requestIndex] identifies the zero-based builder request. Exactly one of [sourceName] and [sourceNamespace] identifies
 * that request. Candidate conflicts also include both expanded provider source names, target names, and the conflicting
 * request index. Fields not used by [reason] are null.
 */
public class RoutineMountValidationIssue internal constructor(
    public val reason: RoutineMountValidationReason,
    public val requestIndex: Int,
    public val conflictingRequestIndex: Int? = null,
    public val routineId: RoutineId? = null,
    public val conflictingRoutineId: RoutineId? = null,
    sourceName: Name? = null,
    sourceNamespace: Namespace? = null,
    targetNamespace: Namespace? = null,
    selectedSourceName: Name? = null,
    targetName: Name? = null,
    conflictingSelectedSourceName: Name? = null,
    conflictingTargetName: Name? = null,
) {
    public val sourceName: Name? = sourceName?.copy()
    public val sourceNamespace: Namespace? = sourceNamespace?.copy()
    public val targetNamespace: Namespace? = targetNamespace?.copy()
    public val selectedSourceName: Name? = selectedSourceName?.copy()
    public val targetName: Name? = targetName?.copy()
    public val conflictingSelectedSourceName: Name? = conflictingSelectedSourceName?.copy()
    public val conflictingTargetName: Name? = conflictingTargetName?.copy()

    init {
        validateFields()
    }

    public val message: String = when (reason) {
        RoutineMountValidationReason.EMPTY_SOURCE_SUBTREE ->
            "Mount request $requestIndex has an empty source subtree."
        RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT ->
            "Mount request $requestIndex source contains an empty segment."
        RoutineMountValidationReason.EMPTY_TARGET_SEGMENT ->
            "Mount request $requestIndex target namespace contains an empty segment."
        RoutineMountValidationReason.UNKNOWN_ROUTINE ->
            "Mount request $requestIndex references unknown routine $sourceName."
        RoutineMountValidationReason.UNKNOWN_SUBTREE ->
            "Mount request $requestIndex references unknown or empty subtree $sourceNamespace."
        RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT ->
            "Mount request $requestIndex candidate $selectedSourceName selects routine $routineId, first selected by " +
                "request $conflictingRequestIndex candidate $conflictingSelectedSourceName."
        RoutineMountValidationReason.TARGET_NAME_COLLISION ->
            "Mount request $requestIndex candidate $selectedSourceName target $targetName collides with request " +
                "$conflictingRequestIndex candidate $conflictingSelectedSourceName target $conflictingTargetName under " +
                "regular identifier matching."
    }

    private fun validateFields() {
        require(requestIndex >= 0)
        require((sourceName == null) != (sourceNamespace == null))
        requireNotNull(targetNamespace)

        when (reason) {
            RoutineMountValidationReason.EMPTY_SOURCE_SUBTREE,
            RoutineMountValidationReason.UNKNOWN_SUBTREE,
            -> {
                requireNotNull(sourceNamespace)
                requireNull(
                    conflictingRequestIndex,
                    routineId,
                    conflictingRoutineId,
                    selectedSourceName,
                    targetName,
                    conflictingSelectedSourceName,
                    conflictingTargetName,
                )
            }
            RoutineMountValidationReason.EMPTY_SOURCE_SEGMENT,
            RoutineMountValidationReason.EMPTY_TARGET_SEGMENT,
            -> requireNull(
                conflictingRequestIndex,
                routineId,
                conflictingRoutineId,
                selectedSourceName,
                targetName,
                conflictingSelectedSourceName,
                conflictingTargetName,
            )
            RoutineMountValidationReason.UNKNOWN_ROUTINE -> {
                requireNotNull(sourceName)
                requireNull(
                    conflictingRequestIndex,
                    routineId,
                    conflictingRoutineId,
                    selectedSourceName,
                    targetName,
                    conflictingSelectedSourceName,
                    conflictingTargetName,
                )
            }
            RoutineMountValidationReason.DUPLICATE_ROUTINE_MOUNT -> {
                requireConflictFields()
                requireNotNull(routineId)
                requireNull(conflictingRoutineId)
            }
            RoutineMountValidationReason.TARGET_NAME_COLLISION -> {
                requireConflictFields()
                requireNotNull(routineId)
                requireNotNull(conflictingRoutineId)
            }
        }
    }

    private fun requireConflictFields() {
        requireNotNull(conflictingRequestIndex)
        require(conflictingRequestIndex >= 0)
        requireNotNull(selectedSourceName)
        requireNotNull(targetName)
        requireNotNull(conflictingSelectedSourceName)
        requireNotNull(conflictingTargetName)
    }

    private fun requireNull(vararg fields: Any?) {
        require(fields.all { it == null })
    }

    private fun Name.copy(): Name = Name.of(toList())

    private fun Namespace.copy(): Namespace = Namespace.of(toList())
}
