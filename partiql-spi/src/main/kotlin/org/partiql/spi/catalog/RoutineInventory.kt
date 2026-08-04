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

import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import java.util.Collections

/**
 * The complete immutable catalog-local routine surface exposed by one [RoutineCatalog].
 *
 * Scalar and aggregate bindings are kept in separate collections and sorted by exact canonical name. Inventory names
 * never contain the owning catalog name, provider source metadata, or artifact identity. Exact duplicate canonical names
 * are invalid within one routine kind. Case-distinct names and the same name in different routine kinds remain distinct.
 */
public class RoutineInventory(
    functions: Collection<RoutineBinding<FnOverload>>,
    aggregations: Collection<RoutineBinding<AggOverload>>,
) {
    public val functions: List<RoutineBinding<FnOverload>> = immutableBindings("scalar", functions)
    public val aggregations: List<RoutineBinding<AggOverload>> = immutableBindings("aggregate", aggregations)

    private companion object {
        private fun <T> immutableBindings(
            kind: String,
            bindings: Collection<RoutineBinding<T>>,
        ): List<RoutineBinding<T>> {
            val copies = bindings.map { RoutineBinding(it.canonicalName, it.overloads) }
            val duplicate = copies
                .groupBy { it.canonicalName }
                .entries
                .firstOrNull { it.value.size > 1 }
                ?.key
            require(duplicate == null) {
                "Routine inventory contains duplicate $kind binding: $duplicate"
            }
            val sorted = copies.sortedWith(
                Comparator { first, second ->
                    compareNames(first.canonicalName, second.canonicalName)
                },
            )
            return Collections.unmodifiableList(ArrayList(sorted))
        }

        private fun compareNames(first: Name, second: Name): Int {
            val firstParts = first.toList()
            val secondParts = second.toList()
            for (index in 0 until minOf(firstParts.size, secondParts.size)) {
                val comparison = firstParts[index].compareTo(secondParts[index])
                if (comparison != 0) {
                    return comparison
                }
            }
            return firstParts.size.compareTo(secondParts.size)
        }
    }
}
