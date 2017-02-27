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

import java.util.*;

import org.neo4j.ogm.annotation.EndNode;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.StartNode;
import org.neo4j.ogm.context.DirectedRelationship;
import org.neo4j.ogm.context.DirectedRelationshipForType;
import org.neo4j.ogm.metadata.AnnotationInfo;
import org.neo4j.ogm.metadata.ClassInfo;
import org.neo4j.ogm.metadata.FieldInfo;
import org.neo4j.ogm.utils.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Determines how entities should be accessed in both reading and writing scenarios by looking up information from
 * {@link ClassInfo} in the following order.
 *
 * <ol>
 * <li>Annotated Field</li>
 * <li>Plain Field</li>
 * </ol>
 * The rationale is simply that we want annotations, whether on fields or on methods, to always take precedence, and we want to
 * use methods in preference to field access, because in many cases hydrating an object means more than just assigning values to
 * fields.
 *
 * @author Adam George
 * @author Luanne Misquitta
 */
public class EntityAccessManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityAccessManager.class);

    //TODO make these LRU caches with configurable size
    private static Map<ClassInfo, Map<DirectedRelationship,FieldReader>> relationalReaderCache = new HashMap<>();
    private static Map<ClassInfo, Map<DirectedRelationshipForType,FieldWriter>> relationalWriterCache = new HashMap<>();
    private static Map<ClassInfo, Map<DirectedRelationshipForType,FieldWriter>> iterableWriterCache = new HashMap<>();
    private static Map<ClassInfo, Map<DirectedRelationshipForType,FieldReader>> iterableReaderCache = new HashMap<>();
    private static Map<ClassInfo, Map<Class, FieldWriter>> relationshipEntityWriterCache = new HashMap<>();
    private static Map<ClassInfo, Map<String, FieldWriter>> propertyWriterCache = new HashMap<>();
    private static Map<ClassInfo, Map<String, FieldReader>> propertyReaderCache = new HashMap<>();
    private static Map<ClassInfo, Collection<FieldReader>> propertyReaders = new HashMap<>();
    private static Map<ClassInfo, FieldReader> identityPropertyReaderCache = new HashMap<>();
    private static Map<ClassInfo, Collection<FieldReader>> relationalReaders = new HashMap<>();

    private static final boolean STRICT_MODE = true; //strict mode for matching readers and writers, will only look for explicit annotations
    private static final boolean INFERRED_MODE = false; //inferred mode for matching readers and writers, will infer the relationship type from the getter/setter

    /**
     * Returns a FieldWriter for a property declared by a ClassInfo
     *
     * @param classInfo A ClassInfo declaring the property
     * @param propertyName the property name of the property in the graph
     * @return A FieldWriter, or none if not found
     */
    public static FieldWriter getPropertyWriter(final ClassInfo classInfo, String propertyName) {
        if(!propertyWriterCache.containsKey(classInfo)) {
            propertyWriterCache.put(classInfo, new HashMap<>());
        }
        Map<String, FieldWriter> entityAccessMap = propertyWriterCache.get(classInfo);
        if(entityAccessMap.containsKey(propertyName)) {
            return propertyWriterCache.get(classInfo).get(propertyName);
        }

        FieldWriter propertyWriter = determinePropertyAccessor(classInfo, propertyName, (AccessorFactory<FieldWriter>) fieldInfo -> new FieldWriter(classInfo, fieldInfo));

        propertyWriterCache.get(classInfo).put(propertyName, propertyWriter);
        return propertyWriter;
    }

    /**
     * Returns a FieldReader for the property of an object in the graph
     *
     * @param classInfo A ClassInfo declaring the property
     * @param propertyName the property name of the property in the graph
     * @return A FieldReader, or none if not found
     */
    public static FieldReader getPropertyReader(final ClassInfo classInfo, String propertyName) {

        if(!propertyReaderCache.containsKey(classInfo)) {
            propertyReaderCache.put(classInfo, new HashMap<>());
        }
        if(propertyReaderCache.get(classInfo).containsKey(propertyName)) {
            return propertyReaderCache.get(classInfo).get(propertyName);
        }

        FieldReader propertyReader =  determinePropertyAccessor(classInfo, propertyName, (AccessorFactory<FieldReader>) fieldInfo -> new FieldReader(classInfo, fieldInfo));

        propertyReaderCache.get(classInfo).put(propertyName, propertyReader);
        return propertyReader;
    }

    /**
     * Returns a FieldWriter for a scalar value represented as a relationship in the graph (i.e. not a primitive property)
     *
     * @param classInfo the ClassInfo (or a superclass thereof) declaring the relationship
     * @param relationshipType the name of the relationship as it is in the graph
     * @param relationshipDirection the direction of the relationship as it is in the graph
     * @param scalarValue an Object whose class the relationship is defined for
     * @return a valid FieldWriter or null if none is found
     */
    public static FieldWriter getRelationalWriter(ClassInfo classInfo, String relationshipType, String relationshipDirection, Object scalarValue) {
        return getRelationalWriter(classInfo, relationshipType, relationshipDirection, scalarValue.getClass());
    }

    /**
     * Returns a FieldWriter for a scalar type on a ClassInfo that is not a primitive graph property
     *
     * @param classInfo the ClassInfo (or a superclass thereof) declaring the relationship
     * @param relationshipType the name of the relationship as it is in the graph
     * @param relationshipDirection the direction of the relationship as it is in the graph
     * @param objectType the class the relationship is defined for
     * @return a valid FieldWriter or null if none is found
     */
    public static FieldWriter getRelationalWriter(ClassInfo classInfo, String relationshipType, String relationshipDirection, Class<?> objectType) {

        if (!relationalWriterCache.containsKey(classInfo)) {
            relationalWriterCache.put(classInfo, new HashMap<>());
        }

        DirectedRelationshipForType directedRelationship = new DirectedRelationshipForType(relationshipType, relationshipDirection, objectType);
        if (relationalWriterCache.get(classInfo).containsKey(directedRelationship)) {
            return relationalWriterCache.get(classInfo).get(directedRelationship);
        }

        ClassInfo lookupClassInfo = classInfo;

        while (classInfo != null) {

            // 1st, try to find a scalar or vector field explicitly annotated as the neo4j relationship type and direction
            for (FieldInfo fieldInfo : classInfo.candidateRelationshipFields(relationshipType, relationshipDirection, STRICT_MODE)) {
                if (fieldInfo != null && !fieldInfo.getAnnotations().isEmpty()) {
                    if (fieldInfo.isTypeOf(objectType) ||
                            fieldInfo.isParameterisedTypeOf(objectType) ||
                            fieldInfo.isArrayOf(objectType)) {
                        FieldWriter fieldWriter = new FieldWriter(lookupClassInfo, fieldInfo);
                        relationalWriterCache.get(lookupClassInfo).put(directedRelationship, fieldWriter);
                        return fieldWriter;
                    }
                }
            }

            //If the direction is INCOMING, then the annotation should have been present and we should have found a match already.
            //If it's outgoing, then proceed to find other matches
            if (!relationshipDirection.equals(Relationship.INCOMING)) {

                // 3rd, try to find a scalar or vector field annotated as the neo4j relationship type and direction, allowing for implied relationships
                for (FieldInfo fieldInfo : classInfo.candidateRelationshipFields(relationshipType, relationshipDirection, INFERRED_MODE)) {
                    if (fieldInfo != null && !fieldInfo.getAnnotations().isEmpty()) {
                        if (fieldInfo.isTypeOf(objectType) ||
                                fieldInfo.isParameterisedTypeOf(objectType) ||
                                fieldInfo.isArrayOf(objectType)) {
                            FieldWriter fieldWriter = new FieldWriter(lookupClassInfo, fieldInfo);
                            relationalWriterCache.get(lookupClassInfo).put(directedRelationship, fieldWriter);
                            return fieldWriter;
                        }
                    }
                }

                // 4th, try to find a "XYZ" field name where XYZ is derived from the relationship type
                for (FieldInfo fieldInfo : classInfo.candidateRelationshipFields(relationshipType, relationshipDirection, INFERRED_MODE)) {
                    if (fieldInfo != null) {
                        if (fieldInfo.isTypeOf(objectType) ||
                                fieldInfo.isParameterisedTypeOf(objectType) ||
                                fieldInfo.isArrayOf(objectType)) {
                            FieldWriter fieldWriter = new FieldWriter(lookupClassInfo, fieldInfo);
                            relationalWriterCache.get(lookupClassInfo).put(directedRelationship, fieldWriter);
                            return fieldWriter;
                        }
                    }
                }

                // 8th, try to find a unique field that has the same type as the parameter
                List<FieldInfo> fieldInfos = classInfo.findFields(objectType);
                if (fieldInfos.size() == 1) {
                    FieldInfo candidateFieldInfo = fieldInfos.iterator().next();
                    if (!candidateFieldInfo.relationshipDirection(Relationship.UNDIRECTED).equals(Relationship.INCOMING)) {
                        FieldWriter fieldWriter = new FieldWriter(lookupClassInfo, candidateFieldInfo);
                        relationalWriterCache.get(lookupClassInfo).put(directedRelationship, fieldWriter);
                        return fieldWriter;
                    }
                }
            }
            // walk up the object hierarchy
            classInfo = classInfo.directSuperclass();
        }
        relationalWriterCache.get(lookupClassInfo).put(directedRelationship, null);
        return null;
    }

    /**
     * Returns a FieldReader for a scalar type definition on a ClassInfo that is not a primitive graph property
     * @param classInfo A ClassInfo declaring the type definition
     * @param relationshipType The name of the relationship in the graph
     * @param relationshipDirection The direction of the relationship in the graph
     * @return A FieldReader or null if none exists
     */
    public static FieldReader getRelationalReader(ClassInfo classInfo, String relationshipType, String relationshipDirection) {

        if(!relationalReaderCache.containsKey(classInfo)) {
            relationalReaderCache.put(classInfo, new HashMap<>());
        }

        DirectedRelationship directedRelationship = new DirectedRelationship(relationshipType,relationshipDirection);
        if(relationalReaderCache.get(classInfo).containsKey(directedRelationship)) {
            return relationalReaderCache.get(classInfo).get(directedRelationship);
        }

        ClassInfo lookupClassInfo = classInfo;

        while (classInfo != null) {
            // 1st, try to find a field explicitly annotated with the neo4j relationship type and direction
            FieldInfo fieldInfo = classInfo.relationshipField(relationshipType, relationshipDirection, STRICT_MODE);
            if (fieldInfo != null && !fieldInfo.getAnnotations().isEmpty()) {
                FieldReader fieldReader = new FieldReader(lookupClassInfo, fieldInfo);
                relationalReaderCache.get(lookupClassInfo).put(directedRelationship, fieldReader);
                return fieldReader;
            }

            //If the direction is INCOMING, then the annotation should have been present and we should have found a match already.
            //If it's outgoing, then proceed to find other matches
            if (!relationshipDirection.equals(Relationship.INCOMING)) {

                // 3rd, try to find a field  annotated with the neo4j relationship type and direction, allowing for implied relationships
                fieldInfo = classInfo.relationshipField(relationshipType, relationshipDirection, INFERRED_MODE);
                if (fieldInfo != null && !fieldInfo.getAnnotations().isEmpty()) {
                    FieldReader fieldReader = new FieldReader(lookupClassInfo, fieldInfo);
                    relationalReaderCache.get(lookupClassInfo).put(directedRelationship, fieldReader);
                    return fieldReader;
                }

                // 4th, try to find a "XYZ" field name where XYZ is derived from the relationship type
                if (fieldInfo != null) {
                    FieldReader fieldReader = new FieldReader(classInfo, fieldInfo);
                    relationalReaderCache.get(lookupClassInfo).put(directedRelationship, fieldReader);
                    return fieldReader;
                }
            }
            classInfo = classInfo.directSuperclass();
        }
        relationalReaderCache.get(lookupClassInfo).put(directedRelationship, null);
        return null;
    }

    /**
     * Returns all the FieldReader instances for this ClassInfo
     * @param classInfo The ClassInfo whose PropertyReaders we want
     * @return a Collection of FieldReader instances which will be empty if no primitive properties are defined by the ClassInfo
     */
    public static Collection<FieldReader> getPropertyReaders(ClassInfo classInfo) {
        // do we care about "implicit" fields?  i.e., setX/getX with no matching X field
        if(propertyReaders.containsKey(classInfo)) {
            return propertyReaders.get(classInfo);
        }
        Collection<FieldReader> readers = new ArrayList<>();
        for (FieldInfo fieldInfo : classInfo.propertyFields()) {
            readers.add(new FieldReader(classInfo, fieldInfo)); //otherwise use the field
        }
        propertyReaders.put(classInfo, readers);
        return readers;
    }

    /**
     * Returns all the FieldReader instances for this ClassInfo
     * @param classInfo The ClassInfo whose RelationalReaders we want
     * @return a Collection of FieldReader instances which will be empty if no relationships are defined by the ClassInfo
     */
    public static Collection<FieldReader> getRelationalReaders(ClassInfo classInfo) {
        if(relationalReaders.containsKey(classInfo)) {
            return relationalReaders.get(classInfo);
        }
        Collection<FieldReader> readers = new ArrayList<>();

        for (FieldInfo fieldInfo : classInfo.relationshipFields()) {
            readers.add(new FieldReader(classInfo, fieldInfo));
        }
        relationalReaders.put(classInfo, readers);
        return readers;
    }

    /**
     * Returns an FieldWriter for an iterable of a non-primitive scalar type defined by a ClassInfo
     *
     * @param classInfo the ClassInfo (or a superclass thereof) declaring the iterable relationship
     * @param relationshipType the name of the relationship as it is in the graph
     * @param relationshipDirection the direction of the relationship as it is in the graph
     * @param parameterType the type that will be iterated over
     * @return a valid FieldWriter or null if none is found
     */
    public static FieldWriter getIterableWriter(ClassInfo classInfo, Class<?> parameterType, String relationshipType, String relationshipDirection) {
        if(!iterableWriterCache.containsKey(classInfo)) {
            iterableWriterCache.put(classInfo, new HashMap<>());
        }
        DirectedRelationshipForType directedRelationshipForType = new DirectedRelationshipForType(relationshipType,relationshipDirection, parameterType);
        if(iterableWriterCache.get(classInfo).containsKey(directedRelationshipForType)) {
            return iterableWriterCache.get(classInfo).get(directedRelationshipForType);
        }

        ClassInfo lookupClassInfo = classInfo;

        while (classInfo != null) {

            //1st find a field annotated with type and direction
            FieldInfo fieldInfo = getIterableFieldInfo(classInfo, parameterType, relationshipType, relationshipDirection, STRICT_MODE);
            if (fieldInfo != null) {
                FieldWriter fieldWriter = new FieldWriter(lookupClassInfo, fieldInfo);
                cacheIterableFieldWriter(lookupClassInfo, parameterType, relationshipType, relationshipDirection, directedRelationshipForType, fieldInfo, fieldWriter);
                return fieldWriter;
            }

            //If relationshipDirection=INCOMING, we should have found an annotated field already

            if (!relationshipDirection.equals(Relationship.INCOMING)) {

                //3rd, find a field with implied type and direction
                fieldInfo = getIterableFieldInfo(classInfo, parameterType, relationshipType, relationshipDirection, INFERRED_MODE);
                if (fieldInfo != null) {
                    FieldWriter fieldWriter = new FieldWriter(lookupClassInfo, fieldInfo);
                    cacheIterableFieldWriter(lookupClassInfo, parameterType, relationshipType, relationshipDirection, directedRelationshipForType, fieldInfo, fieldWriter);
                    return fieldWriter;
                }
            }
            classInfo = classInfo.directSuperclass();
        }
        iterableWriterCache.get(lookupClassInfo).put(directedRelationshipForType, null);
        return null;
    }

    /**
     * Returns a FieldReader for an iterable of a non-primitive scalar type defined by a ClassInfo
     *
     * @param classInfo the ClassInfo (or a superclass thereof) declaring the iterable relationship
     * @param relationshipType the name of the relationship as it is in the graph
     * @param relationshipDirection the direction of the relationship as it is in the graph
     * @param parameterType the type that will be iterated over
     * @return a valid FieldReader or null if none is found
     */
    public static FieldReader getIterableReader(ClassInfo classInfo, Class<?> parameterType, String relationshipType, String relationshipDirection) {
        if(!iterableReaderCache.containsKey(classInfo)) {
            iterableReaderCache.put(classInfo, new HashMap<>());
        }
        DirectedRelationshipForType directedRelationshipForType = new DirectedRelationshipForType(relationshipType,relationshipDirection, parameterType);
        if(iterableReaderCache.get(classInfo).containsKey(directedRelationshipForType)) {
            return iterableReaderCache.get(classInfo).get(directedRelationshipForType);
        }

        ClassInfo lookupClassInfo = classInfo;

        while (classInfo != null) {

            //1st find a field annotated with type and direction
            FieldInfo fieldInfo = getIterableFieldInfo(classInfo, parameterType, relationshipType, relationshipDirection, STRICT_MODE);
            if (fieldInfo != null) {
                FieldReader fieldReader = new FieldReader(lookupClassInfo, fieldInfo);
                iterableReaderCache.get(lookupClassInfo).put(directedRelationshipForType, fieldReader);
                return fieldReader;
            }

            //If relationshipDirection=INCOMING, we should have found an annotated field already

            if (!relationshipDirection.equals(Relationship.INCOMING)) {

                //3rd find a field with implied type and direction
                fieldInfo = getIterableFieldInfo(classInfo, parameterType, relationshipType, relationshipDirection, INFERRED_MODE);
                if (fieldInfo != null) {
                    FieldReader fieldReader = new FieldReader(lookupClassInfo, fieldInfo);
                    iterableReaderCache.get(lookupClassInfo).put(directedRelationshipForType, fieldReader);
                    return fieldReader;
                }
            }
            classInfo = classInfo.directSuperclass();
        }
        iterableReaderCache.get(lookupClassInfo).put(directedRelationshipForType, null);
        return null;
    }

    /**
     * Return the FieldReader for a ClassInfo's identity property
     * @param classInfo The ClassInfo declaring the identity property
     * @return A FieldReader, or null if not found
     */
    public static FieldReader getIdentityPropertyReader(ClassInfo classInfo) {
        FieldReader propertyReader = identityPropertyReaderCache.get(classInfo);
        if (propertyReader != null) {
            return propertyReader;
        }
        propertyReader = new FieldReader(classInfo, classInfo.identityField());
        identityPropertyReaderCache.put(classInfo, propertyReader);
        return propertyReader;
    }

    /**
     * Return a FieldReader for the EndNode of a RelationshipEntity
     * @param relationshipEntityClassInfo the ClassInfo representing the RelationshipEntity
     * @return a FieldReader for the field annotated as the EndNode, or none if not found
     */
    // TODO extend for methods
    public static FieldReader getEndNodeReader(ClassInfo relationshipEntityClassInfo) {
        for (FieldInfo fieldInfo : relationshipEntityClassInfo.relationshipFields()) {
            if (fieldInfo.getAnnotations().get(EndNode.class) != null) {
                return new FieldReader(relationshipEntityClassInfo, fieldInfo);
            }
        }
        LOGGER.warn("Failed to find an @EndNode on {}", relationshipEntityClassInfo);
        return null;
    }

    /**
     * Return a FieldReader for the StartNode of a RelationshipEntity
     * @param relationshipEntityClassInfo the ClassInfo representing the RelationshipEntity
     * @return a FieldReader for the field annotated as the StartNode, or none if not found
     */
    // TODO: extend for methods
    public static FieldReader getStartNodeReader(ClassInfo relationshipEntityClassInfo) {
        for (FieldInfo fieldInfo : relationshipEntityClassInfo.relationshipFields()) {
            if (fieldInfo.getAnnotations().get(StartNode.class) != null) {
                return new FieldReader(relationshipEntityClassInfo, fieldInfo);
            }
        }
        LOGGER.warn("Failed to find an @StartNode on {}", relationshipEntityClassInfo);
        return null;
    }

    @Deprecated
    // TODO replace with getStartNodeWriter() and getEndNodeWriter()
    public static FieldWriter getStartOrEndNodeWriter(ClassInfo classInfo, Class entityAnnotation) {
        if (entityAnnotation.getName() == null) {
            throw new RuntimeException(entityAnnotation.getSimpleName() + " is not defined on " + classInfo.name());
        }

        relationshipEntityWriterCache.computeIfAbsent(classInfo, k -> new HashMap<>());
        if (relationshipEntityWriterCache.get(classInfo).containsKey(entityAnnotation)) {
            return relationshipEntityWriterCache.get(classInfo).get(entityAnnotation);
        }

        //Find annotated field
        FieldInfo field = null;
        for(FieldInfo fieldInfo : classInfo.relationshipFields()) {
            if(fieldInfo.getAnnotations().get(entityAnnotation.getName()) != null) {
                field = fieldInfo;
                break;
            }
        }
        if(field != null) {
            //Otherwise use the field
            FieldWriter fieldWriter =  new FieldWriter(classInfo,field);
            relationshipEntityWriterCache.get(classInfo).put(entityAnnotation,fieldWriter);
            return fieldWriter;
        }
        relationshipEntityWriterCache.get(classInfo).put(entityAnnotation,null);
        return null;
    }


    // TODO: lookup via classinfo hierarchy
    private static FieldInfo getIterableFieldInfo(ClassInfo classInfo, Class<?> parameterType, String relationshipType, String relationshipDirection, boolean strict) {
        List<FieldInfo> fieldInfos = classInfo.findIterableFields(parameterType, relationshipType, relationshipDirection, strict);
        if(fieldInfos.size() == 0) {
            if(!strict) {
                fieldInfos = classInfo.findIterableFields(parameterType);
            }
        }
        if (fieldInfos.size() == 1) {
            FieldInfo candidateFieldInfo = fieldInfos.iterator().next();
            if (candidateFieldInfo.hasAnnotation(Relationship.class)) {
                AnnotationInfo relationshipAnnotation = candidateFieldInfo.getAnnotations().get(Relationship.class);
                if(!relationshipType.equals(relationshipAnnotation.get(Relationship.TYPE, null))) {
                    return null;
                }
            }
            //If the relationshipDirection is incoming and the candidateFieldInfo is also incoming or undirected
            if(relationshipDirection.equals(Relationship.INCOMING) &&
                    (candidateFieldInfo.relationshipDirection(Relationship.OUTGOING).equals(Relationship.INCOMING)) ||
                    (candidateFieldInfo.relationshipDirection(Relationship.OUTGOING).equals(Relationship.UNDIRECTED))) {
                return candidateFieldInfo;
            }
            //If the relationshipDirection is not incoming and the candidateFieldInfo is not incoming
            if(!relationshipDirection.equals(Relationship.INCOMING) && !candidateFieldInfo.relationshipDirection(Relationship.OUTGOING).equals(Relationship.INCOMING)) {
                return candidateFieldInfo;
            }
        }

        if (fieldInfos.size() > 0) {
            LOGGER.warn("Cannot map iterable of {} to instance of {}. More than one potential matching field found.",
                    parameterType, classInfo.name());
        }

        return null;
    }

    private static void cacheIterableFieldWriter(ClassInfo classInfo, Class<?> parameterType, String relationshipType, String relationshipDirection, DirectedRelationshipForType directedRelationshipForType, FieldInfo fieldInfo, FieldWriter fieldWriter) {
        if(fieldInfo.isParameterisedTypeOf(parameterType)) {
            //Cache the writer for the superclass used in the type param
            directedRelationshipForType = new DirectedRelationshipForType(relationshipType,relationshipDirection, ClassUtils.getType(fieldInfo.getTypeDescriptor()));
        }
        iterableWriterCache.get(classInfo).put(directedRelationshipForType, fieldWriter);
    }

    // TODO: enable lookup via classinfo hierarchy
    private static <T> T determinePropertyAccessor(ClassInfo classInfo, String propertyName, AccessorFactory<T> factory) {

        // fall back to the field if method cannot be found
        FieldInfo labelField = classInfo.labelFieldOrNull();
        if (labelField != null && labelField.getName().equals(propertyName)) {
            return factory.makeFieldAccessor(labelField);
        }
        FieldInfo fieldInfo = classInfo.propertyField(propertyName);
        if (fieldInfo != null) {
            return factory.makeFieldAccessor(fieldInfo);
        }
        return null;
    }

    /** Used internally to hide differences in object construction from strategy algorithm. */
    private interface AccessorFactory<T> {
        T makeFieldAccessor(FieldInfo fieldInfo);
    }
}
