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

package org.partiql.spi.catalog.impl

import org.partiql.spi.catalog.Catalog
import org.partiql.spi.catalog.CatalogNameAmbiguousException
import org.partiql.spi.catalog.Catalogs

/**
 * Standard implementation for [Catalogs] backed by an in-memory map.
 *
 * @property catalogs
 */
internal class StandardCatalogs(private val catalogs: Map<String, Catalog>) : Catalogs {

    override fun getCatalog(name: String, ignoreCase: Boolean): Catalog? {
        if (ignoreCase) {
            val matches = catalogs.values.filter { it.getName().equals(name, ignoreCase = true) }
            if (matches.size > 1) {
                throw CatalogNameAmbiguousException(matches.map { it.getName() })
            }
            return matches.singleOrNull()
        }
        return catalogs[name]
    }
}
