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

import java.lang.reflect.Method;
import java.util.Map;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * Utilities for processing {@link Bean}-annotated methods.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 */
abstract class BeanAnnotationHelper {

	private static final Map<Method, String> beanNameCache = new ConcurrentReferenceHashMap<>();

	private static final Map<Method, Boolean> scopedProxyCache = new ConcurrentReferenceHashMap<>();


	public static boolean isBeanAnnotated(Method method) {
		return AnnotatedElementUtils.hasAnnotation(method, Bean.class);
	}

	public static String determineBeanNameFor(Method beanMethod) {
		// 尝试从缓存中获取beanMethod对应的beanName
		String beanName = beanNameCache.get(beanMethod);
		// 如果缓存不存在
		if (beanName == null) {
			// By default, the bean name is the name of the @Bean-annotated method
			// 默认去方法名作为beanName
			beanName = beanMethod.getName();
			// Check to see if the user has explicitly set a custom bean name...
			AnnotationAttributes bean =
					AnnotatedElementUtils.findMergedAnnotationAttributes(beanMethod, Bean.class, false, false);
			// 判断@Bean注解是否存在，如果存在，判断其name属性是否有值，如果有的话，将name属性的第一个元素作为beanName
			if (bean != null) {
				String[] names = bean.getStringArray("name");
				if (names.length > 0) {
					beanName = names[0];
				}
			}
			// 将解析出来的beanName放入缓存
			beanNameCache.put(beanMethod, beanName);
		}
		return beanName;
	}

	public static boolean isScopedProxy(Method beanMethod) {
		Boolean scopedProxy = scopedProxyCache.get(beanMethod);
		if (scopedProxy == null) {
			AnnotationAttributes scope =
					AnnotatedElementUtils.findMergedAnnotationAttributes(beanMethod, Scope.class, false, false);
			scopedProxy = (scope != null && scope.getEnum("proxyMode") != ScopedProxyMode.NO);
			scopedProxyCache.put(beanMethod, scopedProxy);
		}
		return scopedProxy;
	}

}
