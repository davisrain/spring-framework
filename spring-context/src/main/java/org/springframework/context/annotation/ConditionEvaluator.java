/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.context.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MultiValueMap;

/**
 * Internal class used to evaluate {@link Conditional} annotations.
 *
 * @author Phillip Webb
 * @author Juergen Hoeller
 * @since 4.0
 */
class ConditionEvaluator {

	private final ConditionContextImpl context;


	/**
	 * Create a new {@link ConditionEvaluator} instance.
	 */
	public ConditionEvaluator(@Nullable BeanDefinitionRegistry registry,
			@Nullable Environment environment, @Nullable ResourceLoader resourceLoader) {

		// 根据registry， environment，resourceLoader初始化一个ConditionContextImpl持有
		this.context = new ConditionContextImpl(registry, environment, resourceLoader);
	}


	/**
	 * Determine if an item should be skipped based on {@code @Conditional} annotations.
	 * The {@link ConfigurationPhase} will be deduced from the type of item (i.e. a
	 * {@code @Configuration} class will be {@link ConfigurationPhase#PARSE_CONFIGURATION})
	 * @param metadata the meta data
	 * @return if the item should be skipped
	 */
	public boolean shouldSkip(AnnotatedTypeMetadata metadata) {
		return shouldSkip(metadata, null);
	}

	/**
	 * Determine if an item should be skipped based on {@code @Conditional} annotations.
	 * @param metadata the meta data
	 * @param phase the phase of the call
	 * @return if the item should be skipped
	 */
	public boolean shouldSkip(@Nullable AnnotatedTypeMetadata metadata, @Nullable ConfigurationPhase phase) {
		// 如果metadata为null或者metadata上没有标注@Conditional注解，返回false，表示不需要被跳过
		if (metadata == null || !metadata.isAnnotated(Conditional.class.getName())) {
			return false;
		}

		// 如果phase为null
		if (phase == null) {
			// 如果metadata是AnnotationMetadata并且metadata是ConfigurationClass的候选，递归调用本方法且传入PARSE_CONFIGURATION的阶段枚举
			if (metadata instanceof AnnotationMetadata &&
					ConfigurationClassUtils.isConfigurationCandidate((AnnotationMetadata) metadata)) {
				return shouldSkip(metadata, ConfigurationPhase.PARSE_CONFIGURATION);
			}
			// 否则传入REGISTER_BEAN的阶段枚举
			return shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN);
		}

		// 根据metadata获取到@Conditional注解里的内容，并将其整理为一个Condition类的集合
		List<Condition> conditions = new ArrayList<>();
		// getConditionClasses方法获取metadata上标注的所有@Conditional注解中value的值，并且整合为一个集合。
		// 遍历这个集合，得到每个@Condition注解的value值，是一个String类型的数组，里面对应的是Condition类的全限定名
		for (String[] conditionClasses : getConditionClasses(metadata)) {
			// 然后遍历Condition类的类名
			for (String conditionClass : conditionClasses) {
				// 调用getCondition获取对应的实例，并将其加入到conditions集合中
				Condition condition = getCondition(conditionClass, this.context.getClassLoader());
				conditions.add(condition);
			}
		}

		// 根据PriorityOrdered接口 Ordered接口 @Order @Priority注解的order值进行排序
		AnnotationAwareOrderComparator.sort(conditions);

		// 遍历这些condition实例
		for (Condition condition : conditions) {
			ConfigurationPhase requiredPhase = null;
			// 如果condition是ConfigurationCondition类型的，获取其配置阶段
			if (condition instanceof ConfigurationCondition) {
				requiredPhase = ((ConfigurationCondition) condition).getConfigurationPhase();
			}
			// 如果要求的阶段为null或和当前阶段相等 并且 condition类判断出不匹配，那么该配置类需要被过滤，返回true
			if ((requiredPhase == null || requiredPhase == phase) && !condition.matches(this.context, metadata)) {
				return true;
			}
		}

		// 如果检查完了所有的Condition类，都匹配条件，返回false，不用被过滤
		return false;
	}

	@SuppressWarnings("unchecked")
	private List<String[]> getConditionClasses(AnnotatedTypeMetadata metadata) {
		MultiValueMap<String, Object> attributes = metadata.getAllAnnotationAttributes(Conditional.class.getName(), true);
		Object values = (attributes != null ? attributes.get("value") : null);
		return (List<String[]>) (values != null ? values : Collections.emptyList());
	}

	private Condition getCondition(String conditionClassName, @Nullable ClassLoader classloader) {
		// 根据类名加载类
		Class<?> conditionClass = ClassUtils.resolveClassName(conditionClassName, classloader);
		// 初始化实例
		return (Condition) BeanUtils.instantiateClass(conditionClass);
	}


	/**
	 * Implementation of a {@link ConditionContext}.
	 */
	private static class ConditionContextImpl implements ConditionContext {

		@Nullable
		private final BeanDefinitionRegistry registry;

		@Nullable
		private final ConfigurableListableBeanFactory beanFactory;

		private final Environment environment;

		private final ResourceLoader resourceLoader;

		@Nullable
		private final ClassLoader classLoader;

		public ConditionContextImpl(@Nullable BeanDefinitionRegistry registry,
				@Nullable Environment environment, @Nullable ResourceLoader resourceLoader) {

			this.registry = registry;
			// 根据registry推断beanFactory并赋值
			this.beanFactory = deduceBeanFactory(registry);
			// 如果environment不为null，根据registry推断environment并赋值
			this.environment = (environment != null ? environment : deduceEnvironment(registry));
			// 如果resourceLoader不为null，根据registry推断resourceLoader并赋值
			this.resourceLoader = (resourceLoader != null ? resourceLoader : deduceResourceLoader(registry));
			// 根据resourceLoader和beanFactory推断classLoader并赋值
			this.classLoader = deduceClassLoader(resourceLoader, this.beanFactory);
		}

		@Nullable
		private ConfigurableListableBeanFactory deduceBeanFactory(@Nullable BeanDefinitionRegistry source) {
			if (source instanceof ConfigurableListableBeanFactory) {
				return (ConfigurableListableBeanFactory) source;
			}
			if (source instanceof ConfigurableApplicationContext) {
				return (((ConfigurableApplicationContext) source).getBeanFactory());
			}
			return null;
		}

		private Environment deduceEnvironment(@Nullable BeanDefinitionRegistry source) {
			if (source instanceof EnvironmentCapable) {
				return ((EnvironmentCapable) source).getEnvironment();
			}
			return new StandardEnvironment();
		}

		private ResourceLoader deduceResourceLoader(@Nullable BeanDefinitionRegistry source) {
			if (source instanceof ResourceLoader) {
				return (ResourceLoader) source;
			}
			return new DefaultResourceLoader();
		}

		@Nullable
		private ClassLoader deduceClassLoader(@Nullable ResourceLoader resourceLoader,
				@Nullable ConfigurableListableBeanFactory beanFactory) {

			// 如果resourceLoader不为null的话，尝试获取resourceLoader中的classLoader，如果不为null，直接返回
			if (resourceLoader != null) {
				ClassLoader classLoader = resourceLoader.getClassLoader();
				if (classLoader != null) {
					return classLoader;
				}
			}
			// 如果上述操作没有获取到classLoader，如果beanFactory不为null的话，获取其beanClassLoader返回
			if (beanFactory != null) {
				return beanFactory.getBeanClassLoader();
			}
			// 上述操作都没有获取到，调用ClassUtils.getDefaultClassLoader返回
			return ClassUtils.getDefaultClassLoader();
		}

		@Override
		public BeanDefinitionRegistry getRegistry() {
			Assert.state(this.registry != null, "No BeanDefinitionRegistry available");
			return this.registry;
		}

		@Override
		@Nullable
		public ConfigurableListableBeanFactory getBeanFactory() {
			return this.beanFactory;
		}

		@Override
		public Environment getEnvironment() {
			return this.environment;
		}

		@Override
		public ResourceLoader getResourceLoader() {
			return this.resourceLoader;
		}

		@Override
		@Nullable
		public ClassLoader getClassLoader() {
			return this.classLoader;
		}
	}

}
