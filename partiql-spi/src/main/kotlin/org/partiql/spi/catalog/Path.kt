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

import java.util.Collections
import java.util.Spliterator
import java.util.function.Consumer

/**
 * The routine resolution path, accessible via PATH. Each of these namespaces have the catalog name as the first
 * element in the namespace.
 */
public class Path private constructor(
    namespaces: List<Namespace>,
) : Iterable<Namespace> {

    private val namespaces: List<Namespace> = immutableCopy(namespaces)

    public companion object {

        @JvmStatic
        public fun of(vararg namespaces: Namespace): Path = Path(namespaces.toList())

        private fun immutableCopy(namespaces: Collection<Namespace>): List<Namespace> =
            Collections.unmodifiableList(
                namespaces.map { Namespace.of(it.toList()) },
            )
    }

    public fun getLength(): Int {
        return namespaces.size
    }

    public fun isEmpty(): Boolean {
        return namespaces.isEmpty()
    }

    public operator fun get(index: Int): Namespace {
        return Namespace.of(namespaces[index].toList())
    }

    /**
     * Returns a path with the first exact occurrence of [entry] moved to the front.
     *
     * The relative order of every other entry, including later duplicates, is preserved. This path is unchanged.
     *
     * @throws IllegalArgumentException when [entry] is absent
     */
    public fun promote(entry: Namespace): Path {
        val index = namespaces.indexOf(entry)
        require(index >= 0) { "Cannot promote a path entry that is not present: $entry" }
        if (index == 0) {
            return this
        }
        val promoted = namespaces.toMutableList()
        promoted.removeAt(index)
        promoted.add(0, entry)
        return Path(promoted)
    }

    override fun forEach(action: Consumer<in Namespace>?) {
        immutableCopy(namespaces).forEach(action)
    }

    override fun iterator(): Iterator<Namespace> {
        return immutableCopy(namespaces).iterator()
    }

    override fun spliterator(): Spliterator<Namespace> {
        return immutableCopy(namespaces).spliterator()
    }

    override fun toString(): String = "PATH: (${namespaces.joinToString()})"
}
