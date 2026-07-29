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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CatalogsTest {

    @Test
    fun caseInsensitiveLookupReportsAllCaseDistinctMatches() {
        val lower = Catalog.builder().name("demo").build()
        val upper = Catalog.builder().name("DEMO").build()
        val catalogs = Catalogs.of(lower, upper)

        val error = assertThrows<CatalogNameAmbiguousException> {
            catalogs.getCatalog("DeMo", ignoreCase = true)
        }

        assertEquals(listOf("demo", "DEMO"), error.catalogNames)
    }

    @Test
    fun exactLookupKeepsCaseDistinctCatalogsSeparate() {
        val lower = Catalog.builder().name("demo").build()
        val upper = Catalog.builder().name("DEMO").build()
        val catalogs = Catalogs.of(lower, upper)

        assertSame(lower, catalogs.getCatalog("demo"))
        assertSame(upper, catalogs.getCatalog("DEMO"))
    }
}
