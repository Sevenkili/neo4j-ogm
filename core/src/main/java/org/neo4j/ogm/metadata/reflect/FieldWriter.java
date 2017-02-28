/*
 * Copyright (c)  [2011-2015] "Neo Technology" / "Graph Aware Ltd."
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices and license terms. Your use of the source code for these subcomponents is subject to the terms and conditions of the subcomponent's license, as noted in the LICENSE file.
 *
 *
 */

package org.neo4j.ogm.metadata.reflect;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.*;

import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.metadata.ClassInfo;
import org.neo4j.ogm.metadata.FieldInfo;
import org.neo4j.ogm.session.Utils;
import org.neo4j.ogm.utils.ClassUtils;

/**
 * @author Vince Bickers
 * @author Luanne Misquitta
 */
@Deprecated
public class FieldWriter {


    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static Object merge(Class<?> parameterType, Object newValues, Object[] currentValues, Class elementType) {
        if (currentValues != null) {
            return merge(parameterType, newValues, Arrays.asList(currentValues), elementType);
        } else {
            return merge(parameterType, newValues, new ArrayList(), elementType);
        }
    }


    /**
     * Merges the contents of <em>collection</em> with <em>hydrated</em> ensuring no duplicates and returns the result as an
     * instance of the given parameter type.
     *
     * @param parameterType The type of Iterable or array to return
     * @param newValues The objects to merge into a collection of the given parameter type, which may not necessarily be of a
     *        type assignable from <em>parameterType</em> already
     * @param currentValues The Iterable to merge into, which may be <code>null</code> if a new collection needs creating
     * @param elementType   The type of the element in the array or collection
     * @return The result of the merge, as an instance of the specified parameter type
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static Object merge(Class<?> parameterType, Object newValues, Collection currentValues, Class elementType) {

        //While we expect newValues to be an iterable, there are a couple of exceptions

        if (newValues != null) {
            //1. A primitive array cannot be cast directly to Iterable
            newValues = boxPrimitiveArray(newValues);

            //2. A char[] may come in as a String or an array of String[]
            newValues = stringToCharacterIterable(newValues, parameterType, elementType);
        }


        if (parameterType.isArray()) {
            Class type = parameterType.getComponentType();
            List<Object> objects = new ArrayList<>(union((Collection) newValues, currentValues, elementType));

            Object array = Array.newInstance(type, objects.size());
            for (int i = 0; i < objects.size(); i++) {
                Array.set(array, i, objects.get(i));
            }
            return array;
        }

        // create the desired type of collection and use it for the merge
        Collection newCollection = createCollection(parameterType, (Collection) newValues, currentValues, elementType);
        if (newCollection != null) {
            return newCollection;
        }

        // hydrated is unusable at this point so we can just set the other collection if it's compatible
        if (parameterType.isAssignableFrom(newValues.getClass())) {
            return newValues;
        }


        throw new RuntimeException("Unsupported: " + parameterType.getName());
    }

    private static Collection<?> createCollection(Class<?> parameterType, Collection collection, Collection hydrated, Class elementType) {
        if (Vector.class.isAssignableFrom(parameterType)) {
            return new Vector<>(union(collection, hydrated, elementType));
        }
        if (List.class.isAssignableFrom(parameterType)) {
            return new ArrayList<>(union(collection, hydrated, elementType));
        }
        if (SortedSet.class.isAssignableFrom(parameterType)) {
            return new TreeSet<>(union(collection, hydrated, elementType));
        }
        if (Set.class.isAssignableFrom(parameterType)) {
            return new HashSet<>(union(collection, hydrated, elementType));
        }
        return null;
    }

    public static Collection<Object> union(Collection collection, Collection hydrated, Class elementType) {
        if (collection == null) {
            return hydrated;
        }
        if (hydrated==null || hydrated.size() == 0) {
            Collection<Object> result = new ArrayList<>(collection.size());
            for (Object object : collection) {
                result.add(Utils.coerceTypes(elementType, object));
            }
            return result;
        }
        int resultSize = collection.size();
        resultSize += hydrated.size();
        Collection<Object> result = new LinkedHashSet<>(resultSize);

        if (hydrated.size() > collection.size()) {
            result.addAll(hydrated);
            addToCollection(collection, result, elementType);
        }
        else {
            addToCollection(collection, result, elementType);
            addToCollection(hydrated, result, elementType);
        }
        return result;
    }

    private static void addToCollection(Collection add, Collection<Object> addTo, Class elementType) {
        for (Object object : add) {
            addTo.add(Utils.coerceTypes(elementType, object));
        }
    }


    /**
     * Convert to an Iterable of Character if the value is a String
     * @param value the object, which may be a String, String[], Collection of String
     * @return List of Character if the value is a String, or the value unchanged
     */
    private static Object stringToCharacterIterable(Object value, Class parameterType, Class elementType) {
        boolean convertCharacters = false;
        if (value instanceof String) {
            char[] chars = ((String) value).toCharArray();
            List<Character> characters = new ArrayList<>(chars.length);
            for (char c : chars) {
                characters.add(c);
            }
            return characters;
        }

        if (parameterType.getComponentType() != null) {
            if (parameterType.getComponentType().equals(Character.class)) {
                convertCharacters = true;
            }
        }
        else {
            if (elementType == Character.class || elementType == char.class) {
                convertCharacters = true;
            }
        }

        if (value.getClass().isArray() &&  convertCharacters && value.getClass().getComponentType().equals(String.class)) {
            String[] strings = (String[]) value;
            List<Character> characters = new ArrayList<>(strings.length);
            for (String s : strings) {
                characters.add(s.toCharArray()[0]);
            }
            return characters;
        }

        if (value.getClass().isArray() && elementType == String.class) {
            String[] strings = (String[]) value;
            return Arrays.asList(strings);
        }
        return value;
    }

    private static Object boxPrimitiveArray(Object value) {
        if (value.getClass().isArray() && value.getClass().getComponentType().isPrimitive()) {
            switch (value.getClass().getComponentType().toString()) {
                case "int":
                    int[] intArray = (int[]) value;
                    List<Integer> boxedIntList = new ArrayList<>(intArray.length);
                    for (int i : intArray) {
                        boxedIntList.add(i);
                    }
                    return boxedIntList;

                case "float":
                    float[] floatArray = (float[]) value;
                    List<Float> boxedFloatList = new ArrayList<>(floatArray.length);
                    for (float f : floatArray) {
                        boxedFloatList.add(f);
                    }
                    return boxedFloatList;

                case "long":
                    long[] longArray = (long[]) value;
                    List<Long> boxedLongList = new ArrayList<>(longArray.length);
                    for (long l : longArray) {
                        boxedLongList.add(l);
                    }
                    return boxedLongList;

                case "double":
                    double[] dblArray = (double[]) value;
                    List<Double> boxedDoubleList = new ArrayList<>(dblArray.length);
                    for (double d : dblArray) {
                        boxedDoubleList.add(d);
                    }
                    return boxedDoubleList;

                case "boolean":
                    boolean[] booleanArray = (boolean[]) value;
                    List<Boolean> boxedBooleanList = new ArrayList<>(booleanArray.length);
                    for (boolean b : booleanArray) {
                        boxedBooleanList.add(b);
                    }
                    return boxedBooleanList;

                case "char":
                    char[] charArray = (char[]) value;
                    List<Character> boxedCharList = new ArrayList<>(charArray.length);
                    for (char c : charArray) {
                        boxedCharList.add(c);
                    }
                    return boxedCharList;
            }
        }
        return value;
    }


    private final FieldInfo fieldInfo;
    private final Field field;
    private final Class<?> fieldType;

    public FieldWriter(ClassInfo classInfo, FieldInfo fieldInfo) {
        this.fieldInfo = fieldInfo;
        this.field = classInfo.getField(fieldInfo);
        this.fieldType = this.field.getType();
    }

    public static void write(Field field, Object instance, Object value) {
        try {
            field.setAccessible(true);
            field.set(instance, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object read(Field field, Object instance) {
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public void write(Object instance, Object value) {

        if (fieldInfo.hasPropertyConverter()) {
            value = fieldInfo.getPropertyConverter().toEntityAttribute(value);
            FieldWriter.write(field, instance, value);
        } else {
            if (fieldInfo.isScalar()) {
                String descriptor = fieldInfo.getTypeDescriptor();
                value = Utils.coerceTypes(ClassUtils.getType(descriptor), value);
            }
            FieldWriter.write(field, instance, value);
        }
    }

    public Class<?> type() {
        Class convertedType = fieldInfo.convertedType();
        if (convertedType != null) {
            return convertedType;
        }
        return fieldType;
    }

    public String relationshipName() {
        return this.fieldInfo.relationship();
    }

    public String relationshipDirection() {
        return fieldInfo.relationshipDirection(Relationship.UNDIRECTED);
    }

    public boolean forScalar() {
        return !Iterable.class.isAssignableFrom(type()) && !type().isArray();
    }

    public String typeParameterDescriptor() {
        return fieldInfo.getTypeDescriptor();
    }
}
