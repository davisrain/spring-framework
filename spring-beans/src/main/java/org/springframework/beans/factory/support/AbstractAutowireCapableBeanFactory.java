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

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.AutowiredPropertyMarker;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 * 提供了bean创建（包括构造器解析）、属性填充、注入（包括自动注入）和初始化功能。
 * 处理运行时的bean引用，解析被管理的集合，调用初始化方法等。
 * 支持自动注入的构造器，按名称填充属性，按类型填充属性
 *
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 * 主要的需要被子类实现的模板方法是resolveDependency，被用来按类型自动注入。
 * 在一个有能力搜索它持有的beanDefinitions的factory的情况下，匹配bean将通常使用这样的搜索方法来实现。
 * 对于其他类型的factory，简化的匹配算法可以被实现
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 * 注意这个类没有假设或实现beanDefinitionRegistry的能力
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 13.02.2004
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/** Strategy for creating bean instances. */
	// 创建bean实例的策略
	private InstantiationStrategy instantiationStrategy = new CglibSubclassingInstantiationStrategy();

	/** Resolver strategy for method parameter names. */
	// 参数名字的发现器
	@Nullable
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	/** Whether to automatically try to resolve circular references between beans. */
	// 是否允许循环引用
	private boolean allowCircularReferences = true;

	/**
	 * Whether to resort to injecting a raw bean instance in case of circular reference,
	 * even if the injected bean eventually got wrapped.
	 */
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 */
	// 需要在依赖检查和自动注入阶段忽略的类型
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 */
	// 需要在依赖检查和自动注入阶段忽略的接口

	// 在该类的构造方法中添加BeanFactoryAware BeanNameAware BeanClassLoaderAware接口进来，
	// 在AbstractApplicationContext的refresh阶段的prepareBeanFactory方法中，又添加了ApplicationContext相关的Aware接口进来。
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

	/**
	 * The name of the currently created bean, for implicit dependency registration
	 * on getBean etc invocations triggered from a user-specified Supplier callback.
	 */
	private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

	/** Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper. */
	// 用于缓存没有初始化完成的FactoryBean实例，key为FactoryBean的beanName，value为BeanWrapper，
	// 主要是用于getTypeForFactoryBean方法，获取FactoryBean的objectType使用
	private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

	/** Cache of candidate factory methods per factory class. */
	private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

	/** Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array. */
	private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<>();


	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		// 在依赖检查和注入的时候忽略掉这些接口类型的成员变量
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 */
	public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this();
		setParentBeanFactory(parentBeanFactory);
	}


	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 */
	protected InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>Default is a {@link DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	@Nullable
	protected ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 * @see #setAllowCircularReferences
	 */
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 */
	public void ignoreDependencyInterface(Class<?> ifc) {
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory) {
			AbstractAutowireCapableBeanFactory otherAutowireFactory =
					(AbstractAutowireCapableBeanFactory) otherFactory;
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}


	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use prototype bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass);
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(beanClass, getBeanClassLoader());
		return (T) createBean(beanClass.getName(), bd, null);
	}

	@Override
	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), getBeanClassLoader());
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition) {
			RootBeanDefinition rbd = (RootBeanDefinition) mbd;
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (bd == null) {
			bd = new RootBeanDefinition(mbd);
		}
		if (!bd.isPrototype()) {
			bd.setScope(SCOPE_PROTOTYPE);
			bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), getBeanClassLoader());
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(beanName, bd, bw);
		return initializeBean(beanName, existingBean, bd);
	}


	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	@Override
	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	@Override
	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		}
		else {
			Object bean;
			if (System.getSecurityManager() != null) {
				bean = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(bd, null, this),
						getAccessControlContext());
			}
			else {
				bean = getInstantiationStrategy().instantiate(bd, null, this);
			}
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	@Override
	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	@Override
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException {

		// 将bean赋值给result
		Object result = existingBean;
		// 获取持有的BeanPostProcessor集合
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			// 调用每个bbp的postProcessBeforeInitialization方法对result进行处理，然后返回current对象
			Object current = processor.postProcessBeforeInitialization(result, beanName);
			// 如果current为null的话，直接返回result
			if (current == null) {
				return result;
			}
			// 否则，将result设置为current，继续遍历bbp，对result进行处理
			result = current;
		}
		// 最后返回result
		return result;
	}

	@Override
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {
		// 将bean赋值给result
		Object result = existingBean;
		// 遍历持有的BeanPostProcessor
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			// 调用bbp的postProcessAfterInitialization方法对result进行处理，返回current对象
			Object current = processor.postProcessAfterInitialization(result, beanName);
			// 如果current为null的话，直接返回result
			if (current == null) {
				return result;
			}
			// 否则将current赋值给result继续遍历处理
			result = current;
		}
		// 返回result对象
		return result;
	}

	@Override
	public void destroyBean(Object existingBean) {
		new DisposableBeanAdapter(existingBean, getBeanPostProcessors(), getAccessControlContext()).destroy();
	}


	//-------------------------------------------------------------------------
	// Delegate methods for resolving injection points
	//-------------------------------------------------------------------------

	@Override
	public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			// 直接调用getBean方法，其中type使用descriptor的dependencyType
			return getBean(name, descriptor.getDependencyType());
		}
		finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
		return resolveDependency(descriptor, requestingBeanName, null, null);
	}


	//---------------------------------------------------------------------
	// Implementation of relevant AbstractBeanFactory template methods
	//---------------------------------------------------------------------

	/**
	 * Central method of this class: creates a bean instance,
	 * populates the bean instance, applies post-processors, etc.
	 *
	 * 这个类的核心方法，创建bean实例，填充bean实例并且应用beanPostProcessor等
	 * @see #doCreateBean
	 */
	@Override
	protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		if (logger.isTraceEnabled()) {
			logger.trace("Creating instance of bean '" + beanName + "'");
		}
		RootBeanDefinition mbdToUse = mbd;

		// Make sure bean class is actually resolved at this point, and
		// clone the bean definition in case of a dynamically resolved Class
		// which cannot be stored in the shared merged bean definition.
		// 确保beanClass在这个点已经实际的解析了，并且克隆这个beanDefinition以防 动态解析的类 无法保存在共享的mdb的情况
		// 解析mbd的beanClass
		Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
		// 如果解析出的beanClass不为null 并且mbd中的beanClass并不是Class类型的 而是 String类型的，
		// 说明该beanClass是通过dynamicLoader解析的，无法缓存在共享的mdb对象中，那么克隆一个mdb，将beanClass存入，以便后续的使用，
		// 并且将要使用的mbdToUse引用指向克隆出来的带beanClass的这个mbd
		if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
			// 因为beanClass是被动态解析出来的，不能存入共享的mbd中，因此克隆一个mbd出来使用，并且将解析后的class设置进去
			mbdToUse = new RootBeanDefinition(mbd);
			mbdToUse.setBeanClass(resolvedClass);
		}

		// Prepare method overrides.
		try {
			// 准备需要重写的方法，检查方法是否存在，以及对应方法是否有重载版本
			mbdToUse.prepareMethodOverrides();
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}

		try {
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			// 在实例化之前进行解析，给BeanPostProcessor一个机会返回一个代理而不是目标bean实例
			Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
			// 如果创建出了对应的bean，直接返回
			if (bean != null) {
				return bean;
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName,
					"BeanPostProcessor before instantiation of bean failed", ex);
		}

		try {
			// 调用具体的创建逻辑
			Object beanInstance = doCreateBean(beanName, mbdToUse, args);
			if (logger.isTraceEnabled()) {
				logger.trace("Finished creating instance of bean '" + beanName + "'");
			}
			return beanInstance;
		}
		catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
			// A previously detected exception with proper bean creation context already,
			// or illegal singleton state to be communicated up to DefaultSingletonBeanRegistry.
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
		}
	}

	/**
	 * Actually create the specified bean. Pre-creation processing has already happened
	 * at this point, e.g. checking {@code postProcessBeforeInstantiation} callbacks.
	 * <p>Differentiates between default bean instantiation, use of a
	 * factory method, and autowiring a constructor.
	 *
	 * 实际创建指定的bean，创建的前置处理都已经在这个点之前完成了，比如检查postProcessBeforeInstantiation回调
	 * 和默认的bean实例化不同的点在于，使用一个factoryMethod 或者 自动注入一个构造器
	 *
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 * @see #instantiateBean
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 */
	protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		// Instantiate the bean.
		BeanWrapper instanceWrapper = null;
		// 如果mbd是单例的
		if (mbd.isSingleton()) {
			// 尝试从factoryBeanInstanceCache缓存中根据beanName获取对应的BeanWrapper对象
			// 因为FactoryBean可能在getTypeForFactoryBean的时候就创建过未初始化完成的BeanWrapper对象，并且缓存起来了。
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		// 如果instanceWrapper仍为null的话，调用createBeanInstance方法进行创建
		if (instanceWrapper == null) {
			instanceWrapper = createBeanInstance(beanName, mbd, args);
		}
		// 获取被包装的实际的bean
		Object bean = instanceWrapper.getWrappedInstance();
		// 获取被包装的实际的bean类型
		Class<?> beanType = instanceWrapper.getWrappedClass();
		// 如果beanType不是NullBean.class的，将其赋值给mbd的resolvedTargetType
		if (beanType != NullBean.class) {
			mbd.resolvedTargetType = beanType;
		}

		// Allow post-processors to modify the merged bean definition.
		// 允许postProcessor去修改这个mergedBeanDefinition
		synchronized (mbd.postProcessingLock) {
			// 如果该mbd的postProcessed属性为false，表示还没有被处理过
			if (!mbd.postProcessed) {
				try {
					// 调用MergedBeanDefinitionPostProcessor类型的BeanPostProcessor对mergedBeanDefinition进行处理。
					// 1.CommonAnnotationBeanPostProcessor类，该类继承了InitDestroyAnnotationBeanPostProcessor，
					// 上面这两个类分别缓存了beanType或beanName对应的LifecycleMetadata和InjectionMetadata到自身的cache中，
					// 并且设置了mbd的externallyManagedInitMethods externallyManagedDestroyMethods externallyManagedConfigMembers
					// 2.AutowiredAnnotationBeanPostProcessor类也实现了该接口，也缓存了beanName对应的InjectionMetadata到自身的cache中，
					// 并且设置了mbd的externallyManagedConfigMembers
					applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Post-processing of merged bean definition failed", ex);
				}
				// 处理完成后将该mbd的postProcessed置为true
				mbd.postProcessed = true;
			}
		}

		// Eagerly cache singletons to be able to resolve circular references
		// even when triggered by lifecycle interfaces like BeanFactoryAware.
		// 如果mbd是单例 并且 allowCircularReferences标志为true 并且 beanName还在singletonsCurrentlyInCreation中，表示该bean
		// 还处于创建状态。由于需要解析循环依赖的原因，需要将还未初始化的bean提前暴露出来，将其加入到beanFactory的三级缓存中
		boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
				isSingletonCurrentlyInCreation(beanName));
		if (earlySingletonExposure) {
			if (logger.isTraceEnabled()) {
				logger.trace("Eagerly caching bean '" + beanName +
						"' to allow for resolving potential circular references");
			}
			// 根据beanName mbd 提前暴露出来的bean引用封装成getEarlyBeanReference方法引用，添加进三级缓存singletonFactories中。
			// 该ObjectFactory的获取逻辑是：如果mbd不是合成的，依次遍历beanFactory中持有的SmartInstantiationAwareBeanPostProcessor的
			// getEarlyBeanReference方法对bean进行处理，将处理后的对象当作下一次处理的参数。相当于循环对bean进行增强
			addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
		}

		// Initialize the bean instance.
		// 下面开始初始化bean
		Object exposedObject = bean;
		try {
			// 填充bean的属性
			populateBean(beanName, mbd, instanceWrapper);
			// 初始化bean
			exposedObject = initializeBean(beanName, exposedObject, mbd);
		}
		catch (Throwable ex) {
			if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
				throw (BeanCreationException) ex;
			}
			else {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
			}
		}

		// 如果bean的实例是被提前暴露的
		if (earlySingletonExposure) {
			// 尝试从singletonObjects和earlySingletonObjects这两个一级和二级缓存中去查找beanName对应的单例，
			// 这里allowEarlyReference为false，不允许去三级缓存中查找
			Object earlySingletonReference = getSingleton(beanName, false);
			// 如果找到的earlySingletonReference不为null的话，说明存在循环依赖，三级缓存中singletonFactories中的ObjectFactory已经被调用过getObject方法，
			// 执行了getEarlyBeanReference的逻辑(可能通过SmartInstantiationAwareBeanPostProcessor实现了aop)，并且将结果放入了一二级缓存中
			if (earlySingletonReference != null) {
				// 如果exposedObject等于bean，也就是初始化的时候并没有改变exposedObject的引用
				if (exposedObject == bean) {
					// 那么将earlySingletonReference赋值给exposedObject
					exposedObject = earlySingletonReference;
				}
				// 如果exposedObject不等于bean了，说明在初始化的过程中引用的对象已经改变了
				// 如果此时allowRawInjectionDespiteWrapping(允许放弃包装的生的注入，意思是允许其他bean持有该bean被包装之前的对象，而不是包装对象)为false
				// 并且 该beanName被其他bean依赖了
				else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					// 获取那些依赖该beanName的bean的beanName数组
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
					// 遍历beanName数组
					for (String dependentBean : dependentBeans) {
						// 如果dependentBean存在于alreadyCreated这个集合中，说明不是为了依赖检查创建的，而是实实在在创建的bean
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							// 将其加入到actualDependentBeans这个set中
							actualDependentBeans.add(dependentBean);
						}
					}
					// 如果存在实际依赖这个bean的其他bean，报错。
					// 因为earlySingletonReference不为null，说明存在循环依赖，并且其他bean已经持有了earlySingletonReference这个对象。
					// 但是由于在初始化的过程中，引用发生了改变，那么依赖这个bean的对象持有的earlySingletonReference和当前的exposedObject
					// 完全是两个不同的对象，所以需要抛出异常
					if (!actualDependentBeans.isEmpty()) {
						throw new BeanCurrentlyInCreationException(beanName,
								"Bean with name '" + beanName + "' has been injected into other beans [" +
								StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
								"] in its raw version as part of a circular reference, but has eventually been " +
								"wrapped. This means that said other beans do not use the final version of the " +
								"bean. This is often the result of over-eager type matching - consider using " +
								"'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		// Register bean as disposable.
		try {
			// 根据bean是否实现了DisposableBean接口、是否有DestructionAwareBeanPostProcessor可以应用给bean、以及是否有自定义的destroyMethod，
			// 生成一个DisposableBean的适配器，然后根据beanName注册到DefaultSingletonBeanRegistry中。
			// 调用该适配器的destroy方法的时候，执行顺序是：
			// 1.先调用DestructionAwareBeanPostProcessor的postProcessBeforeDestruction方法
			// 2.如果实现了DisposableBean接口，再调用bean的destroy方法
			// 3.如果存在自定义的destroyMethod方法，再调用自定义的destroyMethod
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}

		// 返回exposedObject
		return exposedObject;
	}

	@Override
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = determineTargetType(beanName, mbd, typesToMatch);
		// Apply SmartInstantiationAwareBeanPostProcessors to predict the
		// eventual type after a before-instantiation shortcut.
		// 如果targetType不为null 并且 mbd不是合成的 并且 beanFactory持有InstantiationAwareBeanPostProcessor的bbp
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			// 判断需要匹配的类型是否只有FactoryBean.class
			boolean matchingOnlyFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
			// 遍历持有的bbp
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				// 如果bbp是SmartInstantiationAwareBeanPostProcessor类型的
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					// 调用它的predictBeanType方法
					Class<?> predicted = ibp.predictBeanType(targetType, beanName);
					// 如果判断出的类型不为null 并且 不是只匹配FactoryBean 或者 判断出的类型就是FactoryBean的实现类
					if (predicted != null &&
							(!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
						// 返回predicted
						return predicted;
					}
				}
			}
		}
		// 否则返回targetType
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 */
	@Nullable
	protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// 获取mbd的targetType
		Class<?> targetType = mbd.getTargetType();
		// 如果targetType为null
		if (targetType == null) {
			// 判断mbd的factoryMethodName是否为null
			targetType = (mbd.getFactoryMethodName() != null ?
					// 如果不为null，根据factoryMethod获取type，因为如果存在factoryMethod的话，生成的bean是根据factoryMethod返回值来的，
					// 而不是根据beanClass来，此时beanDefinition的beanClass只是factoryMethod(比如@Bean标注的方法)的所在类。
					getTypeForFactoryMethod(beanName, mbd, typesToMatch) :
					// 否则根据beanClass解析targetType
					resolveBeanClass(mbd, beanName, typesToMatch));
			// 如果typesToMatch为空或者 TempClassLoader为null的话，
			// 说明不是单纯的进行类型检查，可以将targetType设置进mbd中缓存起来
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				mbd.resolvedTargetType = targetType;
			}
		}
		// 返回targetType
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 *
	 * 找出给定的以factoryMethod为基础的beanDefinition的targetType，只在没有target bean的单例已经注册过的情况下调用
	 *
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 *
	 * 这个实现确定类型匹配的不同创建策略，我们尽可能地只做静态类型检查 避免去创建targetBean
	 *
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see #createBean
	 */
	@Nullable
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// 如果factoryMethodReturnType不为null，返回其resolved字段
		ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
		if (cachedReturnType != null) {
			return cachedReturnType.resolve();
		}

		Class<?> commonType = null;
		// 将factoryMethodToIntrospect赋值给uniqueCandidate，
		// 在ConfigurationClassBeanDefinitionReader解析@Bean方法的时候，只有@Bean方法的methodMetadata是StandardMethodMetadata类型的，
		// 才会设置factoryMethodToIntrospect为@Bean方法的反射对象，SimpleMethodMetadata类型的没有该值，且只会设置factoryMethodName。
		Method uniqueCandidate = mbd.factoryMethodToIntrospect;


		// 1.主要的解析逻辑就是找到对应的factoryClass，然后根据factoryMethodName找到对应的方法，然后解析返回的返回值类型。
		// 如果存在多个候选方法，那么返回它们返回值的共同父类，如果不存在共同父类，返回null

		// 如果uniqueCandidate仍为null，进行解析
		if (uniqueCandidate == null) {
			Class<?> factoryClass;
			boolean isStatic = true;

			// 2.根据factoryBeanName是否存在，来判断factoryMethod是静态还是非静态的，
			// 如果是静态的，factoryClass直接根据beanClassName获取到；
			// 如果是非静态的，那么需要通过getType来获取factoryBeanName对应的bean的类型

			// 先找到对应的factoryBeanName
			String factoryBeanName = mbd.getFactoryBeanName();
			// 如果factoryBeanName不为null的话，说明该factoryMethod是成员方法
			if (factoryBeanName != null) {
				// 如果factoryBeanName和自身的beanName相等的话，报错
				if (factoryBeanName.equals(beanName)) {
					throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
							"factory-bean reference points back to the same bean definition");
				}
				// Check declared factory method return type on factory class.
				// 根据factoryBeanName找到对应的factoryClass
				factoryClass = getType(factoryBeanName);
				// 然后将isStatic置为false，表示factoryMethod一定不是静态方法，因为如果是静态方法，对应的bd的factoryBeanName属性为null
				isStatic = false;
			}
			// 如果factoryBeanName为null的话，说明是静态方法，直接根据mbd的beanClass进行解析
			else {
				// Check declared factory method return type on bean class.
				factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
			}

			// 如果factoryClass还是为null的话，直接返回null
			if (factoryClass == null) {
				return null;
			}
			// 如果factoryClass是被cglib代理过的类的话，那么查找到没被代理的原始类
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// If all factory methods have the same return type, return that type.
			// Can't clearly figure out exact method due to type converting / autowiring!
			// 计算mbd中的ConstructorArgumentValues的个数
			int minNrOfArgs =
					(mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
			// 根据类型获取到用户声明的唯一方法，即不是桥接方法也不是编译器生成的
			Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
					clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

			// 遍历候选方法
			for (Method candidate : candidates) {
				// 如果方法的isStatic属性和之前判断出的一致 并且 候选方法名称和mbd的factoryMethodName一致且标注了@Bean注解并且根据方法解析出来的beanName和mbd的drivedBeanName一致
				// 并且候选方法的参数个数大于mbd中ConstructorArgumentValues的个数
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
						candidate.getParameterCount() >= minNrOfArgs) {
					// Declared type variables to inspect?
					// 如果候选方法上的声明有泛型变量
					if (candidate.getTypeParameters().length > 0) {
						try {
							// Fully resolve parameter names and argument values.
							// 完全解析参数名和变量值
							// 获取方法的所有参数类型
							Class<?>[] paramTypes = candidate.getParameterTypes();
							String[] paramNames = null;
							// 获取参数名发现器，默认是DefaultParameterNameDiscover，
							// 其中持有了StandardReflection和LocalVariableTable两个参数名发现器，
							// 第一个是使用反射，第二个是读取class文件中方法表的Code属性的LocalVariableTable属性获取参数名
							ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
							// 如果参数名发现器不为null，获取到方法的所有参数名
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							// 获取到mbd持有的ConstructorArgumentValues
							ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
							Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
							// 根据方法的参数个数创建一个参数数组
							Object[] args = new Object[paramTypes.length];
							// 根据方法的参数个数进行循环
							for (int i = 0; i < args.length; i++) {
								// 尝试根据参数类型和参数名去取对应的ValueHolder
								ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
										i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
								// 如果没取到，那么不按类型和名称，直接去取genericArgumentValues中的valueHolder
								if (valueHolder == null) {
									valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
								}
								// 如果valueHolder不为null，将其value添加进arg数组中，并且将其自身添加到usedValueHolders中
								if (valueHolder != null) {
									args[i] = valueHolder.getValue();
									usedValueHolders.add(valueHolder);
								}
							}
							// 解析出factoryMethod对应的返回值类型
							Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
									candidate, args, getBeanClassLoader());
							// 如果解析出的返回值类型和方法实际的返回值类型一致，将该方法赋值给uniqueCandidate
							uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
									candidate : null);
							// 找到返回值类型和commonType的共同父类
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
							if (commonType == null) {
								// Ambiguous return types found: return null to indicate "not determinable".
								return null;
							}
						}
						catch (Throwable ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Failed to resolve generic return type for factory method: " + ex);
							}
						}
					}
					// 如果候选方法上没有声明泛型变量
					else {
						// 如果commonType为null的话，将候选方法赋值给uniqueCandidate，表示找到了唯一的候选方法。
						// 如果commonType有值，说明在前面的遍历中就找到了候选方法，这次遍历又找到了，那么将uniqueCandidate置为null，因为candidate方法已经不唯一
						uniqueCandidate = (commonType == null ? candidate : null);
						// 根据方法的返回值类型和commonType一起找到一个共同的祖先类
						commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
						// 如果没有找到共同父类，返回null，因为存在不同返回类型的candidate方法
						if (commonType == null) {
							// Ambiguous return types found: return null to indicate "not determinable".
							return null;
						}
					}
				}
			}

			// 将唯一的候选方法赋值给factoryMethodToIntrospect
			mbd.factoryMethodToIntrospect = uniqueCandidate;
			// 如果commonType为null的话，返回null
			if (commonType == null) {
				return null;
			}
		}

		// Common return type found: all factory methods return same type. For a non-parameterized
		// unique candidate, cache the full type declaration context of the target factory method.
		// 根据是否找到了唯一的候选方法，如果找到了，使用唯一方法的返回值；
		// 如果找到的候选方法不唯一，但是它们的返回值有共同的父类，使用共同父类作为cachedReturnType
		cachedReturnType = (uniqueCandidate != null ?
				ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
		// 将returnType赋值给mbd的factoryMethodReturnType
		mbd.factoryMethodReturnType = cachedReturnType;
		// 然后返回其resolved字段
		return cachedReturnType.resolve();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, and {@code allowInit} is {@code true} a
	 * full creation of the FactoryBean is used as fallback (through delegation to the
	 * superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 *
	 * 1、这个实现企图去查询FactoryBean持有的attribute属性来获取ObjectType，key为factoryBeanObjectType。
	 * 2、如果FactoryBean是通过实例方法的factoryMethod创建的，尝试通过方法的返回值范型拿到对应的objectType，如果没有拿到，且包含这个factoryMethod
	 * 的factoryBean没有被创建，直接返回，因为我们不能为了获取这个FactoryBean的objectType去提前创建另一个bean
	 * 3、如果上述解析没有成功，且没有返回，且allowInit参数为true，
	 * 那么创建出未设置属性值的不完整的FactoryBean对象(如果是FactoryBean是单例，会将未初始化完全的BeanWrapper缓存在factoryBeanInstanceCache里面)，调用其getObjectType方法来获取类型；
	 * 如果仍然没有解析成功，那么创建一个完整的FactoryBean对象作为一个兜底策略，这个会被委托给父类方法去执行
	 * 4、如果allowInit不为true，FactoryBean满足静态方法的factoryMethod创建的，根据静态方法的返回值的范型进行解析
	 * 5、上述都没有解析出来，尝试根据beanType的范型去解析objectType
	 *
	 * 快捷方法检查只会被应用在单例的FactoryBean，如果不是单例的话，每次都需要创建FactoryBean实例
	 */
	@Override
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// Check if the bean definition itself has defined the type with an attribute
		// 1.尝试去mergedBeanDefinition的attribute里根据factoryBeanObjectType获取对应的value返回

		// 尝试从mbd设置的属性中获取属性名为factoryBeanObjectType的属性值
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		// 如果result不为ResolvableType.NONE的话，说明存在属性，直接返回result
		if (result != ResolvableType.NONE) {
			return result;
		}

		// 判断mbd是否存在beanClass，如果存在，解析为ResolvableType，否则，将ResolvableType.NONE赋值给beanType
		ResolvableType beanType =
				(mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

		// For instance supplied beans try the target type and bean class
		// 2.如果instanceSupplier存在，尝试根据mbd的targetType 或 beanType去解析范型获取FactoryBean要创建的bean的类型

		// 如果mbd得到instanceSupplier不为null
		if (mbd.getInstanceSupplier() != null) {
			// 尝试解析mbd的targetType
			result = getFactoryBeanGeneric(mbd.targetType);
			// 如果result的resolved字段不为null，直接返回result
			if (result.resolve() != null) {
				return result;
			}
			// 否则，尝试解析mbd的beanType
			result = getFactoryBeanGeneric(beanType);
			// 如果result的resolved字段不为null，返回result
			if (result.resolve() != null) {
				return result;
			}
		}

		// Consider factory methods
		// 3.考虑FactoryBean是通过factoryMethod来实例化的情况，根据factoryMethod的返回值类型上的范型来解析出实际的beanType

		// 考虑是使用factoryMethod生成的FactoryBean的情况
		String factoryBeanName = mbd.getFactoryBeanName();
		String factoryMethodName = mbd.getFactoryMethodName();

		// Scan the factory bean methods
		// 如果factoryBeanName不为null，说明是成员方法
		if (factoryBeanName != null) {
			// 如果factoryMethodName不为null
			if (factoryMethodName != null) {
				// Try to obtain the FactoryBean's object type from its factory method
				// declaration without instantiating the containing bean at all.
				// 获取到factoryBean的bd
				BeanDefinition factoryBeanDefinition = getBeanDefinition(factoryBeanName);
				Class<?> factoryBeanClass;
				// 如果bd是abstractBeanDefinition类型的 并且 bd存在beanClass
				if (factoryBeanDefinition instanceof AbstractBeanDefinition &&
						((AbstractBeanDefinition) factoryBeanDefinition).hasBeanClass()) {
					// 获取到factoryBean的beanClass
					factoryBeanClass = ((AbstractBeanDefinition) factoryBeanDefinition).getBeanClass();
				} else {
					// 否则获取到factoryBean的mbd
					RootBeanDefinition fbmbd = getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
					// 解析出factoryBean的beanClass
					factoryBeanClass = determineTargetType(factoryBeanName, fbmbd);
				}
				// 如果factoryBean的beanClass不为null
				if (factoryBeanClass != null) {
					// 调用getTypeForFactoryBeanFromMethod来获取到factoryMethod的返回类型FactoryBean中所包含的泛型
					result = getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
					if (result.resolve() != null) {
						return result;
					}
				}
			}
			// If not resolvable above and the referenced factory bean doesn't exist yet,
			// exit here - we don't want to force the creation of another bean just to
			// obtain a FactoryBean's object type...
			// 如果上述的操作没有解析成功 并且 引用的factoryBean不存在
			// 我们不想去强制创建另一个bean仅仅为了去持有一个FactoryBean的object type

			// 该方法判断的就是factoryBeanName是否存在于alreadyCrated集合中，如果不存在，说明还没有进行创建，
			// 直接返回NONE，不会为了获取一个FactoryBean的objectType而去提早创建另一个bean
			if (!isBeanEligibleForMetadataCaching(factoryBeanName)) {
				return ResolvableType.NONE;
			}
		}

		// If we're allowed, we can create the factory bean and call getObjectType() early
		// 4.如果是允许init的，那么创建factoryBean并且调用它的getObjectType方法，
		// 因为上述的步骤都没有返回，所以说明了
		// 4.1 这个FactoryBean不是由其他bean的factoryMethod创建的
		// 4.2 或者 当前这个FactoryBean是由 其他bean的静态factoryMethod创建的
		// 4.3 或者 持有创建它的实例方法factoryMethod的bean已经创建好了
		// 那么对FactoryBean进行创建的时候，就不会额外创建其他bean

		if (allowInit) {
			// 根据mbd是否是singleton的，选择不同的方法创建FactoryBean
			FactoryBean<?> factoryBean = (mbd.isSingleton() ?
					getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
					getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
			// 如果factoryBean不为null
			if (factoryBean != null) {
				// Try to obtain the FactoryBean's object type from this early stage of the instance.
				// 调用factoryBean的getObjectType方法
				Class<?> type = getTypeForFactoryBean(factoryBean);
				// 如果type不为null，转换成resolvableType返回
				if (type != null) {
					return ResolvableType.forClass(type);
				}
				// No type found for shortcut FactoryBean instance:
				// fall back to full creation of the FactoryBean instance.
				// 如果上述步骤没有返回，调用父类的getTypeForFactoryBean方法
				return super.getTypeForFactoryBean(beanName, mbd, true);
			}
		}

		// 5.如果factoryBeanName为null 并且 mbd存在beanClass 并且 factoryMethodName不为null，说明是静态方法返回了FactoryBean
		if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
			// No early bean instantiation possible: determine FactoryBean's type from
			// static factory method signature or from class inheritance hierarchy...
			// 同样调用getTypeForFactoryBeanFromMethod去查找对应方法的返回类型
			return getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
		}
		// 6.如果mbd不是通过factoryMethod的方式来生成bean的，那么直接根据beanType的泛型来获取对应的result
		result = getFactoryBeanGeneric(beanType);
		// 如果result的resolved字段不为null，返回result
		if (result.resolve() != null) {
			return result;
		}
		// 否则返回NONE
		return ResolvableType.NONE;
	}

	private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		// 如果type为null，直接返回ResolvableType.NONE
		if (type == null) {
			return ResolvableType.NONE;
		}
		// 否则将其转换为FactoryBean，并且获取其generic
		return type.as(FactoryBean.class).getGeneric();
	}

	/**
	 * Introspect the factory method signatures on the given bean class,
	 * trying to find a common {@code FactoryBean} object type declared there.
	 * @param beanClass the bean class to find the factory method on
	 * @param factoryMethodName the name of the factory method
	 * @return the common {@code FactoryBean} object type, or {@code null} if none
	 */
	private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
		// CGLIB subclass methods hide generic parameters; look at the original user class.
		// cglib的代理类的方法会隐藏掉泛型参数，因此我们需要查看原始的用户类
		Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
		// 创建一个MethodCallback的子类finder进行查找
		FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
		// 遍历factoryBeanClass中的方法
		ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
		// 返回finder中缓存的result字段
		return finder.getResult();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, a full creation of the FactoryBean is
	 * used as fallback (through delegation to the superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	@Deprecated
	@Nullable
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		return getTypeForFactoryBean(beanName, mbd, true).resolve();
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param bean the raw bean instance
	 * @return the object to expose as bean reference
	 */
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		Object exposedObject = bean;
		// 如果mbd不是合成的 并且 beanFactory中持有InstantiationAwareBeanPostProcessor类型的beanPostProcessor
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			// 遍历持有的bp集合
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				// 如果有bp是属于SmartInstantiationAwareBeanPostProcessor类型的
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					// 调用ibp的getEarlyBeanReference方法，获取暴露出的对象
					exposedObject = ibp.getEarlyBeanReference(exposedObject, beanName);
				}
			}
		}
		return exposedObject;
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			// 尝试从factoryBeanInstanceCache中根据beanName查找FactoryBean的BeanWrapper
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			// 如果缓存存在，直接返回其wrappedInstance
			if (bw != null) {
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			// 否则尝试从一二级缓存中查找bean
			Object beanInstance = getSingleton(beanName, false);
			// 如果bean是属于FactoryBean类型的，直接返回
			if (beanInstance instanceof FactoryBean) {
				return (FactoryBean<?>) beanInstance;
			}
			// 如果beanName对应的单例正在创建 或者 factoryMethod所在的factoryBean正在创建，返回null
			if (isSingletonCurrentlyInCreation(beanName) ||
					(mbd.getFactoryBeanName() != null && isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
				return null;
			}

			Object instance;
			try {
				// Mark this bean as currently in creation, even if just partially.
				// 将beanName添加进正在创建的集合中
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				// 调用bbp的beforeInstantiation方法，如果有实例被创建，调用afterInitialization方法
				instance = resolveBeforeInstantiation(beanName, mbd);
				// 如果instance为null
				if (instance == null) {
					// 调用createBeanInstance创建beanWrapper
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			}
			catch (UnsatisfiedDependencyException ex) {
				// Don't swallow, probably misconfiguration...
				throw ex;
			}
			catch (BeanCreationException ex) {
				// Don't swallow a linkage error since it contains a full stacktrace on
				// first occurrence... and just a plain NoClassDefFoundError afterwards.
				if (ex.contains(LinkageError.class)) {
					throw ex;
				}
				// Instantiation failure, maybe too early...
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
				}
				onSuppressedException(ex);
				return null;
			}
			finally {
				// Finished partial creation of this bean.
				// 将beanName从正在创建的集合中删除
				afterSingletonCreation(beanName);
			}

			// 将instance转型为FactoryBean
			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				// 将bw放入缓存中
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			// 返回FactoryBean
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		// 如果beanName存在于正在创建的原型集合中，返回null
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}

		Object instance;
		try {
			// Mark this bean as currently in creation, even if just partially.
			// 将beanName放入正在创建的原型集合中
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			// 调用bbp的beforeInstantiation方法
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				// 根据mbd创建一个beanWrapper
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		}
		catch (UnsatisfiedDependencyException ex) {
			// Don't swallow, probably misconfiguration...
			throw ex;
		}
		catch (BeanCreationException ex) {
			// Instantiation failure, maybe too early...
			if (logger.isDebugEnabled()) {
				logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
			}
			onSuppressedException(ex);
			return null;
		}
		finally {
			// Finished partial creation of this bean.
			// 将beanName从正在创建的原型集合中删除
			afterPrototypeCreation(beanName);
		}

		// 将instance转型为FactoryBean返回
		return getFactoryBean(beanName, instance);
	}

	/**
	 * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
	 * invoking their {@code postProcessMergedBeanDefinition} methods.
	 * @param mbd the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
		// 遍历所持有的所有BeanPostProcessor
		for (BeanPostProcessor bp : getBeanPostProcessors()) {
			// 如果bp是MergedBeanDefinitionPostProcessor类型的
			// 典型的如CommonAnnotationBeanPostProcessor就是这个类型的，该类继承了InitDestroyAnnotationBeanPostProcessor
			// 还有AutowiredAnnotationBeanPostProcessor也实现了该接口
			if (bp instanceof MergedBeanDefinitionPostProcessor) {
				MergedBeanDefinitionPostProcessor bdp = (MergedBeanDefinitionPostProcessor) bp;
				// 调用其postProcessMergedBeanDefinition方法对mbd进行处理
				bdp.postProcessMergedBeanDefinition(mbd, beanType, beanName);
			}
		}
	}

	/**
	 * Apply before-instantiation post-processors, resolving whether there is a
	 * before-instantiation shortcut for the specified bean.
	 *
	 * 应用beforeInstantiation post processor，解析看这个指定的bean是否存在一个before-instantiation的快捷方式，
	 * 能够直接获取实例对象，而不用创建
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 */
	@Nullable
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		Object bean = null;
		// 如果mbd的beforeInstantiationResolved标志不为false的话，
		// 表示该mbd还没有应用过InstantiationAwareBeanPostProcessor的postProcessBeforeInstantiation方法
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// Make sure bean class is actually resolved at this point.
			// 如果mbd不是合成的 并且 beanFactory中持有InstantiationAwareBeanProcessor类型的后置处理器的话
			if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				// 探明mbd对应的targetType
				Class<?> targetType = determineTargetType(beanName, mbd);
				// 如果查找到的targetType不为null的话
				if (targetType != null) {
					// 应用所有InstantiationAwareBeanPostProcessor的beforeInstantiation方法
					bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
					// 如果创建出了对应的bean，那么调用所有BeanPostProcessor的afterInitialization方法
					if (bean != null) {
						bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
					}
				}
			}
			// 根据是否有获取到shortcutBean来决定是否将mbd的beforeInstantiationResolved置为true
			mbd.beforeInstantiationResolved = (bean != null);
		}
		// 返回bean
		return bean;
	}

	/**
	 * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
	 * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
	 * <p>Any returned object will be used as the bean instead of actually instantiating
	 * the target bean. A {@code null} return value from the post-processor will
	 * result in the target bean being instantiated.
	 * @param beanClass the class of the bean to be instantiated
	 * @param beanName the name of the bean
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	@Nullable
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
		// 遍历beanFactory持有的BeanPostProcessor方法，找到是InstantiationAwareBeanPostProcessor类型的
		for (BeanPostProcessor bp : getBeanPostProcessors()) {
			if (bp instanceof InstantiationAwareBeanPostProcessor) {
				InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
				// 根据beanClass和beanName调用其postProcessBeforeInstantiation方法，如果创建出了对应的bean实例，直接返回
				Object result = ibp.postProcessBeforeInstantiation(beanClass, beanName);
				if (result != null) {
					return result;
				}
			}
		}
		// 否则返回null
		return null;
	}

	/**
	 * Create a new instance for the specified bean, using an appropriate instantiation strategy:
	 * factory method, constructor autowiring, or simple instantiation.
	 *
	 * 为指定的bean创建一个新的实例，使用一种合适的实例化策略：
	 * factoryMethod 或者 构造器注入 或者 简单的实例化
	 *
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a BeanWrapper for the new instance
	 * @see #obtainFromSupplier
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * @see #instantiateBean
	 */
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
		// Make sure bean class is actually resolved at this point.
		// 解析mbd中对应的beanClass
		Class<?> beanClass = resolveBeanClass(mbd, beanName);

		// 如果beanClass不为null 并且 不是public的 且mbd不允许非public的访问，报错
		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}

		// 获取mbd中的实例提供器 InstanceSupplier
		Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
		// TODO 如果不为null，通过实例提供器获取实例并返回
		if (instanceSupplier != null) {
			return obtainFromSupplier(instanceSupplier, beanName);
		}

		// 如果mbd的factoryMethodName不为null，说明是通过@Bean方法声明的
		if (mbd.getFactoryMethodName() != null) {
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		// Shortcut when re-creating the same bean...
		// 当重复创建同样的bean的时候返回一个快捷方式，根据mbd缓存的resolvedConstructorOrFactoryMethod
		// 以及resolvedConstructorArguments

		boolean resolved = false;
		boolean autowireNecessary = false;
		// 如果args为null的话
		if (args == null) {
			// 根据mbd的constructorArgumentLock加锁
			synchronized (mbd.constructorArgumentLock) {
				// 如果mbd中已解析的构造器或者工厂方法不为null
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					// 将已解析的标志置为true
					resolved = true;
					// 并且将autowireNecessary设置为constructorArgumentsResolved的值
					autowireNecessary = mbd.constructorArgumentsResolved;
				}
			}
		}
		// 如果已经解析过
		if (resolved) {
			// 并且自动注入的必要的
			if (autowireNecessary) {
				// 调用autowireConstructor方法进行实例化，
				// 这里不需要传入constructors和explicitArgs的原因是mbd已经存在解析过的resolvedConstructorOrFactoryMethod(Constructor)了，
				// 并且也mbd的constructorArgumentsResolved的标志也为true，说明存在解析过的构造器参数resolvedConstructorArguments，
				// 所以不用传入，直接从mbd去获取即可
				return autowireConstructor(beanName, mbd, null, null);
			}
			else {
				// 否则调用instantiateBean方法进行实例化
				// 这里直接调用实例化策略进行实例化的原因是：mbd中已经存在解析过的构造器resolvedConstructorOrFactoryMethod(Constructor)，
				// 并且不存在解析过的构造器参数resolvedConstructorArguments，那么一定是使用无参构造器进行创建，不会涉及到依赖的bean的注入，
				// 因此直接使用instantiateStrategy进行实例化即可，实例化的时候会自动获取mbd的resolvedConstructorOrFactoryMethod这个构造器的
				return instantiateBean(beanName, mbd);
			}
		}

		// Candidate constructors for autowiring?
		// 如果走到了这一步，说明bean不是由factoryMethod来创建，在之前也没有解析过，mbd中不存在resolvedConstructorOrFactoryMethod，
		// 那么就需要选择使用哪一个构造器来进行实例化了，通过beanFactory持有的SmartInstantiationAwareBeanPostProcessor的determineCandidateConstructor方法来选择。

		// 判断类型中是否有候选的构造器用于自动注入
		// 只有AutowiredAnnotationBeanPostProcessor实现了determineCandidateConstructor方法的逻辑
		// 判断依据是：
		// 1.如果存在标注了autowired相关注解(@Autowired @Inject @Value)，且required属性为true的构造器(@Inject @Value的required默认为true，@Autowired可以通过required属性设置)，
		// 那么不能存在其他标注了这三个注解的构造器了，否则抛出异常。返回的数组中只存在一个元素，就是require为true的那个构造器
		// 2.如果不存在required属性为true的注解构造器，但是存在required为false的构造器，那么返回的数组中会有多个required为false的构造器，且会包含默认的无参构造器
		// 3.如果不存在标注了autowired相关注解的构造器，但是类中只存在一个有参构造器，那么返回数组中只包含该有参构造器
		// 4.如果不存在标注了autowired相关注解的构造器，类中声明了两个构造器，一个是无参默认构造器，一个是kotlin的primary构造器，那么将这两个构造器组成数组返回
		// 5.如果不存在标注了autowired相关注解的构造器，类中声明只声明了一个构造器，就是kotlin的primary构造器，那么将其转换为数组返回
		// 其余情况，都是返回空数组，说明没有找到候选的构造器
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		// 如果存在候选构造器 或者 mbd解析出来的自动注入模式是构造器模式 或者 mbd持有constructorArgumentValues 或者 args不为空，
		// 都调用autowireConstructor方法按构造器实例化

		// 1.因为如果ctors数组不为null的话，说明选择到了符合条件的构造器，所以需要使用autowireConstructor来进行实例化
		// 2.如果mbd的resolvedAutowireMode为constructor的话，说明指定了需要使用构造器的方式来实例化
		// 3.如果mbd存在constructorArgumentValues，即mbd中存在构造器参数的话，更应该使用autowireConstructor来实例化
		// 4.如果显式传入的参数不为空的话，也需要使用构造器来实例化
		// 所以只要满足这四个条件中的任意一个，都应该调用autowireConstructor方法
		if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR ||
				mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		// Preferred constructors for default construction?
		// 如果走到了这一步，说明
		// 1.bean不是由factoryMethod来创建的
		// 2.mbd没有被提前解析过，不存在解析过的构造器和构造器参数
		// 3.没有找到合适的构造器 并且 mbd的resolvedAutowireMode不是constructor 并且 mbd不存在构造器参数 并且 显式传入的参数也为空

		// 如果存在kotlin的primary构造器，那么使用该构造器，并且调用autowireConstructor方法
		ctors = mbd.getPreferredConstructors();
		if (ctors != null) {
			return autowireConstructor(beanName, mbd, ctors, null);
		}

		// No special handling: simply use no-arg constructor.
		// 如果上述条件都不满足的话，使用instantiateStrategy进行实例化，由于mbd不存在resolvedConstructorOrFactoryMethod，
		// 那么实例化策略会默认选择类的 无参构造器 进行实例化。
		return instantiateBean(beanName, mbd);
	}

	/**
	 * Obtain a bean instance from the given supplier.
	 *
	 * 从给定的supplier里获取一个bean实例
	 *
	 * @param instanceSupplier the configured supplier
	 * @param beanName the corresponding bean name
	 * @return a BeanWrapper for the new instance
	 * @since 5.0
	 * @see #getObjectForBeanInstance
	 */
	protected BeanWrapper obtainFromSupplier(Supplier<?> instanceSupplier, String beanName) {
		Object instance;

		String outerBean = this.currentlyCreatedBean.get();
		this.currentlyCreatedBean.set(beanName);
		try {
			instance = instanceSupplier.get();
		}
		finally {
			if (outerBean != null) {
				this.currentlyCreatedBean.set(outerBean);
			}
			else {
				this.currentlyCreatedBean.remove();
			}
		}

		if (instance == null) {
			instance = new NullBean();
		}
		BeanWrapper bw = new BeanWrapperImpl(instance);
		initBeanWrapper(bw);
		return bw;
	}

	/**
	 * Overridden in order to implicitly register the currently created bean as
	 * dependent on further beans getting programmatically retrieved during a
	 * {@link Supplier} callback.
	 * @since 5.0
	 * @see #obtainFromSupplier
	 */
	@Override
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		// 如果currentlyCreatedBean不为null的话
		String currentlyCreatedBean = this.currentlyCreatedBean.get();
		if (currentlyCreatedBean != null) {
			// 说明在创建某一个bean的过程中调用了获取其他bean的逻辑，那么将依赖关系注册进容器中。
			// 说明beanName被currentlyCreatedBean依赖
			registerDependentBean(beanName, currentlyCreatedBean);
		}

		// 然后在调用父类的该方法，执行具体的获取逻辑
		return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	@Nullable
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
			throws BeansException {

		// 如果beanClass不为null并且beanFactory中持有了InstantiationAwareBeanProcessor类型的后置处理器
		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			// 遍历所有的后置处理器
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				// 如果有SmartInstantiationAwareBeanPostProcessor类型的
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					// 调用后置处理器的determineCandidateConstructors方法来查找beanClass中可能的候选构造器，如果找到，直接返回
					Constructor<?>[] ctors = ibp.determineCandidateConstructors(beanClass, beanName);
					if (ctors != null) {
						return ctors;
					}
				}
			}
		}
		// 否则返回null
		return null;
	}

	/**
	 * Instantiate the given bean using its default constructor.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper instantiateBean(String beanName, RootBeanDefinition mbd) {
		try {
			Object beanInstance;
			if (System.getSecurityManager() != null) {
				beanInstance = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(mbd, beanName, this),
						getAccessControlContext());
			}
			else {
				// 调用实例化策略进行实例化，默认的实例化策略是CglibSubclassingInstantiationStrategy
				beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, this);
			}
			// 将实例化后的bean对象包装为一个BeanWrapper
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);
			initBeanWrapper(bw);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 */
	protected BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 *
	 * 自动注入构造器，通过按构造器的参数类型从beanFactory中获取bean作为参数的行为。
	 * 同样也应用于显示的构造器参数被指定，剩余的参数就通过beanFactory来进行bean的匹配。
	 *
	 * 这个对应于构造器注入：在这种模式下，spring bean factory持有作为构造器依赖解析的components
	 *
	 *
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param ctors the chosen candidate constructors
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper autowireConstructor(
			String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {

		// 创建一个ConstructorResolver实例来自动注入构造器
		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}

	/**
	 * Populate the bean instance in the given BeanWrapper with the property values
	 * from the bean definition.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param bw the BeanWrapper with bean instance
	 */
	@SuppressWarnings("deprecation")  // for postProcessPropertyValues
	protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
		// 如果bw为null
		if (bw == null) {
			// mbd中的propertyValues不为null且不为空，报错
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			}
			// 如果mbd中也没有propertyValues，那么直接返回，不用进行属性填充
			else {
				// Skip property population phase for null instance.
				return;
			}
		}

		// Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
		// state of the bean before properties are set. This can be used, for example,
		// to support styles of field injection.
		// 给任一InstantiationAwareBeanPostProcessors一个机会在属性set前修改bean的状态，比如去支持字段注入

		// 如果mbd不是合成的 并且 beanFactory中存在InstantiationAwareBeanPostProcessor类型的bp
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			// 遍历持有的bp集合
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				// 如果bp是InstantiationAwareBeanPostProcessor类型的
				if (bp instanceof InstantiationAwareBeanPostProcessor) {
					InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
					// 调用其postProcessAfterInstantiation方法对bean进行处理，一旦有方法返回了false，该方法直接返回。
					// CommonAnnotationBeanPostProcessor和AutowiredAnnotationBeanPostProcessor的该方法实现都是直接返回true，
					// 没有对bean做任何处理
					if (!ibp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
						return;
					}
				}
			}
		}

		// 获取mbd的propertyValues，如果不存在的话，直接赋值为null
		PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);
		// 获取mbd的解析后的autowireMode
		int resolvedAutowireMode = mbd.getResolvedAutowireMode();
		// 如果是byName或者byType的
		if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
			// 将pvs转换为新的MutablePropertyValues
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
			// Add property values based on autowire by name if applicable.
			// 具体的逻辑是：
			// 1.先获取到bw所包装的bean对应的类中声明的 不是简单类型的 且 不是排除依赖检查的 且 没有包含在mbd的propertyValues中的那些属性；
			// 2.然后根据属性名去容器中查找看是否存在这样的单例，如果存在，通过getBean获取出来按属性名和实际的bean注册到newPvs；
			// 3.将这些属性名对应的bean和beanName之间的依赖关系注册进容器中
			if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
				autowireByName(beanName, mbd, bw, newPvs);
			}
			// Add property values based on autowire by type if applicable.
			// 通过byType的方式将propertyValues添加到bean中
			if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
				autowireByType(beanName, mbd, bw, newPvs);
			}
			// 将newPvs赋值给pvs，newPvs中包含了很多由byName或byType方法新添加进来的属性名称和对应的bean
			pvs = newPvs;
		}

		// 查看beanFactory是否持有InstantiationAwareBeanPostProcessor类型的bp
		boolean hasInstAwareBpps = hasInstantiationAwareBeanPostProcessors();
		// 查看mbd是否需要做依赖检查
		boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);

		PropertyDescriptor[] filteredPds = null;
		// 如果beanFactory中持有InstantiationAwareBeanPostProcessor类型的bp
		if (hasInstAwareBpps) {
			// 如果pvs为null，从mbd中获取
			if (pvs == null) {
				pvs = mbd.getPropertyValues();
			}
			// 遍历持有的BeanPostProcessors
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				// 如果bp是属于InstantiationAwareBeanPostProcessor类型的
				if (bp instanceof InstantiationAwareBeanPostProcessor) {
					InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
					// 调用其postProcessProperties方法，生成一个要使用的PropertyValues对象
					PropertyValues pvsToUse = ibp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
					// 如果返回的要使用的PropertyValues为null的话
					if (pvsToUse == null) {
						// 并且filterPds为null的话
						if (filteredPds == null) {
							// 为了依赖检查过滤PropertyDescriptor
							filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
						}
						// 调用bp的processPropertyValues方法，该方法已经被标记为过时方法
						pvsToUse = ibp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
						// 如果要使用的PropertyValues仍为null，直接返回
						if (pvsToUse == null) {
							return;
						}
					}
					// 将要使用的PropertyValues赋值给pvs
					pvs = pvsToUse;
				}
			}
		}
		// 如果需要进行依赖检查
		if (needsDepCheck) {
			// 并且过滤的PropertyDescriptor为null
			if (filteredPds == null) {
				// 进行依赖检查的PropertyDescriptor的过滤，
				// 将那么被ignoreDependencyTypes包含的 或者 是ignoreDependencyInterfaces中接口要注入的类型的属性都过滤掉
				filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			}
			// 调用checkDependencies对过滤后的pds进行检查
			checkDependencies(beanName, mbd, filteredPds, pvs);
		}

		// 如果pvs不等于null的话，应用PropertyValues，将属性设置进bean中
		if (pvs != null) {
			applyPropertyValues(beanName, mbd, bw, pvs);
		}
	}

	/**
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 * @param beanName the name of the bean we're wiring up.
	 * Useful for debugging messages; not used functionally.
	 * @param mbd bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	protected void autowireByName(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		// 查找到mbd的propertyValues中不包含的 且 不是简单类型 且没有被排除依赖检查的属性名称
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		// 遍历这些属性名称
		for (String propertyName : propertyNames) {
			// 如果容器中是否包含这些属性名称对应的bean
			if (containsBean(propertyName)) {
				// 根据属性名称获取到bean
				Object bean = getBean(propertyName);
				// 将属性名和bean添加到pvs中
				pvs.add(propertyName, bean);
				// 注册propertyName和beanName之间的依赖关系
				registerDependentBean(propertyName, beanName);
				if (logger.isTraceEnabled()) {
					logger.trace("Added autowiring by name from bean name '" + beanName +
							"' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			}
			// 如果不存在对应的bean，打印下日志，不进行任何操作
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName +
							"' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 * @param beanName the name of the bean to autowire by type
	 * @param mbd the merged bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	protected void autowireByType(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		// 获取自定义的TypeConverter
		TypeConverter converter = getCustomTypeConverter();
		// 如果不存在自定义的TypeConverter，使用bw作为TypeConverter
		if (converter == null) {
			converter = bw;
		}

		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		// 查找到mbd的propertyValues中不包含的 且 不是简单类型 且没有被排除依赖检查的属性名称
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		// 遍历这些属性名称
		for (String propertyName : propertyNames) {
			try {
				// 调用beanWrapper的getPropertyDescriptor方法，根据属性名称获取到对应的pd
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is a unsatisfied, non-simple property.
				// 如果pd的属性类型是Object.class的，不进行解析，没有意义。
				if (Object.class != pd.getPropertyType()) {
					// 获取pd中写方法中唯一的方法参数的包装对应MethodParameter
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					// 如果bw的被包装对应实现了PriorityOrdered接口，那么eager为false
					boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
					// 根据methodParameter和eager属性创建一个AutowireByTypeDependencyDescriptor，
					// 该DependencyDescriptor的required为false，并且dependencyName方法返回是null。
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					// 调用beanFactory的resolveDependency方法，获取和写方法参数类型一致的容器中的bean
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					// 如果解析出的依赖对象不为null的话，将其添加进propertyValues中
					if (autowiredArgument != null) {
						pvs.add(propertyName, autowiredArgument);
					}
					// 遍历要自动注入的beanNames集合
					for (String autowiredBeanName : autowiredBeanNames) {
						// 将要自动注入的beanName 和 当前beanName的依赖关系注册进容器中
						registerDependentBean(autowiredBeanName, beanName);
						if (logger.isTraceEnabled()) {
							logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
									propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					// 情况autowiredBeanNames集合，以便循环地对下一个属性进行解析
					autowiredBeanNames.clear();
				}
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
	}


	/**
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 * @param mbd the merged bean definition the bean was created with
	 * @param bw the BeanWrapper the bean was created with
	 * @return an array of bean property names
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 */
	// 查找那些不是简单类型，且mbd中的propertyValues中不包含的属性
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		// 创建一个set用于存储结果
		Set<String> result = new TreeSet<>();
		// 获取mbd中的propertyValues
		PropertyValues pvs = mbd.getPropertyValues();
		// 获取bw所包装的bean对应的CachedIntrospectionResults中持有的PropertyDescriptors
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		// 遍历这些pds
		for (PropertyDescriptor pd : pds) {
			// 如果当前遍历到的pd存在写方法 且 不是被排除的依赖检查的类型 且 mbd的pvs中不包含该属性 且 属性类型不是简单类型
			if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
					!BeanUtils.isSimpleProperty(pd.getPropertyType())) {
				// 如果满足上述条件，将属性的名称加入到结果set中
				result.add(pd.getName());
			}
		}
		// 将结果set转换为数组返回
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		// 尝试从缓存中获取过滤后的PropertyDescriptors
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		// 如果缓存未命中
		if (filtered == null) {
			// 进行PropertyDescriptors的过滤
			filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			// 如果cache标志为true
			if (cache) {
				// 将过滤后的PropertyDescriptors存入缓存
				PropertyDescriptor[] existing =
						this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
				// 如果缓存中已经存在了
				if (existing != null) {
					// 将已经存在的pd数组赋值给filtered，用于返回
					filtered = existing;
				}
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		// 获取beanWrapper持有的CachedIntrospectionResults中的PropertyDescriptors，并将其转换为集合
		List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
		// 遍历pds，如果发现pd是被ignoreDependencyTypes包含 或者 是ignoreDependencyInterfaces中接口要注入的类型的时候，
		// 将其从集合中删除
		pds.removeIf(this::isExcludedFromDependencyCheck);
		// 将集合转换为数组返回
		return pds.toArray(new PropertyDescriptor[0]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
				// 如果pd中的写方法是cglib代理类自己声明的，而不是重写的被代理类的方法，那么返回true，需要被排除
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				// 或者如果被属性对应的类型被ignoredDependencyTypes包含了，也返回true
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				// 或者pd的setter方法的声明类实现了ignoredDependencyInterfaces中持有的接口，
				// 并且setter方法就是重写的该接口中的方法。比如各种Aware接口，BeanFactoryAware、ApplicationContextAware等
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition the bean was created with
	 * @param pds the relevant property descriptors for the target bean
	 * @param pvs the property values to be applied to the bean
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
			throws UnsatisfiedDependencyException {

		// 获取mbd的依赖检查类型
		int dependencyCheck = mbd.getDependencyCheck();
		// 遍历过滤之后的pds
		for (PropertyDescriptor pd : pds) {
			// 如果pd存在写方法 并且 pvs为null或者pvs中不包含该属性
			if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
				// 判断pd对应的类型是否是简单类型
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				// 如果检查类型是all 或者 对应属性是simple类型的并且检查类型是simple 或者 对应属性不是simple类型的并且检查类型是objects，
				// 都会被认定为是不满足条件的
				boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				// 如果该属性是不满足条件的，报错
				if (unsatisfied) {
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}

	/**
	 * Apply the given property values, resolving any runtime references
	 * to other beans in this bean factory. Must use deep copy, so we
	 * don't permanently modify this property.
	 * @param beanName the bean name passed for better exception information
	 * @param mbd the merged bean definition
	 * @param bw the BeanWrapper wrapping the target object
	 * @param pvs the new property values
	 */
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		// 如果PropertyValues对象里面是空的，直接返回
		if (pvs.isEmpty()) {
			return;
		}

		if (System.getSecurityManager() != null && bw instanceof BeanWrapperImpl) {
			((BeanWrapperImpl) bw).setSecurityContext(getAccessControlContext());
		}

		MutablePropertyValues mpvs = null;
		List<PropertyValue> original;

		// 如果pvs是MutablePropertyValues类型的
		if (pvs instanceof MutablePropertyValues) {
			mpvs = (MutablePropertyValues) pvs;
			// 判断mpvs是否已经被转换过
			if (mpvs.isConverted()) {
				// Shortcut: use the pre-converted values as-is.
				try {
					// 如果是的话，直接设置进beanWrapper中
					bw.setPropertyValues(mpvs);
					// 然后返回
					return;
				}
				catch (BeansException ex) {
					throw new BeanCreationException(
							mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			// 如果没有转换过的，获取mpvs中持有的PropertyValueList
			original = mpvs.getPropertyValueList();
		}
		// 如果pvs不是MutablePropertyValues类型的
		else {
			// 获取持有的PropertyValue数组并转换为集合赋值给original
			original = Arrays.asList(pvs.getPropertyValues());
		}

		// 获取beanFactory持有的自定义TypeConverter
		TypeConverter converter = getCustomTypeConverter();
		// 如果不存在，使用bw自身作为TypeConverter
		if (converter == null) {
			converter = bw;
		}
		// 创建一个BeanDefinitionValueResolver
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		// Create a deep copy, resolving any references for values.
		// 创建一个深拷贝集合用于存储最后的结果
		List<PropertyValue> deepCopy = new ArrayList<>(original.size());
		boolean resolveNecessary = false;
		// 遍历PropertyValue集合
		for (PropertyValue pv : original) {
			// 如果对应的PropertyValue已经被转换过，直接添加到深拷贝集合中
			if (pv.isConverted()) {
				deepCopy.add(pv);
			}
			// 否则，进行类型转换
			else {
				// 获取属性名字
				String propertyName = pv.getName();
				// 获取属性的原始值
				Object originalValue = pv.getValue();
				// 如果属性的原始值等于AutowiredPropertyMarker的常量，那么表示该属性需要进行依赖解析，从容器中去对应的bean作为值
				if (originalValue == AutowiredPropertyMarker.INSTANCE) {
					// 获取bw对应属性的PropertyDescriptor，然后获取到写方法
					Method writeMethod = bw.getPropertyDescriptor(propertyName).getWriteMethod();
					// 如果写方法为null，报错
					if (writeMethod == null) {
						throw new IllegalArgumentException("Autowire marker for property without write method: " + pv);
					}
					// 根据写方法的第一个参数构建一个MethodParameter，然后根据methodParameter构建一个DependencyDescriptor，赋值给原始值
					originalValue = new DependencyDescriptor(new MethodParameter(writeMethod, 0), true);
				}
				// 调用valueResolver对PropertyValue进行解析，得到解析后的值
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
				Object convertedValue = resolvedValue;
				// 如果属性名对应的属性是可写入的(判断逻辑：1.不是Indexed类型的属性并且对应的pd有写方法 2.是Indexed类型的属性，能够调用getPropertyValue方法)
				boolean convertible = bw.isWritableProperty(propertyName) &&
						// 并且 属性名不是嵌套属性也不是Indexed类型的属性，即属性名中不包含. 或者 [ 符号。如果满足上面两个条件，该属性是可转换的
						!PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				// 如果是可转换的
				if (convertible) {
					// 将解析后的值进行转换得到转换后的值
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}
				// Possibly store converted value in merged bean definition,
				// in order to avoid re-conversion for every created bean instance.
				// 如果解析后的值等于原始值
				if (resolvedValue == originalValue) {
					// 并且属性是可转化的，将转换后的值存入PropertyValue中
					if (convertible) {
						pv.setConvertedValue(convertedValue);
					}
					// 并且将PropertyValue添加到深拷贝集合中
					deepCopy.add(pv);
				}
				// 如果是可转换的 并且原始值是TypedStringValue类型的 并且 不是dynamic的 并且 转换后的值不是集合或数组
				else if (convertible && originalValue instanceof TypedStringValue &&
						!((TypedStringValue) originalValue).isDynamic() &&
						!(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
					// 将转换后的值存入pv中
					pv.setConvertedValue(convertedValue);
					// 将pv添加到深拷贝集合中
					deepCopy.add(pv);
				}
				// 其他情况
				else {
					// 将resolveNecessary标志置为true
					resolveNecessary = true;
					// 并且创建一个新的PropertyValue，将其value设置为转换后的值，加入深拷贝集合中，这一步是真的深拷贝
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}
		// 如果mpvs不为null 并且 resolveNecessary为false
		if (mpvs != null && !resolveNecessary) {
			// 将mpvs的converted属性设置为true，表示已经被转换过
			mpvs.setConverted();
		}

		// Set our (possibly massaged) deep copy.
		try {
			// 根据深拷贝集合创建一个MutablePropertyValues设置进beanWrapper中，实现属性注入
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Error setting property values", ex);
		}
	}

	/**
	 * Convert the given value for the specified target property.
	 */
	@Nullable
	private Object convertForProperty(
			@Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {

		// 如果converter是BeanWrapperImpl类型的，直接调用自身的convertForProperty方法
		if (converter instanceof BeanWrapperImpl) {
			return ((BeanWrapperImpl) converter).convertForProperty(value, propertyName);
		}
		// 否则，根据propertyName从beanWrapper中获取PropertyDescriptor
		else {
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			// 然后根据pd获取写方法的methodParameter
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			// 调用converter进行类型转换，将pd的属性类型 和 方法参数都传入
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}


	/**
	 * Initialize the given bean instance, applying factory callbacks
	 * as well as init methods and bean post processors.
	 * <p>Called from {@link #createBean} for traditionally defined beans,
	 * and from {@link #initializeBean} for existing bean instances.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @return the initialized bean instance (potentially wrapped)
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				invokeAwareMethods(beanName, bean);
				return null;
			}, getAccessControlContext());
		}
		else {
			// 调用bean实现的Aware相关接口的set方法，
			// 这个地方只会调用实现了BeanNameAware BeanClassLoaderAware BeanFactoryAware这三个接口的set方法。

			// 其他类型的Aware接口的调用在BeanPostProcessor的postProcessBeforeInitialization方法中实现。
			// 在refresh阶段的prepareBeanFactory方法中，向beanFactory中添加了ApplicationContextAwareProcessor这个bbp，
			// 其作用是解析ApplicationContext相关的Aware接口的set方法的注入，该bbp在postProcessBeforeInitialization方法中
			// 解析了EnvironmentAware EmbeddedValueResolverAware ResourceLoaderAware ApplicationEventPublisherAware MessageSourceAware ApplicationContextAware
			// 这几种接口的set方法调用
			invokeAwareMethods(beanName, bean);
		}

		Object wrappedBean = bean;
		// 如果mbd为null 或者 mbd不是合成的
		if (mbd == null || !mbd.isSynthetic()) {
			// 调用持有的BeanPostProcessor集合的postProcessBeforeInitialization方法。
			// 这里会调用许多Aware相关接口的set方法，比如ApplicationContextAwareProcessor ServletContextAwareProcessor ImportAwareBeanPostProcessor
			// 以及会调用InitDestroyAnnotationBeanPostProcessor中缓存的bean对应的类中的init方法，即标注了@PostConstruct注解的那些方法
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}

		try {
			// 调用bean的初始化方法，
			// 即调用实现了InitializingBean接口的bean的afterPropertiesSet方法，
			// 以及mbd中声明的initMethodName的自定义初始化方法
			invokeInitMethods(beanName, wrappedBean, mbd);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null),
					beanName, "Invocation of init method failed", ex);
		}
		// 如果mbd为null 或者 mbd不是合成的
		if (mbd == null || !mbd.isSynthetic()) {
			// 调用持有的BeanPostProcessor集合的postProcessAfterInitialization方法
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}

		// 返回bean
		return wrappedBean;
	}

	private void invokeAwareMethods(String beanName, Object bean) {
		// 如果bean实现了Aware接口
		if (bean instanceof Aware) {
			// 如果bean实现了BeanNameAware接口
			if (bean instanceof BeanNameAware) {
				// 调用setBeanName方法将beanName设置进去
				((BeanNameAware) bean).setBeanName(beanName);
			}
			// 如果bean实现了BeanClassLoaderAware接口
			if (bean instanceof BeanClassLoaderAware) {
				// 将beanClassLoader设置进bean中
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
				}
			}
			// 如果bean实现了BeanFactoryAware接口
			if (bean instanceof BeanFactoryAware) {
				// 将beanFactory设置进bean中
				((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * Give a bean a chance to react now all its properties are set,
	 * and a chance to know about its owning bean factory (this object).
	 * This means checking whether the bean implements InitializingBean or defines
	 * a custom init method, and invoking the necessary callback(s) if it does.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the merged bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @throws Throwable if thrown by init methods or by the invocation process
	 * @see #invokeCustomInitMethod
	 */
	protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
			throws Throwable {

		// 判断bean是否实现了InitializingBean接口
		boolean isInitializingBean = (bean instanceof InitializingBean);
		// 如果bean实现了该接口 并且 mbd为null 或者 mbd的外部管理的初始化方法集合
		// (在postProcessMergedBeanDefinition的时候会通过CommonAnnotationBeanPostProcessor解析@PostConstruct注解方法，并放入到该集合中)
		// 中不包含afterPropertiesSet这个方法
		if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}
			if (System.getSecurityManager() != null) {
				try {
					AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
						((InitializingBean) bean).afterPropertiesSet();
						return null;
					}, getAccessControlContext());
				}
				catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			}
			else {
				// 调用bean的afterPropertiesSet方法，进行初始化
				((InitializingBean) bean).afterPropertiesSet();
			}
		}

		// 如果mbd不为null 并且 bean的类型不是NullBean的
		if (mbd != null && bean.getClass() != NullBean.class) {
			// 查看mbd中的initMethodName属性
			String initMethodName = mbd.getInitMethodName();
			// 如果initMethodName有值 且 (bean没有实现InitializingBean接口 或者 initMethodName不等于afterPropertiesSet)
			// 并且initMethodName不在外部管理的初始化方法集合中
			if (StringUtils.hasLength(initMethodName) &&
					!(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
					!mbd.isExternallyManagedInitMethod(initMethodName)) {
				// 那么会调用自定义的初始化方法
				invokeCustomInitMethod(beanName, bean, mbd);
			}
		}
	}

	/**
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, Object bean, RootBeanDefinition mbd)
			throws Throwable {

		String initMethodName = mbd.getInitMethodName();
		Assert.state(initMethodName != null, "No init method set");
		// 根据mbd的nonPublicAccessAllowed属性来决定使用哪种方式获取方法的反射对象
		Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));

		// 如果initMethod为null
		if (initMethod == null) {
			// 如果mbd的enforceInitMethod是true的话，报错
			if (mbd.isEnforceInitMethod()) {
				throw new BeanDefinitionValidationException("Could not find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			}
			// 否则打印日志，返回
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				return;
			}
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}
		// 查找initMethod对应的声明在接口中的方法
		Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod);

		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				ReflectionUtils.makeAccessible(methodToInvoke);
				return null;
			});
			try {
				AccessController.doPrivileged((PrivilegedExceptionAction<Object>)
						() -> methodToInvoke.invoke(bean), getAccessControlContext());
			}
			catch (PrivilegedActionException pae) {
				InvocationTargetException ex = (InvocationTargetException) pae.getException();
				throw ex.getTargetException();
			}
		}
		else {
			try {
				// 反射调用初始化方法
				ReflectionUtils.makeAccessible(methodToInvoke);
				methodToInvoke.invoke(bean);
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}


	/**
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanInstanceCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanInstanceCache.clear();
		}
	}

	/**
	 * Expose the logger to collaborating delegates.
	 * @since 5.0.7
	 */
	Log getLogger() {
		return logger;
	}


	/**
	 * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		public String getDependencyName() {
			return null;
		}
	}


	/**
	 * {@link MethodCallback} used to find {@link FactoryBean} type information.
	 */
	private static class FactoryBeanMethodTypeFinder implements MethodCallback {

		private final String factoryMethodName;

		private ResolvableType result = ResolvableType.NONE;

		FactoryBeanMethodTypeFinder(String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
		}

		@Override
		public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
			// 如果方法名称和要查找的factoryMethodName一致，并且返回类型是FactoryBean
			if (isFactoryBeanMethod(method)) {
				// 根据方法的返回类型创建一个ResolvableType
				ResolvableType returnType = ResolvableType.forMethodReturnType(method);
				// 然后将其转换为FactoryBean并获取其泛型
				ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
				// 如果自身缓存的result为NONE的话，将candidate赋值
				if (this.result == ResolvableType.NONE) {
					this.result = candidate;
				}
				// 如果自身的result已经有值
				else {
					// 那么比较二者的resolved字段，选取其共同祖先类
					Class<?> resolvedResult = this.result.resolve();
					Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
					// 如果共同祖先类不等于result的类，那么将result赋值为commonAncestor的resolvableType
					if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
						this.result = ResolvableType.forClass(commonAncestor);
					}
				}
			}
		}

		private boolean isFactoryBeanMethod(Method method) {
			return (method.getName().equals(this.factoryMethodName) &&
					FactoryBean.class.isAssignableFrom(method.getReturnType()));
		}

		ResolvableType getResult() {
			// 获取result的resolved字段
			Class<?> resolved = this.result.resolve();
			// 如果resolved字段不为null且不是Object.class
			boolean foundResult = resolved != null && resolved != Object.class;
			// 返回result，否则返回NONE
			return (foundResult ? this.result : ResolvableType.NONE);
		}
	}

}
