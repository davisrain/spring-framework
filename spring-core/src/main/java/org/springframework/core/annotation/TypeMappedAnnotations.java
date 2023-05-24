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
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.springframework.lang.Nullable;

/**
 * {@link MergedAnnotations} implementation that searches for and adapts
 * annotations and meta-annotations using {@link AnnotationTypeMappings}.
 *
 * @author Phillip Webb
 * @since 5.2
 */
final class TypeMappedAnnotations implements MergedAnnotations {

	/**
	 * Shared instance that can be used when there are no annotations.
	 */
	static final MergedAnnotations NONE = new TypeMappedAnnotations(
			null, new Annotation[0], RepeatableContainers.none(), AnnotationFilter.ALL);


	@Nullable
	private final Object source;

	@Nullable
	private final AnnotatedElement element;

	@Nullable
	private final SearchStrategy searchStrategy;

	@Nullable
	private final Annotation[] annotations;

	private final RepeatableContainers repeatableContainers;

	private final AnnotationFilter annotationFilter;

	@Nullable
	private volatile List<Aggregate> aggregates;


	private TypeMappedAnnotations(AnnotatedElement element, SearchStrategy searchStrategy,
			RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		this.source = element;
		this.element = element;
		this.searchStrategy = searchStrategy;
		this.annotations = null;
		this.repeatableContainers = repeatableContainers;
		this.annotationFilter = annotationFilter;
	}

	private TypeMappedAnnotations(@Nullable Object source, Annotation[] annotations,
			RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		this.source = source;
		this.element = null;
		this.searchStrategy = null;
		this.annotations = annotations;
		this.repeatableContainers = repeatableContainers;
		this.annotationFilter = annotationFilter;
	}


	@Override
	public <A extends Annotation> boolean isPresent(Class<A> annotationType) {
		if (this.annotationFilter.matches(annotationType)) {
			return false;
		}
		return Boolean.TRUE.equals(scan(annotationType,
				IsPresent.get(this.repeatableContainers, this.annotationFilter, false)));
	}

	@Override
	public boolean isPresent(String annotationType) {
		// 先使用annotationFilter进行过滤，如果满足条件，直接返回false，表示不存在
		if (this.annotationFilter.matches(annotationType)) {
			return false;
		}
		// 调用scan方法，比较返回的是否为true
		return Boolean.TRUE.equals(scan(annotationType,
				// 获取一个实现了AnnotationProcessor接口的IsPresent
				IsPresent.get(this.repeatableContainers, this.annotationFilter, false)));
	}

	@Override
	public <A extends Annotation> boolean isDirectlyPresent(Class<A> annotationType) {
		if (this.annotationFilter.matches(annotationType)) {
			return false;
		}
		return Boolean.TRUE.equals(scan(annotationType,
				IsPresent.get(this.repeatableContainers, this.annotationFilter, true)));
	}

	@Override
	public boolean isDirectlyPresent(String annotationType) {
		if (this.annotationFilter.matches(annotationType)) {
			return false;
		}
		return Boolean.TRUE.equals(scan(annotationType,
				IsPresent.get(this.repeatableContainers, this.annotationFilter, true)));
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(Class<A> annotationType) {
		return get(annotationType, null, null);
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(Class<A> annotationType,
			@Nullable Predicate<? super MergedAnnotation<A>> predicate) {

		return get(annotationType, predicate, null);
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(Class<A> annotationType,
			@Nullable Predicate<? super MergedAnnotation<A>> predicate,
			@Nullable MergedAnnotationSelector<A> selector) {

		if (this.annotationFilter.matches(annotationType)) {
			return MergedAnnotation.missing();
		}
		MergedAnnotation<A> result = scan(annotationType,
				new MergedAnnotationFinder<>(annotationType, predicate, selector));
		return (result != null ? result : MergedAnnotation.missing());
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(String annotationType) {
		return get(annotationType, null, null);
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(String annotationType,
			@Nullable Predicate<? super MergedAnnotation<A>> predicate) {

		return get(annotationType, predicate, null);
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(String annotationType,
			@Nullable Predicate<? super MergedAnnotation<A>> predicate,
			@Nullable MergedAnnotationSelector<A> selector) {

		// 如果注解类型会被filter过滤掉，直接返回一个MissingMergedAnnotation，表示注解不存在
		if (this.annotationFilter.matches(annotationType)) {
			return MergedAnnotation.missing();
		}
		// 调用scan方法，其中AnnotationProcessor传入实现类MergedAnnotationFinder，对MergedAnnotation进行查找
		MergedAnnotation<A> result = scan(annotationType,
				new MergedAnnotationFinder<>(annotationType, predicate, selector));
		// 如果查找的结果不为null，直接返回，否则返回MissingMergedAnnotation
		return (result != null ? result : MergedAnnotation.missing());
	}

	@Override
	public <A extends Annotation> Stream<MergedAnnotation<A>> stream(Class<A> annotationType) {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Stream.empty();
		}
		return StreamSupport.stream(spliterator(annotationType), false);
	}

	@Override
	public <A extends Annotation> Stream<MergedAnnotation<A>> stream(String annotationType) {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Stream.empty();
		}
		return StreamSupport.stream(spliterator(annotationType), false);
	}

	@Override
	public Stream<MergedAnnotation<Annotation>> stream() {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Stream.empty();
		}
		return StreamSupport.stream(spliterator(), false);
	}

	@Override
	public Iterator<MergedAnnotation<Annotation>> iterator() {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Collections.emptyIterator();
		}
		return Spliterators.iterator(spliterator());
	}

	@Override
	public Spliterator<MergedAnnotation<Annotation>> spliterator() {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Spliterators.emptySpliterator();
		}
		return spliterator(null);
	}

	private <A extends Annotation> Spliterator<MergedAnnotation<A>> spliterator(@Nullable Object annotationType) {
		return new AggregatesSpliterator<>(annotationType, getAggregates());
	}

	private List<Aggregate> getAggregates() {
		List<Aggregate> aggregates = this.aggregates;
		if (aggregates == null) {
			aggregates = scan(this, new AggregatesCollector());
			if (aggregates == null || aggregates.isEmpty()) {
				aggregates = Collections.emptyList();
			}
			this.aggregates = aggregates;
		}
		return aggregates;
	}

	@Nullable
	private <C, R> R scan(C criteria, AnnotationsProcessor<C, R> processor) {
		// 如果this.annotations不为null
		// 调用processor的doWithAnnotations方法，得到返回结果，再调用processor的finish方法处理返回结果并返回
		if (this.annotations != null) {
			R result = processor.doWithAnnotations(criteria, 0, this.source, this.annotations);
			return processor.finish(result);
		}
		// 如果element和searchStrategy不为null，调用AnnotationsScanner的scan方法进行查找
		if (this.element != null && this.searchStrategy != null) {
			return AnnotationsScanner.scan(criteria, this.element, this.searchStrategy, processor);
		}
		return null;
	}


	static MergedAnnotations from(AnnotatedElement element, SearchStrategy searchStrategy,
			RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		// 调用AnnotationScanner的isKnownEmpty查看element上的注解是否为空的
		if (AnnotationsScanner.isKnownEmpty(element, searchStrategy)) {
			return NONE;
		}
		// 否则的话，初始化一个TypeMappedAnnotations返回
		return new TypeMappedAnnotations(element, searchStrategy, repeatableContainers, annotationFilter);
	}

	static MergedAnnotations from(@Nullable Object source, Annotation[] annotations,
			RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		if (annotations.length == 0) {
			return NONE;
		}
		return new TypeMappedAnnotations(source, annotations, repeatableContainers, annotationFilter);
	}

	private static boolean isMappingForType(AnnotationTypeMapping mapping,
			AnnotationFilter annotationFilter, @Nullable Object requiredType) {

		// 拿到mapping中的实际注解类型
		Class<? extends Annotation> actualType = mapping.getAnnotationType();
		// 如果实际类型不会被过滤掉 并且 如果请求的类型为null 或者 请求类型等于实际类型 或者 请求类型等于实际的名称，返回true
		return (!annotationFilter.matches(actualType) &&
				(requiredType == null || actualType == requiredType || actualType.getName().equals(requiredType)));
	}


	/**
	 * {@link AnnotationsProcessor} used to detect if an annotation is directly
	 * present or meta-present.
	 */
	private static final class IsPresent implements AnnotationsProcessor<Object, Boolean> {

		/**
		 * Shared instances that save us needing to create a new processor for
		 * the common combinations.
		 */
		private static final IsPresent[] SHARED;
		static {
			SHARED = new IsPresent[4];
			SHARED[0] = new IsPresent(RepeatableContainers.none(), AnnotationFilter.PLAIN, true);
			SHARED[1] = new IsPresent(RepeatableContainers.none(), AnnotationFilter.PLAIN, false);
			SHARED[2] = new IsPresent(RepeatableContainers.standardRepeatables(), AnnotationFilter.PLAIN, true);
			SHARED[3] = new IsPresent(RepeatableContainers.standardRepeatables(), AnnotationFilter.PLAIN, false);
		}

		private final RepeatableContainers repeatableContainers;

		private final AnnotationFilter annotationFilter;

		private final boolean directOnly;

		private IsPresent(RepeatableContainers repeatableContainers,
				AnnotationFilter annotationFilter, boolean directOnly) {

			this.repeatableContainers = repeatableContainers;
			this.annotationFilter = annotationFilter;
			this.directOnly = directOnly;
		}

		@Override
		@Nullable
		public Boolean doWithAnnotations(Object requiredType, int aggregateIndex,
				@Nullable Object source, Annotation[] annotations) {

			// 遍历注解数组
			for (Annotation annotation : annotations) {
				// 如果遍历到的注解不为null的话
				if (annotation != null) {
					// 获取注解的类型type
					Class<? extends Annotation> type = annotation.annotationType();
					// 如果type不为null，且不满足过滤器的条件，不会被过滤
					if (type != null && !this.annotationFilter.matches(type)) {
						// 判断type是否和requireType相等 或者 type的名称是否等于requireType，如果是的话，直接返回true
						// 表示requireType所表示的注解存在于source上
						if (type == requiredType || type.getName().equals(requiredType)) {
							return Boolean.TRUE;
						}
						// 如果annotation是repeatedAnnotations的一个容器注解，尝试拿到其持有的repeatedAnnotations注解数组；
						// 如果不是，返回null
						Annotation[] repeatedAnnotations =
								this.repeatableContainers.findRepeatedAnnotations(annotation);
						// 如果repeatedAnnotations不为null，将获取到的注解数组递归调用doWithAnnotations方法
						if (repeatedAnnotations != null) {
							Boolean result = doWithAnnotations(
									requiredType, aggregateIndex, source, repeatedAnnotations);
							if (result != null) {
								return result;
							}
						}
						// 如果directOnly为false的话，表示不单单只查找标注的注解本身，如果注解类上标注了其他注解，也会一并查找
						// 比如@Controller注解类上标注了@Component注解
						if (!this.directOnly) {
							// 将注解类型映射为AnnotationTypeMappings，这是AnnotationTypeMapping的聚合类型。
							// mappings中持有了一个AnnotationTypeMapping的集合，里面包含了注解和所有标注在注解上的元注解的映射类型
							AnnotationTypeMappings mappings = AnnotationTypeMappings.forAnnotationType(type);
							// 遍历持有的AnnotationTypeMapping
							for (int i = 0; i < mappings.size(); i++) {
								AnnotationTypeMapping mapping = mappings.get(i);
								// 查看AnnotationTypeMapping是否符合是requireType的映射类型，如果符合，返回true，表示请求的注解类型存在
								if (isMappingForType(mapping, this.annotationFilter, requiredType)) {
									return Boolean.TRUE;
								}
							}
						}
					}
				}
			}
			return null;
		}

		static IsPresent get(RepeatableContainers repeatableContainers,
				AnnotationFilter annotationFilter, boolean directOnly) {

			// Use a single shared instance for common combinations
			if (annotationFilter == AnnotationFilter.PLAIN) {
				if (repeatableContainers == RepeatableContainers.none()) {
					return SHARED[directOnly ? 0 : 1];
				}
				if (repeatableContainers == RepeatableContainers.standardRepeatables()) {
					return SHARED[directOnly ? 2 : 3];
				}
			}
			return new IsPresent(repeatableContainers, annotationFilter, directOnly);
		}
	}


	/**
	 * {@link AnnotationsProcessor} that finds a single {@link MergedAnnotation}.
	 */
	private class MergedAnnotationFinder<A extends Annotation>
			implements AnnotationsProcessor<Object, MergedAnnotation<A>> {

		private final Object requiredType;

		@Nullable
		private final Predicate<? super MergedAnnotation<A>> predicate;

		private final MergedAnnotationSelector<A> selector;

		@Nullable
		private MergedAnnotation<A> result;

		MergedAnnotationFinder(Object requiredType, @Nullable Predicate<? super MergedAnnotation<A>> predicate,
				@Nullable MergedAnnotationSelector<A> selector) {

			this.requiredType = requiredType;
			this.predicate = predicate;
			// 如果selector为null的话，默认取nearest选择器，选择逻辑是更靠近根注解，优先级越高
			this.selector = (selector != null ? selector : MergedAnnotationSelectors.nearest());
		}

		@Override
		@Nullable
		public MergedAnnotation<A> doWithAggregate(Object context, int aggregateIndex) {
			return this.result;
		}

		@Override
		@Nullable
		public MergedAnnotation<A> doWithAnnotations(Object type, int aggregateIndex,
				@Nullable Object source, Annotation[] annotations) {

			// 遍历传入的注解数组
			for (Annotation annotation : annotations) {
				// 如果注解不为null且不会被filter过滤掉
				if (annotation != null && !annotationFilter.matches(annotation)) {
					// 调用process方法返回result，如果result不为null的话，直接返回
					MergedAnnotation<A> result = process(type, aggregateIndex, source, annotation);
					if (result != null) {
						return result;
					}
				}
			}
			// 否则返回null
			return null;
		}

		@Nullable
		private MergedAnnotation<A> process(
				Object type, int aggregateIndex, @Nullable Object source, Annotation annotation) {

			// 尝试注解是否是重复容器注解，如果是的话，递归调用doWithAnnotations方法进行解析
			Annotation[] repeatedAnnotations = repeatableContainers.findRepeatedAnnotations(annotation);
			if (repeatedAnnotations != null) {
				return doWithAnnotations(type, aggregateIndex, source, repeatedAnnotations);
			}
			// 如果不是重复容器注解，将注解映射为AnnotationTypeMappings
			AnnotationTypeMappings mappings = AnnotationTypeMappings.forAnnotationType(
					annotation.annotationType(), repeatableContainers, annotationFilter);
			// 然后遍历mappings中持有的AnnotationTypeMapping
			for (int i = 0; i < mappings.size(); i++) {
				AnnotationTypeMapping mapping = mappings.get(i);
				// 判断mapping中持有注解类型 和 要求的注解类型是否一致
				if (isMappingForType(mapping, annotationFilter, this.requiredType)) {
					// 如果一致的话，尝试根据mapping, source, rootAnnotation创建一个MergedAnnotation
					MergedAnnotation<A> candidate = TypeMappedAnnotation.createIfPossible(
							mapping, source, annotation, aggregateIndex, IntrospectionFailureLogger.INFO);
					// 如果candidate不为null 并且 predicate为null或者判断通过
					if (candidate != null && (this.predicate == null || this.predicate.test(candidate))) {
						// 使用选择器来判断该候选对象是否是最佳的，如果是，直接返回
						if (this.selector.isBestCandidate(candidate)) {
							return candidate;
						}
						// 如果不是的话，将候选对象和当前的result进行比较，选出一个更好的放入result中，等待下一次比较
						updateLastResult(candidate);
					}
				}
			}
			return null;
		}

		private void updateLastResult(MergedAnnotation<A> candidate) {
			MergedAnnotation<A> lastResult = this.result;
			// 如果上一次的result不为null的话，使用选择器在二者之间选择；否则，直接将候选对象赋值给result
			this.result = (lastResult != null ? this.selector.select(lastResult, candidate) : candidate);
		}

		@Override
		@Nullable
		public MergedAnnotation<A> finish(@Nullable MergedAnnotation<A> result) {
			// 如果传入的result为null的话，返回自身持有的result；如果不为null，返回传入的result
			return (result != null ? result : this.result);
		}
	}


	/**
	 * {@link AnnotationsProcessor} that collects {@link Aggregate} instances.
	 */
	private class AggregatesCollector implements AnnotationsProcessor<Object, List<Aggregate>> {

		private final List<Aggregate> aggregates = new ArrayList<>();

		@Override
		@Nullable
		public List<Aggregate> doWithAnnotations(Object criteria, int aggregateIndex,
				@Nullable Object source, Annotation[] annotations) {

			this.aggregates.add(createAggregate(aggregateIndex, source, annotations));
			return null;
		}

		private Aggregate createAggregate(int aggregateIndex, @Nullable Object source, Annotation[] annotations) {
			List<Annotation> aggregateAnnotations = getAggregateAnnotations(annotations);
			return new Aggregate(aggregateIndex, source, aggregateAnnotations);
		}

		private List<Annotation> getAggregateAnnotations(Annotation[] annotations) {
			List<Annotation> result = new ArrayList<>(annotations.length);
			addAggregateAnnotations(result, annotations);
			return result;
		}

		private void addAggregateAnnotations(List<Annotation> aggregateAnnotations, Annotation[] annotations) {
			for (Annotation annotation : annotations) {
				if (annotation != null && !annotationFilter.matches(annotation)) {
					Annotation[] repeatedAnnotations = repeatableContainers.findRepeatedAnnotations(annotation);
					if (repeatedAnnotations != null) {
						addAggregateAnnotations(aggregateAnnotations, repeatedAnnotations);
					}
					else {
						aggregateAnnotations.add(annotation);
					}
				}
			}
		}

		@Override
		public List<Aggregate> finish(@Nullable List<Aggregate> processResult) {
			return this.aggregates;
		}
	}


	private static class Aggregate {

		private final int aggregateIndex;

		@Nullable
		private final Object source;

		private final List<Annotation> annotations;

		private final AnnotationTypeMappings[] mappings;

		Aggregate(int aggregateIndex, @Nullable Object source, List<Annotation> annotations) {
			this.aggregateIndex = aggregateIndex;
			this.source = source;
			this.annotations = annotations;
			this.mappings = new AnnotationTypeMappings[annotations.size()];
			for (int i = 0; i < annotations.size(); i++) {
				this.mappings[i] = AnnotationTypeMappings.forAnnotationType(annotations.get(i).annotationType());
			}
		}

		int size() {
			return this.annotations.size();
		}

		@Nullable
		AnnotationTypeMapping getMapping(int annotationIndex, int mappingIndex) {
			AnnotationTypeMappings mappings = getMappings(annotationIndex);
			return (mappingIndex < mappings.size() ? mappings.get(mappingIndex) : null);
		}

		AnnotationTypeMappings getMappings(int annotationIndex) {
			return this.mappings[annotationIndex];
		}

		@Nullable
		<A extends Annotation> MergedAnnotation<A> createMergedAnnotationIfPossible(
				int annotationIndex, int mappingIndex, IntrospectionFailureLogger logger) {

			return TypeMappedAnnotation.createIfPossible(
					this.mappings[annotationIndex].get(mappingIndex), this.source,
					this.annotations.get(annotationIndex), this.aggregateIndex, logger);
		}
	}


	/**
	 * {@link Spliterator} used to consume merged annotations from the
	 * aggregates in distance fist order.
	 */
	private class AggregatesSpliterator<A extends Annotation> implements Spliterator<MergedAnnotation<A>> {

		@Nullable
		private final Object requiredType;

		private final List<Aggregate> aggregates;

		private int aggregateCursor;

		@Nullable
		private int[] mappingCursors;

		AggregatesSpliterator(@Nullable Object requiredType, List<Aggregate> aggregates) {
			this.requiredType = requiredType;
			this.aggregates = aggregates;
			this.aggregateCursor = 0;
		}

		@Override
		public boolean tryAdvance(Consumer<? super MergedAnnotation<A>> action) {
			while (this.aggregateCursor < this.aggregates.size()) {
				Aggregate aggregate = this.aggregates.get(this.aggregateCursor);
				if (tryAdvance(aggregate, action)) {
					return true;
				}
				this.aggregateCursor++;
				this.mappingCursors = null;
			}
			return false;
		}

		private boolean tryAdvance(Aggregate aggregate, Consumer<? super MergedAnnotation<A>> action) {
			if (this.mappingCursors == null) {
				this.mappingCursors = new int[aggregate.size()];
			}
			int lowestDistance = Integer.MAX_VALUE;
			int annotationResult = -1;
			for (int annotationIndex = 0; annotationIndex < aggregate.size(); annotationIndex++) {
				AnnotationTypeMapping mapping = getNextSuitableMapping(aggregate, annotationIndex);
				if (mapping != null && mapping.getDistance() < lowestDistance) {
					annotationResult = annotationIndex;
					lowestDistance = mapping.getDistance();
				}
				if (lowestDistance == 0) {
					break;
				}
			}
			if (annotationResult != -1) {
				MergedAnnotation<A> mergedAnnotation = aggregate.createMergedAnnotationIfPossible(
						annotationResult, this.mappingCursors[annotationResult],
						this.requiredType != null ? IntrospectionFailureLogger.INFO : IntrospectionFailureLogger.DEBUG);
				this.mappingCursors[annotationResult]++;
				if (mergedAnnotation == null) {
					return tryAdvance(aggregate, action);
				}
				action.accept(mergedAnnotation);
				return true;
			}
			return false;
		}

		@Nullable
		private AnnotationTypeMapping getNextSuitableMapping(Aggregate aggregate, int annotationIndex) {
			int[] cursors = this.mappingCursors;
			if (cursors != null) {
				AnnotationTypeMapping mapping;
				do {
					mapping = aggregate.getMapping(annotationIndex, cursors[annotationIndex]);
					if (mapping != null && isMappingForType(mapping, annotationFilter, this.requiredType)) {
						return mapping;
					}
					cursors[annotationIndex]++;
				}
				while (mapping != null);
			}
			return null;
		}

		@Override
		@Nullable
		public Spliterator<MergedAnnotation<A>> trySplit() {
			return null;
		}

		@Override
		public long estimateSize() {
			int size = 0;
			for (int aggregateIndex = this.aggregateCursor;
					aggregateIndex < this.aggregates.size(); aggregateIndex++) {
				Aggregate aggregate = this.aggregates.get(aggregateIndex);
				for (int annotationIndex = 0; annotationIndex < aggregate.size(); annotationIndex++) {
					AnnotationTypeMappings mappings = aggregate.getMappings(annotationIndex);
					int numberOfMappings = mappings.size();
					if (aggregateIndex == this.aggregateCursor && this.mappingCursors != null) {
						numberOfMappings -= Math.min(this.mappingCursors[annotationIndex], mappings.size());
					}
					size += numberOfMappings;
				}
			}
			return size;
		}

		@Override
		public int characteristics() {
			return NONNULL | IMMUTABLE;
		}
	}

}
