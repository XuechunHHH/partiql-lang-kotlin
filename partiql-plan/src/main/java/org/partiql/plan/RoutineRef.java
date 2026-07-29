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
import org.partiql.spi.function.RoutineId;

import java.util.Objects;

/**
 * Provider identity and exact catalog-local name of a resolved routine.
 * <p>
 * The provider ID is independent of the catalog and name under which the routine is mounted.
 */
public final class RoutineRef {

    @NotNull
    private final RoutineId providerId;

    @NotNull
    private final String catalog;

    @NotNull
    private final Name name;

    /**
     * Creates a routine reference.
     *
     * @param providerId opaque identity assigned by the routine provider
     * @param catalog canonical catalog name containing the selected binding
     * @param name exact canonical catalog-local binding name
     */
    public RoutineRef(
            @NotNull RoutineId providerId,
            @NotNull String catalog,
            @NotNull Name name
    ) {
        this.providerId = providerId;
        this.catalog = catalog;
        this.name = name;
    }

    /**
     * Returns the opaque identity assigned by the routine provider.
     */
    @NotNull
    public RoutineId getProviderId() {
        return providerId;
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
        return name;
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
        return providerId.equals(that.providerId)
                && catalog.equals(that.catalog)
                && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, catalog, name);
    }

    @Override
    public String toString() {
        return "RoutineRef{"
                + "providerId=" + providerId
                + ", catalog='" + catalog + '\''
                + ", name=" + name
                + '}';
    }
}
