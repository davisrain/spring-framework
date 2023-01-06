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

package org.springframework.core;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Defines the algorithm for searching for metadata-associated methods exhaustively
 * including interfaces and parent classes while also dealing with parameterized methods
 * as well as common scenarios encountered with interface and class-based proxies.
 *
 * <p>Typically, but not necessarily, used for finding annotated handler methods.
 *
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @since 4.2.3
 */
public final class MethodIntrospector {

	private MethodIntrospector() {
	}


	/**
	 * Select methods on the given target type based on the lookup of associated metadata.
	 * <p>Callers define methods of interest through the {@link MetadataLookup} parameter,
	 * allowing to collect the associated metadata into the result map.
	 * @param targetType the target type to search methods on
	 * @param metadataLookup a {@link MetadataLookup} callback to inspect methods of interest,
	 * returning non-null metadata to be associated with a given method if there is a match,
	 * or {@code null} for no match
	 * @return the selected methods associated with their metadata (in the order of retrieval),
	 * or an empty map in case of no match
	 */
	public static <T> Map<Method, T> selectMethods(Class<?> targetType, final MetadataLookup<T> metadataLookup) {
		final Map<Method, T> methodMap = new LinkedHashMap<>();
		Set<Class<?>> handlerTypes = new LinkedHashSet<>();
		Class<?> specificHandlerType = null;

		// 如果不是JDK代理
		if (!Proxy.isProxyClass(targetType)) {
			// 判断是否是CGLIB代理，如果是，获取其父类，赋值给specificHandlerType；如果不是，返回自身
			specificHandlerType = ClassUtils.getUserClass(targetType);
			handlerTypes.add(specificHandlerType);
		}
		// 获取targetType的所有的接口
		handlerTypes.addAll(ClassUtils.getAllInterfacesForClassAsSet(targetType));

		// 循环遍历handlerTypes，里面有targetType本身(如果targetType是cglib生成的代理类，那么添加进list的是它的父类，也就是被代理的类)
		// 以及它的所有实现了的接口
		for (Class<?> currentHandlerType : handlerTypes) {
			// 如果specificHandlerType不为null的话，赋值给targetClass，否则将currentHandlerType赋值给targetClass
			final Class<?> targetClass = (specificHandlerType != null ? specificHandlerType : currentHandlerType);

			// 调用doWithMethods筛选和处理方法，
			// 该方法内部会查找传入的类的所有声明的方法以及其实现接口的默认方法，并且会递归查找其父类
			// 如果传入的是接口，那么会递归查找接口实现的接口中的声明的方法
			// MethodFilter筛选的是没有synthesis和bridge标志的方法，即不是编译器自动生成的方法，
			// 然后调用MethodCallback对筛选出的方法进行处理
			ReflectionUtils.doWithMethods(currentHandlerType, method -> {
				// 获取最明确的方法，即如果method是targetClass父类或接口中声明的，但是targetClass中又重写了该方法，那么获取到的就是重写方法，
				// 不过该方法可能会获取到targetClass中的桥接方法
				Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
				// 根据metadataLookup进行映射，得到结果
				T result = metadataLookup.inspect(specificMethod);
				// 如果result不为null的话
				if (result != null) {
					// 寻找specificMethod的被桥接方法
					Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
					// 如果被桥接方法等于原方法（即原方法不是桥接方法） 或者 被桥接方法的映射结果为null，将原方法作为key，结果作为value存入map中
					if (bridgedMethod == specificMethod || metadataLookup.inspect(bridgedMethod) == null) {
						methodMap.put(specificMethod, result);
					}
				}
			}, ReflectionUtils.USER_DECLARED_METHODS);
		}

		return methodMap;
	}

	/**
	 * Select methods on the given target type based on a filter.
	 * <p>Callers define methods of interest through the {@code MethodFilter} parameter.
	 * @param targetType the target type to search methods on
	 * @param methodFilter a {@code MethodFilter} to help
	 * recognize handler methods of interest
	 * @return the selected methods, or an empty set in case of no match
	 */
	public static Set<Method> selectMethods(Class<?> targetType, final ReflectionUtils.MethodFilter methodFilter) {
		return selectMethods(targetType,
				(MetadataLookup<Boolean>) method -> (methodFilter.matches(method) ? Boolean.TRUE : null)).keySet();
	}

	/**
	 * Select an invocable method on the target type: either the given method itself
	 * if actually exposed on the target type, or otherwise a corresponding method
	 * on one of the target type's interfaces or on the target type itself.
	 * <p>Matches on user-declared interfaces will be preferred since they are likely
	 * to contain relevant metadata that corresponds to the method on the target class.
	 * @param method the method to check
	 * @param targetType the target type to search methods on
	 * (typically an interface-based JDK proxy)
	 * @return a corresponding invocable method on the target type
	 * @throws IllegalStateException if the given method is not invocable on the given
	 * target type (typically due to a proxy mismatch)
	 */
	public static Method selectInvocableMethod(Method method, Class<?> targetType) {
		// 如果方法的声明类就是targetType，那么直接返回
		if (method.getDeclaringClass().isAssignableFrom(targetType)) {
			return method;
		}
		try {
			// 根据方法名和参数在targetType的接口中去寻找对应方法
			String methodName = method.getName();
			Class<?>[] parameterTypes = method.getParameterTypes();
			for (Class<?> ifc : targetType.getInterfaces()) {
				try {
					return ifc.getMethod(methodName, parameterTypes);
				}
				catch (NoSuchMethodException ex) {
					// Alright, not on this interface then...
				}
			}
			// A final desperate attempt on the proxy class itself...
			// 在自身类中去寻找方法
			return targetType.getMethod(methodName, parameterTypes);
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(String.format(
					"Need to invoke method '%s' declared on target class '%s', " +
					"but not found in any interface(s) of the exposed proxy type. " +
					"Either pull the method up to an interface or switch to CGLIB " +
					"proxies by enforcing proxy-target-class mode in your configuration.",
					method.getName(), method.getDeclaringClass().getSimpleName()));
		}
	}


	/**
	 * A callback interface for metadata lookup on a given method.
	 * @param <T> the type of metadata returned
	 */
	@FunctionalInterface
	public interface MetadataLookup<T> {

		/**
		 * Perform a lookup on the given method and return associated metadata, if any.
		 * @param method the method to inspect
		 * @return non-null metadata to be associated with a method if there is a match,
		 * or {@code null} for no match
		 */
		@Nullable
		T inspect(Method method);
	}

}
