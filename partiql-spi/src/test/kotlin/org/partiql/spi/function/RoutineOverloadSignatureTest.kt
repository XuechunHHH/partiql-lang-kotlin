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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.types.PType

class RoutineOverloadSignatureTest {

    @Test
    fun copiesParameterTypes() {
        val parameterTypes = mutableListOf(PType.string())
        val signature = RoutineOverloadSignature("tokenize", parameterTypes)

        parameterTypes += PType.dynamic()

        assertEquals(listOf(PType.string()), signature.parameterTypes)
    }

    @Test
    fun parameterTypesAreJavaUnmodifiable() {
        val signature = RoutineOverloadSignature("tokenize", listOf(PType.string()))

        assertThrows<UnsupportedOperationException> {
            signature.parameterTypes.add(PType.dynamic())
        }
    }
}
