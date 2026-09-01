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

package org.partiql.plan;

import org.jetbrains.annotations.NotNull;
import org.partiql.spi.catalog.Name;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact catalog and catalog-local name of a resolved SQL routine.
 * <p>
 * This identity is independent of provider inventory names and artifact metadata.
 */
public final class RoutineRef {

    @NotNull
    private final String catalog;

    @NotNull
    private final Name name;

    /**
     * Creates a routine reference.
     *
     * @param catalog canonical catalog name containing the selected binding
     * @param name exact canonical catalog-local binding name
     */
    public RoutineRef(
            @NotNull String catalog,
            @NotNull Name name
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.name = copy(Objects.requireNonNull(name, "name"));
    }

    /**
     * Returns the canonical catalog name containing the selected binding.
     */
    @NotNull
    public String getCatalog() {
        return catalog;
    }

    /**
     * Returns the exact canonical catalog-local binding name.
     */
    @NotNull
    public Name getName() {
        return copy(name);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoutineRef)) {
            return false;
        }
        RoutineRef that = (RoutineRef) other;
        return catalog.equals(that.catalog) && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalog, name);
    }

    @Override
    public String toString() {
        return "RoutineRef{"
                + "catalog='" + catalog + '\''
                + ", name=" + name
                + '}';
    }

    private static Name copy(Name source) {
        List<String> parts = new ArrayList<>();
        source.forEach(parts::add);
        return Name.of(parts);
    }
}
