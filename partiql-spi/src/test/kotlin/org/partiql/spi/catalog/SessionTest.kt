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
import org.junit.jupiter.api.Test

class SessionTest {

    private val catalog = Catalog.builder().name("andes").build()

    @Test
    fun defaultPathContainsCurrentNamespaceThenSystem() {
        val session = Session.builder()
            .catalogs(catalog)
            .catalog("andes")
            .namespace("default")
            .build()

        assertEquals(
            listOf(
                Namespace.of("andes", "default"),
                Namespace.of("\$system"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun configuredPathReplacesDefaultAndAppendsSystem() {
        val session = Session.builder()
            .catalogs(catalog)
            .catalog("andes")
            .namespace("default")
            .path(
                Namespace.of("andes", "collection"),
                Namespace.of("andes", "math"),
            )
            .build()

        assertEquals(
            listOf(
                Namespace.of("andes", "collection"),
                Namespace.of("andes", "math"),
                Namespace.of("\$system"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun configuredSystemEntryIsNormalizedToPathEnd() {
        val session = Session.builder()
            .catalogs(catalog)
            .catalog("andes")
            .path(
                Namespace.of("\$system"),
                Namespace.of("andes", "math"),
                Namespace.of("\$system"),
            )
            .build()

        assertEquals(
            listOf(
                Namespace.of("andes", "math"),
                Namespace.of("\$system"),
            ),
            session.getPath().toList(),
        )
    }
}
