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

import java.util.Collections

/**
 * Thrown when routine mount requests cannot produce one complete valid component.
 *
 * [issues] is a deterministic, Java-unmodifiable list of all discoverable configuration failures.
 */
public class RoutineMountValidationException internal constructor(
    issues: Collection<RoutineMountValidationIssue>,
) : IllegalStateException(
    "Routine mount validation failed with ${issues.size} issue(s).",
) {
    public val issues: List<RoutineMountValidationIssue> =
        Collections.unmodifiableList(ArrayList(issues))
}
