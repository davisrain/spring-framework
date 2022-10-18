package org.springframework.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.util.List;

class TypeTests<T extends List<T>> {

	static List<List<String>> parameterizedTypeField;

	static List<? extends List<String>> wildcardTypeField;

	static List<Object>[] genericArrayTypeField;


	@Test
	void testGetRawType() throws Exception {
		Field parameterizedTypeField = TypeTests.class.getDeclaredField("parameterizedTypeField");
		Type fieldType = parameterizedTypeField.getGenericType();
		ParameterizedType parameterizedType = (ParameterizedType) fieldType;
		Type rawType = parameterizedType.getRawType();
		System.out.println(rawType instanceof ParameterizedType);
		System.out.println(rawType instanceof Class);
	}

	@Test
	void testGetUpperBounds() throws Exception {
		Field wildcardTypeField = TypeTests.class.getDeclaredField("wildcardTypeField");
		Type fieldType = wildcardTypeField.getGenericType();
		ParameterizedType parameterizedType = (ParameterizedType) fieldType;
		Type actualTypeArgument = parameterizedType.getActualTypeArguments()[0];
		System.out.println(actualTypeArgument instanceof WildcardType);
		WildcardType wildcardType = (WildcardType) actualTypeArgument;
		Type upperBound = wildcardType.getUpperBounds()[0];
		System.out.println(upperBound instanceof ParameterizedType);
	}

	@Test
	void testGetBounds() throws Exception {
		TypeVariable<Class<TypeTests>> typeVariable = TypeTests.class.getTypeParameters()[0];
		Type bound = typeVariable.getBounds()[0];
		System.out.println(bound);
		System.out.println(bound instanceof Class);
		System.out.println(bound instanceof ParameterizedType);
	}

	@Test
	void testGetGenerics() throws Exception {
		Field genericArrayTypeField = TypeTests.class.getDeclaredField("genericArrayTypeField");
		ResolvableType resolvableType = ResolvableType.forType(genericArrayTypeField.getGenericType());
		ResolvableType[] generics = resolvableType.getGenerics();
		System.out.println(generics);
	}
}
