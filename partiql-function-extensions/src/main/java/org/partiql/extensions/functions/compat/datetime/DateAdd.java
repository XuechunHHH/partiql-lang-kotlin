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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Compatibility overloads for adding years, months, days, hours, minutes, or seconds to a timestamp.
 *
 * <p>This preserves the behavior of
 * <a href="https://github.com/partiql/partiql-lang-kotlin/blob/v0.14.8/partiql-lang/src/main/kotlin/org/partiql/lang/eval/builtins/ScalarBuiltinsExt.kt#L143-L188">
 * PartiQL Lang Kotlin v0.14 {@code date_add}</a>.
 */
public final class DateAdd {
    private static final List<FnOverload> OVERLOADS = Collections.singletonList(
        new FnOverload.Builder("date_add")
            .addParameters(PType.string(), PType.integer(), PType.timestamp())
            .returns(PType.timestamp())
            .isNullCall(true)
            .body(DateAdd::add)
            .build()
    );

    private DateAdd() {
    }

    /**
     * Returns the immutable {@code date_add} overloads.
     *
     * @return {@code date_add} overloads
     */
    public static List<FnOverload> overloads() {
        return OVERLOADS;
    }

    private static Datum add(Datum[] args) {
        String part = args[0].getString().toLowerCase(Locale.ROOT);
        int quantity = args[1].getInt();
        LocalDateTime timestamp = args[2].getLocalDateTime();
        LocalDateTime result;

        switch (part) {
            case "year":
                result = timestamp.plusYears(quantity);
                break;
            case "month":
                result = timestamp.plusMonths(quantity);
                break;
            case "day":
                result = timestamp.plusDays(quantity);
                break;
            case "hour":
                result = timestamp.plusHours(quantity);
                break;
            case "minute":
                result = timestamp.plusMinutes(quantity);
                break;
            case "second":
                result = timestamp.plusSeconds(quantity);
                break;
            default:
                throw new IllegalArgumentException("Invalid datetime part for date_add: " + args[0].getString());
        }

        return Datum.timestamp(result, args[2].getType().getPrecision());
    }
}
