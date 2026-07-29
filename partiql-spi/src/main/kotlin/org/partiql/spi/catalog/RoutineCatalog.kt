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
 * Optional catalog extension for exact, namespace-aware routine lookup.
 */
public interface RoutineCatalog : Catalog {

    /**
     * Resolves scalar routines matching the complete catalog-local [identifier].
     */
    public fun resolveFunctions(
        session: Session,
        identifier: Identifier,
    ): Collection<RoutineBinding<FnOverload>>

    /**
     * Resolves aggregate routines matching the complete catalog-local [identifier].
     */
    public fun resolveAggregations(
        session: Session,
        identifier: Identifier,
    ): Collection<RoutineBinding<AggOverload>>
}
