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

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import org.springframework.aop.SpringProxy;
import org.springframework.aop.TargetClassAware;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.core.DecoratingProxy;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * Utility methods for AOP proxy factories.
 * Mainly for internal use within the AOP framework.
 *
 * <p>See {@link org.springframework.aop.support.AopUtils} for a collection of
 * generic AOP utility methods which do not depend on AOP framework internals.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.aop.support.AopUtils
 */
public abstract class AopProxyUtils {

	/**
	 * Obtain the singleton target object behind the given proxy, if any.
	 * @param candidate the (potential) proxy to check
	 * @return the singleton target object managed in a {@link SingletonTargetSource},
	 * or {@code null} in any other case (not a proxy, not an existing singleton target)
	 * @since 4.3.8
	 * @see Advised#getTargetSource()
	 * @see SingletonTargetSource#getTarget()
	 */
	@Nullable
	public static Object getSingletonTarget(Object candidate) {
		if (candidate instanceof Advised) {
			TargetSource targetSource = ((Advised) candidate).getTargetSource();
			if (targetSource instanceof SingletonTargetSource) {
				return ((SingletonTargetSource) targetSource).getTarget();
			}
		}
		return null;
	}

	/**
	 * Determine the ultimate target class of the given bean instance, traversing
	 * not only a top-level proxy but any number of nested proxies as well &mdash;
	 * as long as possible without side effects, that is, just for singleton targets.
	 * @param candidate the instance to check (might be an AOP proxy)
	 * @return the ultimate target class (or the plain class of the given
	 * object as fallback; never {@code null})
	 * @see org.springframework.aop.TargetClassAware#getTargetClass()
	 * @see Advised#getTargetSource()
	 */
	public static Class<?> ultimateTargetClass(Object candidate) {
		Assert.notNull(candidate, "Candidate object must not be null");
		Object current = candidate;
		Class<?> result = null;
		// 如果current是TargetClassAware类型的
		while (current instanceof TargetClassAware) {
			// result为current的targetClass
			result = ((TargetClassAware) current).getTargetClass();
			// 如果current的advised类型的，获取其targetSource中的target，然后循环判断，
			// 如果current仍为TargetClassAware的话，继续获取其targetClass
			current = getSingletonTarget(current);
		}
		// 如果result为null
		if (result == null) {
			// 如果candidate是被cglib代理的，获取其父类的类型，否则获取自身的类型赋值给result
			result = (AopUtils.isCglibProxy(candidate) ? candidate.getClass().getSuperclass() : candidate.getClass());
		}
		// 返回result
		return result;
	}

	/**
	 * Determine the complete set of interfaces to proxy for the given AOP configuration.
	 * <p>This will always add the {@link Advised} interface unless the AdvisedSupport's
	 * {@link AdvisedSupport#setOpaque "opaque"} flag is on. Always adds the
	 * {@link org.springframework.aop.SpringProxy} marker interface.
	 * @param advised the proxy config
	 * @return the complete set of interfaces to proxy
	 * @see SpringProxy
	 * @see Advised
	 */
	public static Class<?>[] completeProxiedInterfaces(AdvisedSupport advised) {
		return completeProxiedInterfaces(advised, false);
	}

	/**
	 * Determine the complete set of interfaces to proxy for the given AOP configuration.
	 * <p>This will always add the {@link Advised} interface unless the AdvisedSupport's
	 * {@link AdvisedSupport#setOpaque "opaque"} flag is on. Always adds the
	 * {@link org.springframework.aop.SpringProxy} marker interface.
	 * @param advised the proxy config
	 * @param decoratingProxy whether to expose the {@link DecoratingProxy} interface
	 * @return the complete set of interfaces to proxy
	 * @since 4.3
	 * @see SpringProxy
	 * @see Advised
	 * @see DecoratingProxy
	 */
	static Class<?>[] completeProxiedInterfaces(AdvisedSupport advised, boolean decoratingProxy) {
		// 获取advisedSupport中需要被代理的接口
		Class<?>[] specifiedInterfaces = advised.getProxiedInterfaces();
		// 如果需要被代理的接口长度为0
		if (specifiedInterfaces.length == 0) {
			// No user-specified interfaces: check whether target class is an interface.
			// 如果没有用户指定的接口，检查targetClass是否为接口
			Class<?> targetClass = advised.getTargetClass();
			// 如果targetClass不为null
			if (targetClass != null) {
				// 且targetClass是接口的话，将其添加进advised的interfaces中
				if (targetClass.isInterface()) {
					advised.setInterfaces(targetClass);
				}
				// 如果targetClass已经被JDK代理过，那么将其实现的接口添加进advised的interfaces中
				else if (Proxy.isProxyClass(targetClass)) {
					advised.setInterfaces(targetClass.getInterfaces());
				}
				// 再次获取advisedSupport中的interfaces
				specifiedInterfaces = advised.getProxiedInterfaces();
			}
		}
		// 如果advisedSupport中不存在SpringProxy.class这个接口类型的话，需要添加进去
		boolean addSpringProxy = !advised.isInterfaceProxied(SpringProxy.class);

		// 如果advisedSupport中的opaque属性为false 并且 接口中不包含Advised.class 这个接口类型，那么需要将Advised接口添加进去。

		// opaque属性用来判断被代理的类是否要实现Advised接口，Advised接口持有了代理这个类的相关配置，
		// 即advisedSupport对象的相关属性，包括interfaces、advisors、targetSource等
		boolean addAdvised = !advised.isOpaque() && !advised.isInterfaceProxied(Advised.class);
		// 如果decoratingProxy为true并且advisedSupport中不包含DecoratingProxy.class这个接口，那么需要将其添加进去
		boolean addDecoratingProxy = (decoratingProxy && !advised.isInterfaceProxied(DecoratingProxy.class));
		// 计算非用户接口的数量
		int nonUserIfcCount = 0;
		// 如果需要添加SpringProxy接口，将非用户接口数量+1
		if (addSpringProxy) {
			nonUserIfcCount++;
		}
		// 如果需要添加Advised接口，将非用户接口数量+1
		if (addAdvised) {
			nonUserIfcCount++;
		}
		// 如果需要添加DecoratingProxy接口，将非用户接口数量+1
		if (addDecoratingProxy) {
			nonUserIfcCount++;
		}
		// 根据指定的接口数量 + 非用户接口数量为长度创建一个Class类型的数组，用于保存要代理的接口
		Class<?>[] proxiedInterfaces = new Class<?>[specifiedInterfaces.length + nonUserIfcCount];
		// 将指定的接口赋值到新的接口数组中，然后按照需求添加SpringProxy、Advised、DecoratingProxy等接口
		System.arraycopy(specifiedInterfaces, 0, proxiedInterfaces, 0, specifiedInterfaces.length);
		int index = specifiedInterfaces.length;
		if (addSpringProxy) {
			proxiedInterfaces[index] = SpringProxy.class;
			index++;
		}
		if (addAdvised) {
			proxiedInterfaces[index] = Advised.class;
			index++;
		}
		if (addDecoratingProxy) {
			proxiedInterfaces[index] = DecoratingProxy.class;
		}
		// 返回需要代理的接口数组
		return proxiedInterfaces;
	}

	/**
	 * Extract the user-specified interfaces that the given proxy implements,
	 * i.e. all non-Advised interfaces that the proxy implements.
	 * @param proxy the proxy to analyze (usually a JDK dynamic proxy)
	 * @return all user-specified interfaces that the proxy implements,
	 * in the original order (never {@code null} or empty)
	 * @see Advised
	 */
	public static Class<?>[] proxiedUserInterfaces(Object proxy) {
		Class<?>[] proxyInterfaces = proxy.getClass().getInterfaces();
		int nonUserIfcCount = 0;
		if (proxy instanceof SpringProxy) {
			nonUserIfcCount++;
		}
		if (proxy instanceof Advised) {
			nonUserIfcCount++;
		}
		if (proxy instanceof DecoratingProxy) {
			nonUserIfcCount++;
		}
		Class<?>[] userInterfaces = Arrays.copyOf(proxyInterfaces, proxyInterfaces.length - nonUserIfcCount);
		Assert.notEmpty(userInterfaces, "JDK proxy must implement one or more interfaces");
		return userInterfaces;
	}

	/**
	 * Check equality of the proxies behind the given AdvisedSupport objects.
	 * Not the same as equality of the AdvisedSupport objects:
	 * rather, equality of interfaces, advisors and target sources.
	 */
	public static boolean equalsInProxy(AdvisedSupport a, AdvisedSupport b) {
		return (a == b ||
				(equalsProxiedInterfaces(a, b) && equalsAdvisors(a, b) && a.getTargetSource().equals(b.getTargetSource())));
	}

	/**
	 * Check equality of the proxied interfaces behind the given AdvisedSupport objects.
	 */
	public static boolean equalsProxiedInterfaces(AdvisedSupport a, AdvisedSupport b) {
		return Arrays.equals(a.getProxiedInterfaces(), b.getProxiedInterfaces());
	}

	/**
	 * Check equality of the advisors behind the given AdvisedSupport objects.
	 */
	public static boolean equalsAdvisors(AdvisedSupport a, AdvisedSupport b) {
		return Arrays.equals(a.getAdvisors(), b.getAdvisors());
	}


	/**
	 * Adapt the given arguments to the target signature in the given method,
	 * if necessary: in particular, if a given vararg argument array does not
	 * match the array type of the declared vararg parameter in the method.
	 * @param method the target method
	 * @param arguments the given arguments
	 * @return a cloned argument array, or the original if no adaptation is needed
	 * @since 4.2.3
	 */
	static Object[] adaptArgumentsIfNecessary(Method method, @Nullable Object[] arguments) {
		// 如果方法的调用参数为空的话，直接返回一个空数组
		if (ObjectUtils.isEmpty(arguments)) {
			return new Object[0];
		}
		// 如果方法存在可变参数的话
		if (method.isVarArgs()) {
			// 如果方法的参数个数 等于 传入的参数数组的长度
			if (method.getParameterCount() == arguments.length) {
				// 获取方法的参数类型数组
				Class<?>[] paramTypes = method.getParameterTypes();
				// 获取到方法可变参数的下标位置
				int varargIndex = paramTypes.length - 1;
				// 根据下标位置获取到可变参数的类型
				Class<?> varargType = paramTypes[varargIndex];
				// 如果可变参数类型是数组类型的
				if (varargType.isArray()) {
					// 查看传入的参数数组对应下标的参数对象
					Object varargArray = arguments[varargIndex];
					// 如果传入的参数对象是Object数组类型的 并且 不是可变参数要求的参数类型的实例
					if (varargArray instanceof Object[] && !varargType.isInstance(varargArray)) {
						// 创建一个新的参数数组，将 旧参数数组 可变参数之前 的 参数 都复制过去
						Object[] newArguments = new Object[arguments.length];
						System.arraycopy(arguments, 0, newArguments, 0, varargIndex);
						// 然后获取 可变参数数组类型 的 元素类型
						Class<?> targetElementType = varargType.getComponentType();
						// 获取到 可变参数数组 的长度
						int varargLength = Array.getLength(varargArray);
						// 根据具体的可变参数类型创建 一个新的可变参数数组
						Object newVarargArray = Array.newInstance(targetElementType, varargLength);
						// 将旧的可变参数复制到新的数组中
						System.arraycopy(varargArray, 0, newVarargArray, 0, varargLength);
						// 将新的可变参数数组 赋值到新的参数数组中 对应的下标元素中去
						newArguments[varargIndex] = newVarargArray;
						// 然后返回新的参数数组
						return newArguments;
					}
				}
			}
		}
		// 如果上述条件不满足，直接返回传入的参数数组
		return arguments;
	}

}
