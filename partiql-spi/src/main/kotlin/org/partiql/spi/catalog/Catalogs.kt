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

import org.partiql.spi.catalog.impl.StandardCatalogs

/**
 * Catalogs is used to provide the default catalog and possibly others by name.
 */
public interface Catalogs {

    /**
     * Returns a catalog by name (single identifier).
     *
     * @throws CatalogNameAmbiguousException when case-insensitive lookup matches multiple canonical catalog names
     */
    public fun getCatalog(name: String, ignoreCase: Boolean = false): Catalog?

    /**
     * Factory methods and builder.
     */
    public companion object {

        @JvmStatic
        public fun of(vararg catalogs: Catalog): Catalogs = of(catalogs.toList())

        @JvmStatic
        public fun of(catalogs: Collection<Catalog>): Catalogs {
            return builder().apply { catalogs.forEach { add(it) } }.build()
        }

        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /**
     * Lombok java-style builder for a default [Catalogs] implementation.
     */
    public class Builder {

        private val catalogs = mutableMapOf<String, Catalog>()

        /**
         * Adds this catalog, overwriting any existing one with the same name.
         */
        public fun add(catalog: Catalog): Builder = this.apply {
            catalogs[catalog.getName()] = catalog
        }

        public fun build(): Catalogs = StandardCatalogs(catalogs)
    }
}
