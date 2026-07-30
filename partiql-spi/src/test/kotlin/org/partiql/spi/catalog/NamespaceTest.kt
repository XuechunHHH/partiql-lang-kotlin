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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NamespaceTest {

    @Test
    fun copiesFactoryInput() {
        val levels = mutableListOf("example", "functions")
        val namespace = Namespace.of(levels)

        levels[0] = "changed"

        assertEquals(listOf("example", "functions"), namespace.toList())
    }

    @Test
    fun getLevelsReturnsDefensiveCopy() {
        val namespace = Namespace.of("example", "functions")
        val levels = namespace.getLevels()

        levels[0] = "changed"

        assertArrayEquals(arrayOf("example", "functions"), namespace.getLevels())
    }
}
