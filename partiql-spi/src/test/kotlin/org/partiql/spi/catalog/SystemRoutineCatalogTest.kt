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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.function.FnOverload
import org.partiql.spi.utils.FunctionUtils

class SystemRoutineCatalogTest {

    private val session = Session.empty()
    private val catalog = session.getCatalogs().getCatalog("\$system") as RoutineCatalog

    @Test
    fun systemCatalogImplementsExactRoutineContract() {
        assertEquals("\$system", catalog.getName())
        assertTrue(catalog.resolveFunctions(session, Identifier.regular("lower")).isNotEmpty())
        assertTrue(catalog.resolveAggregations(session, Identifier.regular("count")).isNotEmpty())
    }

    @Test
    fun resolvesScalarWithIdentifierCaseRulesAtCatalogRoot() {
        val regular = catalog.resolveFunctions(session, Identifier.regular("UTCNOW")).single()
        val delimited = catalog.resolveFunctions(session, Identifier.delimited("utcnow")).single()

        assertEquals(Name.of("utcnow"), regular.canonicalName)
        assertSame(regular, delimited)
        assertEquals(catalog.getFunctions(session, "utcnow"), regular.overloads)
        assertTrue(catalog.resolveFunctions(session, Identifier.delimited("UTCNOW")).isEmpty())
        assertTrue(catalog.resolveFunctions(session, Identifier.regular("datetime", "utcnow")).isEmpty())
    }

    @Test
    fun preservesHiddenCanonicalName() {
        val hiddenName = FunctionUtils.hide("date_add_day")
        val binding = catalog.resolveFunctions(session, Identifier.delimited(hiddenName)).single()

        assertEquals(Name.of(hiddenName), binding.canonicalName)
        assertEquals(catalog.getFunctions(session, hiddenName), binding.overloads)
    }

    @Test
    fun resolvesAggregateWithIdentifierCaseRulesAtCatalogRoot() {
        val binding = catalog.resolveAggregations(session, Identifier.regular("COUNT")).single()

        assertEquals(Name.of("count"), binding.canonicalName)
        assertEquals(catalog.getAggregations(session, "count"), binding.overloads)
        assertTrue(catalog.resolveAggregations(session, Identifier.delimited("COUNT")).isEmpty())
        assertTrue(catalog.resolveAggregations(session, Identifier.regular("stats", "count")).isEmpty())
    }

    @Test
    fun exactLookupCollectionsAreJavaUnmodifiable() {
        val binding = catalog.resolveFunctions(session, Identifier.regular("utcnow")).single()
        val matches = catalog.resolveFunctions(session, Identifier.regular("utcnow"))

        assertThrows<UnsupportedOperationException> {
            (matches as MutableCollection<RoutineBinding<FnOverload>>).add(binding)
        }
    }
}
