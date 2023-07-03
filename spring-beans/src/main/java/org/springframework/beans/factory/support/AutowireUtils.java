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

package org.springframework.beans.factory.support;

import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * Utility class that contains various methods useful for the implementation of
 * autowire-capable bean factories.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Sam Brannen
 * @since 1.1.2
 * @see AbstractAutowireCapableBeanFactory
 */
abstract class AutowireUtils {

	public static final Comparator<Executable> EXECUTABLE_COMPARATOR = (e1, e2) -> {
		int result = Boolean.compare(Modifier.isPublic(e2.getModifiers()), Modifier.isPublic(e1.getModifiers()));
		return result != 0 ? result : Integer.compare(e2.getParameterCount(), e1.getParameterCount());
	};


	/**
	 * Sort the given constructors, preferring public constructors and "greedy" ones with
	 * a maximum number of arguments. The result will contain public constructors first,
	 * with decreasing number of arguments, then non-public constructors, again with
	 * decreasing number of arguments.
	 * @param constructors the constructor array to sort
	 */
	public static void sortConstructors(Constructor<?>[] constructors) {
		Arrays.sort(constructors, EXECUTABLE_COMPARATOR);
	}

	/**
	 * Sort the given factory methods, preferring public methods and "greedy" ones
	 * with a maximum of arguments. The result will contain public methods first,
	 * with decreasing number of arguments, then non-public methods, again with
	 * decreasing number of arguments.
	 * @param factoryMethods the factory method array to sort
	 */
	public static void sortFactoryMethods(Method[] factoryMethods) {
		Arrays.sort(factoryMethods, EXECUTABLE_COMPARATOR);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 */
	public static boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		// 获取pd中持有的写方法
		Method wm = pd.getWriteMethod();
		// 如果不存在写方法，那么返回false，不用排除
		if (wm == null) {
			return false;
		}
		// 如果写方法的声明类名称中不包含$$，说明不是cglib代理类中的方法，所以返回false，不用排除
		if (!wm.getDeclaringClass().getName().contains("$$")) {
			// Not a CGLIB method so it's OK.
			return false;
		}
		// It was declared by CGLIB, but we might still want to autowire it
		// if it was actually declared by the superclass.
		// 如果声明写方法的类是cglib代理后的类，那么判断其父类，即被代理类中，是否存在签名一致的方法。
		// 如果不存在的话，说明该方法是代理类自己生成的，返回true，需要被排除。否则不排除
		Class<?> superclass = wm.getDeclaringClass().getSuperclass();
		return !ClassUtils.hasMethod(superclass, wm);
	}

	/**
	 * Return whether the setter method of the given bean property is defined
	 * in any of the given interfaces.
	 * @param pd the PropertyDescriptor of the bean property
	 * @param interfaces the Set of interfaces (Class objects)
	 * @return whether the setter method is defined by an interface
	 */
	public static boolean isSetterDefinedInInterface(PropertyDescriptor pd, Set<Class<?>> interfaces) {
		Method setter = pd.getWriteMethod();
		if (setter != null) {
			Class<?> targetClass = setter.getDeclaringClass();
			for (Class<?> ifc : interfaces) {
				if (ifc.isAssignableFrom(targetClass) && ClassUtils.hasMethod(ifc, setter)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Resolve the given autowiring value against the given required type,
	 * e.g. an {@link ObjectFactory} value to its actual object result.
	 * @param autowiringValue the value to resolve
	 * @param requiredType the type to assign the result to
	 * @return the resolved value
	 */
	public static Object resolveAutowiringValue(Object autowiringValue, Class<?> requiredType) {
		// 如果自动注入的值是ObjectFactory类型的 并且 其不是要求的类型的实例
		if (autowiringValue instanceof ObjectFactory && !requiredType.isInstance(autowiringValue)) {
			ObjectFactory<?> factory = (ObjectFactory<?>) autowiringValue;
			// 那么判断autowiringValue是否是可序列化的 并且 要求的类型是接口类型
			if (autowiringValue instanceof Serializable && requiredType.isInterface()) {
				// 如果是的话，使用JDK动态代理生成一个代理类继承要求类型的接口返回
				autowiringValue = Proxy.newProxyInstance(requiredType.getClassLoader(),
						new Class<?>[] {requiredType}, new ObjectFactoryDelegatingInvocationHandler(factory));
			}
			// 如果不是，直接调用getObject方法返回实际对象
			else {
				return factory.getObject();
			}
		}
		// 如果不满足条件，直接返回原值
		return autowiringValue;
	}

	/**
	 * Determine the target type for the generic return type of the given
	 * <em>generic factory method</em>, where formal type variables are declared
	 * on the given method itself.
	 * <p>For example, given a factory method with the following signature, if
	 * {@code resolveReturnTypeForFactoryMethod()} is invoked with the reflected
	 * method for {@code createProxy()} and an {@code Object[]} array containing
	 * {@code MyService.class}, {@code resolveReturnTypeForFactoryMethod()} will
	 * infer that the target return type is {@code MyService}.
	 * <pre class="code">{@code public static <T> T createProxy(Class<T> clazz)}</pre>
	 * <h4>Possible Return Values</h4>
	 * <ul>
	 * <li>the target return type, if it can be inferred</li>
	 * <li>the {@linkplain Method#getReturnType() standard return type}, if
	 * the given {@code method} does not declare any {@linkplain
	 * Method#getTypeParameters() formal type variables}</li>
	 * <li>the {@linkplain Method#getReturnType() standard return type}, if the
	 * target return type cannot be inferred (e.g., due to type erasure)</li>
	 * <li>{@code null}, if the length of the given arguments array is shorter
	 * than the length of the {@linkplain
	 * Method#getGenericParameterTypes() formal argument list} for the given
	 * method</li>
	 * </ul>
	 * @param method the method to introspect (never {@code null})
	 * @param args the arguments that will be supplied to the method when it is
	 * invoked (never {@code null})
	 * @param classLoader the ClassLoader to resolve class names against,
	 * if necessary (never {@code null})
	 * @return the resolved target return type or the standard method return type
	 * @since 3.2.5
	 */
	public static Class<?> resolveReturnTypeForFactoryMethod(
			Method method, Object[] args, @Nullable ClassLoader classLoader) {

		Assert.notNull(method, "Method must not be null");
		Assert.notNull(args, "Argument array must not be null");

		// 获取方法声明的泛型变量
		TypeVariable<Method>[] declaredTypeVariables = method.getTypeParameters();
		// 获取方法的泛型返回类型
		Type genericReturnType = method.getGenericReturnType();
		// 获取方法的泛型参数类型
		Type[] methodParameterTypes = method.getGenericParameterTypes();
		// 判断传入的参数个数是否和方法的参数个数相等
		Assert.isTrue(args.length == methodParameterTypes.length, "Argument array does not match parameter count");

		// Ensure that the type variable (e.g., T) is declared directly on the method
		// itself (e.g., via <T>), not on the enclosing class or interface.
		// 判断泛型变量是直接声明在方法上的 而不是声明在类或接口上
		boolean locallyDeclaredTypeVariableMatchesReturnType = false;
		for (TypeVariable<Method> currentTypeVariable : declaredTypeVariables) {
			if (currentTypeVariable.equals(genericReturnType)) {
				locallyDeclaredTypeVariableMatchesReturnType = true;
				break;
			}
		}

		// 如果泛型变量是声明在方法上的
		if (locallyDeclaredTypeVariableMatchesReturnType) {
			// 遍历方法的泛型参数
			for (int i = 0; i < methodParameterTypes.length; i++) {
				Type methodParameterType = methodParameterTypes[i];
				// 并且获取对应下标的传入的参数
				Object arg = args[i];
				// 如果它和返回值类型相等了
				if (methodParameterType.equals(genericReturnType)) {
					// 如果对应的arg是TypedStringValue类型的
					if (arg instanceof TypedStringValue) {
						TypedStringValue typedValue = ((TypedStringValue) arg);
						if (typedValue.hasTargetType()) {
							return typedValue.getTargetType();
						}
						try {
							Class<?> resolvedType = typedValue.resolveTargetType(classLoader);
							if (resolvedType != null) {
								return resolvedType;
							}
						}
						catch (ClassNotFoundException ex) {
							throw new IllegalStateException("Failed to resolve value type [" +
									typedValue.getTargetTypeName() + "] for factory method argument", ex);
						}
					}
					else if (arg != null && !(arg instanceof BeanMetadataElement)) {
						// Only consider argument type if it is a simple value...
						return arg.getClass();
					}
					return method.getReturnType();
				}
				else if (methodParameterType instanceof ParameterizedType) {
					ParameterizedType parameterizedType = (ParameterizedType) methodParameterType;
					Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
					for (Type typeArg : actualTypeArguments) {
						if (typeArg.equals(genericReturnType)) {
							if (arg instanceof Class) {
								return (Class<?>) arg;
							}
							else {
								String className = null;
								if (arg instanceof String) {
									className = (String) arg;
								}
								else if (arg instanceof TypedStringValue) {
									TypedStringValue typedValue = ((TypedStringValue) arg);
									String targetTypeName = typedValue.getTargetTypeName();
									if (targetTypeName == null || Class.class.getName().equals(targetTypeName)) {
										className = typedValue.getValue();
									}
								}
								if (className != null) {
									try {
										return ClassUtils.forName(className, classLoader);
									}
									catch (ClassNotFoundException ex) {
										throw new IllegalStateException("Could not resolve class name [" + arg +
												"] for factory method argument", ex);
									}
								}
								// Consider adding logic to determine the class of the typeArg, if possible.
								// For now, just fall back...
								return method.getReturnType();
							}
						}
					}
				}
			}
		}

		// Fall back...
		return method.getReturnType();
	}


	/**
	 * Reflective {@link InvocationHandler} for lazy access to the current target object.
	 */
	@SuppressWarnings("serial")
	private static class ObjectFactoryDelegatingInvocationHandler implements InvocationHandler, Serializable {

		private final ObjectFactory<?> objectFactory;

		public ObjectFactoryDelegatingInvocationHandler(ObjectFactory<?> objectFactory) {
			this.objectFactory = objectFactory;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String methodName = method.getName();
			if (methodName.equals("equals")) {
				// Only consider equal when proxies are identical.
				return (proxy == args[0]);
			}
			else if (methodName.equals("hashCode")) {
				// Use hashCode of proxy.
				return System.identityHashCode(proxy);
			}
			else if (methodName.equals("toString")) {
				return this.objectFactory.toString();
			}
			try {
				return method.invoke(this.objectFactory.getObject(), args);
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}

}
