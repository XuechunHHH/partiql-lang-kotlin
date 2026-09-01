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

class SessionPathTest {

    private val hostCatalog = Catalog.builder().name("example").build()

    @Test
    fun omittedPathPreservesCurrentCatalogNamespaceAndSystemDefault() {
        val session = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .namespace("tables")
            .build()

        assertEquals(
            listOf(
                Namespace.of("example", "tables"),
                Namespace.of("\$system"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun omittedPathContinuesToFollowTheLegacyNamespaceObject() {
        val namespace = Namespace.of("before")
        val session = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .namespace(namespace)
            .build()

        namespace.getLevels()[0] = "after"

        assertEquals(
            listOf(
                Namespace.of("example", "after"),
                Namespace.of("\$system"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun explicitPathIsIndependentFromTableNamespaceAndAppendsMissingSystemRoot() {
        val session = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .namespace("tables")
            .path(
                Namespace.of("example", "collection"),
                Namespace.of("example", "math"),
            )
            .build()

        assertEquals(Namespace.of("tables"), session.getNamespace())
        assertEquals(
            listOf(
                Namespace.of("example", "collection"),
                Namespace.of("example", "math"),
                Namespace.of("\$system"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun explicitSystemRootKeepsAnySuppliedPosition() {
        val paths = listOf(
            listOf(
                Namespace.of("\$system"),
                Namespace.of("example", "collection"),
                Namespace.of("example", "math"),
            ),
            listOf(
                Namespace.of("example", "collection"),
                Namespace.of("\$system"),
                Namespace.of("example", "math"),
            ),
            listOf(
                Namespace.of("example", "collection"),
                Namespace.of("example", "math"),
                Namespace.of("\$system"),
            ),
        )

        paths.forEach { entries ->
            val session = Session.builder()
                .catalogs(hostCatalog)
                .catalog("example")
                .path(Path.of(*entries.toTypedArray()))
                .build()

            assertEquals(entries, session.getPath().toList())
        }
    }

    @Test
    fun suppliedPathMembershipAndDuplicatesArePreserved() {
        val session = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .path(
                Path.of(
                    Namespace.of("example", "collection"),
                    Namespace.of("\$system"),
                    Namespace.of("example", "collection"),
                ),
            )
            .build()

        assertEquals(
            listOf(
                Namespace.of("example", "collection"),
                Namespace.of("\$system"),
                Namespace.of("example", "collection"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun explicitEmptyPathContainsOnlySystemRoot() {
        val session = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .path()
            .build()

        assertEquals(listOf(Namespace.of("\$system")), session.getPath().toList())
    }

    @Test
    fun customSystemSelectionIsIndependentOfBuilderMethodOrder() {
        val customSystem = Catalog.builder().name("builtins").build()
        val configuredPath = Path.of(
            Namespace.of("builtins"),
            Namespace.of("example", "math"),
        )

        val pathThenSystem = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .path(configuredPath)
            .system(customSystem)
            .build()
        val systemThenPath = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .system(customSystem)
            .path(configuredPath)
            .build()

        val expected = listOf(
            Namespace.of("builtins"),
            Namespace.of("example", "math"),
        )
        assertEquals(expected, pathThenSystem.getPath().toList())
        assertEquals(expected, systemThenPath.getPath().toList())
    }

    @Test
    fun missingCustomSystemRootIsAppended() {
        val customSystem = Catalog.builder().name("builtins").build()
        val session = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .path(Namespace.of("example", "math"))
            .system(customSystem)
            .build()

        assertEquals(
            listOf(
                Namespace.of("example", "math"),
                Namespace.of("builtins"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun pathInputIsSnapshotted() {
        val entries = arrayOf(Namespace.of("example", "math"))
        val builder = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .path(*entries)

        entries[0] = Namespace.of("changed")
        val session = builder.build()

        assertEquals(
            listOf(
                Namespace.of("example", "math"),
                Namespace.of("\$system"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun pathNamespaceContentsAreSnapshottedAndDefensive() {
        val entry = Namespace.of("example", "math")
        val session = Session.builder()
            .catalogs(hostCatalog)
            .catalog("example")
            .path(entry)
            .build()

        entry.getLevels()[1] = "changed"
        session.getPath()[0].getLevels()[1] = "also_changed"

        assertEquals(
            listOf(
                Namespace.of("example", "math"),
                Namespace.of("\$system"),
            ),
            session.getPath().toList(),
        )
    }

    @Test
    fun customSessionDefaultPathRemainsCurrentCatalogAndNamespaceOnly() {
        val custom = object : Session {
            override fun getIdentity(): String = "test"
            override fun getCatalog(): String = "example"
            override fun getCatalogs(): Catalogs = Catalogs.of(hostCatalog)
            override fun getNamespace(): Namespace = Namespace.of("tables")
        }

        assertEquals(
            listOf(Namespace.of("example", "tables")),
            custom.getPath().toList(),
        )
    }
}
