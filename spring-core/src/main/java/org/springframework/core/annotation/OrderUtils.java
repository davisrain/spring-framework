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

import java.lang.reflect.AnnotatedElement;
import java.util.Map;

import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * General utility for determining the order of an object based on its type declaration.
 * Handles Spring's {@link Order} annotation as well as {@link javax.annotation.Priority}.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @since 4.1
 * @see Order
 * @see javax.annotation.Priority
 */
public abstract class OrderUtils {

	/** Cache marker for a non-annotated Class. */
	private static final Object NOT_ANNOTATED = new Object();

	private static final String JAVAX_PRIORITY_ANNOTATION = "javax.annotation.Priority";

	/** Cache for @Order value (or NOT_ANNOTATED marker) per Class. */
	private static final Map<AnnotatedElement, Object> orderCache = new ConcurrentReferenceHashMap<>(64);


	/**
	 * Return the order on the specified {@code type}, or the specified
	 * default value if none can be found.
	 * <p>Takes care of {@link Order @Order} and {@code @javax.annotation.Priority}.
	 * @param type the type to handle
	 * @return the priority value, or the specified default order if none can be found
	 * @since 5.0
	 * @see #getPriority(Class)
	 */
	public static int getOrder(Class<?> type, int defaultOrder) {
		Integer order = getOrder(type);
		return (order != null ? order : defaultOrder);
	}

	/**
	 * Return the order on the specified {@code type}, or the specified
	 * default value if none can be found.
	 * <p>Takes care of {@link Order @Order} and {@code @javax.annotation.Priority}.
	 * @param type the type to handle
	 * @return the priority value, or the specified default order if none can be found
	 * @see #getPriority(Class)
	 */
	@Nullable
	public static Integer getOrder(Class<?> type, @Nullable Integer defaultOrder) {
		Integer order = getOrder(type);
		return (order != null ? order : defaultOrder);
	}

	/**
	 * Return the order on the specified {@code type}.
	 * <p>Takes care of {@link Order @Order} and {@code @javax.annotation.Priority}.
	 * @param type the type to handle
	 * @return the order value, or {@code null} if none can be found
	 * @see #getPriority(Class)
	 */
	@Nullable
	public static Integer getOrder(Class<?> type) {
		// 从注解中查找order，并且注解的搜索策略是TYPE_HIERARCHY，即父类和接口都进行搜索，且不限制父类的注解必须要标注@Inherit注解
		return getOrderFromAnnotations(type, MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY));
	}

	/**
	 * Return the order from the specified annotations collection.
	 * <p>Takes care of {@link Order @Order} and
	 * {@code @javax.annotation.Priority}.
	 * @param element the source element
	 * @param annotations the annotation to consider
	 * @return the order value, or {@code null} if none can be found
	 */
	@Nullable
	static Integer getOrderFromAnnotations(AnnotatedElement element, MergedAnnotations annotations) {
		// 如果element不是Class类型的，直接调用findOrder返回
		if (!(element instanceof Class)) {
			return findOrder(annotations);
		}
		// 否则尝试从缓存中查找
		Object cached = orderCache.get(element);
		if (cached != null) {
			// 缓存命中，且是Integer类型的，返回结果；否则返回null
			return (cached instanceof Integer ? (Integer) cached : null);
		}
		// 缓存未命中，调用findOrder进行查找
		Integer result = findOrder(annotations);
		// 如果结果存在，放入缓存，否则，放入一个常量NOT_ANNOTATED
		orderCache.put(element, result != null ? result : NOT_ANNOTATED);
		// 返回结果
		return result;
	}

	@Nullable
	private static Integer findOrder(MergedAnnotations annotations) {
		// 从MergeAnnotations聚合注解中查找@Order所对应的MergedAnnotation
		MergedAnnotation<Order> orderAnnotation = annotations.get(Order.class);
		// 如果@Order注解是存在的，获取其value属性值
		if (orderAnnotation.isPresent()) {
			return orderAnnotation.getInt(MergedAnnotation.VALUE);
		}
		// 否则查找@javax.annotation.Priority注解，如果存在，返回其value属性值
		MergedAnnotation<?> priorityAnnotation = annotations.get(JAVAX_PRIORITY_ANNOTATION);
		if (priorityAnnotation.isPresent()) {
			return priorityAnnotation.getInt(MergedAnnotation.VALUE);
		}
		// 否则返回null
		return null;
	}

	/**
	 * Return the value of the {@code javax.annotation.Priority} annotation
	 * declared on the specified type, or {@code null} if none.
	 * @param type the type to handle
	 * @return the priority value if the annotation is declared, or {@code null} if none
	 */
	@Nullable
	public static Integer getPriority(Class<?> type) {
		return MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY).get(JAVAX_PRIORITY_ANNOTATION)
				.getValue(MergedAnnotation.VALUE, Integer.class).orElse(null);
	}

}
