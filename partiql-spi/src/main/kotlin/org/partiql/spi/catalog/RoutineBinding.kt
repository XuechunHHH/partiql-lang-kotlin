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
import java.util.Collections

/**
 * A catalog-local routine and all of its overloads.
 *
 * [providerId] is an opaque provider identity independent of [canonicalName]. A provider routine may be mounted under
 * different catalog-local names without changing its identity.
 */
public class RoutineBinding<T>(
    public val providerId: RoutineId,
    canonicalName: Name,
    overloads: Collection<T>,
) {
    public val canonicalName: Name = Name.of(canonicalName.toList())
    public val overloads: Collection<T> = Collections.unmodifiableList(ArrayList(overloads))
}
