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

package org.partiql.extensions.functions.custom.collection;

import org.partiql.spi.function.FnOverload;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Array {@code contains} overloads that use PartiQL value comparison semantics.
 */
public final class Contains {
    private static final Comparator<Datum> COMPARATOR = Datum.comparator();
    private static final List<FnOverload> OVERLOADS = Collections.singletonList(
        new FnOverload.Builder("contains")
            .addParameters(PType.array(), PType.string())
            .returns(PType.bool())
            .isNullCall(true)
            .body(Contains::contains)
            .build()
    );

    private Contains() {
    }

    /**
     * Returns the immutable {@code contains} overloads.
     *
     * @return {@code contains} overloads
     */
    public static List<FnOverload> overloads() {
        return OVERLOADS;
    }

    private static Datum contains(Datum[] args) {
        Datum expected = args[1];
        for (Datum element : args[0]) {
            if (COMPARATOR.compare(element, expected) == 0) {
                return Datum.bool(true);
            }
        }
        return Datum.bool(false);
    }
}
