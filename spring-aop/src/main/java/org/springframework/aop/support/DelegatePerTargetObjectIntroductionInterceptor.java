/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.aop.support;

import java.util.Map;
import java.util.WeakHashMap;

import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.DynamicIntroductionAdvice;
import org.springframework.aop.IntroductionInterceptor;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 * Convenient implementation of the
 * {@link org.springframework.aop.IntroductionInterceptor} interface.
 *
 * <p>This differs from {@link DelegatingIntroductionInterceptor} in that a single
 * instance of this class can be used to advise multiple target objects, and each target
 * object will have its <i>own</i> delegate (whereas DelegatingIntroductionInterceptor
 * shares the same delegate, and hence the same state across all targets).
 *
 * <p>The {@code suppressInterface} method can be used to suppress interfaces
 * implemented by the delegate class but which should not be introduced to the
 * owning AOP proxy.
 *
 * <p>An instance of this class is serializable if the delegates are.
 *
 * <p><i>Note: There are some implementation similarities between this class and
 * {@link DelegatingIntroductionInterceptor} that suggest a possible refactoring
 * to extract a common ancestor class in the future.</i>
 *
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @since 2.0
 * @see #suppressInterface
 * @see DelegatingIntroductionInterceptor
 */
@SuppressWarnings("serial")
public class DelegatePerTargetObjectIntroductionInterceptor extends IntroductionInfoSupport
		implements IntroductionInterceptor {

	/**
	 * Hold weak references to keys as we don't want to interfere with garbage collection..
	 */
	private final Map<Object, Object> delegateMap = new WeakHashMap<>();

	private Class<?> defaultImplType;

	private Class<?> interfaceType;


	public DelegatePerTargetObjectIntroductionInterceptor(Class<?> defaultImplType, Class<?> interfaceType) {
		this.defaultImplType = defaultImplType;
		this.interfaceType = interfaceType;
		// Create a new delegate now (but don't store it in the map).
		// We do this for two reasons:
		// 1) to fail early if there is a problem instantiating delegates
		// 2) to populate the interface map once and once only

		// 根据defaultImplType创建一个实例作为委托对象，
		// 用于在调用接口中的方法的时候委托给这个对象来执行
		Object delegate = createNewDelegate();
		// 获取delegate及其父类实现的所有接口，并存入到publishedInterfaces中
		implementInterfacesOnObject(delegate);
		// 从publishedInterfaces中将IntroductionInterceptor 和 DynamicIntroductionAdvice接口删除
		suppressInterface(IntroductionInterceptor.class);
		suppressInterface(DynamicIntroductionAdvice.class);
	}


	/**
	 * Subclasses may need to override this if they want to perform custom
	 * behaviour in around advice. However, subclasses should invoke this
	 * method, which handles introduced interfaces and forwarding to the target.
	 */
	@Override
	@Nullable
	public Object invoke(MethodInvocation mi) throws Throwable {
		// 查看MethodInvocation中要调用的method是否存在于interfaceType这个接口中
		if (isMethodOnIntroducedInterface(mi)) {
			// 如果是，根据 被代理的对象 获取 要调用这个接口方法的委托对象
			Object delegate = getIntroductionDelegateFor(mi.getThis());

			// Using the following method rather than direct reflection,
			// we get correct handling of InvocationTargetException
			// if the introduced method throws an exception.
			// 反射调用该方法，调用方法的实例对象为委托对象，并取得返回值
			Object retVal = AopUtils.invokeJoinpointUsingReflection(delegate, mi.getMethod(), mi.getArguments());

			// Massage return value if possible: if the delegate returned itself,
			// we really want to return the proxy.
			// 如果返回值的委托对象自己的话，并且mi是ProxyMethodInvocation类型的，
			// 那么我们真正想要返回的应该是代理对象自己，所以从mi中获取代理对象赋值给返回值
			if (retVal == delegate && mi instanceof ProxyMethodInvocation) {
				retVal = ((ProxyMethodInvocation) mi).getProxy();
			}
			// 将返回值返回
			return retVal;
		}

		// 如果要调用的方法 不属于 能够委托给 委托对象 的方法，调用mi的proceed方法，执行下一个MethodInterceptor
		return doProceed(mi);
	}

	/**
	 * Proceed with the supplied {@link org.aopalliance.intercept.MethodInterceptor}.
	 * Subclasses can override this method to intercept method invocations on the
	 * target object which is useful when an introduction needs to monitor the object
	 * that it is introduced into. This method is <strong>never</strong> called for
	 * {@link MethodInvocation MethodInvocations} on the introduced interfaces.
	 */
	protected Object doProceed(MethodInvocation mi) throws Throwable {
		// If we get here, just pass the invocation on.
		return mi.proceed();
	}

	private Object getIntroductionDelegateFor(Object targetObject) {
		synchronized (this.delegateMap) {
			// 查看delegateMap中是否存在targetObject的key
			if (this.delegateMap.containsKey(targetObject)) {
				// 如果存在，直接返回
				return this.delegateMap.get(targetObject);
			}
			// 如果不存在
			else {
				// 创建出委托对象
				Object delegate = createNewDelegate();
				// 并且将委托对象 作为 targetObject的value放入缓存中
				this.delegateMap.put(targetObject, delegate);
				// 返回委托对象
				return delegate;
			}
		}
	}

	private Object createNewDelegate() {
		try {
			return ReflectionUtils.accessibleConstructor(this.defaultImplType).newInstance();
		}
		catch (Throwable ex) {
			throw new IllegalArgumentException("Cannot create default implementation for '" +
					this.interfaceType.getName() + "' mixin (" + this.defaultImplType.getName() + "): " + ex);
		}
	}

}
