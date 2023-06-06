/*
 * Copyright 2002-2021 the original author or authors.
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

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Utilities for identifying {@link Configuration} classes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 */
abstract class ConfigurationClassUtils {

	public static final String CONFIGURATION_CLASS_FULL = "full";

	public static final String CONFIGURATION_CLASS_LITE = "lite";

	public static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	private static final String ORDER_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "order");


	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);

	private static final Set<String> candidateIndicators = new HashSet<>(8);

	static {
		candidateIndicators.add(Component.class.getName());
		candidateIndicators.add(ComponentScan.class.getName());
		candidateIndicators.add(Import.class.getName());
		candidateIndicators.add(ImportResource.class.getName());
	}


	/**
	 * Check whether the given bean definition is a candidate for a configuration class
	 * (or a nested component class declared within a configuration/component class,
	 * to be auto-registered as well), and mark it accordingly.
	 * @param beanDef the bean definition to check
	 * @param metadataReaderFactory the current factory in use by the caller
	 * @return whether the candidate qualifies as (any kind of) configuration class
	 */
	public static boolean checkConfigurationClassCandidate(
			BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {

		// 获取bd的beanClassName
		String className = beanDef.getBeanClassName();
		// 如果不存在className 或者 bd的factoryMethodName不为null的话，直接返回false，
		// 说明不是ConfigurationClassCandidate
		if (className == null || beanDef.getFactoryMethodName() != null) {
			return false;
		}

		AnnotationMetadata metadata;
		// 如果bd是AnnotatedBeanDefinition类型的 并且 beanClassName和metadata中的className相同，直接获取bd持有的AnnotationMetadata
		if (beanDef instanceof AnnotatedBeanDefinition &&
				className.equals(((AnnotatedBeanDefinition) beanDef).getMetadata().getClassName())) {
			// Can reuse the pre-parsed metadata from the given BeanDefinition...
			metadata = ((AnnotatedBeanDefinition) beanDef).getMetadata();
		}
		// 如果bd是AbstractBeanDefinition类型的，并且存在beanClass
		else if (beanDef instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) beanDef).hasBeanClass()) {
			// Check already loaded Class if present...
			// since we possibly can't even load the class file for this Class.
			Class<?> beanClass = ((AbstractBeanDefinition) beanDef).getBeanClass();
			// 判断beanClass是否是以下几种类型的，如果是，直接返回false
			if (BeanFactoryPostProcessor.class.isAssignableFrom(beanClass) ||
					BeanPostProcessor.class.isAssignableFrom(beanClass) ||
					AopInfrastructureBean.class.isAssignableFrom(beanClass) ||
					EventListenerFactory.class.isAssignableFrom(beanClass)) {
				return false;
			}
			// 根据beanClass生成一个StandardAnnotationMetadata类型的实例赋值给metadata
			metadata = AnnotationMetadata.introspect(beanClass);
		}
		// 否则，根据beanName，使用metadataReaderFactory生成一个metadataReader，再使用这个reader去读取metadata
		else {
			try {
				// 具体逻辑是：根据类名获取到对应的Resource，然后根据Resource生成一个SimpleMetadataReader用于读取元数据，
				// 在其初始化的时候，就会使用asm框架的ClassReader去读取resource对应的class文件的字节，然后使用SimpleAnnotationMetadataReadingVisitor
				// 去读取类的基本信息和注解信息，以及被注解标注的方法基本信息和方法上的注解，封装成一个SimpleAnnotationMetadata对象持有
				MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
				// 获取reader持有的AnnotationMetadata类型的对象
				metadata = metadataReader.getAnnotationMetadata();
			}
			catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not find class file for introspecting configuration annotations: " +
							className, ex);
				}
				return false;
			}
		}

		// 获取到类上标注的@Configuration注解的属性
		Map<String, Object> config = metadata.getAnnotationAttributes(Configuration.class.getName());
		// 如果注解存在，且proxyBeanMethods为true的话，设置bd的属性CONFIGURATION_CLASS_ATTRIBUTE为full
		if (config != null && !Boolean.FALSE.equals(config.get("proxyBeanMethods"))) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
		}
		// 如果标注了@Configuration注解 或者
		// 标注了@Component @ComponentScan @Import @ImportResource这四种注解其中一个 或者
		// 类中声明了标注了@Bean的方法
		// 那么也是ConfigurationClass的candidate，不过是lite类型的，将bd的属性CONFIGURATION_CLASS_ATTRIBUTE设置为lite
		else if (config != null || isConfigurationCandidate(metadata)) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
		}
		// 其他情况返回false，表示不是ConfigurationClass的候选
		else {
			return false;
		}

		// It's a full or lite configuration candidate... Let's determine the order value, if any.
		// 根据元数据获取order值，如果存在的话，将bd的ORDER_ATTRIBUTE属性设置为order的值
		Integer order = getOrder(metadata);
		if (order != null) {
			beanDef.setAttribute(ORDER_ATTRIBUTE, order);
		}

		// 最后返回true，表示是ConfigurationClass的候选
		return true;
	}

	/**
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered for
	 * configuration class processing; {@code false} otherwise
	 */
	public static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		// 如果metadata对表示的类是接口，直接返回false
		if (metadata.isInterface()) {
			return false;
		}

		// Any of the typical annotations found?
		// 查看对应的类上是否标注了@Component @ComponentScan @Import @ImportResource这四种注解之一，如果是，返回true
		for (String indicator : candidateIndicators) {
			if (metadata.isAnnotated(indicator)) {
				return true;
			}
		}

		// Finally, let's look for @Bean methods...
		// 判断类中是否声明了标注了@Bean注解的方法
		return hasBeanMethods(metadata);
	}

	static boolean hasBeanMethods(AnnotationMetadata metadata) {
		try {
			return metadata.hasAnnotatedMethods(Bean.class.getName());
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to introspect @Bean methods on class [" + metadata.getClassName() + "]: " + ex);
			}
			return false;
		}
	}

	/**
	 * Determine the order for the given configuration class metadata.
	 * @param metadata the metadata of the annotated class
	 * @return the {@code @Order} annotation value on the configuration class,
	 * or {@code Ordered.LOWEST_PRECEDENCE} if none declared
	 * @since 5.0
	 */
	@Nullable
	public static Integer getOrder(AnnotationMetadata metadata) {
		// 获取类上标注的@Order注解的属性map
		Map<String, Object> orderAttributes = metadata.getAnnotationAttributes(Order.class.getName());
		// 如果属性map不为null，获取value属性对应的值，否则返回null
		return (orderAttributes != null ? ((Integer) orderAttributes.get(AnnotationUtils.VALUE)) : null);
	}

	/**
	 * Determine the order for the given configuration class bean definition,
	 * as set by {@link #checkConfigurationClassCandidate}.
	 * @param beanDef the bean definition to check
	 * @return the {@link Order @Order} annotation value on the configuration class,
	 * or {@link Ordered#LOWEST_PRECEDENCE} if none declared
	 * @since 4.2
	 */
	public static int getOrder(BeanDefinition beanDef) {
		// 获取bd中之前设置的order属性
		Integer order = (Integer) beanDef.getAttribute(ORDER_ATTRIBUTE);
		// 如果order不存在，返回最低的优先级
		return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
	}

}
