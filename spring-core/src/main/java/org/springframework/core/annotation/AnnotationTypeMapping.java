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

		// source是标注了该注解的注解，比如@Service就在声明中标注了@Component，因此@Service是@Component的source
		this.source = source;
		// 如果source不为null，设置为source的root，如果为null，设置为自己。
		// 比如，@Component的source是@Service @Service由于没有source，所以root是自身，那么@Component的root也就是@Service
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
		// 解析标注了@AliasFor的属性方法，构造一个map将@AliasFor中指向的Method作为key，将标注了@AliasFor的属性方法集合作为value
		this.aliasedBy = resolveAliasedForTargets();
		// 处理aliases
		processAliases();
		// 为根注解和元注解同名的属性强制设置别名，根注解就是root指向的注解，元注解就是最底层的注解
		// 比如@Service中 @Service就是根注解，@Component就是元注解。还可以称@Service为子注解 @Component是父注解
		addConventionMappings();
		// 为非根注解的子注解和元注解同名的属性强制设置别名，比如有三层结构，比如@RestController上标注了@Controller，@Controller上又标注了@Component。
		// 那么@Controller是非根注解的子注解，@Component是元注解
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
			// 避免重复创建集合
			aliases.clear();
			// 将下标i对应的属性方法加入到list中
			aliases.add(this.attributes.get(i));
			// 1.收集别名
			// 该方法可以收集到 source链 上所有以 下标i对应的属性方法 为别名的 其他属性方法(标注了@AliasFor("attribute.getName()")的方法)。
			// 比如：attribute如果对应的是@Component的value方法，
			// @Component有source @Service的话，因为@Service的value方法上标注了@AliasFor(value = "value", annotation = "Component.class")，
			// 那么@Service的AnnotationTypeMapping的aliasedBy中有(value(Component:Method), [value(Service:Method)])，
			// 所以@Service的value方法也会被收集到aliases集合中去。那么aliases中的结构是[value(Component:Method), value(Service:Method)]
			collectAliases(aliases);
			// 如果aliases的size大于1的话，说明根据属性方法取到了对应的别名方法
			if (aliases.size() > 1) {
				// 2.处理别名
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
				// 尝试从mapping的aliasedBy属性中取到下标为j对应的属性方法的别名方法
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
		// 确认别名链上，是否有别名来自于root
		int rootAttributeIndex = getFirstRootAttributeIndex(aliases);
		// 将this赋值给mapping
		AnnotationTypeMapping mapping = this;
		// 从当前注解向root递归
		while (mapping != null) {
			// 若有当前正在处理的注解中：
			// 1.有别名字段来自于root；
			// 2.别名链中有一个别名来自于该注解；
			// 则在当前处理的注解的aliasMappings上，记录这个来自于root的别名属性，表示它存在一个来自root的别名
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
			// 更新mapping持有的mirrorSets，用于记录这个mapping中有多少对属性相关联
			mapping.mirrorSets.updateFrom(aliases);
			// 将aliases添加进claimedAliases集合中
			mapping.claimedAliases.addAll(aliases);
			// 如果mapping的annotation不为null的话
			if (mapping.annotation != null) {
				// 调用mirrorSets的resolve方法，返回一个int数组，表示了mapping中的各个属性的取值索引
				int[] resolvedMirrors = mapping.mirrorSets.resolve(null,
						mapping.annotation, ReflectionUtils::invokeMethod);
				// 遍历当前mapping的属性方法
				for (int i = 0; i < mapping.attributes.size(); i++) {
					// 如果别名链集合中存在当前mapping的属性方法的话
					if (aliases.contains(mapping.attributes.get(i))) {
						// 设置this对象的attributeIndex对应的属性的取值下标为resolvedMirrors[i]，该属性要和annotationValueSource配合使用
						// 设置this对象的attributeIndex对应的属性的取值mapping为当前mapping。
						// 因此this对象attributeIndex下标的属性取值方式就是从mapping对象的resolvedMirrors[i]位置的属性去取值。
						// 所以在同一组别名链中，越靠近root的属性值优先级越高，会覆盖远离root的属性值。
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
		// 如果distance为0，表示自身就是根注解，那么直接返回
		if (this.distance == 0) {
			return;
		}
		// 拿到root注解的属性方法聚合对象
		AttributeMethods rootAttributes = this.root.getAttributes();
		int[] mappings = this.conventionMappings;
		// 遍历conventionMappings数组
		for (int i = 0; i < mappings.length; i++) {
			// 获取自身下标i位置的属性方法的名称
			String name = this.attributes.get(i).getName();
			// 获取下标i位置的MirrorSet，如果不为null的话，说明下标i和自身的其他属性方法构成了别名
			MirrorSet mirrors = getMirrorSets().getAssigned(i);
			// 查找root注解的属性方法中名称等于name的下标，如果不存在，返回-1
			int mapped = rootAttributes.indexOf(name);
			// 如果name不是等于value字符串 并且 root注解中存在相同名称的属性方法
			if (!MergedAnnotation.VALUE.equals(name) && mapped != -1) {
				// 将conventionMappings下标i的元素更新为root注解同名属性方法的下标mapped
				mappings[i] = mapped;
				// 如果MirrorSet不为null，将MirrorSet所关联的属性方法的下标位置在conventionMappings中的元素也映射为mapped
				if (mirrors != null) {
					for (int j = 0; j < mirrors.size(); j++) {
						mappings[mirrors.getAttributeIndex(j)] = mapped;
					}
				}
			}
		}
	}

	private void addConventionAnnotationValues() {
		// 遍历元注解的属性方法集合对象
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			// 判断属性方法名称是否等于value字符串
			boolean isValueAttribute = MergedAnnotation.VALUE.equals(attribute.getName());
			AnnotationTypeMapping mapping = this;
			// 从元注解开始遍历，当遍历到根注解的时候跳出循环
			while (mapping != null && mapping.distance > 0) {
				// 查找当前mapping的属性方法中和元注解的attribute属性方法同名的下标，如果不存在，返回-1
				int mapped = mapping.getAttributes().indexOf(attribute.getName());
				// 如果存在同名方法 且 判断出当前mapping的优先级更高
				if (mapped != -1 && isBetterConventionAnnotationValue(i, isValueAttribute, mapping)) {
					// 将annotationValueMappings和annotationValueSource中的内容更新掉
					this.annotationValueMappings[i] = mapped;
					this.annotationValueSource[i] = mapping;
				}
				// 将mapping替换为mapping.source
				mapping = mapping.source;
			}
		}
	}

	private boolean isBetterConventionAnnotationValue(int index, boolean isValueAttribute,
			AnnotationTypeMapping mapping) {

		// 如果annotationValueMappings数组index位置的元素为-1，表示还没有映射关系，那么直接返回true
		if (this.annotationValueMappings[index] == -1) {
			return true;
		}
		// 否则的话，获取index在annotationValueSource数组中的mapping元素，查看它的distance
		int existingDistance = this.annotationValueSource[index].distance;
		// 当新的mapping的distance更小，且方法不是value方法的时候，返回true。
		// 说明越靠近根注解，优先级越高
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
		// 如果注解的属性方法中返回值有是注解或者是注解数组的
		if (getAttributes().hasNestedAnnotation()) {
			AttributeMethods attributeMethods = getAttributes();
			for (int i = 0; i < attributeMethods.size(); i++) {
				Method method = attributeMethods.get(i);
				Class<?> type = method.getReturnType();
				if (type.isAnnotation() || (type.isArray() && type.getComponentType().isAnnotation())) {
					// 拿到返回值的注解类型，然后查看该返回类型的注解是否是可合成的
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
		// 检查所有标注了@AliasFor注解的属性方法中@AliasFor annotation属性指向的注解是否存在于该注解的元注解中，如果不存在的话，报错
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
		// 检查互为MirrorSet的属性方法是否都存在默认值 且默认值相等
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
		// 根据attributeIndex获取属性方法的映射索引mappedIndex
		int mappedIndex = this.annotationValueMappings[attributeIndex];
		// 如果mappedIndex为-1，说明该下标的属性方法没有非根注解的元注解的属性方法映射，直接返回null
		if (mappedIndex == -1) {
			return null;
		}
		// 否则的话，根据attributeIndex获取对应的元注解映射mapping
		AnnotationTypeMapping source = this.annotationValueSource[attributeIndex];
		// 如果source等于自身的话，并且要求必须从元注解中取值，那么直接返回null
		if (source == this && metaAnnotationsOnly) {
			return null;
		}
		// 根据元注解的实例 和 元注解对应的属性方法进行反射调用，或者值返回
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
				// 如果aliases中包含对应的属性方法，表示该注解存在属性位于别名链中
				if (aliases.contains(attribute)) {
					// 将size+1
					size++;
					// 当size > 1时，表示该注解存在大于1个的属性处于别名链中，因此需要多个属性方法关联起来。
					// 即创建一个MirrorSet，将assigned数组中 属性对应下标的元素都设置为这个MirrorSet
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
				// 用mirrorSet中的size记录有多少个相关联的属性，并且用indexes数组记录每个属性对应的下标
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
				// 调用mirrorSet的resolve方法，该方法将返回一个属性方法下标resolved，
				// 表示MirrorSet所关联的所有属性的值都从由该resolved指向的属性来获取
				int resolved = mirrorSet.resolve(source, annotation, valueExtractor);
				// 因此循环mirrorSet的indexes，每个index表示这个mirrorSet所关联的属性在attributes中的下标位置，
				// 将result数组中的index下标的值设置为上一步得到的resolved，也就表示这些属性的取值都取决于resolved指向的那个属性
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
					// 调用注解的属性方法获取value(也可能不是反射调用，比如TypeMappedAnnotation的构造方法中的TypeMappedAnnotation::getValueForMirrorResolution)
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
