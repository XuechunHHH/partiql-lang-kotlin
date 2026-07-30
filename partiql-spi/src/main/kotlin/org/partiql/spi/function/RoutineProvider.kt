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

package org.partiql.spi.function

/**
 * Supplies a named inventory of scalar and aggregate routines for explicit loading by a host.
 *
 * Loading a provider does not make its routines visible to SQL. A host must separately mount selected routines into a
 * catalog-local namespace.
 */
public interface RoutineProvider {

    /**
     * Returns the provider's scalar routine inventory.
     */
    public fun getFunctions(): Collection<ProvidedRoutine<FnOverload>>

    /**
     * Returns the provider's aggregate routine inventory.
     */
    public fun getAggregations(): Collection<ProvidedRoutine<AggOverload>>
}
