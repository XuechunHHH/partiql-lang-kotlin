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
 * One catalog-local SQL routine binding and all of its overloads for one routine kind.
 *
 * [canonicalName] contains the complete catalog-local name, including any namespace and the routine leaf. It is
 * independent of provider source names and may be placed under any host catalog that exposes this binding. [overloads]
 * must not be empty.
 */
public class RoutineBinding<T>(
    canonicalName: Name,
    overloads: Collection<T>,
) {
    public val canonicalName: Name = Name.of(canonicalName.toList())
    public val overloads: List<T> = Collections.unmodifiableList(ArrayList(overloads))

    init {
        require(this.overloads.isNotEmpty()) { "Routine binding overloads cannot be empty" }
    }
}
