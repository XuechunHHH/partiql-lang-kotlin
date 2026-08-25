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

package org.partiql.extensions.functions.compat.datetime;

import org.partiql.spi.function.FnOverload;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

/**
 * Compatibility overloads that return the current UTC time as a timestamp without a timezone.
 *
 * <p>This preserves the behavior of
 * <a href="https://github.com/partiql/partiql-lang-kotlin/blob/v0.14.8/partiql-lang/src/main/kotlin/org/partiql/lang/eval/builtins/ScalarBuiltinsExt.kt#L96-L110">
 * PartiQL Lang Kotlin v0.14 {@code utcnow}</a>.
 */
public final class UtcNow {
    private static final int PRECISION = 6;
    private static final List<FnOverload> OVERLOADS = Collections.singletonList(
        new FnOverload.Builder("utcnow")
            .returns(PType.timestamp())
            .isNullCall(true)
            .body(args -> Datum.timestamp(
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
                PRECISION
            ))
            .build()
    );

    private UtcNow() {
    }

    /**
     * Returns the immutable {@code utcnow} overloads.
     *
     * @return {@code utcnow} overloads
     */
    public static List<FnOverload> overloads() {
        return OVERLOADS;
    }
}
