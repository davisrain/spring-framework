/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils;

/**
 * Provides a quick way to access the attribute methods of an {@link Annotation}
 * with consistent ordering as well as a few useful utility methods.
 *
 * @author Phillip Webb
 * @since 5.2
 */
final class AttributeMethods {

	static final AttributeMethods NONE = new AttributeMethods(null, new Method[0]);


	private static final Map<Class<? extends Annotation>, AttributeMethods> cache =
			new ConcurrentReferenceHashMap<>();

	private static final Comparator<Method> methodComparator = (m1, m2) -> {
		if (m1 != null && m2 != null) {
			return m1.getName().compareTo(m2.getName());
		}
		return m1 != null ? -1 : 1;
	};


	@Nullable
	private final Class<? extends Annotation> annotationType;

	private final Method[] attributeMethods;

	private final boolean[] canThrowTypeNotPresentException;

	private final boolean hasDefaultValueMethod;

	private final boolean hasNestedAnnotation;


	private AttributeMethods(@Nullable Class<? extends Annotation> annotationType, Method[] attributeMethods) {
		this.annotationType = annotationType;
		this.attributeMethods = attributeMethods;
		// 根据attributeMethods的数量初始化一个boolean类型的数组
		this.canThrowTypeNotPresentException = new boolean[attributeMethods.length];
		boolean foundDefaultValueMethod = false;
		boolean foundNestedAnnotation = false;
		// 遍历属性方法数组
		for (int i = 0; i < attributeMethods.length; i++) {
			Method method = this.attributeMethods[i];
			// 获取到方法的返回类型
			Class<?> type = method.getReturnType();
			// 如果方法存在默认值
			if (method.getDefaultValue() != null) {
				// 将foundDefaultValueMethod标志置为true
				foundDefaultValueMethod = true;
			}
			// 如果返回值类型是注解或是注解数组
			if (type.isAnnotation() || (type.isArray() && type.getComponentType().isAnnotation())) {
				// 将foundNestedAnnotation标志置为true
				foundNestedAnnotation = true;
			}
			// 将方法accessible设为true
			ReflectionUtils.makeAccessible(method);
			// 如果返回值类型为Class.class 或者 Class[].class 或者 枚举类型，将canThrowTypeNotPresentException数组对应下标的值设为true
			this.canThrowTypeNotPresentException[i] = (type == Class.class || type == Class[].class || type.isEnum());
		}
		// 将上述两个标志置赋值给自身属性
		this.hasDefaultValueMethod = foundDefaultValueMethod;
		this.hasNestedAnnotation = foundNestedAnnotation;
	}


	/**
	 * Determine if this instance only contains a single attribute named
	 * {@code value}.
	 * @return {@code true} if there is only a value attribute
	 */
	boolean hasOnlyValueAttribute() {
		return (this.attributeMethods.length == 1 &&
				MergedAnnotation.VALUE.equals(this.attributeMethods[0].getName()));
	}


	/**
	 * Determine if values from the given annotation can be safely accessed without
	 * causing any {@link TypeNotPresentException TypeNotPresentExceptions}.
	 * @param annotation the annotation to check
	 * @return {@code true} if all values are present
	 * @see #validate(Annotation)
	 */
	boolean isValid(Annotation annotation) {
		// 判断annotation是annotationType的实例
		assertAnnotation(annotation);
		// 然后根据注解类持有的属性方法的个数循环
		for (int i = 0; i < size(); i++) {
			// 如果对应下标的canThrowTypeNotPresentException数组的值为true
			if (canThrowTypeNotPresentException(i)) {
				try {
					// 获取对应属性方法进行调用
					get(i).invoke(annotation);
				}
				// 如果抛出了异常，说明是非法的
				catch (Throwable ex) {
					return false;
				}
			}
		}
		// 否则，返回true
		return true;
	}

	/**
	 * Check if values from the given annotation can be safely accessed without causing
	 * any {@link TypeNotPresentException TypeNotPresentExceptions}. In particular,
	 * this method is designed to cover Google App Engine's late arrival of such
	 * exceptions for {@code Class} values (instead of the more typical early
	 * {@code Class.getAnnotations() failure}.
	 * @param annotation the annotation to validate
	 * @throws IllegalStateException if a declared {@code Class} attribute could not be read
	 * @see #isValid(Annotation)
	 */
	void validate(Annotation annotation) {
		assertAnnotation(annotation);
		// 遍历注解类型的所有属性方法
		for (int i = 0; i < size(); i++) {
			// 如果对应下标的属性方法可能会抛出TypeNotPresent异常，就尝试通过传入的注解实例去调用这个方法，
			// 检查是否真的会抛出异常
			if (canThrowTypeNotPresentException(i)) {
				try {
					get(i).invoke(annotation);
				}
				catch (Throwable ex) {
					throw new IllegalStateException("Could not obtain annotation attribute value for " +
							get(i).getName() + " declared on " + annotation.annotationType(), ex);
				}
			}
		}
	}

	private void assertAnnotation(Annotation annotation) {
		Assert.notNull(annotation, "Annotation must not be null");
		if (this.annotationType != null) {
			Assert.isInstanceOf(this.annotationType, annotation);
		}
	}

	/**
	 * Get the attribute with the specified name or {@code null} if no
	 * matching attribute exists.
	 * @param name the attribute name to find
	 * @return the attribute method or {@code null}
	 */
	@Nullable
	Method get(String name) {
		int index = indexOf(name);
		return index != -1 ? this.attributeMethods[index] : null;
	}

	/**
	 * Get the attribute at the specified index.
	 * @param index the index of the attribute to return
	 * @return the attribute method
	 * @throws IndexOutOfBoundsException if the index is out of range
	 * (<tt>index &lt; 0 || index &gt;= size()</tt>)
	 */
	Method get(int index) {
		return this.attributeMethods[index];
	}

	/**
	 * Determine if the attribute at the specified index could throw a
	 * {@link TypeNotPresentException} when accessed.
	 * @param index the index of the attribute to check
	 * @return {@code true} if the attribute can throw a
	 * {@link TypeNotPresentException}
	 */
	boolean canThrowTypeNotPresentException(int index) {
		return this.canThrowTypeNotPresentException[index];
	}

	/**
	 * Get the index of the attribute with the specified name, or {@code -1}
	 * if there is no attribute with the name.
	 * @param name the name to find
	 * @return the index of the attribute, or {@code -1}
	 */
	int indexOf(String name) {
		for (int i = 0; i < this.attributeMethods.length; i++) {
			if (this.attributeMethods[i].getName().equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the index of the specified attribute, or {@code -1} if the
	 * attribute is not in this collection.
	 * @param attribute the attribute to find
	 * @return the index of the attribute, or {@code -1}
	 */
	int indexOf(Method attribute) {
		for (int i = 0; i < this.attributeMethods.length; i++) {
			if (this.attributeMethods[i].equals(attribute)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the number of attributes in this collection.
	 * @return the number of attributes
	 */
	int size() {
		return this.attributeMethods.length;
	}

	/**
	 * Determine if at least one of the attribute methods has a default value.
	 * @return {@code true} if there is at least one attribute method with a default value
	 */
	boolean hasDefaultValueMethod() {
		return this.hasDefaultValueMethod;
	}

	/**
	 * Determine if at least one of the attribute methods is a nested annotation.
	 * @return {@code true} if there is at least one attribute method with a nested
	 * annotation type
	 */
	boolean hasNestedAnnotation() {
		return this.hasNestedAnnotation;
	}


	/**
	 * Get the attribute methods for the given annotation type.
	 * @param annotationType the annotation type
	 * @return the attribute methods for the annotation type
	 */
	static AttributeMethods forAnnotationType(@Nullable Class<? extends Annotation> annotationType) {
		// 如果annotationType为null的话，直接返回常量NONE
		if (annotationType == null) {
			return NONE;
		}
		// 否则从缓存中查询，如果不存在的话，执行compute方法计算出结果存入缓存之后返回
		return cache.computeIfAbsent(annotationType, AttributeMethods::compute);
	}

	private static AttributeMethods compute(Class<? extends Annotation> annotationType) {
		// 获取注解类中声明的方法
		Method[] methods = annotationType.getDeclaredMethods();
		int size = methods.length;
		// 遍历方法
		for (int i = 0; i < methods.length; i++) {
			// 如果不是属性方法的话，将其数组下标对应的元素置为null，并且将size-1
			if (!isAttributeMethod(methods[i])) {
				methods[i] = null;
				size--;
			}
		}
		// 如果size最终为0了，返回常量NONE
		if (size == 0) {
			return NONE;
		}
		// 将方法根据名称排序，如果数组中元素为null的话，排到后面
		Arrays.sort(methods, methodComparator);
		// 然后将数组按size大小复制过来
		Method[] attributeMethods = Arrays.copyOf(methods, size);
		// 根据annotationType和attributeMethods封装一个AttributeMethods对象返回
		return new AttributeMethods(annotationType, attributeMethods);
	}

	private static boolean isAttributeMethod(Method method) {
		// 方法参数为0且返回返回值不为void的才是属性方法
		return (method.getParameterCount() == 0 && method.getReturnType() != void.class);
	}

	/**
	 * Create a description for the given attribute method suitable to use in
	 * exception messages and logs.
	 * @param attribute the attribute to describe
	 * @return a description of the attribute
	 */
	static String describe(@Nullable Method attribute) {
		if (attribute == null) {
			return "(none)";
		}
		return describe(attribute.getDeclaringClass(), attribute.getName());
	}

	/**
	 * Create a description for the given attribute method suitable to use in
	 * exception messages and logs.
	 * @param annotationType the annotation type
	 * @param attributeName the attribute name
	 * @return a description of the attribute
	 */
	static String describe(@Nullable Class<?> annotationType, @Nullable String attributeName) {
		if (attributeName == null) {
			return "(none)";
		}
		String in = (annotationType != null ? " in annotation [" + annotationType.getName() + "]" : "");
		return "attribute '" + attributeName + "'" + in;
	}

}
