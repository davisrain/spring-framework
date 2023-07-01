/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.beans;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.LogFactory;

import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Extension of the standard JavaBeans {@link PropertyDescriptor} class,
 * overriding {@code getPropertyType()} such that a generically declared
 * type variable will be resolved against the containing bean class.
 *
 * @author Juergen Hoeller
 * @since 2.5.2
 */
final class GenericTypeAwarePropertyDescriptor extends PropertyDescriptor {

	private final Class<?> beanClass;

	@Nullable
	private final Method readMethod;

	@Nullable
	private final Method writeMethod;

	@Nullable
	private volatile Set<Method> ambiguousWriteMethods;

	@Nullable
	private MethodParameter writeMethodParameter;

	@Nullable
	private Class<?> propertyType;

	@Nullable
	private final Class<?> propertyEditorClass;


	public GenericTypeAwarePropertyDescriptor(Class<?> beanClass, String propertyName,
			@Nullable Method readMethod, @Nullable Method writeMethod,
			@Nullable Class<?> propertyEditorClass) throws IntrospectionException {

		super(propertyName, null, null);
		this.beanClass = beanClass;

		// 如果readMethod不为null的话，获取其被桥接的方法(如果该方法是一个桥接方法的话)，否则用null赋值
		Method readMethodToUse = (readMethod != null ? BridgeMethodResolver.findBridgedMethod(readMethod) : null);
		// 如果writeMethod不为null的话，获取其被桥接的方法(如果该方法是一个桥接方法的话)，否则用null赋值
		Method writeMethodToUse = (writeMethod != null ? BridgeMethodResolver.findBridgedMethod(writeMethod) : null);
		// 如果要使用的写方法不存在 且 要使用的读方法存在
		if (writeMethodToUse == null && readMethodToUse != null) {
			// Fallback: Original JavaBeans introspection might not have found matching setter
			// method due to lack of bridge method resolution, in case of the getter using a
			// covariant return type whereas the setter is defined for the concrete property type.
			// 原始的javabean内省没有找到匹配的setter方法是因为缺少了桥接方法的解析，当getter返回一个协变类型，而setter使用的是具体类型的时候
			// 尝试使用set+属性名的方式拼接为方法名然后去查找
			Method candidate = ClassUtils.getMethodIfAvailable(
					this.beanClass, "set" + StringUtils.capitalize(getName()), (Class<?>[]) null);
			// 如果找到对应的方法 且 参数个数为1，将其作为要使用的写方法
			if (candidate != null && candidate.getParameterCount() == 1) {
				writeMethodToUse = candidate;
			}
		}
		// 将读方法和写方法都赋值给自身属性
		this.readMethod = readMethodToUse;
		this.writeMethod = writeMethodToUse;

		// 如果写方法不为null
		if (this.writeMethod != null) {
			// 如果读方法为null
			if (this.readMethod == null) {
				// Write method not matched against read method: potentially ambiguous through
				// several overloaded variants, in which case an arbitrary winner has been chosen
				// by the JDK's JavaBeans Introspector...
				// 写方法没有匹配到对应的读方法，可能是写方法存在重载的变体，这种情况jdk的introspector会任意地选择一个方法作为写方法
				Set<Method> ambiguousCandidates = new HashSet<>();
				// 遍历beanClass的方法
				for (Method method : beanClass.getMethods()) {
					// 如果方法名等于写方法的名称 并且 方法不等于写方法 并且 方法不是桥接方法 并且方法参数个数等于写方法的参数个数
					if (method.getName().equals(writeMethodToUse.getName()) &&
							!method.equals(writeMethodToUse) && !method.isBridge() &&
							method.getParameterCount() == writeMethodToUse.getParameterCount()) {
						// 将该方法添加到模拟两可的候选set中
						ambiguousCandidates.add(method);
					}
				}
				// 如果set不为空的话，将其赋值给自身字段
				if (!ambiguousCandidates.isEmpty()) {
					this.ambiguousWriteMethods = ambiguousCandidates;
				}
			}
			// 创建一个MethodParameter作为写方法的参数
			this.writeMethodParameter = new MethodParameter(this.writeMethod, 0).withContainingClass(this.beanClass);
		}

		// 如果读方法不为null，通过获取读方法的返回值解析出propertyType
		if (this.readMethod != null) {
			this.propertyType = GenericTypeResolver.resolveReturnType(this.readMethod, this.beanClass);
		}
		// 否则，根据写方法的方法参数获取propertyType
		else if (this.writeMethodParameter != null) {
			this.propertyType = this.writeMethodParameter.getParameterType();
		}

		// 将propertyEditorClass直接赋值
		this.propertyEditorClass = propertyEditorClass;
	}


	public Class<?> getBeanClass() {
		return this.beanClass;
	}

	@Override
	@Nullable
	public Method getReadMethod() {
		return this.readMethod;
	}

	@Override
	@Nullable
	public Method getWriteMethod() {
		return this.writeMethod;
	}

	public Method getWriteMethodForActualAccess() {
		Assert.state(this.writeMethod != null, "No write method available");
		Set<Method> ambiguousCandidates = this.ambiguousWriteMethods;
		if (ambiguousCandidates != null) {
			this.ambiguousWriteMethods = null;
			LogFactory.getLog(GenericTypeAwarePropertyDescriptor.class).debug("Non-unique JavaBean property '" +
					getName() + "' being accessed! Ambiguous write methods found next to actually used [" +
					this.writeMethod + "]: " + ambiguousCandidates);
		}
		return this.writeMethod;
	}

	public MethodParameter getWriteMethodParameter() {
		Assert.state(this.writeMethodParameter != null, "No write method available");
		return this.writeMethodParameter;
	}

	@Override
	@Nullable
	public Class<?> getPropertyType() {
		return this.propertyType;
	}

	@Override
	@Nullable
	public Class<?> getPropertyEditorClass() {
		return this.propertyEditorClass;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof GenericTypeAwarePropertyDescriptor)) {
			return false;
		}
		GenericTypeAwarePropertyDescriptor otherPd = (GenericTypeAwarePropertyDescriptor) other;
		return (getBeanClass().equals(otherPd.getBeanClass()) && PropertyDescriptorUtils.equals(this, otherPd));
	}

	@Override
	public int hashCode() {
		int hashCode = getBeanClass().hashCode();
		hashCode = 29 * hashCode + ObjectUtils.nullSafeHashCode(getReadMethod());
		hashCode = 29 * hashCode + ObjectUtils.nullSafeHashCode(getWriteMethod());
		return hashCode;
	}

}
