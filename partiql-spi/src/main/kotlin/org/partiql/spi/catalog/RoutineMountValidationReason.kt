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

/**
 * Classifies an invalid routine mount configuration.
 */
public enum class RoutineMountValidationReason {
    /** A subtree request uses the empty provider namespace. */
    EMPTY_SOURCE_SUBTREE,

    /** An exact or subtree source contains an empty segment. */
    EMPTY_SOURCE_SEGMENT,

    /** A non-root target namespace contains an empty segment. */
    EMPTY_TARGET_SEGMENT,

    /** An exact request does not match a routine in its loaded provider. */
    UNKNOWN_ROUTINE,

    /** A subtree request has no strict-descendant routines in its loaded provider. */
    UNKNOWN_SUBTREE,

    /** A routine ID was selected by an earlier request in the same component. */
    DUPLICATE_ROUTINE_MOUNT,

    /** Two different routines produce targets equal under regular identifier matching. */
    TARGET_NAME_COLLISION,
}
