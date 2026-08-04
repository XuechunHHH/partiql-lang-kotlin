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
 * Utilities for constructing routine paths from a catalog's complete routine inventory.
 */
public object RoutinePaths {

    /**
     * Returns the directly populated namespaces in [catalog], followed by the standard system catalog root.
     */
    @JvmStatic
    public fun fromInventory(catalog: RoutineCatalog): Path = fromInventory(catalog, System.INSTANCE)

    /**
     * Returns the directly populated namespaces in [catalog], followed by [systemCatalog]'s root.
     *
     * Catalog-local namespaces are de-duplicated across scalar and aggregate bindings, sorted lexically by exact
     * case-sensitive segment sequence, and prefixed with [catalog]'s name. Parent namespaces are not inferred.
     */
    @JvmStatic
    public fun fromInventory(
        catalog: RoutineCatalog,
        systemCatalog: Catalog,
    ): Path {
        val inventory = catalog.getRoutineInventory()
        val namespaces = mutableSetOf<Namespace>()
        inventory.functions.forEach { namespaces.add(it.canonicalName.getNamespace()) }
        inventory.aggregations.forEach { namespaces.add(it.canonicalName.getNamespace()) }

        val entries = namespaces
            .sortedWith(NAMESPACE_COMPARATOR)
            .map { Namespace.of(catalog.getName()).append(*it.getLevels()) }
            .toMutableList()
        val systemRoot = Namespace.of(systemCatalog.getName())
        entries.removeAll { it == systemRoot }
        entries.add(systemRoot)
        return Path.of(*entries.toTypedArray())
    }

    private val NAMESPACE_COMPARATOR: Comparator<Namespace> = Comparator { first, second ->
        for (index in 0 until minOf(first.getLength(), second.getLength())) {
            val comparison = first[index].compareTo(second[index])
            if (comparison != 0) {
                return@Comparator comparison
            }
        }
        first.getLength().compareTo(second.getLength())
    }
}
