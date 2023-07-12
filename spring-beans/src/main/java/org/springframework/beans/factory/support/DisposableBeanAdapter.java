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

package org.springframework.beans.factory.support;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Adapter that implements the {@link DisposableBean} and {@link Runnable}
 * interfaces performing various destruction steps on a given bean instance:
 * <ul>
 * <li>DestructionAwareBeanPostProcessors;
 * <li>the bean implementing DisposableBean itself;
 * <li>a custom destroy method specified on the bean definition.
 * </ul>
 *
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Stephane Nicoll
 * @since 2.0
 * @see AbstractBeanFactory
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
 * @see AbstractBeanDefinition#getDestroyMethodName()
 */
@SuppressWarnings("serial")
class DisposableBeanAdapter implements DisposableBean, Runnable, Serializable {

	private static final String DESTROY_METHOD_NAME = "destroy";

	private static final String CLOSE_METHOD_NAME = "close";

	private static final String SHUTDOWN_METHOD_NAME = "shutdown";

	private static final Log logger = LogFactory.getLog(DisposableBeanAdapter.class);


	private final Object bean;

	private final String beanName;

	private final boolean invokeDisposableBean;

	private final boolean nonPublicAccessAllowed;

	@Nullable
	private final AccessControlContext acc;

	@Nullable
	private String destroyMethodName;

	@Nullable
	private transient Method destroyMethod;

	@Nullable
	private final List<DestructionAwareBeanPostProcessor> beanPostProcessors;


	/**
	 * Create a new DisposableBeanAdapter for the given bean.
	 * @param bean the bean instance (never {@code null})
	 * @param beanName the name of the bean
	 * @param beanDefinition the merged bean definition
	 * @param postProcessors the List of BeanPostProcessors
	 * (potentially DestructionAwareBeanPostProcessor), if any
	 */
	public DisposableBeanAdapter(Object bean, String beanName, RootBeanDefinition beanDefinition,
			List<BeanPostProcessor> postProcessors, @Nullable AccessControlContext acc) {

		Assert.notNull(bean, "Disposable bean must not be null");
		this.bean = bean;
		this.beanName = beanName;
		// 如果bean是属于DisposableBean的，并且destroy方法不包含在外部管理的destroyMethod方法名集合中，
		// 那么invokeDisposableBean为true，表示需要调用DisposableBean的destroy方法
		this.invokeDisposableBean = (bean instanceof DisposableBean &&
				!beanDefinition.isExternallyManagedDestroyMethod(DESTROY_METHOD_NAME));
		this.nonPublicAccessAllowed = beanDefinition.isNonPublicAccessAllowed();
		this.acc = acc;

		// 推断出bean的destroyMethodName
		String destroyMethodName = inferDestroyMethodIfNecessary(bean, beanDefinition);
		// 如果destroyMethodName不为null 并且 (invokeDisposableBean为false 或者 destroyMethodName不等于destroy)
		// 并且 destroyMethodName不包含在外部管理的destroyMethod方法名集合中
		if (destroyMethodName != null &&
				!(this.invokeDisposableBean && DESTROY_METHOD_NAME.equals(destroyMethodName)) &&
				!beanDefinition.isExternallyManagedDestroyMethod(destroyMethodName)) {

			// 将destroyMethodName赋值给自身属性
			this.destroyMethodName = destroyMethodName;
			// 根据name查找到destroyMethod的反射对象
			Method destroyMethod = determineDestroyMethod(destroyMethodName);
			// 如果destroyMethod为null
			if (destroyMethod == null) {
				// 并且bd的enforceDestroyMethod为true，报错
				if (beanDefinition.isEnforceDestroyMethod()) {
					throw new BeanDefinitionValidationException("Could not find a destroy method named '" +
							destroyMethodName + "' on bean with name '" + beanName + "'");
				}
			}
			// 如果destroyMethod不为null
			else {
				// 如果方法的参数个数大于0
				if (destroyMethod.getParameterCount() > 0) {
					Class<?>[] paramTypes = destroyMethod.getParameterTypes();
					// 如果参数个数大于1，报错
					if (paramTypes.length > 1) {
						throw new BeanDefinitionValidationException("Method '" + destroyMethodName + "' of bean '" +
								beanName + "' has more than one parameter - not supported as destroy method");
					}
					// 如果参数个数等于1，并且参数类型不是boolean类型的，报错
					else if (paramTypes.length == 1 && boolean.class != paramTypes[0]) {
						throw new BeanDefinitionValidationException("Method '" + destroyMethodName + "' of bean '" +
								beanName + "' has a non-boolean parameter - not supported as destroy method");
					}
				}
				// 尝试查找destroyMethod的接口声明版本
				destroyMethod = ClassUtils.getInterfaceMethodIfPossible(destroyMethod);
			}
			// 将destroyMethod赋值给自身属性
			this.destroyMethod = destroyMethod;
		}

		// 过滤传入的bbp集合，挑选出满足当前bean的那些DestructionAwareBeanPostProcessor
		this.beanPostProcessors = filterPostProcessors(postProcessors, bean);
	}

	/**
	 * Create a new DisposableBeanAdapter for the given bean.
	 * @param bean the bean instance (never {@code null})
	 * @param postProcessors the List of BeanPostProcessors
	 * (potentially DestructionAwareBeanPostProcessor), if any
	 */
	public DisposableBeanAdapter(Object bean, List<BeanPostProcessor> postProcessors, AccessControlContext acc) {
		Assert.notNull(bean, "Disposable bean must not be null");
		this.bean = bean;
		this.beanName = bean.getClass().getName();
		this.invokeDisposableBean = (this.bean instanceof DisposableBean);
		this.nonPublicAccessAllowed = true;
		this.acc = acc;
		this.beanPostProcessors = filterPostProcessors(postProcessors, bean);
	}

	/**
	 * Create a new DisposableBeanAdapter for the given bean.
	 */
	private DisposableBeanAdapter(Object bean, String beanName, boolean invokeDisposableBean,
			boolean nonPublicAccessAllowed, @Nullable String destroyMethodName,
			@Nullable List<DestructionAwareBeanPostProcessor> postProcessors) {

		this.bean = bean;
		this.beanName = beanName;
		this.invokeDisposableBean = invokeDisposableBean;
		this.nonPublicAccessAllowed = nonPublicAccessAllowed;
		this.acc = null;
		this.destroyMethodName = destroyMethodName;
		this.beanPostProcessors = postProcessors;
	}


	@Override
	public void run() {
		destroy();
	}

	@Override
	public void destroy() {
		// 如果持有的bbp集合不为空
		if (!CollectionUtils.isEmpty(this.beanPostProcessors)) {
			// 遍历，然后调用bbp的postProcessBeforeDestruction方法
			for (DestructionAwareBeanPostProcessor processor : this.beanPostProcessors) {
				processor.postProcessBeforeDestruction(this.bean, this.beanName);
			}
		}

		// 如果invokeDisposableBean为true，调用bean的destroy方法
		if (this.invokeDisposableBean) {
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking destroy() on bean with name '" + this.beanName + "'");
			}
			try {
				if (System.getSecurityManager() != null) {
					AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
						((DisposableBean) this.bean).destroy();
						return null;
					}, this.acc);
				}
				else {
					((DisposableBean) this.bean).destroy();
				}
			}
			catch (Throwable ex) {
				String msg = "Invocation of destroy method failed on bean with name '" + this.beanName + "'";
				if (logger.isDebugEnabled()) {
					logger.warn(msg, ex);
				}
				else {
					logger.warn(msg + ": " + ex);
				}
			}
		}

		// 如果destroyMethod不为null，调用自定义的destroyMethod
		if (this.destroyMethod != null) {
			invokeCustomDestroyMethod(this.destroyMethod);
		}
		// 如果destroyMethod为null，但是destroyMethodName不为null
		else if (this.destroyMethodName != null) {
			// 根据destroyMethodName查找destroyMethod
			Method methodToInvoke = determineDestroyMethod(this.destroyMethodName);
			// 如果methodToInvoke不为null的话，反射调用该方法的接口版本
			if (methodToInvoke != null) {
				invokeCustomDestroyMethod(ClassUtils.getInterfaceMethodIfPossible(methodToInvoke));
			}
		}
	}


	@Nullable
	private Method determineDestroyMethod(String name) {
		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Method>) () -> findDestroyMethod(name));
			}
			else {
				// 根据方法名查找到对应的方法
				return findDestroyMethod(name);
			}
		}
		catch (IllegalArgumentException ex) {
			throw new BeanDefinitionValidationException("Could not find unique destroy method on bean with name '" +
					this.beanName + ": " + ex.getMessage());
		}
	}

	@Nullable
	private Method findDestroyMethod(String name) {
		// 根据方法名查找到参数个数最少的方法
		return (this.nonPublicAccessAllowed ?
				BeanUtils.findMethodWithMinimalParameters(this.bean.getClass(), name) :
				BeanUtils.findMethodWithMinimalParameters(this.bean.getClass().getMethods(), name));
	}

	/**
	 * Invoke the specified custom destroy method on the given bean.
	 * <p>This implementation invokes a no-arg method if found, else checking
	 * for a method with a single boolean argument (passing in "true",
	 * assuming a "force" parameter), else logging an error.
	 */
	private void invokeCustomDestroyMethod(final Method destroyMethod) {
		// 获取destroyMethod的参数个数
		int paramCount = destroyMethod.getParameterCount();
		final Object[] args = new Object[paramCount];
		// 如果参数个数为1，那么使用true作为参数
		if (paramCount == 1) {
			args[0] = Boolean.TRUE;
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Invoking destroy method '" + this.destroyMethodName +
					"' on bean with name '" + this.beanName + "'");
		}
		try {
			if (System.getSecurityManager() != null) {
				AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
					ReflectionUtils.makeAccessible(destroyMethod);
					return null;
				});
				try {
					AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () ->
						destroyMethod.invoke(this.bean, args), this.acc);
				}
				catch (PrivilegedActionException pax) {
					throw (InvocationTargetException) pax.getException();
				}
			}
			else {
				// 反射调用自定义的destroyMethod
				ReflectionUtils.makeAccessible(destroyMethod);
				destroyMethod.invoke(this.bean, args);
			}
		}
		catch (InvocationTargetException ex) {
			String msg = "Destroy method '" + this.destroyMethodName + "' on bean with name '" +
					this.beanName + "' threw an exception";
			if (logger.isDebugEnabled()) {
				logger.warn(msg, ex.getTargetException());
			}
			else {
				logger.warn(msg + ": " + ex.getTargetException());
			}
		}
		catch (Throwable ex) {
			logger.warn("Failed to invoke destroy method '" + this.destroyMethodName +
					"' on bean with name '" + this.beanName + "'", ex);
		}
	}


	/**
	 * Serializes a copy of the state of this class,
	 * filtering out non-serializable BeanPostProcessors.
	 */
	protected Object writeReplace() {
		List<DestructionAwareBeanPostProcessor> serializablePostProcessors = null;
		if (this.beanPostProcessors != null) {
			serializablePostProcessors = new ArrayList<>();
			for (DestructionAwareBeanPostProcessor postProcessor : this.beanPostProcessors) {
				if (postProcessor instanceof Serializable) {
					serializablePostProcessors.add(postProcessor);
				}
			}
		}
		return new DisposableBeanAdapter(this.bean, this.beanName, this.invokeDisposableBean,
				this.nonPublicAccessAllowed, this.destroyMethodName, serializablePostProcessors);
	}


	/**
	 * Check whether the given bean has any kind of destroy method to call.
	 * @param bean the bean instance
	 * @param beanDefinition the corresponding bean definition
	 */
	public static boolean hasDestroyMethod(Object bean, RootBeanDefinition beanDefinition) {
		// 如果bean实现了DisposableBean接口 或者 bean实现了AutoCloseable接口，直接返回true
		if (bean instanceof DisposableBean || bean instanceof AutoCloseable) {
			return true;
		}
		// 推断bean的destroyMethod，如果该方法返回的不是null，那么代表存在destroyMethod，返回true，否则返回false
		return inferDestroyMethodIfNecessary(bean, beanDefinition) != null;
	}


	/**
	 * If the current value of the given beanDefinition's "destroyMethodName" property is
	 * {@link AbstractBeanDefinition#INFER_METHOD}, then attempt to infer a destroy method.
	 * Candidate methods are currently limited to public, no-arg methods named "close" or
	 * "shutdown" (whether declared locally or inherited). The given BeanDefinition's
	 * "destroyMethodName" is updated to be null if no such method is found, otherwise set
	 * to the name of the inferred method. This constant serves as the default for the
	 * {@code @Bean#destroyMethod} attribute and the value of the constant may also be
	 * used in XML within the {@code <bean destroy-method="">} or {@code
	 * <beans default-destroy-method="">} attributes.
	 * <p>Also processes the {@link java.io.Closeable} and {@link java.lang.AutoCloseable}
	 * interfaces, reflectively calling the "close" method on implementing beans as well.
	 */
	@Nullable
	private static String inferDestroyMethodIfNecessary(Object bean, RootBeanDefinition beanDefinition) {
		// 获取bd的resolvedDestroyMethodName作为destroyMethodName
		String destroyMethodName = beanDefinition.resolvedDestroyMethodName;
		// 如果destroyMethodName为null的话
		if (destroyMethodName == null) {
			// 尝试获取bd的destroyMethodName属性
			destroyMethodName = beanDefinition.getDestroyMethodName();
			// 如果destroyMethodName等于(inferred) 或者 (destroyMethodName为null 并且 bean是属于AutoCloseable的)
			if (AbstractBeanDefinition.INFER_METHOD.equals(destroyMethodName) ||
					(destroyMethodName == null && bean instanceof AutoCloseable)) {
				// Only perform destroy method inference in case of the bean
				// not explicitly implementing the DisposableBean interface
				destroyMethodName = null;
				// 如果bean没有实现DisposableBean接口
				if (!(bean instanceof DisposableBean)) {
					try {
						// 尝试获取名为close的方法名称
						destroyMethodName = bean.getClass().getMethod(CLOSE_METHOD_NAME).getName();
					}
					// 如果没有找到close方法
					catch (NoSuchMethodException ex) {
						try {
							// 尝试获取shutdown方法的名称
							destroyMethodName = bean.getClass().getMethod(SHUTDOWN_METHOD_NAME).getName();
						}
						// 如果仍然没有找到，忽略异常
						catch (NoSuchMethodException ex2) {
							// no candidate destroy method found
						}
					}
				}
			}
			// 设置bd的resolvedDestroyMethodName为destroyMethodName或者空字符串
			beanDefinition.resolvedDestroyMethodName = (destroyMethodName != null ? destroyMethodName : "");
		}
		// 如果destroyMethodName不是空字符串，返回；否则返回null
		return (StringUtils.hasLength(destroyMethodName) ? destroyMethodName : null);
	}

	/**
	 * Check whether the given bean has destruction-aware post-processors applying to it.
	 * @param bean the bean instance
	 * @param postProcessors the post-processor candidates
	 */
	public static boolean hasApplicableProcessors(Object bean, List<BeanPostProcessor> postProcessors) {
		// 如果持有的bbp集合不为空
		if (!CollectionUtils.isEmpty(postProcessors)) {
			// 遍历bbp集合
			for (BeanPostProcessor processor : postProcessors) {
				// 如果bbp是DestructionAwareBeanPostProcessor类型的
				if (processor instanceof DestructionAwareBeanPostProcessor) {
					DestructionAwareBeanPostProcessor dabpp = (DestructionAwareBeanPostProcessor) processor;
					// 调用dabpp的requiresDestruction方法判断bean是否满足要求
					// 比如CommonAnnotationBeanPostProcessor中就是判断bean的类对象对应的LifecycleMetadata中是否包含@PreDestroy注解标注的方法
					if (dabpp.requiresDestruction(bean)) {
						return true;
					}
				}
			}
		}
		// 如果没有dabbp满足要求，返回false
		return false;
	}

	/**
	 * Search for all DestructionAwareBeanPostProcessors in the List.
	 * @param processors the List to search
	 * @return the filtered List of DestructionAwareBeanPostProcessors
	 */
	@Nullable
	private List<DestructionAwareBeanPostProcessor> filterPostProcessors(List<BeanPostProcessor> processors, Object bean) {
		List<DestructionAwareBeanPostProcessor> filteredPostProcessors = null;
		if (!CollectionUtils.isEmpty(processors)) {
			filteredPostProcessors = new ArrayList<>(processors.size());
			for (BeanPostProcessor processor : processors) {
				if (processor instanceof DestructionAwareBeanPostProcessor) {
					DestructionAwareBeanPostProcessor dabpp = (DestructionAwareBeanPostProcessor) processor;
					if (dabpp.requiresDestruction(bean)) {
						filteredPostProcessors.add(dabpp);
					}
				}
			}
		}
		return filteredPostProcessors;
	}

}
