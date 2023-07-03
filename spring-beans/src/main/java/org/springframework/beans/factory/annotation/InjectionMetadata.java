/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 * Internal class for managing injection metadata.
 * Not intended for direct use in applications.
 *
 * <p>Used by {@link AutowiredAnnotationBeanPostProcessor},
 * {@link org.springframework.context.annotation.CommonAnnotationBeanPostProcessor} and
 * {@link org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor}.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
public class InjectionMetadata {

	/**
	 * An empty {@code InjectionMetadata} instance with no-op callbacks.
	 * @since 5.2
	 */
	public static final InjectionMetadata EMPTY = new InjectionMetadata(Object.class, Collections.emptyList()) {
		@Override
		protected boolean needsRefresh(Class<?> clazz) {
			return false;
		}
		@Override
		public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		}
		@Override
		public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) {
		}
		@Override
		public void clear(@Nullable PropertyValues pvs) {
		}
	};


	private final Class<?> targetClass;

	private final Collection<InjectedElement> injectedElements;

	@Nullable
	private volatile Set<InjectedElement> checkedElements;


	/**
	 * Create a new {@code InjectionMetadata instance}.
	 * <p>Preferably use {@link #forElements} for reusing the {@link #EMPTY}
	 * instance in case of no elements.
	 * @param targetClass the target class
	 * @param elements the associated elements to inject
	 * @see #forElements
	 */
	public InjectionMetadata(Class<?> targetClass, Collection<InjectedElement> elements) {
		this.targetClass = targetClass;
		this.injectedElements = elements;
	}


	/**
	 * Determine whether this metadata instance needs to be refreshed.
	 * @param clazz the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 * @since 5.2.4
	 */
	protected boolean needsRefresh(Class<?> clazz) {
		return this.targetClass != clazz;
	}

	public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		Set<InjectedElement> checkedElements = new LinkedHashSet<>(this.injectedElements.size());
		// 遍历持有的injectedElement的集合
		for (InjectedElement element : this.injectedElements) {
			// 获取element的member
			Member member = element.getMember();
			// 如果bd的externallyManagedConfigMembers集合中不包含该member
			if (!beanDefinition.isExternallyManagedConfigMember(member)) {
				// 将该member注册进bd的externallyManagedConfigMembers集合中
				beanDefinition.registerExternallyManagedConfigMember(member);
				// 并且添加到checkedElements这个set中
				checkedElements.add(element);
			}
		}
		// 将set赋值给自身属性
		this.checkedElements = checkedElements;
	}

	public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
		// 获取checkedElements
		Collection<InjectedElement> checkedElements = this.checkedElements;
		// 如果checkedElements为null的话，直接使用injectedElements
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		// 如果要遍历的elements不为空
		if (!elementsToIterate.isEmpty()) {
			// 进行遍历，并且调用每个element的inject方法
			for (InjectedElement element : elementsToIterate) {
				element.inject(target, beanName, pvs);
			}
		}
	}

	/**
	 * Clear property skipping for the contained elements.
	 * @since 3.2.13
	 */
	public void clear(@Nullable PropertyValues pvs) {
		// 获取其检查过的元素
		Collection<InjectedElement> checkedElements = this.checkedElements;
		// 如果检查过的元素存在，使用它进行遍历，否则直接使用injectedElements进行遍历
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		// 如果要遍历的集合不为空
		if (!elementsToIterate.isEmpty()) {
			for (InjectedElement element : elementsToIterate) {
				// 遍历集合调用每个element的clearPropertySkipping方法
				element.clearPropertySkipping(pvs);
			}
		}
	}


	/**
	 * Return an {@code InjectionMetadata} instance, possibly for empty elements.
	 * @param elements the elements to inject (possibly empty)
	 * @param clazz the target class
	 * @return a new {@link #InjectionMetadata(Class, Collection)} instance
	 * @since 5.2
	 */
	public static InjectionMetadata forElements(Collection<InjectedElement> elements, Class<?> clazz) {
		return (elements.isEmpty() ? new InjectionMetadata(clazz, Collections.emptyList()) :
				new InjectionMetadata(clazz, elements));
	}

	/**
	 * Check whether the given injection metadata needs to be refreshed.
	 * @param metadata the existing metadata instance
	 * @param clazz the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 * @see #needsRefresh(Class)
	 */
	public static boolean needsRefresh(@Nullable InjectionMetadata metadata, Class<?> clazz) {
		// 如果元数据不存在 或者 元数据需要更新了(即元数据中的targetClass和传入的clazz不相等)，返回true
		return (metadata == null || metadata.needsRefresh(clazz));
	}


	/**
	 * A single injected element.
	 */
	public abstract static class InjectedElement {

		protected final Member member;

		protected final boolean isField;

		@Nullable
		protected final PropertyDescriptor pd;

		@Nullable
		protected volatile Boolean skip;

		protected InjectedElement(Member member, @Nullable PropertyDescriptor pd) {
			this.member = member;
			this.isField = (member instanceof Field);
			this.pd = pd;
		}

		public final Member getMember() {
			return this.member;
		}

		protected final Class<?> getResourceType() {
			// 如果是字段类型
			if (this.isField) {
				// 获取字段的类型返回
				return ((Field) this.member).getType();
			}
			// 如果pd存在，返回pd的propertyType
			else if (this.pd != null) {
				return this.pd.getPropertyType();
			}
			// 如果是方法类型，返回方法的第一个参数的类型
			else {
				return ((Method) this.member).getParameterTypes()[0];
			}
		}

		protected final void checkResourceType(Class<?> resourceType) {
			// 如果member是字段类型的
			if (this.isField) {
				// 获取字段对应的类型
				Class<?> fieldType = ((Field) this.member).getType();
				// 如果resourceType和字段类型没有继承关系，报错
				if (!(resourceType.isAssignableFrom(fieldType) || fieldType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified field type [" + fieldType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}
			// 如果member是方法类型的
			else {
				// 如果pd存在，获取propertyType，否则获取方法的第一个参数的类型
				Class<?> paramType =
						(this.pd != null ? this.pd.getPropertyType() : ((Method) this.member).getParameterTypes()[0]);
				// 如果resourceType和参数类型没有继承关系，报错
				if (!(resourceType.isAssignableFrom(paramType) || paramType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified parameter type [" + paramType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}
		}

		/**
		 * Either this or {@link #getResourceToInject} needs to be overridden.
		 */
		protected void inject(Object target, @Nullable String requestingBeanName, @Nullable PropertyValues pvs)
				throws Throwable {

			// 如果持有的member是field类型的
			if (this.isField) {
				Field field = (Field) this.member;
				ReflectionUtils.makeAccessible(field);
				// 调用getResourceToInject方法获取要注入的值
				// 调用field的set方法将值设置进去
				field.set(target, getResourceToInject(target, requestingBeanName));
			}
			// 如果持有的member是method类型的
			else {
				// 查看injectedElement的skip属性看是否需要跳过，如果该属性为null的话，
				// 那么比较pvs中是否存在相同属性名的属性，如果存在的话，将skip属性置为true并返回true，
				// 否则置为false并返回false
				if (checkPropertySkipping(pvs)) {
					return;
				}
				try {
					Method method = (Method) this.member;
					ReflectionUtils.makeAccessible(method);
					// 调用getResourceToInject方法获取要注入的值
					// 调用method方法将值设置进target对象中
					method.invoke(target, getResourceToInject(target, requestingBeanName));
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		/**
		 * Check whether this injector's property needs to be skipped due to
		 * an explicit property value having been specified. Also marks the
		 * affected property as processed for other processors to ignore it.
		 */
		// 判断这个属性是否需要被跳过
		protected boolean checkPropertySkipping(@Nullable PropertyValues pvs) {
			// 获取其skip属性
			Boolean skip = this.skip;
			// skip默认应该是null，如果skip不为null的话，直接返回skip表示结果
			if (skip != null) {
				return skip;
			}
			// 如果传入的PropertyValues是null，那么将skip属性置为false，返回false，表示不跳过
			if (pvs == null) {
				this.skip = false;
				return false;
			}
			// 如果skip为null，加锁
			synchronized (pvs) {
				// double check，防止多个线程执行了解析并赋值skip的操作
				skip = this.skip;
				if (skip != null) {
					return skip;
				}
				// 如果持有的pd不为null
				if (this.pd != null) {
					// 判断pvs中是否包含有该属性名
					if (pvs.contains(this.pd.getName())) {
						// Explicit value provided as part of the bean definition.
						// 如果已经包含了，那么将skip属性置为true，因为该属性会通过pvs进行设置，返回true
						this.skip = true;
						return true;
					}
					// 如果pvs中不包含该属性名，且pvs是MutablePropertyValues类型的
					else if (pvs instanceof MutablePropertyValues) {
						// 调用registerProcessedProperty方法，将属性名添加进其processedProperties集合中，表示已经处理过该属性名
						((MutablePropertyValues) pvs).registerProcessedProperty(this.pd.getName());
					}
				}
				// 将skip属性设置为false，并返回false
				this.skip = false;
				return false;
			}
		}

		/**
		 * Clear property skipping for this element.
		 * @since 3.2.13
		 */
		protected void clearPropertySkipping(@Nullable PropertyValues pvs) {
			// 如果pvs为null，直接返回
			if (pvs == null) {
				return;
			}
			synchronized (pvs) {
				// 如果skip属性为false 并且 存在pd 并且pvs是属于MutablePropertyValues类型的
				if (Boolean.FALSE.equals(this.skip) && this.pd != null && pvs instanceof MutablePropertyValues) {
					// 调用pvs的clearProcessedProperty方法，根据pd的name
					((MutablePropertyValues) pvs).clearProcessedProperty(this.pd.getName());
				}
			}
		}

		/**
		 * Either this or {@link #inject} needs to be overridden.
		 */
		@Nullable
		protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
			return null;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof InjectedElement)) {
				return false;
			}
			InjectedElement otherElement = (InjectedElement) other;
			return this.member.equals(otherElement.member);
		}

		@Override
		public int hashCode() {
			return this.member.getClass().hashCode() * 29 + this.member.getName().hashCode();
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + " for " + this.member;
		}
	}

}
