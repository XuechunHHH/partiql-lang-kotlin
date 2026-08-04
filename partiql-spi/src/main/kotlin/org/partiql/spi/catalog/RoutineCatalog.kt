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

import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload

/**
 * Optional [Catalog] extension for exact, namespace-aware routine lookup.
 *
 * Implement this interface when a catalog needs fully qualified routine calls or namespace-bearing entries in the
 * session routine path. A catalog that needs only the existing bare-name behavior should continue implementing
 * [Catalog] alone.
 *
 * The supplied identifiers are complete and catalog-local: they contain the namespace and routine leaf but never the
 * catalog name. The host catalog name from [Catalog.getName] is prepended only when PLK forms a fully qualified SQL
 * identity. Lookup matches the complete identifier depth without table-style longest-prefix matching. Each regular
 * identifier part matches case-insensitively and each delimited part matches case-sensitively.
 *
 * These methods are authoritative for an implementing catalog. PLK does not fall back to [Catalog.getFunctions] or
 * [Catalog.getAggregations] when exact lookup returns no binding. Before adopting this interface, a host must therefore
 * combine every routine source it intends to expose behind these methods; omitted legacy routines become invisible to
 * qualified and namespace-aware lookup.
 *
 * A lookup returns zero bindings when the routine is absent, one binding when uniquely selected, and every matching
 * canonical binding when a regular identifier is ambiguous.
 *
 * [getRoutineInventory] describes the complete registered surface. Session-aware lookup may return an authorized subset
 * of that surface, but it must never return a binding absent from the inventory.
 */
public interface RoutineCatalog : Catalog {

    /**
     * Resolves scalar bindings matching the complete catalog-local [identifier].
     */
    public fun resolveFunctions(
        session: Session,
        identifier: Identifier,
    ): Collection<RoutineBinding<FnOverload>>

    /**
     * Resolves aggregate bindings matching the complete catalog-local [identifier].
     */
    public fun resolveAggregations(
        session: Session,
        identifier: Identifier,
    ): Collection<RoutineBinding<AggOverload>>

    /**
     * Returns the complete immutable catalog-local routine inventory.
     */
    public fun getRoutineInventory(): RoutineInventory
}
