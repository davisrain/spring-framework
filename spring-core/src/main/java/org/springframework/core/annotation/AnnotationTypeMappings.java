/*
 * Copyright 2002-2020 the original author or authors.
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * Provides {@link AnnotationTypeMapping} information for a single source
 * annotation type. Performs a recursive breadth first crawl of all
 * meta-annotations to ultimately provide a quick way to map the attributes of
 * a root {@link Annotation}.
 *
 * <p>Supports convention based merging of meta-annotations as well as implicit
 * and explicit {@link AliasFor @AliasFor} aliases. Also provides information
 * about mirrored attributes.
 *
 * <p>This class is designed to be cached so that meta-annotations only need to
 * be searched once, regardless of how many times they are actually used.
 *
 * @author Phillip Webb
 * @since 5.2
 * @see AnnotationTypeMapping
 */
final class AnnotationTypeMappings {

	private static final IntrospectionFailureLogger failureLogger = IntrospectionFailureLogger.DEBUG;

	private static final Map<AnnotationFilter, Cache> standardRepeatablesCache = new ConcurrentReferenceHashMap<>();

	private static final Map<AnnotationFilter, Cache> noRepeatablesCache = new ConcurrentReferenceHashMap<>();


	private final RepeatableContainers repeatableContainers;

	private final AnnotationFilter filter;

	private final List<AnnotationTypeMapping> mappings;


	private AnnotationTypeMappings(RepeatableContainers repeatableContainers,
			AnnotationFilter filter, Class<? extends Annotation> annotationType) {

		this.repeatableContainers = repeatableContainers;
		this.filter = filter;
		this.mappings = new ArrayList<>();
		addAllMappings(annotationType);
		this.mappings.forEach(AnnotationTypeMapping::afterAllMappingsSet);
	}


	private void addAllMappings(Class<? extends Annotation> annotationType) {
		Deque<AnnotationTypeMapping> queue = new ArrayDeque<>();
		// 初始化一个AnnotationTypeMapping添加进队尾
		addIfPossible(queue, null, annotationType, null);
		// 如果队列不为空，循环
		while (!queue.isEmpty()) {
			// 从队首取出一个mapping，添加进mappings中
			AnnotationTypeMapping mapping = queue.removeFirst();
			this.mappings.add(mapping);
			// 将mapping的元注解(标注在mapping指向的注解上的注解)添加进队列中
			addMetaAnnotationsToQueue(queue, mapping);
		}
	}

	private void addMetaAnnotationsToQueue(Deque<AnnotationTypeMapping> queue, AnnotationTypeMapping source) {
		// 查找到标注在source的注解类型上的所有符合条件的注解
		Annotation[] metaAnnotations = AnnotationsScanner.getDeclaredAnnotations(source.getAnnotationType(), false);
		// 遍历注解数组
		for (Annotation metaAnnotation : metaAnnotations) {
			// 如果不符合map条件的话，执行下一次循环
			if (!isMappable(source, metaAnnotation)) {
				continue;
			}
			// 调用repeatableContainers查找元注解是否是重复注解的容器注解，
			// 因为多个标注了@Repeatable注解的注解声明在同一个位置时，java会将其映射为一个容器注解
			// 即@Repeatable注解的value属性所指向的那个注解类型。
			// 比如：@ComponentScan注解上标注了@Repeatable(ComponentScans.class)。当一个类上标注了多个@ComponentScan注解，查找该类声明的注解时，
			// 返回的是@ComponentScans注解，调用其value方法会返回原本标注在类上的多个@ComponentScan注解的实例
			Annotation[] repeatedAnnotations = this.repeatableContainers.findRepeatedAnnotations(metaAnnotation);
			// 如果数组不为null
			if (repeatedAnnotations != null) {
				// 遍历重复注解数组
				for (Annotation repeatedAnnotation : repeatedAnnotations) {
					// 判断是否符合map条件
					if (!isMappable(source, repeatedAnnotation)) {
						continue;
					}
					// 将注解映射为AnnotationTypeMapping添加进队尾
					addIfPossible(queue, source, repeatedAnnotation);
				}
			}
			// 如果该注解不是重复注解的容器注解，直接尝试将其映射为AnnotationTypeMapping类型添加进队尾，并且会传入source对象
			else {
				addIfPossible(queue, source, metaAnnotation);
			}
		}
	}

	private void addIfPossible(Deque<AnnotationTypeMapping> queue, AnnotationTypeMapping source, Annotation ann) {
		addIfPossible(queue, source, ann.annotationType(), ann);
	}

	private void addIfPossible(Deque<AnnotationTypeMapping> queue, @Nullable AnnotationTypeMapping source,
			Class<? extends Annotation> annotationType, @Nullable Annotation ann) {

		try {
			// 初始化一个AnnotationTypeMapping添加进队尾
			queue.addLast(new AnnotationTypeMapping(source, annotationType, ann));
		}
		catch (Exception ex) {
			AnnotationUtils.rethrowAnnotationConfigurationException(ex);
			if (failureLogger.isEnabled()) {
				failureLogger.log("Failed to introspect meta-annotation " + annotationType.getName(),
						(source != null ? source.getAnnotationType() : null), ex);
			}
		}
	}

	private boolean isMappable(AnnotationTypeMapping source, @Nullable Annotation metaAnnotation) {
		// 判断source和metaAnnotation是否是可以匹配的
		// 判断逻辑是：metaAnnotation不为null 并且 不会被自身持有的filter过滤 并且source的注解类型不是java.lang org.springframework.lang开头的
		// 并且不是早已经匹配过的
		return (metaAnnotation != null && !this.filter.matches(metaAnnotation) &&
				!AnnotationFilter.PLAIN.matches(source.getAnnotationType()) &&
				!isAlreadyMapped(source, metaAnnotation));
	}

	private boolean isAlreadyMapped(AnnotationTypeMapping source, Annotation metaAnnotation) {
		Class<? extends Annotation> annotationType = metaAnnotation.annotationType();
		AnnotationTypeMapping mapping = source;
		// 从source一直循环到root
		while (mapping != null) {
			// 如果存在当前mapping的注解类型和元注解类型相同，说明已经匹配过，返回true
			// 该方法可以解决循环依赖或者标注自身的问题，比如@A上标注了@B，注解@B上标注了@A，在解析b的元注解的时候会找到到a，
			// 但是会发现a的类型已经在b的source中出现过，所以返回true，用于跳过该元注解的解析
			if (mapping.getAnnotationType() == annotationType) {
				return true;
			}
			mapping = mapping.getSource();
		}
		// 否则返回false
		return false;
	}

	/**
	 * Get the total number of contained mappings.
	 * @return the total number of mappings
	 */
	int size() {
		return this.mappings.size();
	}

	/**
	 * Get an individual mapping from this instance.
	 * <p>Index {@code 0} will always return the root mapping; higher indexes
	 * will return meta-annotation mappings.
	 * @param index the index to return
	 * @return the {@link AnnotationTypeMapping}
	 * @throws IndexOutOfBoundsException if the index is out of range
	 * (<tt>index &lt; 0 || index &gt;= size()</tt>)
	 */
	AnnotationTypeMapping get(int index) {
		return this.mappings.get(index);
	}


	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 * @param annotationType the source annotation type
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType) {
		return forAnnotationType(annotationType, AnnotationFilter.PLAIN);
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 * @param annotationType the source annotation type
	 * @param annotationFilter the annotation filter used to limit which
	 * annotations are considered
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(
			Class<? extends Annotation> annotationType, AnnotationFilter annotationFilter) {

		return forAnnotationType(annotationType, RepeatableContainers.standardRepeatables(), annotationFilter);
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 * @param annotationType the source annotation type
	 * @param repeatableContainers the repeatable containers that may be used by
	 * the meta-annotations
	 * @param annotationFilter the annotation filter used to limit which
	 * annotations are considered
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType,
			RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		// 如果传入的repeatableContainers参数是StandardRepeatableContainers，从standardRepeatablesCache缓存中取。
		// 如果缓存未命中，以annotationFilter为key，创建一个cache放入缓存中。然后尝试从cache的mappings字段中根据annotationType获取
		// AnnotationTypeMappings，如果mappings字段中不存在key为annotationType的缓存，那么初始化一个AnnotationTypeMappings类型放入缓存并返回
		if (repeatableContainers == RepeatableContainers.standardRepeatables()) {
			return standardRepeatablesCache.computeIfAbsent(annotationFilter,
					key -> new Cache(repeatableContainers, key)).get(annotationType);
		}
		// 如果传入的repeatableContainers参数是NoRepeatableContainers，从noRepeatablesCache缓存中取。
		// 如果缓存未命中，创建一个cache放入缓存中，以annotationFilter为key。然后尝试从cache的mappings字段中根据annotationType获取
		// AnnotationTypeMappings，如果mappings字段中不存在key为annotationType的缓存，那么初始化一个AnnotationTypeMappings类型放入缓存并返回
		if (repeatableContainers == RepeatableContainers.none()) {
			return noRepeatablesCache.computeIfAbsent(annotationFilter,
					key -> new Cache(repeatableContainers, key)).get(annotationType);
		}
		// 否则的话，直接根据repeatableContainers annotationFilter annotationType初始化一个AnnotationTypeMappings返回
		return new AnnotationTypeMappings(repeatableContainers, annotationFilter, annotationType);
	}

	static void clearCache() {
		standardRepeatablesCache.clear();
		noRepeatablesCache.clear();
	}


	/**
	 * Cache created per {@link AnnotationFilter}.
	 */
	private static class Cache {

		private final RepeatableContainers repeatableContainers;

		private final AnnotationFilter filter;

		private final Map<Class<? extends Annotation>, AnnotationTypeMappings> mappings;

		/**
		 * Create a cache instance with the specified filter.
		 * @param filter the annotation filter
		 */
		Cache(RepeatableContainers repeatableContainers, AnnotationFilter filter) {
			this.repeatableContainers = repeatableContainers;
			this.filter = filter;
			this.mappings = new ConcurrentReferenceHashMap<>();
		}

		/**
		 * Get or create {@link AnnotationTypeMappings} for the specified annotation type.
		 * @param annotationType the annotation type
		 * @return a new or existing {@link AnnotationTypeMappings} instance
		 */
		AnnotationTypeMappings get(Class<? extends Annotation> annotationType) {
			return this.mappings.computeIfAbsent(annotationType, this::createMappings);
		}

		AnnotationTypeMappings createMappings(Class<? extends Annotation> annotationType) {
			return new AnnotationTypeMappings(this.repeatableContainers, this.filter, annotationType);
		}
	}

}
