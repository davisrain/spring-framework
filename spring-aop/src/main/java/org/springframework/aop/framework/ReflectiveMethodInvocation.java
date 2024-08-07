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

package org.springframework.aop.framework;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.lang.Nullable;

/**
 * Spring's implementation of the AOP Alliance
 * {@link org.aopalliance.intercept.MethodInvocation} interface,
 * implementing the extended
 * {@link org.springframework.aop.ProxyMethodInvocation} interface.
 *
 * <p>Invokes the target object using reflection. Subclasses can override the
 * {@link #invokeJoinpoint()} method to change this behavior, so this is also
 * a useful base class for more specialized MethodInvocation implementations.
 *
 * 使用反射调用target对象的方法，子类可以重写invokeJoinpoint方法来改变这个行为，因此它也是一个有用的基础类，
 * 对于更加具体的MethodInvocation实现来说
 *
 * <p>It is possible to clone an invocation, to invoke {@link #proceed()}
 * repeatedly (once per clone), using the {@link #invocableClone()} method.
 * It is also possible to attach custom attributes to the invocation,
 * using the {@link #setUserAttribute} / {@link #getUserAttribute} methods.
 *
 * 克隆一个调用是可能的，去重复调用proceed的时候，使用invocableClone方法。
 * 附加一些自定义的参数到调用上也是可能的，通过使用setUserAttribute 和 getUserAttribute方法
 *
 * <p><b>NOTE:</b> This class is considered internal and should not be
 * directly accessed. The sole reason for it being public is compatibility
 * with existing framework integrations (e.g. Pitchfork). For any other
 * purposes, use the {@link ProxyMethodInvocation} interface instead.
 *
 * 这个类被考虑为内部的并且不应该被直接的访问。
 * 公开它的唯一原因是考虑和现有框架的兼容性。
 * 如果是为了其他目的，直接使用ProxyMethodInvocation接口
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Adrian Colyer
 * @see #invokeJoinpoint
 * @see #proceed
 * @see #invocableClone
 * @see #setUserAttribute
 * @see #getUserAttribute
 */
public class ReflectiveMethodInvocation implements ProxyMethodInvocation, Cloneable {

	// 代理对象
	protected final Object proxy;

	// 被代理对象
	@Nullable
	protected final Object target;

	// 调用的方法
	protected final Method method;

	// 方法参数
	protected Object[] arguments;

	// 被代理的类
	@Nullable
	private final Class<?> targetClass;

	/**
	 * Lazily initialized map of user-specific attributes for this invocation.
	 */
	@Nullable
	private Map<String, Object> userAttributes;

	/**
	 * List of MethodInterceptor and InterceptorAndDynamicMethodMatcher
	 * that need dynamic checks.
	 */
	// MethodInterceptor 和 需要动态检查的 InterceptorAndDynamicMethodMatcher集合
	protected final List<?> interceptorsAndDynamicMethodMatchers;

	/**
	 * Index from 0 of the current interceptor we're invoking.
	 * -1 until we invoke: then the current interceptor.
	 */
	// 当前执行到的interceptor的index，在调用之前是-1
	private int currentInterceptorIndex = -1;


	/**
	 * Construct a new ReflectiveMethodInvocation with the given arguments.
	 * @param proxy the proxy object that the invocation was made on
	 * @param target the target object to invoke
	 * @param method the method to invoke
	 * @param arguments the arguments to invoke the method with
	 * @param targetClass the target class, for MethodMatcher invocations
	 * @param interceptorsAndDynamicMethodMatchers interceptors that should be applied,
	 * along with any InterceptorAndDynamicMethodMatchers that need evaluation at runtime.
	 * MethodMatchers included in this struct must already have been found to have matched
	 * as far as was possibly statically. Passing an array might be about 10% faster,
	 * but would complicate the code. And it would work only for static pointcuts.
	 */
	protected ReflectiveMethodInvocation(
			Object proxy, @Nullable Object target, Method method, @Nullable Object[] arguments,
			@Nullable Class<?> targetClass, List<Object> interceptorsAndDynamicMethodMatchers) {

		this.proxy = proxy;
		this.target = target;
		this.targetClass = targetClass;
		// 如果是桥接方法，找到那个被桥接的方法
		this.method = BridgeMethodResolver.findBridgedMethod(method);
		this.arguments = AopProxyUtils.adaptArgumentsIfNecessary(method, arguments);
		this.interceptorsAndDynamicMethodMatchers = interceptorsAndDynamicMethodMatchers;
	}


	@Override
	public final Object getProxy() {
		return this.proxy;
	}

	@Override
	@Nullable
	public final Object getThis() {
		return this.target;
	}

	@Override
	public final AccessibleObject getStaticPart() {
		return this.method;
	}

	/**
	 * Return the method invoked on the proxied interface.
	 * May or may not correspond with a method invoked on an underlying
	 * implementation of that interface.
	 */
	@Override
	public final Method getMethod() {
		return this.method;
	}

	@Override
	public final Object[] getArguments() {
		return this.arguments;
	}

	@Override
	public void setArguments(Object... arguments) {
		this.arguments = arguments;
	}


	@Override
	@Nullable
	public Object proceed() throws Throwable {
		// We start with an index of -1 and increment early.
		// 如果当前执行到的interceptorIndex已经是最后一个MethodInterceptor了
		if (this.currentInterceptorIndex == this.interceptorsAndDynamicMethodMatchers.size() - 1) {
			// 那么调用invokeJoinPoint方法并返回
			// 该方法默认是使用反射调用被代理的target对象的方法，
			// 但cglib的实现类会采用methodProxy通过FastClass去调用target的对应方法
			return invokeJoinpoint();
		}

		// 否则，将当前执行到的interceptorIndex+1，并获取到对应下标的interceptor
		Object interceptorOrInterceptionAdvice =
				this.interceptorsAndDynamicMethodMatchers.get(++this.currentInterceptorIndex);
		// 如果获取到的interceptor是InterceptorAndDynamicMethodMatcher类型的
		if (interceptorOrInterceptionAdvice instanceof InterceptorAndDynamicMethodMatcher) {
			// Evaluate dynamic method matcher here: static part will already have
			// been evaluated and found to match.
			InterceptorAndDynamicMethodMatcher dm =
					(InterceptorAndDynamicMethodMatcher) interceptorOrInterceptionAdvice;
			Class<?> targetClass = (this.targetClass != null ? this.targetClass : this.method.getDeclaringClass());
			// 调用其持有的MethodMatcher检验当前方法是否匹配
			if (dm.methodMatcher.matches(this.method, targetClass, this.arguments)) {
				// 如果匹配，调用其持有的interceptor的invoke方法
				return dm.interceptor.invoke(this);
			}
			// 如果匹配失败了，跳过当前要执行interceptor，通过proceed方法调用chain中的一个interceptor
			else {
				// Dynamic matching failed.
				// Skip this interceptor and invoke the next in the chain.
				return proceed();
			}
		}
		// 如果获取到interceptor就是单纯的MethodInterceptor类型的
		else {
			// It's an interceptor, so we just invoke it: The pointcut will have
			// been evaluated statically before this object was constructed.
			// 调用interceptor的invoke方法
			// 其中通过AspectJ注解声明的几种advice对应的MethodInterceptor分别是：
			// @Before：MethodBeforeAdviceInterceptor，这是由DefaultAdvisorAdapterRegistry根据AspectJMethodBeforeAdvice适配而来的
			// @AfterReturning：AfterReturningAdviceInterceptor，这也是由DefaultAdvisorAdapterRegistry根据AspectJAfterReturningAdvice适配来的
			// @After：AspectJAfterAdvice，因为其自身就实现了MethodInterceptor接口，所以不需要适配
			// @AfterThrowing：AspectJAfterThrowingAdvice，因为其自身就实现了MethodInterceptor接口，所以也不需要适配
			// @Around：AspectJAroundAdvice，该Advice类也实现了MethodInterceptor接口，不需要适配
			return ((MethodInterceptor) interceptorOrInterceptionAdvice).invoke(this);
		}
	}

	/**
	 * Invoke the joinpoint using reflection.
	 * Subclasses can override this to use custom invocation.
	 * @return the return value of the joinpoint
	 * @throws Throwable if invoking the joinpoint resulted in an exception
	 */
	@Nullable
	protected Object invokeJoinpoint() throws Throwable {
		// 通过反射调用被代理对象的被代理方法
		return AopUtils.invokeJoinpointUsingReflection(this.target, this.method, this.arguments);
	}


	/**
	 * This implementation returns a shallow copy of this invocation object,
	 * including an independent copy of the original arguments array.
	 * <p>We want a shallow copy in this case: We want to use the same interceptor
	 * chain and other object references, but we want an independent value for the
	 * current interceptor index.
	 *
	 * 这个实现返回了一个invocation对象的浅克隆，包含了一个原始参数数组的独立的拷贝。
	 * 我们想要去使用同样的interceptor链 以及 其他的对象引用，但是我们想要一个独立的当前interceptor的index，
	 * 所以在这种情况下我们需要一个浅克隆
	 * @see java.lang.Object#clone()
	 */
	@Override
	public MethodInvocation invocableClone() {
		Object[] cloneArguments = this.arguments;
		if (this.arguments.length > 0) {
			// Build an independent copy of the arguments array.
			cloneArguments = this.arguments.clone();
		}
		return invocableClone(cloneArguments);
	}

	/**
	 * This implementation returns a shallow copy of this invocation object,
	 * using the given arguments array for the clone.
	 * <p>We want a shallow copy in this case: We want to use the same interceptor
	 * chain and other object references, but we want an independent value for the
	 * current interceptor index.
	 * @see java.lang.Object#clone()
	 */
	@Override
	public MethodInvocation invocableClone(Object... arguments) {
		// Force initialization of the user attributes Map,
		// for having a shared Map reference in the clone.
		// 强制初始化一个user attributes的map，
		// 为了在clone里面持有一个共享的map引用
		if (this.userAttributes == null) {
			this.userAttributes = new HashMap<>();
		}

		// Create the MethodInvocation clone.
		try {
			ReflectiveMethodInvocation clone = (ReflectiveMethodInvocation) clone();
			clone.arguments = arguments;
			return clone;
		}
		catch (CloneNotSupportedException ex) {
			throw new IllegalStateException(
					"Should be able to clone object of type [" + getClass() + "]: " + ex);
		}
	}


	@Override
	public void setUserAttribute(String key, @Nullable Object value) {
		if (value != null) {
			if (this.userAttributes == null) {
				this.userAttributes = new HashMap<>();
			}
			this.userAttributes.put(key, value);
		}
		else {
			if (this.userAttributes != null) {
				this.userAttributes.remove(key);
			}
		}
	}

	@Override
	@Nullable
	public Object getUserAttribute(String key) {
		return (this.userAttributes != null ? this.userAttributes.get(key) : null);
	}

	/**
	 * Return user attributes associated with this invocation.
	 * This method provides an invocation-bound alternative to a ThreadLocal.
	 * <p>This map is initialized lazily and is not used in the AOP framework itself.
	 * @return any user attributes associated with this invocation
	 * (never {@code null})
	 */
	public Map<String, Object> getUserAttributes() {
		if (this.userAttributes == null) {
			this.userAttributes = new HashMap<>();
		}
		return this.userAttributes;
	}


	@Override
	public String toString() {
		// Don't do toString on target, it may be proxied.
		StringBuilder sb = new StringBuilder("ReflectiveMethodInvocation: ");
		sb.append(this.method).append("; ");
		if (this.target == null) {
			sb.append("target is null");
		}
		else {
			sb.append("target is of class [").append(this.target.getClass().getName()).append(']');
		}
		return sb.toString();
	}

}
