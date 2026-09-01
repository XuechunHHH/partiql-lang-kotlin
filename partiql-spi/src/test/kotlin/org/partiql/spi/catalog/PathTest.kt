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

class PathTest {

    @Test
    fun constructionSnapshotsTheInputArray() {
        val entries = arrayOf(Namespace.of("example", "math"))
        val path = Path.of(*entries)

        entries[0] = Namespace.of("changed")

        assertEquals(listOf(Namespace.of("example", "math")), path.toList())
    }

    @Test
    fun constructionSnapshotsNamespaceContents() {
        val entry = Namespace.of("example", "math")
        val path = Path.of(entry)

        entry.getLevels()[1] = "changed"

        assertEquals(listOf(Namespace.of("example", "math")), path.toList())
    }

    @Test
    fun returnedNamespacesCannotMutatePath() {
        val path = Path.of(Namespace.of("example", "math"))

        path[0].getLevels()[1] = "changed"
        path.iterator().next().getLevels()[1] = "also_changed"

        assertEquals(listOf(Namespace.of("example", "math")), path.toList())
    }

    @Test
    fun iteratorCannotMutatePath() {
        val path = Path.of(Namespace.of("example", "math"))
        val iterator = path.iterator() as MutableIterator<Namespace>
        iterator.next()

        assertThrows<UnsupportedOperationException> {
            iterator.remove()
        }
        assertEquals(listOf(Namespace.of("example", "math")), path.toList())
    }

    @Test
    fun promoteMovesFirstExactMatchAndPreservesLaterDuplicate() {
        val first = Namespace.of("example", "first")
        val selected = Namespace.of("example", "selected")
        val other = Namespace.of("example", "other")
        val path = Path.of(first, selected, other, selected)

        val promoted = path.promote(selected)

        assertEquals(listOf(selected, first, other, selected), promoted.toList())
        assertEquals(listOf(first, selected, other, selected), path.toList())
    }

    @Test
    fun successivePromotionsPutMostRecentEntryFirst() {
        val first = Namespace.of("example", "first")
        val second = Namespace.of("example", "second")
        val third = Namespace.of("example", "third")

        val promoted = Path.of(first, second, third)
            .promote(second)
            .promote(third)

        assertEquals(listOf(third, second, first), promoted.toList())
    }

    @Test
    fun promotingFirstEntryReturnsSameImmutablePath() {
        val path = Path.of(Namespace.of("example"))

        assertSame(path, path.promote(Namespace.of("example")))
    }

    @Test
    fun promotingMissingEntryFailsWithoutChangingPath() {
        val original = Namespace.of("example", "math")
        val path = Path.of(original)

        assertThrows<IllegalArgumentException> {
            path.promote(Namespace.of("example", "missing"))
        }
        assertEquals(listOf(original), path.toList())
    }
}
