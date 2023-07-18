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

package org.springframework.aop.aspectj;

import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;

/**
 * Utility methods for working with AspectJ proxies.
 *
 * @author Rod Johnson
 * @author Ramnivas Laddad
 * @author Juergen Hoeller
 * @since 2.0
 */
public abstract class AspectJProxyUtils {

	/**
	 * Add special advisors if necessary to work with a proxy chain that contains AspectJ advisors:
	 * concretely, {@link ExposeInvocationInterceptor} at the beginning of the list.
	 * <p>This will expose the current Spring AOP invocation (necessary for some AspectJ pointcut
	 * matching) and make available the current AspectJ JoinPoint. The call will have no effect
	 * if there are no AspectJ advisors in the advisor chain.
	 * @param advisors the advisors available
	 * @return {@code true} if an {@link ExposeInvocationInterceptor} was added to the list,
	 * otherwise {@code false}
	 */
	public static boolean makeAdvisorChainAspectJCapableIfNecessary(List<Advisor> advisors) {
		// Don't add advisors to an empty list; may indicate that proxying is just not required
		// 如果advisors不为空
		if (!advisors.isEmpty()) {
			boolean foundAspectJAdvice = false;
			// 尝试从advisors查找包含有AspectJ类型的advice的advisor，遍历advisors
			for (Advisor advisor : advisors) {
				// Be careful not to get the Advice without a guard, as this might eagerly
				// instantiate a non-singleton AspectJ aspect...
				// 如果advisor包含了AspectJ类型的advice
				if (isAspectJAdvice(advisor)) {
					// 将foundAspectAdvice置为true
					foundAspectJAdvice = true;
					break;
				}
			}
			// 如果找到了AspectJ类型的Advice，并且advisors中不包含ExposeInvocationInterceptor.ADVISOR
			if (foundAspectJAdvice && !advisors.contains(ExposeInvocationInterceptor.ADVISOR)) {
				// 那么将ExposeInvocationInterceptor.ADVISOR添加到advisors的第一个位置，返回true
				advisors.add(0, ExposeInvocationInterceptor.ADVISOR);
				return true;
			}
		}
		// 否则返回false
		return false;
	}

	/**
	 * Determine whether the given Advisor contains an AspectJ advice.
	 * @param advisor the Advisor to check
	 */
	private static boolean isAspectJAdvice(Advisor advisor) {
		// 如果advisor是InstantiationModelAwarePointcutAdvisor类型的
		// 或者 advisor的advice是AbstractAspectJAdvice的
		// 或者 advisor是PointcutAdvisor并且其pointcut是AspectJExpressionPointcut类型的
		// 那么返回true，说明该advisor包含有AspectJ的advice
		return (advisor instanceof InstantiationModelAwarePointcutAdvisor ||
				advisor.getAdvice() instanceof AbstractAspectJAdvice ||
				(advisor instanceof PointcutAdvisor &&
						((PointcutAdvisor) advisor).getPointcut() instanceof AspectJExpressionPointcut));
	}

}
