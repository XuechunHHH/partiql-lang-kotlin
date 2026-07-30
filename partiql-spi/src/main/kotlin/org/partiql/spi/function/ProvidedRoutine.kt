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
import java.util.Collections

/**
 * One routine in a provider's mountable inventory.
 *
 * [id] is opaque provider identity. [sourceName] is its independent location in the provider inventory tree and is never
 * inferred from the ID or from a Java package. All overloads belong to this one routine.
 */
public class ProvidedRoutine<T>(
    public val id: RoutineId,
    sourceName: Name,
    overloads: Collection<T>,
) {
    public val sourceName: Name = Name.of(sourceName.toList())
    public val overloads: List<T> = Collections.unmodifiableList(ArrayList(overloads))
}
