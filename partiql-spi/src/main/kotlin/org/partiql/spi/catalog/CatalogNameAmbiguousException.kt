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

/**
 * Indicates that a case-insensitive catalog lookup matched multiple canonical catalog names.
 *
 * [catalogNames] is an immutable snapshot sorted by exact, case-sensitive name.
 */
public class CatalogNameAmbiguousException private constructor(
    public val catalogNames: List<String>,
) : IllegalStateException("Catalog name is ambiguous; matched ${catalogNames.joinToString()}.") {

    public constructor(catalogNames: Collection<String>) :
        this(Collections.unmodifiableList(ArrayList(catalogNames.sorted())))
}
