package org.evosuite.testcase.factories.importing;

import com.github.javaparser.resolution.types.*;

import java.lang.reflect.*;

import org.evosuite.TestGenerationContext;

public class ResolvedTypeToReflectTypeConverter {
	public static Class<?> toReflectType(ResolvedType resolvedType) throws ClassNotFoundException {
		if (resolvedType.isPrimitive()) {
			return mapPrimitive(resolvedType.asPrimitive());
		} else if (resolvedType.isArray()) {
			return mapArray(resolvedType.asArrayType());
		} else if (resolvedType.isReferenceType()) {
			return mapReference(resolvedType.asReferenceType());
		} else if (resolvedType.isTypeVariable()) {
			// Generics type variables — fallback: Object
			return Object.class;
		} else if (resolvedType.isWildcard()) {
			// Wildcard — fallback: Object
			return Object.class;
		} else {
			throw new UnsupportedOperationException("Type not supported: " + resolvedType.describe());
		}
	}

	private static Class<?> mapPrimitive(ResolvedPrimitiveType primitiveType) {
		switch (primitiveType.getBoxTypeQName()) {
		case "java.lang.Boolean":
			return boolean.class;
		case "java.lang.Byte":
			return byte.class;
		case "java.lang.Character":
			return char.class;
		case "java.lang.Double":
			return double.class;
		case "java.lang.Float":
			return float.class;
		case "java.lang.Integer":
			return int.class;
		case "java.lang.Long":
			return long.class;
		case "java.lang.Short":
			return short.class;
		default:
			throw new IllegalArgumentException("Unknown primitive: " + primitiveType.describe());
		}
	}

	private static Class<?> mapArray(ResolvedArrayType arrayType) throws ClassNotFoundException {
		Type componentType = toReflectType(arrayType.getComponentType());
		if (componentType instanceof Class) {
			return Array.newInstance((Class<?>) componentType, 0).getClass();
		} else {
			// Evosuite handles only classes: fallback Object[].class
			return Object[].class;
		}
	}

	private static Class<?> mapReference(ResolvedReferenceType referenceType) throws ClassNotFoundException {
		String qualifiedName = referenceType.getQualifiedName();
		return TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(qualifiedName);
	}
}
