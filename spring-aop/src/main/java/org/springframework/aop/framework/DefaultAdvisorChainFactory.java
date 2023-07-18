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

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aopalliance.intercept.Interceptor;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.IntroductionAwareMethodMatcher;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.lang.Nullable;

/**
 * A simple but definitive way of working out an advice chain for a Method,
 * given an {@link Advised} object. Always rebuilds each advice chain;
 * caching can be provided by subclasses.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Adrian Colyer
 * @since 2.0.3
 */
@SuppressWarnings("serial")
public class DefaultAdvisorChainFactory implements AdvisorChainFactory, Serializable {

	@Override
	public List<Object> getInterceptorsAndDynamicInterceptionAdvice(
			Advised config, Method method, @Nullable Class<?> targetClass) {

		// This is somewhat tricky... We have to process introductions first,
		// but we need to preserve order in the ultimate list.
		// 获取到DefaultAdvisorAdapterRegistry用于Advice和Advisor之间的转换
		AdvisorAdapterRegistry registry = GlobalAdvisorAdapterRegistry.getInstance();
		// 获取到advisedSupport持有的advisor数组
		Advisor[] advisors = config.getAdvisors();
		// 创建一个list用于存储最终的结果
		List<Object> interceptorList = new ArrayList<>(advisors.length);
		// 如果targetClass不为null的话，赋值给actualClass，否则使用声明method的类对象
		Class<?> actualClass = (targetClass != null ? targetClass : method.getDeclaringClass());
		Boolean hasIntroductions = null;

		// 遍历持有的advisor数组
		for (Advisor advisor : advisors) {
			// 如果advisor是属于PointcutAdvisor类型的
			if (advisor instanceof PointcutAdvisor) {
				// Add it conditionally.
				PointcutAdvisor pointcutAdvisor = (PointcutAdvisor) advisor;
				// 如果config的isPreFiltered为true，这里默认是true，因为在获取在findEligibleAdvisors的时候就已经进行过类型匹配了；
				// 如果为false，那么还需要使用pointcut的classFilter进行匹配一次。
				// 如果满足条件
				if (config.isPreFiltered() || pointcutAdvisor.getPointcut().getClassFilter().matches(actualClass)) {
					// 那么获取pointcut的MethodMatcher
					MethodMatcher mm = pointcutAdvisor.getPointcut().getMethodMatcher();
					boolean match;
					// 如果MethodMatch是IntroductionAwareMethodMatcher类型的
					if (mm instanceof IntroductionAwareMethodMatcher) {
						// 且hasIntroductions为null
						if (hasIntroductions == null) {
							// 那么判断是否存在introductions
							hasIntroductions = hasMatchingIntroductions(advisors, actualClass);
						}
						// 然后调用IntroductionAwareMethodMatcher的matches方法
						match = ((IntroductionAwareMethodMatcher) mm).matches(method, actualClass, hasIntroductions);
					}
					// 如果不是，则直接调用matches方法
					else {
						match = mm.matches(method, actualClass);
					}
					// 如果advisor和要调用的方法匹配
					if (match) {
						// 通过DefaultAdvisorAdapterRegistry将advisor转换为Advice的子类MethodInterceptor
						MethodInterceptor[] interceptors = registry.getInterceptors(advisor);
						// 如果这个MethodMatcher是需要动态匹配的
						if (mm.isRuntime()) {
							// Creating a new object instance in the getInterceptors() method
							// isn't a problem as we normally cache created chains.
							// 那么遍历查找到的MethodInterceptor，将MethodInterceptor和MethodMatcher封装成一个
							// InterceptorAndDynamicMethodMatcher添加到集合中
							for (MethodInterceptor interceptor : interceptors) {
								interceptorList.add(new InterceptorAndDynamicMethodMatcher(interceptor, mm));
							}
						}
						// 如果不需要动态匹配，那么直接将得到的MethodInterceptor添加到集合中
						else {
							interceptorList.addAll(Arrays.asList(interceptors));
						}
					}
				}
			}
			// 如果advisor是IntroductionAdvisor类型的
			else if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				// 如果之前已经匹配过类型 或者 类型匹配通过
				if (config.isPreFiltered() || ia.getClassFilter().matches(actualClass)) {
					// 将其转换为MethodInterceptor，添加进集合中
					Interceptor[] interceptors = registry.getInterceptors(advisor);
					interceptorList.addAll(Arrays.asList(interceptors));
				}
			}
			// 如果advisor属于其他类型
			else {
				// 也将它们转换为MethodInterceptor，添加到集合中
				Interceptor[] interceptors = registry.getInterceptors(advisor);
				interceptorList.addAll(Arrays.asList(interceptors));
			}
		}

		// 返回interceptor集合
		return interceptorList;
	}

	/**
	 * Determine whether the Advisors contain matching introductions.
	 */
	private static boolean hasMatchingIntroductions(Advisor[] advisors, Class<?> actualClass) {
		// 如果advisors中存在IntroductionAdvisor类型的，且classFilter匹配actualClass，返回true
		for (Advisor advisor : advisors) {
			if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				if (ia.getClassFilter().matches(actualClass)) {
					return true;
				}
			}
		}
		return false;
	}

}
