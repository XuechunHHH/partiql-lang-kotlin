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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemRoutineCatalogTest {

    private val session = Session.empty()
    private val catalog = session.getCatalogs().getCatalog("\$system") as RoutineCatalog

    @Test
    fun resolvesVisibleFunctionWithStableId() {
        val binding = catalog.resolveFunctions(session, Identifier.regular("UTCNOW")).single()

        assertEquals("org.partiql.builtin.utcnow", binding.providerId.value)
        assertEquals(Name.of("utcnow"), binding.canonicalName)
        assertEquals(1, binding.overloads.size)
    }

    @Test
    fun sharesOneIdAcrossAllOverloads() {
        val binding = catalog.resolveFunctions(session, Identifier.regular("abs")).single()

        assertEquals("org.partiql.builtin.abs", binding.providerId.value)
        assertTrue(binding.overloads.size > 1)
        assertTrue(binding.overloads.all { it.signature.name == binding.canonicalName.getName() })
    }

    @Test
    fun resolvesHiddenFunctionWithoutLeakingPrefixIntoId() {
        val hiddenName = "\uFDEFdate_add_day"
        val binding = catalog.resolveFunctions(session, Identifier.delimited(hiddenName)).single()

        assertEquals("org.partiql.builtin.internal.date_add_day", binding.providerId.value)
        assertEquals(Name.of(hiddenName), binding.canonicalName)
        assertEquals(4, binding.overloads.size)
        assertTrue(binding.overloads.all { it.signature.name == hiddenName })
    }

    @Test
    fun appliesIdentifierCaseRulesAndRequiresRootName() {
        assertTrue(catalog.resolveFunctions(session, Identifier.delimited("UTCNOW")).isEmpty())
        assertTrue(catalog.resolveFunctions(session, Identifier.regular("namespace", "utcnow")).isEmpty())
    }

    @Test
    fun resolvesAggregationWithStableId() {
        val binding = catalog.resolveAggregations(session, Identifier.regular("COUNT")).single()

        assertEquals("org.partiql.builtin.count", binding.providerId.value)
        assertEquals(Name.of("count"), binding.canonicalName)
        assertTrue(binding.overloads.isNotEmpty())
    }
}
