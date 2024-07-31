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

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Complete implementation of the
 * {@link org.springframework.beans.factory.support.AutowireCandidateResolver} strategy
 * interface, providing support for qualifier annotations as well as for lazy resolution
 * driven by the {@link Lazy} annotation in the {@code context.annotation} package.
 *
 * AutowireCandidateResolver这个策略接口的完整实现，
 * 提供了对qualifier注解的支持 以及 对context.annotation包下的@Lazy注解的懒加载的支持
 *
 * @author Juergen Hoeller
 * @since 4.0
 */
public class ContextAnnotationAutowireCandidateResolver extends QualifierAnnotationAutowireCandidateResolver {

	@Override
	@Nullable
	public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, @Nullable String beanName) {
		// 判断descriptor是否是懒加载的，如果是，构建懒解析的代理，否则返回null
		return (isLazy(descriptor) ? buildLazyResolutionProxy(descriptor, beanName) : null);
	}

	protected boolean isLazy(DependencyDescriptor descriptor) {
		// 1.第一步是判断字段 或者 方法的参数上有没有标注@Lazy注解，且注解value为true
		// 获取依赖描述上标注的所有注解并遍历
		for (Annotation ann : descriptor.getAnnotations()) {
			// 根据对应的注解获取@Lazy注解
			Lazy lazy = AnnotationUtils.getAnnotation(ann, Lazy.class);
			// 如果存在@Lazy注解并且value属性是true，那么返回true
			if (lazy != null && lazy.value()) {
				return true;
			}
		}
		// 2.第二步是判断DependencyDescriptor是否是方法参数，且方法是构造方法，并且构造方法上标注了@Lazy注解，并且value为true
		// 如果上述没有判断出来，尝试获取依赖描述的方法参数
		MethodParameter methodParam = descriptor.getMethodParameter();
		if (methodParam != null) {
			// 获取方法参数对应的方法，如果是构造器的话，返回null
			Method method = methodParam.getMethod();
			// 如果是构造器 或者 方法的返回值是void(说明是set方法)
			if (method == null || void.class == method.getReturnType()) {
				// 获取方法上标注的@Lazy注解
				Lazy lazy = AnnotationUtils.getAnnotation(methodParam.getAnnotatedElement(), Lazy.class);
				// 然后进行判断
				if (lazy != null && lazy.value()) {
					return true;
				}
			}
		}
		// 如果上面的都步骤都没有返回的话，返回false
		return false;
	}

	protected Object buildLazyResolutionProxy(final DependencyDescriptor descriptor, final @Nullable String beanName) {
		BeanFactory beanFactory = getBeanFactory();
		Assert.state(beanFactory instanceof DefaultListableBeanFactory,
				"BeanFactory needs to be a DefaultListableBeanFactory");
		final DefaultListableBeanFactory dlbf = (DefaultListableBeanFactory) beanFactory;

		TargetSource ts = new TargetSource() {
			@Override
			public Class<?> getTargetClass() {
				return descriptor.getDependencyType();
			}
			@Override
			public boolean isStatic() {
				return false;
			}
			@Override
			// 在getTarget逻辑里面才去真正的解析对应的依赖项
			public Object getTarget() {
				Set<String> autowiredBeanNames = (beanName != null ? new LinkedHashSet<>(1) : null);
				Object target = dlbf.doResolveDependency(descriptor, beanName, autowiredBeanNames, null);
				// 如果没有找到对应的bean
				if (target == null) {
					// 判断依赖项的类型，如果是集合类型的，创建空集合返回
					Class<?> type = getTargetClass();
					if (Map.class == type) {
						return Collections.emptyMap();
					}
					else if (List.class == type) {
						return Collections.emptyList();
					}
					else if (Set.class == type || Collection.class == type) {
						return Collections.emptySet();
					}
					// 否则，抛出异常
					throw new NoSuchBeanDefinitionException(descriptor.getResolvableType(),
							"Optional dependency not present for lazy injection point");
				}
				// 并且将依赖关系注入到DefaultSingletonBeanRegistry中，表示autowiredBeanName被beanName所依赖，以及beanName依赖了autowiredBeanName
				if (autowiredBeanNames != null) {
					for (String autowiredBeanName : autowiredBeanNames) {
						if (dlbf.containsBean(autowiredBeanName)) {
							dlbf.registerDependentBean(autowiredBeanName, beanName);
						}
					}
				}
				return target;
			}
			@Override
			public void releaseTarget(Object target) {
			}
		};

		// 创建一个代理类并返回
		ProxyFactory pf = new ProxyFactory();
		// 自主修改源代码，设置暴露代理对象，方便测试
		pf.setExposeProxy(true);
		pf.setTargetSource(ts);
		Class<?> dependencyType = descriptor.getDependencyType();
		if (dependencyType.isInterface()) {
			pf.addInterface(dependencyType);
		}
		return pf.getProxy(dlbf.getBeanClassLoader());
	}

}
