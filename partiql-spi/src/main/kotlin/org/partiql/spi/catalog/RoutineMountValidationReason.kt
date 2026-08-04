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
 * Classifies an invalid final routine mount configuration.
 */
public enum class RoutineMountValidationReason {
    /** A provider source name contains an empty segment. */
    EMPTY_SOURCE_SEGMENT,

    /** A non-root target namespace contains an empty segment. */
    EMPTY_TARGET_SEGMENT,

    /** An exact source does not match a scalar or aggregate definition in its loaded provider. */
    UNKNOWN_ROUTINE,

    /** Distinct canonical target names are equal under regular SQL identifier matching. */
    TARGET_NAME_COLLISION,

    /** Two sources contribute the same declared parameter signature to one target and routine kind. */
    DUPLICATE_TARGET_SIGNATURE,
}
