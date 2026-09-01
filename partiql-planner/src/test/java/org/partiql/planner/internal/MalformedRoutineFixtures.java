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

package org.partiql.planner.internal;

import org.partiql.spi.catalog.Identifier;
import org.partiql.spi.catalog.Name;
import org.partiql.spi.catalog.RoutineBinding;
import org.partiql.spi.catalog.RoutineCatalog;
import org.partiql.spi.catalog.Session;
import org.partiql.spi.catalog.Table;
import org.partiql.spi.function.AggOverload;
import org.partiql.spi.function.Fn;
import org.partiql.spi.function.FnOverload;
import org.partiql.spi.function.RoutineOverloadSignature;
import org.partiql.spi.types.PType;

import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Java fixtures exercise invalid platform-type values that Kotlin cannot return directly.
 */
final class MalformedRoutineFixtures {

    private MalformedRoutineFixtures() {
    }

    static RoutineCatalog catalog(
            String name,
            Collection<RoutineBinding<FnOverload>> functions,
            Collection<RoutineBinding<AggOverload>> aggregations
    ) {
        return new RoutineCatalog() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public Table getTable(Session session, Name tableName) {
                return null;
            }

            @Override
            public Name resolveTable(Session session, Identifier identifier) {
                return null;
            }

            @Override
            public Collection<FnOverload> getFunctions(Session session, String routineName) {
                return Collections.emptyList();
            }

            @Override
            public Collection<AggOverload> getAggregations(Session session, String routineName) {
                return Collections.emptyList();
            }

            @Override
            public Collection<RoutineBinding<FnOverload>> resolveFunctions(
                    Session session,
                    Identifier identifier
            ) {
                return functions;
            }

            @Override
            public Collection<RoutineBinding<AggOverload>> resolveAggregations(
                    Session session,
                    Identifier identifier
            ) {
                return aggregations;
            }
        };
    }

    static RoutineCatalog catalogWithNullFunctions(String name) {
        return catalog(name, null, Collections.emptyList());
    }

    static RoutineCatalog catalogWithNullAggregations(String name) {
        return catalog(name, Collections.emptyList(), null);
    }

    static Collection<RoutineBinding<FnOverload>> nullBindingResults() {
        return Collections.singletonList(null);
    }

    static Collection<RoutineBinding<FnOverload>> throwingResults(RuntimeException failure) {
        return new AbstractCollection<RoutineBinding<FnOverload>>() {
            @Override
            public Iterator<RoutineBinding<FnOverload>> iterator() {
                throw failure;
            }

            @Override
            public int size() {
                return 1;
            }
        };
    }

    static RoutineBinding<FnOverload> bindingWithNullOverload(Name canonicalName) {
        return new RoutineBinding<>(canonicalName, Collections.singletonList(null));
    }

    static FnOverload overloadWithNullSignature() {
        return overloadWithSignature(null);
    }

    static FnOverload overloadThrowingSignature(Error failure) {
        return new FnOverload() {
            @Override
            public RoutineOverloadSignature getSignature() {
                throw failure;
            }

            @Override
            public Fn getInstance(PType[] args) {
                return null;
            }
        };
    }

    static FnOverload overloadWithSignature(RoutineOverloadSignature signature) {
        return new FnOverload() {
            @Override
            public RoutineOverloadSignature getSignature() {
                return signature;
            }

            @Override
            public Fn getInstance(PType[] args) {
                return null;
            }
        };
    }

    static RoutineOverloadSignature signatureWithNullName(List<PType> parameterTypes) {
        return new RoutineOverloadSignature(null, parameterTypes);
    }

    static RoutineOverloadSignature signatureWithNullParameters(String name) {
        return new RoutineOverloadSignature(name, null);
    }

    static RoutineOverloadSignature signatureWithNullParameter(String name) {
        return new RoutineOverloadSignature(name, Collections.singletonList(null));
    }

    static <T> RoutineBinding<T> bindingWithCanonicalParts(List<String> canonicalParts, T overload) {
        RoutineBinding<T> binding = new RoutineBinding<>(Name.of("echo"), Collections.singletonList(overload));
        setField(binding, "canonicalNameParts", canonicalParts);
        return binding;
    }

    static <T> RoutineBinding<T> bindingWithEmptyOverloads(Name canonicalName, T overload) {
        RoutineBinding<T> binding = new RoutineBinding<>(canonicalName, Collections.singletonList(overload));
        setField(binding, "overloads", Collections.emptyList());
        return binding;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = RoutineBinding.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
