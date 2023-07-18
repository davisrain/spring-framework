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

package org.springframework.aop.framework.adapter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/**
 * Default implementation of the {@link AdvisorAdapterRegistry} interface.
 * Supports {@link org.aopalliance.intercept.MethodInterceptor},
 * {@link org.springframework.aop.MethodBeforeAdvice},
 * {@link org.springframework.aop.AfterReturningAdvice},
 * {@link org.springframework.aop.ThrowsAdvice}.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Juergen Hoeller
 */
@SuppressWarnings("serial")
public class DefaultAdvisorAdapterRegistry implements AdvisorAdapterRegistry, Serializable {

	private final List<AdvisorAdapter> adapters = new ArrayList<>(3);


	/**
	 * Create a new DefaultAdvisorAdapterRegistry, registering well-known adapters.
	 */
	public DefaultAdvisorAdapterRegistry() {
		registerAdvisorAdapter(new MethodBeforeAdviceAdapter());
		registerAdvisorAdapter(new AfterReturningAdviceAdapter());
		registerAdvisorAdapter(new ThrowsAdviceAdapter());
	}


	@Override
	public Advisor wrap(Object adviceObject) throws UnknownAdviceTypeException {
		// 如果传入的对象本身就是Advisor类型的，直接返回
		if (adviceObject instanceof Advisor) {
			return (Advisor) adviceObject;
		}
		// 如果传入的对象不是Advice类型的，报错
		if (!(adviceObject instanceof Advice)) {
			throw new UnknownAdviceTypeException(adviceObject);
		}
		Advice advice = (Advice) adviceObject;
		// 如果advice是属于MethodInterceptor类型的
		if (advice instanceof MethodInterceptor) {
			// So well-known it doesn't even need an adapter.
			// 将其包装成一个DefaultPointcutAdvisor返回
			return new DefaultPointcutAdvisor(advice);
		}
		// 否则遍历adapters集合
		for (AdvisorAdapter adapter : this.adapters) {
			// Check that it is supported.
			// 如果存在某个adapter支持传入的advice
			if (adapter.supportsAdvice(advice)) {
				// 那么也将其包装成DefaultPointcutAdvisor返回
				return new DefaultPointcutAdvisor(advice);
			}
		}
		throw new UnknownAdviceTypeException(advice);
	}

	@Override
	public MethodInterceptor[] getInterceptors(Advisor advisor) throws UnknownAdviceTypeException {
		List<MethodInterceptor> interceptors = new ArrayList<>(3);
		// 获取advisor中持有的advice
		Advice advice = advisor.getAdvice();
		// 如果advice是属于MethodInterceptor类型的，直接添加进集合中便可
		if (advice instanceof MethodInterceptor) {
			interceptors.add((MethodInterceptor) advice);
		}
		// 否则遍历持有的适配器集合
		for (AdvisorAdapter adapter : this.adapters) {
			// 如果发现某个适配器支持这种类型的advice
			if (adapter.supportsAdvice(advice)) {
				// 那么调用适配器的getInterceptor方法将advisor转换为MethodInterceptor类型的，然后添加进集合
				interceptors.add(adapter.getInterceptor(advisor));
			}
		}
		// 如果最终集合为空，报错
		if (interceptors.isEmpty()) {
			throw new UnknownAdviceTypeException(advisor.getAdvice());
		}
		// 将集合转换成数组返回
		return interceptors.toArray(new MethodInterceptor[0]);
	}

	@Override
	public void registerAdvisorAdapter(AdvisorAdapter adapter) {
		this.adapters.add(adapter);
	}

}
