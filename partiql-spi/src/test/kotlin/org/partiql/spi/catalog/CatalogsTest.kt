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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CatalogsTest {

    @Test
    fun caseInsensitiveLookupReturnsZeroOrOneMatch() {
        val catalog = Catalog.builder().name("Demo").build()
        val catalogs = Catalogs.of(catalog)

        assertSame(catalog, catalogs.getCatalog("DEMO", ignoreCase = true))
        assertNull(catalogs.getCatalog("missing", ignoreCase = true))
    }

    @Test
    fun caseInsensitiveLookupReportsAllCaseDistinctMatchesInExactNameOrder() {
        val lower = Catalog.builder().name("demo").build()
        val title = Catalog.builder().name("Demo").build()
        val upper = Catalog.builder().name("DEMO").build()
        val catalogs = Catalogs.of(lower, title, upper)

        val error = assertThrows<CatalogNameAmbiguousException> {
            catalogs.getCatalog("dEmO", ignoreCase = true)
        }

        assertEquals(listOf("DEMO", "Demo", "demo"), error.catalogNames)
        assertEquals("Catalog name is ambiguous; matched DEMO, Demo, demo.", error.message)
    }

    @Test
    fun exactLookupKeepsCaseDistinctCatalogsSeparate() {
        val lower = Catalog.builder().name("demo").build()
        val upper = Catalog.builder().name("DEMO").build()
        val catalogs = Catalogs.of(lower, upper)

        assertSame(lower, catalogs.getCatalog("demo"))
        assertSame(upper, catalogs.getCatalog("DEMO"))
        assertNull(catalogs.getCatalog("Demo"))
    }

    @Test
    fun ambiguityDetailsAreDefensiveAndJavaUnmodifiable() {
        val source = mutableListOf("demo", "DEMO")
        val error = CatalogNameAmbiguousException(source)

        source.clear()

        assertEquals(listOf("DEMO", "demo"), error.catalogNames)
        assertThrows<UnsupportedOperationException> {
            (error.catalogNames as MutableList<String>).clear()
        }
    }
}
