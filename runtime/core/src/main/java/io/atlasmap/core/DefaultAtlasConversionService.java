/**
 * Copyright (C) 2017 Red Hat, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atlasmap.core;

import static java.util.Objects.hash;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.atlasmap.api.AtlasConversionException;
import io.atlasmap.api.AtlasConversionService;
import io.atlasmap.api.AtlasConverter;
import io.atlasmap.spi.AtlasConversionInfo;
import io.atlasmap.v2.FieldType;

public class DefaultAtlasConversionService implements AtlasConversionService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAtlasConversionService.class);
    private static final Set<String> PRIMITIVE_CLASSNAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("boolean", "byte", "char", "double", "float", "int", "long", "short")));
    private static final Set<FieldType> PRIMITIVE_FIELDTYPES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(FieldType.BOOLEAN, FieldType.BYTE, FieldType.CHAR, FieldType.DECIMAL, FieldType.DOUBLE,
                    FieldType.FLOAT, FieldType.INTEGER, FieldType.LONG, FieldType.SHORT, FieldType.STRING)));
    private static final Set<String> BOXED_PRIMITIVE_CLASSNAMES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Double",
                    "java.lang.Float", "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.String")));

    private static DefaultAtlasConversionService instance = null;

    private Map<ConverterKey, ConverterMethodHolder> converterMethods = null;
    private Map<ConverterKey, ConverterMethodHolder> customConverterMethods = null;

    // Used as the lookup key in the converter methods map
    private class ConverterKey {
        private String sourceClassName;
        private String targetClassName;

        public ConverterKey(String sourceClassName, String targetClassName) {
            this.sourceClassName = sourceClassName;
            this.targetClassName = targetClassName;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj != null && obj instanceof ConverterKey) {
                ConverterKey s = (ConverterKey) obj;
                return sourceClassName.equals(s.sourceClassName) && targetClassName.equals(s.targetClassName);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return hash(sourceClassName, targetClassName);
        }
    }

    // used to hold converter and method for future reflection use
    private class ConverterMethodHolder {
        private AtlasConverter<?> converter;
        private Method method;
        private boolean staticMethod;
        private boolean containsFormat;

        public ConverterMethodHolder(AtlasConverter<?> converter, Method method, boolean staticMethod,
                boolean containsFormat) {
            this.converter = converter;
            this.method = method;
            this.staticMethod = staticMethod;
            this.containsFormat = containsFormat;
        }

        public AtlasConverter<?> getConverter() {
            return converter;
        }
    }

    private DefaultAtlasConversionService() {
    }

    public static DefaultAtlasConversionService getInstance() {
        if (instance == null) {
            synchronized (DefaultAtlasConversionService.class) {
                if (instance == null) {
                    instance = new DefaultAtlasConversionService();
                    instance.init();
                }
            }
        }
        return instance;
    }

    public static Set<String> listPrimitiveClassNames() {
        return PRIMITIVE_CLASSNAMES;
    }

    @Override
    public Optional<AtlasConverter<?>> findMatchingConverter(FieldType source, FieldType target) {

        // get the default types
        Class sourceClass = classFromFieldType(source);
        Class targetClass = classFromFieldType(target);

        if (sourceClass != null && targetClass != null) {
            return findMatchingConverter(sourceClass.getCanonicalName(), targetClass.getCanonicalName());
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AtlasConverter<?>> findMatchingConverter(String sourceClassName, String targetClassName) {
        ConverterKey converterKey = new ConverterKey(sourceClassName, targetClassName);
        if (customConverterMethods.containsKey(converterKey)) {
            return Optional.of(customConverterMethods.get(converterKey).getConverter());
        } else if (converterMethods.containsKey(converterKey)) {
            return Optional.of(converterMethods.get(converterKey).getConverter());
        } else {
            return Optional.empty();
        }
    }

    private void init() {
        loadConverters();
    }

    @SuppressWarnings("rawtypes")
    private void loadConverters() {
        ClassLoader classLoader = this.getClass().getClassLoader();
        final ServiceLoader<AtlasConverter> converterServiceLoader = ServiceLoader.load(AtlasConverter.class,
                classLoader);

        // used to load up methods first;
        Map<ConverterKey, ConverterMethodHolder> methodsLoadMap = new LinkedHashMap<>();
        Map<ConverterKey, ConverterMethodHolder> customMethodsLoadMap = new LinkedHashMap<>();

        for (final AtlasConverter<?> atlasConverter : converterServiceLoader) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Loading converter : " + atlasConverter.getClass().getCanonicalName());
            }

            boolean inbuiltConverter = atlasConverter.getClass().getPackage().getName().startsWith("io.atlasmap");

            Class<?> klass = atlasConverter.getClass();
            // collect all the specific conversion methods on the class
            while (klass != Object.class) {
                final List<Method> allMethods = new ArrayList<Method>(Arrays.asList(klass.getDeclaredMethods()));
                for (final Method method : allMethods) {
                    if (method.isAnnotationPresent(AtlasConversionInfo.class) && method.getParameters().length > 0) {
                        String sourceClassName = method.getParameters()[0].getType().getCanonicalName();
                        ConverterKey coordinate = new ConverterKey(sourceClassName,
                                method.getReturnType().getCanonicalName());
                        // if the method has three arguments and the last two as strings then they used
                        // as the format attributes
                        boolean containsFormat = false;
                        if (method.getParameters().length == 3 && method.getParameters()[1].getType() == String.class
                                && method.getParameters()[2].getType() == String.class) {
                            containsFormat = true;
                        }

                        boolean staticMethod = Modifier.isStatic(method.getModifiers());
                        ConverterMethodHolder methodHolder = new ConverterMethodHolder(atlasConverter, method,
                                staticMethod, containsFormat);
                        if (inbuiltConverter) {
                            if (!methodsLoadMap.containsKey(coordinate)) {
                                methodsLoadMap.put(coordinate, methodHolder);
                            } else {
                                LOG.warn("Converter between " + sourceClassName + " and "
                                        + method.getReturnType().getCanonicalName() + " aleady exists.");
                            }
                        } else {
                            if (!customMethodsLoadMap.containsKey(coordinate)) {
                                customMethodsLoadMap.put(coordinate, methodHolder);
                            } else {
                                LOG.warn("Custom converter between " + sourceClassName + " and "
                                        + method.getReturnType().getCanonicalName() + " aleady exists.");
                            }
                        }
                    }
                }
                // move to the upper class in the hierarchy in search for more methods
                klass = klass.getSuperclass();
            }

        }

        if (!methodsLoadMap.isEmpty()) {
            converterMethods = Collections.unmodifiableMap(methodsLoadMap);
        }
        if (!methodsLoadMap.isEmpty()) {
            customConverterMethods = Collections.unmodifiableMap(customMethodsLoadMap);
        }
    }

    private static <R> Predicate<R> not(Predicate<R> predicate) {
        return predicate.negate();
    }

    @Override
    public Object copyPrimitive(Object sourceValue) {

        if (sourceValue == null) {
            return null;
        }

        Class<?> clazz = sourceValue.getClass();
        if (clazz == null) {
            return clazz;
        } else if (boolean.class.getName().equals(clazz.getName())) {
            return Boolean.valueOf((boolean) sourceValue);
        } else if (Boolean.class.getName().equals(clazz.getName())) {
            return Boolean.valueOf((Boolean) sourceValue);
        } else if (byte.class.getName().equals(clazz.getName())) {
            return Byte.valueOf((byte) sourceValue);
        } else if (Byte.class.getName().equals(clazz.getName())) {
            return Byte.valueOf((Byte) sourceValue);
        } else if (char.class.getName().equals(clazz.getName())) {
            return Character.valueOf((char) sourceValue);
        } else if (Character.class.getName().equals(clazz.getName())) {
            return Character.valueOf((Character) sourceValue);
        } else if (double.class.getName().equals(clazz.getName())) {
            return Double.valueOf((double) sourceValue);
        } else if (Double.class.getName().equals(clazz.getName())) {
            return Double.valueOf((Double) sourceValue);
        } else if (float.class.getName().equals(clazz.getName())) {
            return Float.valueOf((float) sourceValue);
        } else if (Float.class.getName().equals(clazz.getName())) {
            return Float.valueOf((Float) sourceValue);
        } else if (int.class.getName().equals(clazz.getName())) {
            return Integer.valueOf((int) sourceValue);
        } else if (Integer.class.getName().equals(clazz.getName())) {
            return Integer.valueOf((Integer) sourceValue);
        } else if (long.class.getName().equals(clazz.getName())) {
            return Long.valueOf((long) sourceValue);
        } else if (Long.class.getName().equals(clazz.getName())) {
            return Long.valueOf((Long) sourceValue);
        } else if (short.class.getName().equals(clazz.getName())) {
            return Short.valueOf((short) sourceValue);
        } else if (Short.class.getName().equals(clazz.getName())) {
            return Short.valueOf((Short) sourceValue);
        }

        // can't count on java copy
        return sourceValue;
    }

    @Override
    public Object convertType(Object sourceValue, FieldType origSourceType, FieldType targetType)
            throws AtlasConversionException {

        if (origSourceType == null || targetType == null) {
            throw new AtlasConversionException("FieldTypes must be specified on convertType method.");
        }
        if (origSourceType.equals(targetType)) {
            return sourceValue;
        } else {
            return convertType(sourceValue, null, classFromFieldType(targetType), null);
        }
    }

    @Override
    public Object convertType(Object sourceValue, String sourceFormat, FieldType targetType, String targetFormat)
            throws AtlasConversionException {
        return convertType(sourceValue, sourceFormat, classFromFieldType(targetType), targetFormat);
    }

    @Override
    public Object convertType(Object sourceValue, String sourceFormat, Class targetType, String targetFormat)
            throws AtlasConversionException {

        if (sourceValue == null || targetType == null) {
            throw new AtlasConversionException("AutoConversion requires sourceValue and targetType to be specified");
        }

        if (sourceValue.getClass().equals(targetType)) {
            return sourceValue;
        }

        ConverterKey converterKey = new ConverterKey(sourceValue.getClass().getCanonicalName(),
                targetType.getCanonicalName());
        // use custom converter first
        ConverterMethodHolder methodHolder = customConverterMethods.get(converterKey);
        if (methodHolder == null) {
            // try the inbuilt defaults
            methodHolder = converterMethods.get(converterKey);
        }
        if (methodHolder != null) {
            try {
                Object target = methodHolder.staticMethod ? null : methodHolder.converter;
                if (methodHolder.containsFormat) {
                    return methodHolder.method.invoke(target, sourceValue, sourceFormat, targetFormat);
                } else {
                    return methodHolder.method.invoke(target, sourceValue);
                }
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new AtlasConversionException("Invoking type convertor failed: " + e);
            }
        } else {
            throw new AtlasConversionException("Type Conversion is not supported for sT="
                    + sourceValue.getClass().getCanonicalName() + " tT=" + targetType.getCanonicalName());
        }
    }

    public Object convertTypeOriginal(Object sourceValue, FieldType origSourceType, String sourceFormat,
            FieldType targetType, String targetFormat) throws AtlasConversionException {
        FieldType sourceType = null;

        if (origSourceType == null && sourceValue != null) {
            sourceType = fieldTypeFromClass(sourceValue.getClass());
        } else {
            sourceType = FieldType.fromValue(origSourceType.value());
        }

        if (sourceType == null && targetType == null) {
            throw new AtlasConversionException("AutoConversion requires sourceType and targetType be specified");
        }

        if (sourceType.equals(targetType)) {
            return sourceValue;
        }

        String targetClassname = classFromFieldType(targetType).getCanonicalName();
        ConverterKey conveterKey = new ConverterKey(sourceValue.getClass().getCanonicalName(), targetClassname);
        ConverterMethodHolder methodHolder = converterMethods.get(conveterKey);
        if (methodHolder != null) {
            try {
                Object target = methodHolder.staticMethod ? null : methodHolder.converter;
                if (methodHolder.containsFormat) {
                    return methodHolder.method.invoke(target, sourceValue, sourceFormat, targetFormat);
                } else {
                    return methodHolder.method.invoke(target, sourceValue);
                }
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new AtlasConversionException("Invoking type convertor failed: " + e);
            }
        } else {
            throw new AtlasConversionException(
                    "Type Conversion is not supported for sT=" + sourceType + " tT=" + targetType);
        }
    }

    @Override
    public Boolean isPrimitive(String className) {
        if (className == null) {
            return false;
        }
        if (PRIMITIVE_CLASSNAMES.contains(className)) {
            return true;
        }
        return false;
    }

    @Override
    public Boolean isPrimitive(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        if (PRIMITIVE_CLASSNAMES.contains(clazz.getCanonicalName())) {
            return true;
        }
        return false;
    }

    @Override
    public Boolean isPrimitive(FieldType fieldType) {
        if (fieldType == null) {
            return false;
        }
        return PRIMITIVE_FIELDTYPES.contains(fieldType);
    }

    @Override
    public Boolean isBoxedPrimitive(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return BOXED_PRIMITIVE_CLASSNAMES.contains(clazz.getCanonicalName());
    }

    @Override
    public Class<?> boxOrUnboxPrimitive(Class<?> clazz) {
        if (clazz == null) {
            return clazz;
        } else if (boolean.class.getName().equals(clazz.getName())) {
            return Boolean.class;
        } else if (Boolean.class.getName().equals(clazz.getName())) {
            return boolean.class;
        } else if (byte.class.getName().equals(clazz.getName())) {
            return Byte.class;
        } else if (Byte.class.getName().equals(clazz.getName())) {
            return byte.class;
        } else if (char.class.getName().equals(clazz.getName())) {
            return Character.class;
        } else if (Character.class.getName().equals(clazz.getName())) {
            return char.class;
        } else if (double.class.getName().equals(clazz.getName())) {
            return Double.class;
        } else if (Double.class.getName().equals(clazz.getName())) {
            return double.class;
        } else if (float.class.getName().equals(clazz.getName())) {
            return Float.class;
        } else if (Float.class.getName().equals(clazz.getName())) {
            return float.class;
        } else if (int.class.getName().equals(clazz.getName())) {
            return Integer.class;
        } else if (Integer.class.getName().equals(clazz.getName())) {
            return int.class;
        } else if (long.class.getName().equals(clazz.getName())) {
            return Long.class;
        } else if (Long.class.getName().equals(clazz.getName())) {
            return long.class;
        } else if (short.class.getName().equals(clazz.getName())) {
            return Short.class;
        } else if (Short.class.getName().equals(clazz.getName())) {
            return short.class;
        }
        return clazz;
    }

    @Override
    public FieldType fieldTypeFromClass(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }

        return fieldTypeFromClass(clazz.getName());
    }

    @Override
    public FieldType fieldTypeFromClass(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }

        switch (className) {
        case "boolean":
            return FieldType.BOOLEAN;
        case "java.lang.Boolean":
            return FieldType.BOOLEAN;
        case "byte":
            return FieldType.BYTE;
        case "java.lang.Byte":
            return FieldType.BYTE;
        case "char":
            return FieldType.CHAR;
        case "java.lang.Character":
            return FieldType.CHAR;
        case "double":
            return FieldType.DOUBLE;
        case "java.lang.Double":
            return FieldType.DOUBLE;
        case "float":
            return FieldType.FLOAT;
        case "java.lang.Float":
            return FieldType.FLOAT;
        case "int":
            return FieldType.INTEGER;
        case "java.lang.Integer":
            return FieldType.INTEGER;
        case "long":
            return FieldType.LONG;
        case "java.lang.Long":
            return FieldType.LONG;
        case "short":
            return FieldType.SHORT;
        case "java.lang.Short":
            return FieldType.SHORT;
        case "java.lang.String":
            return FieldType.STRING;
        case "java.time.Year":
        case "java.time.Month":
        case "java.time.YearMonth":
        case "java.time.MonthDay":
        case "java.time.LocalDate":
            return FieldType.DATE;
        case "java.time.LocalTime":
            return FieldType.TIME;
        case "java.time.LocalDateTime":
            return FieldType.DATE_TIME;
        case "java.sql.Date":
        case "java.util.Date":
        case "java.time.ZonedDateTime":
            return FieldType.DATE_TIME_TZ;
        default:
            return FieldType.COMPLEX;
        }
    }

    @Override
    public Class<?> classFromFieldType(FieldType fieldType) {
        if (fieldType == null) {
            return null;
        }

        switch (fieldType) {
        case BOOLEAN:
            return Boolean.class;
        case BYTE:
            return Byte.class;
        case CHAR:
            return java.lang.Character.class;
        case DOUBLE:
            return java.lang.Double.class;
        case FLOAT:
            return java.lang.Float.class;
        case INTEGER:
            return java.lang.Integer.class;
        case LONG:
            return java.lang.Long.class;
        case SHORT:
            return java.lang.Short.class;
        case STRING:
            return java.lang.String.class;
        case DATE:
            return java.time.LocalDate.class;
        case TIME:
            return java.time.LocalTime.class;
        case DATE_TIME:
            return java.time.LocalDateTime.class;
        case DATE_TZ:
        case TIME_TZ:
        case DATE_TIME_TZ:
            return java.time.ZonedDateTime.class;
        // TODO: need to fix the default return type for non-primitive
        default:
            return java.lang.Object.class;
        }
    }

}
