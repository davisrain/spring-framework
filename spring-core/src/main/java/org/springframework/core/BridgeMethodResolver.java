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

package org.springframework.core;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodFilter;

/**
 * Helper for resolving synthetic {@link Method#isBridge bridge Methods} to the
 * {@link Method} being bridged.
 *
 * <p>Given a synthetic {@link Method#isBridge bridge Method} returns the {@link Method}
 * being bridged. A bridge method may be created by the compiler when extending a
 * parameterized type whose methods have parameterized arguments. During runtime
 * invocation the bridge {@link Method} may be invoked and/or used via reflection.
 * When attempting to locate annotations on {@link Method Methods}, it is wise to check
 * for bridge {@link Method Methods} as appropriate and find the bridged {@link Method}.
 *
 * <p>See <a href="https://java.sun.com/docs/books/jls/third_edition/html/expressions.html#15.12.4.5">
 * The Java Language Specification</a> for more details on the use of bridge methods.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @since 2.0
 */
public final class BridgeMethodResolver {

	private static final Map<Method, Method> cache = new ConcurrentReferenceHashMap<>();

	private BridgeMethodResolver() {
	}


	/**
	 * Find the original method for the supplied {@link Method bridge Method}.
	 * <p>It is safe to call this method passing in a non-bridge {@link Method} instance.
	 * In such a case, the supplied {@link Method} instance is returned directly to the caller.
	 * Callers are <strong>not</strong> required to check for bridging before calling this method.
	 * @param bridgeMethod the method to introspect
	 * @return the original method (either the bridged method or the passed-in method
	 * if no more specific one could be found)
	 */
	public static Method findBridgedMethod(Method bridgeMethod) {
		// 如果传入的方法不是bridge方法，直接返回
		if (!bridgeMethod.isBridge()) {
			return bridgeMethod;
		}
		// 尝试从缓存中获取
		Method bridgedMethod = cache.get(bridgeMethod);
		// 缓存未命中
		if (bridgedMethod == null) {
			// Gather all methods with matching name and parameter size.
			// 初始化一个list用于保存方法名相同且参数个数相同的方法
			List<Method> candidateMethods = new ArrayList<>();
			// 创建一个methodFilter用于过滤方法，筛选出不是桥接方法 并且不等于传入的bridgeMethod 且方法名相同 且参数个数相等的方法
			MethodFilter filter = candidateMethod ->
					isBridgedCandidateFor(candidateMethod, bridgeMethod);
			ReflectionUtils.doWithMethods(bridgeMethod.getDeclaringClass(), candidateMethods::add, filter);
			// 如果筛选出来的候选方法列表不为空
			if (!candidateMethods.isEmpty()) {
				// 如果个数为1的话，直接返回第一个，否则调用searchCandidates方法进行查找
				bridgedMethod = candidateMethods.size() == 1 ?
						candidateMethods.get(0) :
						searchCandidates(candidateMethods, bridgeMethod);
			}
			// 如果被桥接的方法为null的话，那么将bridgeMethod赋值给bridgedMethod
			if (bridgedMethod == null) {
				// A bridge method was passed in but we couldn't find the bridged method.
				// Let's proceed with the passed-in method and hope for the best...
				bridgedMethod = bridgeMethod;
			}
			cache.put(bridgeMethod, bridgedMethod);
		}
		return bridgedMethod;
	}

	/**
	 * Returns {@code true} if the supplied '{@code candidateMethod}' can be
	 * consider a validate candidate for the {@link Method} that is {@link Method#isBridge() bridged}
	 * by the supplied {@link Method bridge Method}. This method performs inexpensive
	 * checks and can be used quickly filter for a set of possible matches.
	 */
	private static boolean isBridgedCandidateFor(Method candidateMethod, Method bridgeMethod) {
		// 当候选方法不是桥接方法，且候选方法不等于提供的桥接方法，且方法名和参数名都相等的时候，返回true，加入候选
		return (!candidateMethod.isBridge() && !candidateMethod.equals(bridgeMethod) &&
				candidateMethod.getName().equals(bridgeMethod.getName()) &&
				candidateMethod.getParameterCount() == bridgeMethod.getParameterCount());
	}

	/**
	 * Searches for the bridged method in the given candidates.
	 * @param candidateMethods the List of candidate Methods
	 * @param bridgeMethod the bridge method
	 * @return the bridged method, or {@code null} if none found
	 */
	@Nullable
	private static Method searchCandidates(List<Method> candidateMethods, Method bridgeMethod) {
		if (candidateMethods.isEmpty()) {
			return null;
		}
		Method previousMethod = null;
		boolean sameSig = true;
		// 遍历候选方法列表
		for (Method candidateMethod : candidateMethods) {
			// 调用isBridgeMethodFor方法判断该候选方法是否是 桥接方法 对应的 被桥接方法
			if (isBridgeMethodFor(bridgeMethod, candidateMethod, bridgeMethod.getDeclaringClass())) {
				return candidateMethod;
			}
			// 如果前一个比较过的方法不为null
			else if (previousMethod != null) {
				// 判断当前比较的候选方法和上一个比较的方法的泛型参数类型是否相等，如果一直相等，那么sameSig一直为true，表示候选方法的签名都相同
				sameSig = sameSig &&
						Arrays.equals(candidateMethod.getGenericParameterTypes(), previousMethod.getGenericParameterTypes());
			}
			// 将本次判断的候选方法赋值给previousMethod
			previousMethod = candidateMethod;
		}
		// 当候选方法签名都相同的时候，返回第一个，否则返回null
		return (sameSig ? candidateMethods.get(0) : null);
	}

	/**
	 * Determines whether or not the bridge {@link Method} is the bridge for the
	 * supplied candidate {@link Method}.
	 */
	static boolean isBridgeMethodFor(Method bridgeMethod, Method candidateMethod, Class<?> declaringClass) {
		// 调用isResolvedTypeMatch方法进行判断，比较两个方法的参数类型是否相同
		if (isResolvedTypeMatch(candidateMethod, bridgeMethod, declaringClass)) {
			// 如果两个方法的参数类型相同的话，返回true
			return true;
		}
		// 查找桥接方法的对应的泛型声明方法，即一般是父类或接口声明的泛型方法
		Method method = findGenericDeclaration(bridgeMethod);
		// 如果method不为null且method和候选方法的参数类型相同，返回true
		return (method != null && isResolvedTypeMatch(method, candidateMethod, declaringClass));
	}

	/**
	 * Returns {@code true} if the {@link Type} signature of both the supplied
	 * {@link Method#getGenericParameterTypes() generic Method} and concrete {@link Method}
	 * are equal after resolving all types against the declaringType, otherwise
	 * returns {@code false}.
	 */
	// 方法签名是由方法名和参数类型，参数顺序决定，与返回值类型和声明的异常都无关
	private static boolean isResolvedTypeMatch(Method genericMethod, Method candidateMethod, Class<?> declaringClass) {
		// 获取genericMethod的所有带泛型的参数类型
		Type[] genericParameters = genericMethod.getGenericParameterTypes();
		// 如果参数个数不相等，直接返回false
		if (genericParameters.length != candidateMethod.getParameterCount()) {
			return false;
		}
		// 获取候选方法的所有参数类型
		Class<?>[] candidateParameters = candidateMethod.getParameterTypes();
		// 遍历候选方法的参数类型
		for (int i = 0; i < candidateParameters.length; i++) {
			// 将genericMethod的每个参数类型解析为ResolvableType
			ResolvableType genericParameter = ResolvableType.forMethodParameter(genericMethod, i, declaringClass);
			// 获取到候选方法对应下标的参数类型
			Class<?> candidateParameter = candidateParameters[i];
			// 如果候选方法的参数类型是数组类型的
			if (candidateParameter.isArray()) {
				// An array type: compare the component type.
				// 比较两个参数的componentType是否相等，如果不相等，返回false
				if (!candidateParameter.getComponentType().equals(genericParameter.getComponentType().toClass())) {
					return false;
				}
			}
			// A non-array type: compare the type itself.
			// 如果不是数组类型的，比较两个参数的class类型是否相等，如果不相等，返回false
			if (!candidateParameter.equals(genericParameter.toClass())) {
				return false;
			}
		}
		// 否则返回true
		return true;
	}

	/**
	 * Searches for the generic {@link Method} declaration whose erased signature
	 * matches that of the supplied bridge method.
	 * @throws IllegalStateException if the generic declaration cannot be found
	 */
	@Nullable
	private static Method findGenericDeclaration(Method bridgeMethod) {
		// Search parent types for method that has same signature as bridge.
		// 从父类开始查找和桥接方法有相同签名的方法
		Class<?> superclass = bridgeMethod.getDeclaringClass().getSuperclass();
		// 如果父类不为null且父类不是Object
		while (superclass != null && Object.class != superclass) {
			Method method = searchForMatch(superclass, bridgeMethod);
			// 如果method不为null且不是桥接方法，返回
			if (method != null && !method.isBridge()) {
				return method;
			}
			// 否则继续查找父类
			superclass = superclass.getSuperclass();
		}

		// 获取类所实现的所有接口
		Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(bridgeMethod.getDeclaringClass());
		// 从接口中寻找和桥接方法有相同签名的方法
		return searchInterfaces(interfaces, bridgeMethod);
	}

	@Nullable
	private static Method searchInterfaces(Class<?>[] interfaces, Method bridgeMethod) {
		// 遍历接口
		for (Class<?> ifc : interfaces) {
			// 在接口中寻找和桥接方法有相同签名的方法
			Method method = searchForMatch(ifc, bridgeMethod);
			// 如果方法不为null且不是桥接方法，返回
			if (method != null && !method.isBridge()) {
				return method;
			}
			// 否则的话，去接口实现的接口中查找
			else {
				method = searchInterfaces(ifc.getInterfaces(), bridgeMethod);
				if (method != null) {
					return method;
				}
			}
		}
		return null;
	}

	/**
	 * If the supplied {@link Class} has a declared {@link Method} whose signature matches
	 * that of the supplied {@link Method}, then this matching {@link Method} is returned,
	 * otherwise {@code null} is returned.
	 */
	@Nullable
	private static Method searchForMatch(Class<?> type, Method bridgeMethod) {
		try {
			// 从type中查找名称相等且参数类型和个数也相等的 声明方法，如果找到就返回， 没有找到返回null
			return type.getDeclaredMethod(bridgeMethod.getName(), bridgeMethod.getParameterTypes());
		}
		catch (NoSuchMethodException ex) {
			return null;
		}
	}

	/**
	 * Compare the signatures of the bridge method and the method which it bridges. If
	 * the parameter and return types are the same, it is a 'visibility' bridge method
	 * introduced in Java 6 to fix https://bugs.java.com/view_bug.do?bug_id=6342411.
	 * See also https://stas-blogspot.blogspot.com/2010/03/java-bridge-methods-explained.html
	 * @return whether signatures match as described
	 */
	public static boolean isVisibilityBridgeMethodPair(Method bridgeMethod, Method bridgedMethod) {
		if (bridgeMethod == bridgedMethod) {
			return true;
		}
		return (bridgeMethod.getReturnType().equals(bridgedMethod.getReturnType()) &&
				bridgeMethod.getParameterCount() == bridgedMethod.getParameterCount() &&
				Arrays.equals(bridgeMethod.getParameterTypes(), bridgedMethod.getParameterTypes()));
	}

}
