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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.annotation.AnnotationTypeMapping.MirrorSets.MirrorSet;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Provides mapping information for a single annotation (or meta-annotation) in
 * the context of a root annotation type.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 5.2
 * @see AnnotationTypeMappings
 */
final class AnnotationTypeMapping {

	private static final MirrorSet[] EMPTY_MIRROR_SETS = new MirrorSet[0];


	@Nullable
	private final AnnotationTypeMapping source;

	private final AnnotationTypeMapping root;

	private final int distance;

	private final Class<? extends Annotation> annotationType;

	private final List<Class<? extends Annotation>> metaTypes;

	@Nullable
	private final Annotation annotation;

	private final AttributeMethods attributes;

	private final MirrorSets mirrorSets;

	private final int[] aliasMappings;

	private final int[] conventionMappings;

	private final int[] annotationValueMappings;

	private final AnnotationTypeMapping[] annotationValueSource;

	private final Map<Method, List<Method>> aliasedBy;

	private final boolean synthesizable;

	private final Set<Method> claimedAliases = new HashSet<>();


	AnnotationTypeMapping(@Nullable AnnotationTypeMapping source,
			Class<? extends Annotation> annotationType, @Nullable Annotation annotation) {

		this.source = source;
		// 如果source不为null，设置为source的root，如果为null，设置为自己
		this.root = (source != null ? source.getRoot() : this);
		// 如果source不为null，将source的distance+1，如果为null，设置为0
		this.distance = (source == null ? 0 : source.getDistance() + 1);
		this.annotationType = annotationType;
		// 如果source不为null，将source的metaTypes和annotationType合并起来，否则，直接用annotationType生成一个list
		this.metaTypes = merge(
				source != null ? source.getMetaTypes() : null,
				annotationType);
		this.annotation = annotation;
		// 生成一个AttributeMethods赋值
		this.attributes = AttributeMethods.forAnnotationType(annotationType);
		// 生成一个mirrorSets赋值
		this.mirrorSets = new MirrorSets();
		// 根据attributes的size创建一个int数组，并且元素初始化为-1
		this.aliasMappings = filledIntArray(this.attributes.size());
		this.conventionMappings = filledIntArray(this.attributes.size());
		this.annotationValueMappings = filledIntArray(this.attributes.size());
		// 根据attribute的size创建一个AnnotationTypeMapping的数组
		this.annotationValueSource = new AnnotationTypeMapping[this.attributes.size()];
		// 解析alias
		this.aliasedBy = resolveAliasedForTargets();
		// 处理aliases
		processAliases();
		addConventionMappings();
		addConventionAnnotationValues();
		this.synthesizable = computeSynthesizableFlag();
	}


	private static <T> List<T> merge(@Nullable List<T> existing, T element) {
		if (existing == null) {
			return Collections.singletonList(element);
		}
		List<T> merged = new ArrayList<>(existing.size() + 1);
		merged.addAll(existing);
		merged.add(element);
		return Collections.unmodifiableList(merged);
	}

	private Map<Method, List<Method>> resolveAliasedForTargets() {
		Map<Method, List<Method>> aliasedBy = new HashMap<>();
		// 遍历注解的属性方法AttributeMethods
		for (int i = 0; i < this.attributes.size(); i++) {
			// 拿到下标i对应的方法
			Method attribute = this.attributes.get(i);
			// 查找方法上是否声明了@AliasFor注解
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			// 如果存在@AliasFor
			if (aliasFor != null) {
				// 解析@AliasFor注解指向的target属性方法
				Method target = resolveAliasTarget(attribute, aliasFor);
				// 将目标方法作为key，声明@AliasFor注解的方法放入value的list中
				aliasedBy.computeIfAbsent(target, key -> new ArrayList<>()).add(attribute);
			}
		}
		// 将map包装成不可修改的map返回
		return Collections.unmodifiableMap(aliasedBy);
	}

	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor) {
		return resolveAliasTarget(attribute, aliasFor, true);
	}

	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor, boolean checkAliasPair) {
		// 判断AliasFor注解的value方法和attribute方法是否都有不为空的返回值，如果是的话，报错，只能存在一个
		if (StringUtils.hasText(aliasFor.value()) && StringUtils.hasText(aliasFor.attribute())) {
			throw new AnnotationConfigurationException(String.format(
					"In @AliasFor declared on %s, attribute 'attribute' and its alias 'value' " +
					"are present with values of '%s' and '%s', but only one is permitted.",
					AttributeMethods.describe(attribute), aliasFor.attribute(),
					aliasFor.value()));
		}
		// 获取注解的annotation方法的返回值
		Class<? extends Annotation> targetAnnotation = aliasFor.annotation();
		// 如果targetAnnotation是默认的Annotation.class的话，将其设置为自身的annotationType，
		// 说明是在自身注解范围内进行别名映射
		if (targetAnnotation == Annotation.class) {
			targetAnnotation = this.annotationType;
		}
		// 获取attribute方法的返回值，如果为空的话，获取value方法返回值。将结果赋值给targetAttributeName
		String targetAttributeName = aliasFor.attribute();
		if (!StringUtils.hasLength(targetAttributeName)) {
			targetAttributeName = aliasFor.value();
		}
		// 如果targetAttributeName仍然为空，获取声明@AliasFor注解的属性方法的方法名
		if (!StringUtils.hasLength(targetAttributeName)) {
			targetAttributeName = attribute.getName();
		}
		// 从targetAnnotation目标注解的属性方法中查找到targetAttributeName目标属性名称对应的方法作为目标方法
		Method target = AttributeMethods.forAnnotationType(targetAnnotation).get(targetAttributeName);
		// 如果目标方法为null，抛出异常
		if (target == null) {
			if (targetAnnotation == this.annotationType) {
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for '%s' which is not present.",
						AttributeMethods.describe(attribute), targetAttributeName));
			}
			throw new AnnotationConfigurationException(String.format(
					"%s is declared as an @AliasFor nonexistent %s.",
					StringUtils.capitalize(AttributeMethods.describe(attribute)),
					AttributeMethods.describe(targetAnnotation, targetAttributeName)));
		}
		// 如果目标方法就等于声明@AliasFor注解的属性方法，也抛出异常
		if (target.equals(attribute)) {
			throw new AnnotationConfigurationException(String.format(
					"@AliasFor declaration on %s points to itself. " +
					"Specify 'annotation' to point to a same-named attribute on a meta-annotation.",
					AttributeMethods.describe(attribute)));
		}
		// 如果目标方法和声明@AliasFor注解的属性方法返回值不兼容，抛出异常
		// 判断逻辑：声明@AliasFor的方法的返回值类型需要和目标方法相等，或者目标方法返回值类型是其数组类型
		if (!isCompatibleReturnType(attribute.getReturnType(), target.getReturnType())) {
			throw new AnnotationConfigurationException(String.format(
					"Misconfigured aliases: %s and %s must declare the same return type.",
					AttributeMethods.describe(attribute),
					AttributeMethods.describe(target)));
		}
		// 如果目标方法的声明类就是当前注解类 并且 checkAliasPair标志为true
		if (isAliasPair(target) && checkAliasPair) {
			// 获取目标方法声明的@AliasFor注解
			AliasFor targetAliasFor = target.getAnnotation(AliasFor.class);
			// 如果目标方法上也声明了@AliasFor注解，说明是一对别名
			if (targetAliasFor != null) {
				// 根据目标方法解析@AliasFor注解，拿到镜像方法
				Method mirror = resolveAliasTarget(target, targetAliasFor, false);
				// 如果镜像方法不等于当前声明@AliasFor注解的方法，报错
				if (!mirror.equals(attribute)) {
					throw new AnnotationConfigurationException(String.format(
							"%s must be declared as an @AliasFor %s, not %s.",
							StringUtils.capitalize(AttributeMethods.describe(target)),
							AttributeMethods.describe(attribute), AttributeMethods.describe(mirror)));
				}
			}
		}
		// 返回目标方法
		return target;
	}

	private boolean isAliasPair(Method target) {
		return (this.annotationType == target.getDeclaringClass());
	}

	private boolean isCompatibleReturnType(Class<?> attributeType, Class<?> targetType) {
		return (attributeType == targetType || attributeType == targetType.getComponentType());
	}

	private void processAliases() {
		List<Method> aliases = new ArrayList<>();
		// 遍历注解的属性方法
		for (int i = 0; i < this.attributes.size(); i++) {
			aliases.clear();
			// 将下标i对应的属性方法加入到list中
			aliases.add(this.attributes.get(i));
			// 收集Aliases
			collectAliases(aliases);
			// 如果aliases的size大于1的话，说明根据属性方法取到了对应的别名方法
			if (aliases.size() > 1) {
				// 调用processAliases重载方法进行处理
				processAliases(i, aliases);
			}
		}
	}

	private void collectAliases(List<Method> aliases) {
		// 将this赋值给mapping
		AnnotationTypeMapping mapping = this;
		// 如果mapping不为null
		while (mapping != null) {
			int size = aliases.size();
			// 遍历aliases
			for (int j = 0; j < size; j++) {
				// 尝试从aliasedBy中取到下标为j对应的属性方法的别名方法
				List<Method> additional = mapping.aliasedBy.get(aliases.get(j));
				// 如果取到的方法集合不为null的话
				if (additional != null) {
					// 将其全部添加进aliases中
					aliases.addAll(additional);
				}
			}
			// 将mapping赋值为mapping的source
			mapping = mapping.source;
		}
	}

	private void processAliases(int attributeIndex, List<Method> aliases) {
		// 获取aliases中包含的第一个root的属性方法的下标
		int rootAttributeIndex = getFirstRootAttributeIndex(aliases);
		// 将this赋值给mapping
		AnnotationTypeMapping mapping = this;
		// 如果mapping不为null
		while (mapping != null) {
			// rootAttributeIndex不为-1且root不等于自身
			if (rootAttributeIndex != -1 && mapping != this.root) {
				// 遍历循环mapping的属性方法
				for (int i = 0; i < mapping.attributes.size(); i++) {
					// 如果aliases中包含下标i对应的属性方法
					if (aliases.contains(mapping.attributes.get(i))) {
						// 将mapping的aliasMappings的下标i的元素替换为rootAttributeIndex
						mapping.aliasMappings[i] = rootAttributeIndex;
					}
				}
			}
			// 更新mapping持有的mirrorSets
			mapping.mirrorSets.updateFrom(aliases);
			// 将aliases添加进claimedAliases集合中
			mapping.claimedAliases.addAll(aliases);
			// 如果mapping的annotation不为null的话
			if (mapping.annotation != null) {
				// 调用mirrorSets的resolve方法
				int[] resolvedMirrors = mapping.mirrorSets.resolve(null,
						mapping.annotation, ReflectionUtils::invokeMethod);
				// 遍历mapping的属性方法
				for (int i = 0; i < mapping.attributes.size(); i++) {
					// 如果aliases中包含了对应的属性方法
					if (aliases.contains(mapping.attributes.get(i))) {
						this.annotationValueMappings[attributeIndex] = resolvedMirrors[i];
						this.annotationValueSource[attributeIndex] = mapping;
					}
				}
			}
			// 将mapping赋值为mapping.source
			mapping = mapping.source;
		}
	}

	private int getFirstRootAttributeIndex(Collection<Method> aliases) {
		// 获取root的AttributeMethods
		AttributeMethods rootAttributes = this.root.getAttributes();
		// 进行遍历
		for (int i = 0; i < rootAttributes.size(); i++) {
			// 如果发现aliases中包含了下标为i的root的属性方法，直接返回下标i
			if (aliases.contains(rootAttributes.get(i))) {
				return i;
			}
		}
		// 如果不存在的话，返回-1
		return -1;
	}

	private void addConventionMappings() {
		if (this.distance == 0) {
			return;
		}
		AttributeMethods rootAttributes = this.root.getAttributes();
		int[] mappings = this.conventionMappings;
		for (int i = 0; i < mappings.length; i++) {
			String name = this.attributes.get(i).getName();
			MirrorSet mirrors = getMirrorSets().getAssigned(i);
			int mapped = rootAttributes.indexOf(name);
			if (!MergedAnnotation.VALUE.equals(name) && mapped != -1) {
				mappings[i] = mapped;
				if (mirrors != null) {
					for (int j = 0; j < mirrors.size(); j++) {
						mappings[mirrors.getAttributeIndex(j)] = mapped;
					}
				}
			}
		}
	}

	private void addConventionAnnotationValues() {
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			boolean isValueAttribute = MergedAnnotation.VALUE.equals(attribute.getName());
			AnnotationTypeMapping mapping = this;
			while (mapping != null && mapping.distance > 0) {
				int mapped = mapping.getAttributes().indexOf(attribute.getName());
				if (mapped != -1 && isBetterConventionAnnotationValue(i, isValueAttribute, mapping)) {
					this.annotationValueMappings[i] = mapped;
					this.annotationValueSource[i] = mapping;
				}
				mapping = mapping.source;
			}
		}
	}

	private boolean isBetterConventionAnnotationValue(int index, boolean isValueAttribute,
			AnnotationTypeMapping mapping) {

		if (this.annotationValueMappings[index] == -1) {
			return true;
		}
		int existingDistance = this.annotationValueSource[index].distance;
		return !isValueAttribute && existingDistance > mapping.distance;
	}

	@SuppressWarnings("unchecked")
	private boolean computeSynthesizableFlag() {
		// Uses @AliasFor for local aliases?
		for (int index : this.aliasMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Uses @AliasFor for attribute overrides in meta-annotations?
		if (!this.aliasedBy.isEmpty()) {
			return true;
		}

		// Uses convention-based attribute overrides in meta-annotations?
		for (int index : this.conventionMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Has nested annotations or arrays of annotations that are synthesizable?
		if (getAttributes().hasNestedAnnotation()) {
			AttributeMethods attributeMethods = getAttributes();
			for (int i = 0; i < attributeMethods.size(); i++) {
				Method method = attributeMethods.get(i);
				Class<?> type = method.getReturnType();
				if (type.isAnnotation() || (type.isArray() && type.getComponentType().isAnnotation())) {
					Class<? extends Annotation> annotationType =
							(Class<? extends Annotation>) (type.isAnnotation() ? type : type.getComponentType());
					AnnotationTypeMapping mapping = AnnotationTypeMappings.forAnnotationType(annotationType).get(0);
					if (mapping.isSynthesizable()) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/**
	 * Method called after all mappings have been set. At this point no further
	 * lookups from child mappings will occur.
	 */
	void afterAllMappingsSet() {
		validateAllAliasesClaimed();
		for (int i = 0; i < this.mirrorSets.size(); i++) {
			validateMirrorSet(this.mirrorSets.get(i));
		}
		this.claimedAliases.clear();
	}

	private void validateAllAliasesClaimed() {
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null && !this.claimedAliases.contains(attribute)) {
				Method target = resolveAliasTarget(attribute, aliasFor);
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for %s which is not meta-present.",
						AttributeMethods.describe(attribute), AttributeMethods.describe(target)));
			}
		}
	}

	private void validateMirrorSet(MirrorSet mirrorSet) {
		Method firstAttribute = mirrorSet.get(0);
		Object firstDefaultValue = firstAttribute.getDefaultValue();
		for (int i = 1; i <= mirrorSet.size() - 1; i++) {
			Method mirrorAttribute = mirrorSet.get(i);
			Object mirrorDefaultValue = mirrorAttribute.getDefaultValue();
			if (firstDefaultValue == null || mirrorDefaultValue == null) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare default values.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
			if (!ObjectUtils.nullSafeEquals(firstDefaultValue, mirrorDefaultValue)) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare the same default value.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
		}
	}

	/**
	 * Get the root mapping.
	 * @return the root mapping
	 */
	AnnotationTypeMapping getRoot() {
		return this.root;
	}

	/**
	 * Get the source of the mapping or {@code null}.
	 * @return the source of the mapping
	 */
	@Nullable
	AnnotationTypeMapping getSource() {
		return this.source;
	}

	/**
	 * Get the distance of this mapping.
	 * @return the distance of the mapping
	 */
	int getDistance() {
		return this.distance;
	}

	/**
	 * Get the type of the mapped annotation.
	 * @return the annotation type
	 */
	Class<? extends Annotation> getAnnotationType() {
		return this.annotationType;
	}

	List<Class<? extends Annotation>> getMetaTypes() {
		return this.metaTypes;
	}

	/**
	 * Get the source annotation for this mapping. This will be the
	 * meta-annotation, or {@code null} if this is the root mapping.
	 * @return the source annotation of the mapping
	 */
	@Nullable
	Annotation getAnnotation() {
		return this.annotation;
	}

	/**
	 * Get the annotation attributes for the mapping annotation type.
	 * @return the attribute methods
	 */
	AttributeMethods getAttributes() {
		return this.attributes;
	}

	/**
	 * Get the related index of an alias mapped attribute, or {@code -1} if
	 * there is no mapping. The resulting value is the index of the attribute on
	 * the root annotation that can be invoked in order to obtain the actual
	 * value.
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getAliasMapping(int attributeIndex) {
		return this.aliasMappings[attributeIndex];
	}

	/**
	 * Get the related index of a convention mapped attribute, or {@code -1}
	 * if there is no mapping. The resulting value is the index of the attribute
	 * on the root annotation that can be invoked in order to obtain the actual
	 * value.
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getConventionMapping(int attributeIndex) {
		return this.conventionMappings[attributeIndex];
	}

	/**
	 * Get a mapped attribute value from the most suitable
	 * {@link #getAnnotation() meta-annotation}.
	 * <p>The resulting value is obtained from the closest meta-annotation,
	 * taking into consideration both convention and alias based mapping rules.
	 * For root mappings, this method will always return {@code null}.
	 * @param attributeIndex the attribute index of the source attribute
	 * @param metaAnnotationsOnly if only meta annotations should be considered.
	 * If this parameter is {@code false} then aliases within the annotation will
	 * also be considered.
	 * @return the mapped annotation value, or {@code null}
	 */
	@Nullable
	Object getMappedAnnotationValue(int attributeIndex, boolean metaAnnotationsOnly) {
		int mappedIndex = this.annotationValueMappings[attributeIndex];
		if (mappedIndex == -1) {
			return null;
		}
		AnnotationTypeMapping source = this.annotationValueSource[attributeIndex];
		if (source == this && metaAnnotationsOnly) {
			return null;
		}
		return ReflectionUtils.invokeMethod(source.attributes.get(mappedIndex), source.annotation);
	}

	/**
	 * Determine if the specified value is equivalent to the default value of the
	 * attribute at the given index.
	 * @param attributeIndex the attribute index of the source attribute
	 * @param value the value to check
	 * @param valueExtractor the value extractor used to extract values from any
	 * nested annotations
	 * @return {@code true} if the value is equivalent to the default value
	 */
	boolean isEquivalentToDefaultValue(int attributeIndex, Object value, ValueExtractor valueExtractor) {

		Method attribute = this.attributes.get(attributeIndex);
		return isEquivalentToDefaultValue(attribute, value, valueExtractor);
	}

	/**
	 * Get the mirror sets for this type mapping.
	 * @return the attribute mirror sets
	 */
	MirrorSets getMirrorSets() {
		return this.mirrorSets;
	}

	/**
	 * Determine if the mapped annotation is <em>synthesizable</em>.
	 * <p>Consult the documentation for {@link MergedAnnotation#synthesize()}
	 * for an explanation of what is considered synthesizable.
	 * @return {@code true} if the mapped annotation is synthesizable
	 * @since 5.2.6
	 */
	boolean isSynthesizable() {
		return this.synthesizable;
	}


	private static int[] filledIntArray(int size) {
		int[] array = new int[size];
		Arrays.fill(array, -1);
		return array;
	}

	private static boolean isEquivalentToDefaultValue(Method attribute, Object value,
			ValueExtractor valueExtractor) {

		return areEquivalent(attribute.getDefaultValue(), value, valueExtractor);
	}

	private static boolean areEquivalent(@Nullable Object value, @Nullable Object extractedValue,
			ValueExtractor valueExtractor) {

		if (ObjectUtils.nullSafeEquals(value, extractedValue)) {
			return true;
		}
		if (value instanceof Class && extractedValue instanceof String) {
			return areEquivalent((Class<?>) value, (String) extractedValue);
		}
		if (value instanceof Class[] && extractedValue instanceof String[]) {
			return areEquivalent((Class[]) value, (String[]) extractedValue);
		}
		if (value instanceof Annotation) {
			return areEquivalent((Annotation) value, extractedValue, valueExtractor);
		}
		return false;
	}

	private static boolean areEquivalent(Class<?>[] value, String[] extractedValue) {
		if (value.length != extractedValue.length) {
			return false;
		}
		for (int i = 0; i < value.length; i++) {
			if (!areEquivalent(value[i], extractedValue[i])) {
				return false;
			}
		}
		return true;
	}

	private static boolean areEquivalent(Class<?> value, String extractedValue) {
		return value.getName().equals(extractedValue);
	}

	private static boolean areEquivalent(Annotation annotation, @Nullable Object extractedValue,
			ValueExtractor valueExtractor) {

		AttributeMethods attributes = AttributeMethods.forAnnotationType(annotation.annotationType());
		for (int i = 0; i < attributes.size(); i++) {
			Method attribute = attributes.get(i);
			Object value1 = ReflectionUtils.invokeMethod(attribute, annotation);
			Object value2;
			if (extractedValue instanceof TypeMappedAnnotation) {
				value2 = ((TypeMappedAnnotation<?>) extractedValue).getValue(attribute.getName()).orElse(null);
			}
			else {
				value2 = valueExtractor.extract(attribute, extractedValue);
			}
			if (!areEquivalent(value1, value2, valueExtractor)) {
				return false;
			}
		}
		return true;
	}


	/**
	 * A collection of {@link MirrorSet} instances that provides details of all
	 * defined mirrors.
	 */
	class MirrorSets {

		private MirrorSet[] mirrorSets;

		private final MirrorSet[] assigned;

		MirrorSets() {
			this.assigned = new MirrorSet[attributes.size()];
			this.mirrorSets = EMPTY_MIRROR_SETS;
		}

		void updateFrom(Collection<Method> aliases) {
			MirrorSet mirrorSet = null;
			int size = 0;
			int last = -1;
			// 遍历属性方法
			for (int i = 0; i < attributes.size(); i++) {
				Method attribute = attributes.get(i);
				// 如果aliases中包含对应的属性方法
				if (aliases.contains(attribute)) {
					size++;
					// 当size > 1时
					if (size > 1) {
						// 如果mirrorSet为null，创建一个，并将assigned[last]赋值为mirrorSet
						if (mirrorSet == null) {
							mirrorSet = new MirrorSet();
							this.assigned[last] = mirrorSet;
						}
						// 否则，将assigned[i]赋值为mirrorSet
						this.assigned[i] = mirrorSet;
					}
					last = i;
				}
			}
			// 如果mirrorSet不为null
			if (mirrorSet != null) {
				// 调用mirrorSet的update方法
				mirrorSet.update();
				// 将assigned转换为set，并且去掉null元素
				Set<MirrorSet> unique = new LinkedHashSet<>(Arrays.asList(this.assigned));
				unique.remove(null);
				// 将set转换为数组并赋值给mirrorSets
				this.mirrorSets = unique.toArray(EMPTY_MIRROR_SETS);
			}
		}

		int size() {
			return this.mirrorSets.length;
		}

		MirrorSet get(int index) {
			return this.mirrorSets[index];
		}

		@Nullable
		MirrorSet getAssigned(int attributeIndex) {
			return this.assigned[attributeIndex];
		}

		int[] resolve(@Nullable Object source, @Nullable Object annotation, ValueExtractor valueExtractor) {
			// 根据属性方法创建一个int类型的数组result
			int[] result = new int[attributes.size()];
			// 先将result按下标赋值，表示属性方法的值来自自身的返回值
			for (int i = 0; i < result.length; i++) {
				result[i] = i;
			}
			// 遍历mirrorSets数组
			for (int i = 0; i < size(); i++) {
				MirrorSet mirrorSet = get(i);
				// 调用mirrorSet的resolve方法
				int resolved = mirrorSet.resolve(source, annotation, valueExtractor);
				// 循环mirrorSet的indexes，并且将result数组中index下标的值设置为resolved，
				// 表示index下标的方法的值来自resolved下标方法的返回值
				for (int j = 0; j < mirrorSet.size; j++) {
					result[mirrorSet.indexes[j]] = resolved;
				}
			}
			// 返回result数组
			return result;
		}


		/**
		 * A single set of mirror attributes.
		 */
		class MirrorSet {

			private int size;

			private final int[] indexes = new int[attributes.size()];

			void update() {
				this.size = 0;
				Arrays.fill(this.indexes, -1);
				// 遍历assigned数组
				for (int i = 0; i < MirrorSets.this.assigned.length; i++) {
					// 如果发现下标i的元素和自身相等
					if (MirrorSets.this.assigned[i] == this) {
						// 将indexes对应的size下标设置为i
						this.indexes[this.size] = i;
						// 然后size+1
						this.size++;
					}
				}
			}

			<A> int resolve(@Nullable Object source, @Nullable A annotation, ValueExtractor valueExtractor) {
				// 初始化result为-1
				int result = -1;
				Object lastValue = null;
				// 遍历MirrorSet的indexes
				for (int i = 0; i < this.size; i++) {
					// 获取到对应下标的属性方法
					Method attribute = attributes.get(this.indexes[i]);
					// 调用注解的属性方法获取value
					Object value = valueExtractor.extract(attribute, annotation);
					// 如果value为null或者value的值和默认值相等
					boolean isDefaultValue = (value == null ||
							isEquivalentToDefaultValue(attribute, value, valueExtractor));
					// 如果isDefaultValue为true 或者 lastValue和value相等
					if (isDefaultValue || ObjectUtils.nullSafeEquals(lastValue, value)) {
						// 如果result = -1的话，将result赋值为indexes[i]
						// 这一步是为了防止注解对应的两个属性方法的value都为null或者默认值，导致最后result直接为-1
						if (result == -1) {
							result = this.indexes[i];
						}
						continue;
					}
					// 如果lastValue不为null 并且 value不等于lastValue，这种情况表示annotation的两个用@AliasFor标注的属性方法的返回值不同。
					// 报错
					if (lastValue != null && !ObjectUtils.nullSafeEquals(lastValue, value)) {
						String on = (source != null) ? " declared on " + source : "";
						throw new AnnotationConfigurationException(String.format(
								"Different @AliasFor mirror values for annotation [%s]%s; attribute '%s' " +
								"and its alias '%s' are declared with values of [%s] and [%s].",
								getAnnotationType().getName(), on,
								attributes.get(result).getName(),
								attribute.getName(),
								ObjectUtils.nullSafeToString(lastValue),
								ObjectUtils.nullSafeToString(value)));
					}
					// 如果value不为null且不是默认值的话，result为index下标，表示取index为下标的属性方法的返回值作为结果
					result = this.indexes[i];
					// 并且将value赋值给lastValue
					lastValue = value;
				}
				// 返回result
				return result;
			}

			int size() {
				return this.size;
			}

			Method get(int index) {
				int attributeIndex = this.indexes[index];
				return attributes.get(attributeIndex);
			}

			int getAttributeIndex(int index) {
				return this.indexes[index];
			}
		}
	}

}
