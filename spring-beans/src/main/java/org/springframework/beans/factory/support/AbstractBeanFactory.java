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

import java.beans.PropertyEditor;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.PropertyEditorRegistrySupport;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanIsAbstractException;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.Scope;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.DecoratingClassLoader;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.log.LogMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Abstract base class for {@link org.springframework.beans.factory.BeanFactory}
 * implementations, providing the full capabilities of the
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory} SPI.
 * Does <i>not</i> assume a listable bean factory: can therefore also be used
 * as base class for bean factory implementations which obtain bean definitions
 * from some backend resource (where bean definition access is an expensive operation).
 *
 * BeanFactory实现的抽象基类，提供了ConfigurableBeanFactory接口的全部能力。
 * 不假设一个listable的beanFactory，因此也被用作从某些后端资源持有beanDefinitions的beanFactory的实现基类，
 * 这些资源访问beanDefinition是一个昂贵的操作
 *
 * <p>This class provides a singleton cache (through its base class
 * {@link org.springframework.beans.factory.support.DefaultSingletonBeanRegistry},
 * singleton/prototype determination, {@link org.springframework.beans.factory.FactoryBean}
 * handling, aliases, bean definition merging for child bean definitions,
 * and bean destruction ({@link org.springframework.beans.factory.DisposableBean}
 * interface, custom destroy methods). Furthermore, it can manage a bean factory
 * hierarchy (delegating to the parent in case of an unknown bean), through implementing
 * the {@link org.springframework.beans.factory.HierarchicalBeanFactory} interface.
 *
 * 这个类提供了一个单例缓存，通过它继承的DefaultSingletonBeanRegistry，
 * 单例/原型的测定，FactoryBean的处理，别名，beanDefinition的合并以及bean的销毁（DisposableBean接口，自定义的destroy方法。
 * 此外，它可以管理一个beanFactory的层次结构，通过实现HierarchicalBeanFactory将不认识的bean委托给父工厂
 *
 * <p>The main template methods to be implemented by subclasses are
 * {@link #getBeanDefinition} and {@link #createBean}, retrieving a bean definition
 * for a given bean name and creating a bean instance for a given bean definition,
 * respectively. Default implementations of those operations can be found in
 * {@link DefaultListableBeanFactory} and {@link AbstractAutowireCapableBeanFactory}.
 * 主要的需要子类去实现的模板方法是getBeanDefinition 和 createBean，通过给的beanName去查找一个BeanDefinition
 * 以及通过给出的beanDefinition去创建一个bean，这些操作的默认实现在DefaultListableBeanFactory和AbstractAutowireCapableBeanFactory能够被找到
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @since 15 April 2001
 * @see #getBeanDefinition
 * @see #createBean
 * @see AbstractAutowireCapableBeanFactory#createBean
 * @see DefaultListableBeanFactory#getBeanDefinition
 */
public abstract class AbstractBeanFactory extends FactoryBeanRegistrySupport implements ConfigurableBeanFactory {

	/** Parent bean factory, for bean inheritance support. */
	@Nullable
	private BeanFactory parentBeanFactory;

	/** ClassLoader to resolve bean class names with, if necessary. */
	@Nullable
	// 在refresh的prepareBeanFactory方法中会添加一个classloader进来作为beanClassLoader
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	/** ClassLoader to temporarily resolve bean class names with, if necessary. */
	// 用于临时解析beanClassName的类加载器
	@Nullable
	private ClassLoader tempClassLoader;

	/** Whether to cache bean metadata or rather reobtain it for every access. */
	private boolean cacheBeanMetadata = true;

	/** Resolution strategy for expressions in bean definition values. */
	// beanDefinition的values中的表达式的解析策略
	@Nullable
	// 在refresh的prepareBeanFactory方法中会添加一个StandardBeanExpressionResolver进来，并且该类初始化的时候需要传入一个classLoader，
	// 传入的就是beanFactory持有的beanClassLoader
	private BeanExpressionResolver beanExpressionResolver;

	/** Spring ConversionService to use instead of PropertyEditors. */
	// 替代propertyEditor的ConversionService
	@Nullable
	private ConversionService conversionService;

	/** Custom PropertyEditorRegistrars to apply to the beans of this factory. */
	// 在refresh阶段的prepareBeanFactory中会添加一个ResourceEditorRegistrar到集合中
	private final Set<PropertyEditorRegistrar> propertyEditorRegistrars = new LinkedHashSet<>(4);

	/** Custom PropertyEditors to apply to the beans of this factory. */
	private final Map<Class<?>, Class<? extends PropertyEditor>> customEditors = new HashMap<>(4);

	/** A custom TypeConverter to use, overriding the default PropertyEditor mechanism. */
	// 一个自定义的TypeConverter，重写了默认的propertyEditor机制
	@Nullable
	private TypeConverter typeConverter;

	/** String resolvers to apply e.g. to annotation attribute values. */
	// 在BeanFactoryPostProcessor中会添加元素进来。
	// 如果没有在该阶段添加的话，那么在refresh的阶段的finishBeanFactoryInitialization方法中会
	// 添加一个strVal -> getEnvironment().resolvePlaceHolder(strVal)的lambda表达式进集合中
	private final List<StringValueResolver> embeddedValueResolvers = new CopyOnWriteArrayList<>();

	/** BeanPostProcessors to apply. */
	// 所有的bean后置处理器都维护在这里
	private final List<BeanPostProcessor> beanPostProcessors = new CopyOnWriteArrayList<>();

	/** Indicates whether any InstantiationAwareBeanPostProcessors have been registered. */
	// 显示是否有InstantiationAwareBeanPostProcessors类型的processor被注册进来
	private volatile boolean hasInstantiationAwareBeanPostProcessors;

	/** Indicates whether any DestructionAwareBeanPostProcessors have been registered. */
	// 指示是否有DestructionAwareBeanPostProcessors类型的processor被注册进来
	private volatile boolean hasDestructionAwareBeanPostProcessors;

	/** Map from scope identifier String to corresponding Scope. */
	// scope标识符 映射 对应的Scope的map
	private final Map<String, Scope> scopes = new LinkedHashMap<>(8);

	/** Security context used when running with a SecurityManager. */
	@Nullable
	private SecurityContextProvider securityContextProvider;

	/** Map from bean name to merged RootBeanDefinition. */
	// baneName 映射 合并后的RootBeanDefinition的map
	private final Map<String, RootBeanDefinition> mergedBeanDefinitions = new ConcurrentHashMap<>(256);

	/** Names of beans that have already been created at least once. */
	// 那些已经被创建过的bean的beanName会被保存在这里
	private final Set<String> alreadyCreated = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	/** Names of beans that are currently in creation. */
	// 那么正在被创建的bean的beanName会被保存在这里，这个ThreadLocal只针对原型类型的
	private final ThreadLocal<Object> prototypesCurrentlyInCreation =
			new NamedThreadLocal<>("Prototype beans currently in creation");


	/**
	 * Create a new AbstractBeanFactory.
	 */
	public AbstractBeanFactory() {
	}

	/**
	 * Create a new AbstractBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 * @see #getBean
	 */
	public AbstractBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this.parentBeanFactory = parentBeanFactory;
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public Object getBean(String name) throws BeansException {
		return doGetBean(name, null, null, false);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		return doGetBean(name, requiredType, null, false);
	}

	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		return doGetBean(name, null, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * @param name the name of the bean to retrieve
	 * @param requiredType the required type of the bean to retrieve
	 * @param args arguments to use when creating a bean instance using explicit arguments
	 * (only applied when creating a new instance as opposed to retrieving an existing one)
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	public <T> T getBean(String name, @Nullable Class<T> requiredType, @Nullable Object... args)
			throws BeansException {

		return doGetBean(name, requiredType, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * @param name the name of the bean to retrieve
	 * @param requiredType the required type of the bean to retrieve
	 * @param args arguments to use when creating a bean instance using explicit arguments
	 * (only applied when creating a new instance as opposed to retrieving an existing one)
	 * @param typeCheckOnly whether the instance is obtained for a type check,
	 * not for actual use
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	@SuppressWarnings("unchecked")
	protected <T> T doGetBean(
			String name, @Nullable Class<T> requiredType, @Nullable Object[] args, boolean typeCheckOnly)
			throws BeansException {

		// 将name前的&符号去掉，并且将别名映射为真实的beanName
		String beanName = transformedBeanName(name);
		Object bean;

		// Eagerly check singleton cache for manually registered singletons.
		// 尝试从三级缓存中根据beanName获取bean
		Object sharedInstance = getSingleton(beanName);
		// 如果获取到的bean不为null 并且 传入的参数args为null
		if (sharedInstance != null && args == null) {
			if (logger.isTraceEnabled()) {
				// 如果当前bean正在创建中，打印对应的日志。说明获取到了提前暴露的未初始化完全的对象，循环依赖的时候会出现
				if (isSingletonCurrentlyInCreation(beanName)) {
					logger.trace("Returning eagerly cached instance of singleton bean '" + beanName +
							"' that is not fully initialized yet - a consequence of a circular reference");
				}
				else {
					logger.trace("Returning cached instance of singleton bean '" + beanName + "'");
				}
			}
			// 根据beanInstance去获取对应的bean
			bean = getObjectForBeanInstance(sharedInstance, name, beanName, null);
		}

		else {
			// Fail if we're already creating this bean instance:
			// We're assumably within a circular reference.
			// 如果beanName正处于创建中，且是prototype类型的，报错
			if (isPrototypeCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(beanName);
			}

			// Check if bean definition exists in this factory.
			// 获取父容器
			BeanFactory parentBeanFactory = getParentBeanFactory();
			// 如果父容器不为null 并且 自身并不存在beanName对应的BeanDefinition，尝试去父容器中查找
			if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
				// Not found -> check parent.
				// 如果name是别名，将其映射为真实的beanName，并且保留&符号
				String nameToLookup = originalBeanName(name);
				// 如果父容器是AbstractBeanFactory类型的，调用父容器的doGetBean查找
				if (parentBeanFactory instanceof AbstractBeanFactory) {
					return ((AbstractBeanFactory) parentBeanFactory).doGetBean(
							nameToLookup, requiredType, args, typeCheckOnly);
				}
				// 如果args不为null，调用父容器的getBean(beanName, args)方法
				else if (args != null) {
					// Delegation to parent with explicit args.
					return (T) parentBeanFactory.getBean(nameToLookup, args);
				}
				// 如果requiredType不为null，调用父容器的getBean(beanName, requireType)方法
				else if (requiredType != null) {
					// No args -> delegate to standard getBean method.
					return parentBeanFactory.getBean(nameToLookup, requiredType);
				}
				else {
					// 否则调用父容器的getBean(beanName)方法
					return (T) parentBeanFactory.getBean(nameToLookup);
				}
			}

			// 如果typeCheckOnly为false的话，表示不是仅仅做类型检查，而是真正的创建这个bean
			if (!typeCheckOnly) {
				// 将这个beanName标记为已经创建过的，即将beanName添加进alreadyCreated集合中，
				// 并且将mergedBeanDefinitions中该beanName的缓存置为过期状态
				markBeanAsCreated(beanName);
			}

			try {
				// 根据beanName获取合并的RootBeanDefinition
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				// 检查MergedBeanDefinition是不是抽象的，如果是的话，报错
				checkMergedBeanDefinition(mbd, beanName, args);

				// Guarantee initialization of beans that the current bean depends on.
				// 获取mbd依赖的所有bean的beanName，确保依赖的bean已经被初始化了
				String[] dependsOn = mbd.getDependsOn();
				// 如果依赖的beanName不为null的话
				if (dependsOn != null) {
					// 遍历这些beanName
					for (String dep : dependsOn) {
						// 判断是否存在循环依赖的关系，如果是，报错
						if (isDependent(beanName, dep)) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"Circular depends-on relationship between '" + beanName + "' and '" + dep + "'");
						}
						// 将依赖关系注册进dependentBeanMap和dependenciesForBeanMap中
						registerDependentBean(dep, beanName);
						try {
							// 然后根据dep，即依赖的beanName，调用getBean方法，保证对应的bean已经初始化
							getBean(dep);
						}
						catch (NoSuchBeanDefinitionException ex) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"'" + beanName + "' depends on missing bean '" + dep + "'", ex);
						}
					}
				}

				// Create bean instance.
				// 如果mbd是singleton类型的
				if (mbd.isSingleton()) {
					// 调用getSingleton方法，传入beanName和一个ObjectFactory的lambda表达式，并将返回值赋值给sharedInstance
					sharedInstance = getSingleton(beanName, () -> {
						try {
							return createBean(beanName, mbd, args);
						}
						catch (BeansException ex) {
							// Explicitly remove instance from singleton cache: It might have been put there
							// eagerly by the creation process, to allow for circular reference resolution.
							// Also remove any beans that received a temporary reference to the bean.
							// 如果创建时发生了异常，调用destroySingleton方法清除beanName相关的数据
							destroySingleton(beanName);
							throw ex;
						}
					});
					// 然后根据sharedInstance获取最终的bean
					bean = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
				}
				// 如果mbd是prototype类型的
				else if (mbd.isPrototype()) {
					// It's a prototype -> create a new instance.
					Object prototypeInstance = null;
					try {
						// 调用beforePrototypeCreation方法，将beanName放入到prototypesCurrentlyInCreation集合中，
						// 表示正在创建的prototype类型的bean
						beforePrototypeCreation(beanName);
						// 然后调用createBean，每次都创建一个bean实例
						prototypeInstance = createBean(beanName, mbd, args);
					}
					finally {
						// 调用afterPrototypeCreation方法，将beanName从prototypesCurrentlyInCreation集合里面删除
						afterPrototypeCreation(beanName);
					}
					// 然后根据创建出来的prototype实例，获取对应的prototypeBean
					bean = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
				}
				// 如果mbd的scope是其他值，即自定义的scope
				else {
					// 获取scope的name
					String scopeName = mbd.getScope();
					// 如果scopeName为空，报错
					if (!StringUtils.hasLength(scopeName)) {
						throw new IllegalStateException("No scope name defined for bean '" + beanName + "'");
					}
					// 根据scopeName获取到AbstractBeanFactory里面保存的Scope对象
					Scope scope = this.scopes.get(scopeName);
					// 如果scope对象为null，报错
					if (scope == null) {
						throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
					}
					try {
						// 调用scope的get方法，并且传入一个ObjectFactory，其逻辑仍然是按照prototype的方式，每次创建一个bean实例。
						// 得到scopedInstance
						Object scopedInstance = scope.get(beanName, () -> {
							beforePrototypeCreation(beanName);
							try {
								return createBean(beanName, mbd, args);
							}
							finally {
								afterPrototypeCreation(beanName);
							}
						});
						// 然后再根据这个scopedInstance，获取到对应的scopedBean
						bean = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
					}
					catch (IllegalStateException ex) {
						throw new BeanCreationException(beanName,
								"Scope '" + scopeName + "' is not active for the current thread; consider " +
								"defining a scoped proxy for this bean if you intend to refer to it from a singleton",
								ex);
					}
				}
			}
			catch (BeansException ex) {
				// 调用cleanupAfterBeanCreationFailure方法作bean创建失败后的清理操作
				// 具体逻辑就是将beanName从alreadyCreated集合中删除
				cleanupAfterBeanCreationFailure(beanName);
				throw ex;
			}
		}

		// Check if required type matches the type of the actual bean instance.
		// 如果requiredType不为null 并且 bean不是属于requiredType类型的
		if (requiredType != null && !requiredType.isInstance(bean)) {
			try {
				// 获取typeConverter进行类型转换
				T convertedBean = getTypeConverter().convertIfNecessary(bean, requiredType);
				// 如果转换之后的bean为null，报错
				if (convertedBean == null) {
					throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
				}
				// 否则返回转换之后的bean
				return convertedBean;
			}
			catch (TypeMismatchException ex) {
				if (logger.isTraceEnabled()) {
					logger.trace("Failed to convert bean '" + name + "' to required type '" +
							ClassUtils.getQualifiedName(requiredType) + "'", ex);
				}
				throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
			}
		}
		// 直接返回bean
		return (T) bean;
	}

	@Override
	public boolean containsBean(String name) {
		// 将name进行转换为beanName
		String beanName = transformedBeanName(name);
		// 如果单例map中存在beanName或者beanDefinitionMap中存在beanName
		if (containsSingleton(beanName) || containsBeanDefinition(beanName)) {
			// 如果name不是以&开头的 或者 name对应的bean是FactoryBean，返回true，表示容器中包含这个bean
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(name));
		}
		// Not found -> check parent.
		// 如果没有找到的话，去父容器中查找
		BeanFactory parentBeanFactory = getParentBeanFactory();
		return (parentBeanFactory != null && parentBeanFactory.containsBean(originalBeanName(name)));
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		// 转换name，去掉&符号，将别名映射成真实的beanName
		String beanName = transformedBeanName(name);

		// 尝试从singletonObjects和earlySingletonObjects中获取bean
		Object beanInstance = getSingleton(beanName, false);
		// 如果bean存在
		if (beanInstance != null) {
			// 如果是FactoryBean
			if (beanInstance instanceof FactoryBean) {
				// 如果name是以&开头的，说明要判断的就是FactoryBean本身是否是单例，其存在于一二级缓存中，那么肯定是单例，返回true；
				// 获取name不是以&开头，那么要判断的是FactoryBean生成的bean是否是单例，那么调用FactoryBean的isSingleton方法将结果返回。
				return (BeanFactoryUtils.isFactoryDereference(name) || ((FactoryBean<?>) beanInstance).isSingleton());
			} else {
				// 如果bean不是FactoryBean，那么如果name不是以&开头的话，直接返回true，如果以&开头，出现错误，直接返回false
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}

		// No singleton instance found -> check bean definition.
		// 如果没有从一二级缓存中找到对应的bean，检查父容器
		BeanFactory parentBeanFactory = getParentBeanFactory();
		// 如果parentBeanFactory存在，且自身不包含beanName对应的bd
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			// 调用parentBeanFactory的isSingleton方法，将name从别名映射为真实的beanName，但是&符号保留
			return parentBeanFactory.isSingleton(originalBeanName(name));
		}

		// 根据beanName获取到对应的MergedBeanDefinition
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// In case of FactoryBean, return singleton status of created object if not a dereference.
		// 如果mbd的scope是singleton的
		if (mbd.isSingleton()) {
			// 如果mbd是FactoryBean
			if (isFactoryBean(beanName, mbd)) {
				// 如果name是以&开头的，说明是判断FactoryBean本身是否是单例，直接返回true
				if (BeanFactoryUtils.isFactoryDereference(name)) {
					return true;
				}
				// 如果不是以&开头的，说明是判断FactoryBean生成的bean是否是单例。
				// 尝试根据&开头的beanName获取FactoryBean的bean实例
				FactoryBean<?> factoryBean = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
				// 然后调用FactoryBean的isSingleton方法
				return factoryBean.isSingleton();
			}
			// 如果mbd不是FactoryBean
			else {
				// 那么如果name不为&开头，直接返回true，如果以&开头，出现错误，返回false
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}
		// 如果mbd的scope都不是singleton的，直接返回false
		else {
			return false;
		}
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isPrototype(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isPrototype()) {
			// In case of FactoryBean, return singleton status of created object if not a dereference.
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName, mbd));
		}

		// Singleton or scoped - not a prototype.
		// However, FactoryBean may still produce a prototype object...
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			return false;
		}
		if (isFactoryBean(beanName, mbd)) {
			FactoryBean<?> fb = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(
						(PrivilegedAction<Boolean>) () ->
								((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
										!fb.isSingleton()),
						getAccessControlContext());
			}
			else {
				return ((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
						!fb.isSingleton());
			}
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, typeToMatch, true);
	}

	/**
	 * Internal extended variant of {@link #isTypeMatch(String, ResolvableType)}
	 * to check whether the bean with the given name matches the specified type. Allow
	 * additional constraints to be applied to ensure that beans are not created early.
	 * @param name the name of the bean to query
	 * @param typeToMatch the type to match against (as a
	 * {@code ResolvableType})
	 * @return {@code true} if the bean type matches, {@code false} if it
	 * doesn't match or cannot be determined yet
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @since 5.2
	 * @see #getBean
	 * @see #getType
	 */
	protected boolean isTypeMatch(String name, ResolvableType typeToMatch, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		// 将name前面的&符号去掉，并且将别面转换为真实的beanName
		String beanName = transformedBeanName(name);
		// 判断name前是否有&符号
		boolean isFactoryDereference = BeanFactoryUtils.isFactoryDereference(name);

		// Check manually registered singletons.
		// 尝试从一二级缓存中获取beanName对应的bean
		Object beanInstance = getSingleton(beanName, false);
		// 如果bean存在，且bean的类对象不是NullBean
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			// 如果bean是FactoryBean类型的
			if (beanInstance instanceof FactoryBean) {
				// 并且name不是以&开头的，说明是为了判断FactoryBean创建的bean是否匹配类型
				if (!isFactoryDereference) {
					// 调用FactoryBean的getObjectType方法返回要创建的bean的类型
					Class<?> type = getTypeForFactoryBean((FactoryBean<?>) beanInstance);
					// 如果type不为null 并且 要匹配的类型是type的父类或接口，返回true
					return (type != null && typeToMatch.isAssignableFrom(type));
				}
				// 如果name是以&开头的，说明是为了判断FactoryBean本身是否匹配类型
				else {
					// 直接返回bean是否是要匹配类型的实例
					return typeToMatch.isInstance(beanInstance);
				}
			}
			// 如果bean不是FactoryBean类型的且name中不以&开头
			else if (!isFactoryDereference) {
				// 如果bean是要匹配类型的实例，直接返回true
				if (typeToMatch.isInstance(beanInstance)) {
					// Direct match for exposed instance?
					return true;
				}
				// 如果要匹配的类型存在泛型 并且 存在beanName对应的bd
				else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
					// Generics potentially only match on the target class, not on the proxy...
					// 根据beanName获取mergedBeanDefinition
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// 获取mbd的targetType
					Class<?> targetType = mbd.getTargetType();
					// 如果targetType不为null 并且 targetType不等于bean的被代理的类型
					if (targetType != null && targetType != ClassUtils.getUserClass(beanInstance)) {
						// Check raw class match as well, making sure it's exposed on the proxy.
						Class<?> classToMatch = typeToMatch.resolve();
						if (classToMatch != null && !classToMatch.isInstance(beanInstance)) {
							return false;
						}
						if (typeToMatch.isAssignableFrom(targetType)) {
							return true;
						}
					}
					ResolvableType resolvableType = mbd.targetType;
					if (resolvableType == null) {
						resolvableType = mbd.factoryMethodReturnType;
					}
					return (resolvableType != null && typeToMatch.isAssignableFrom(resolvableType));
				}
			}
			return false;
		}
		// 如果bean是NullBean类型的 并且 不存在beanName对应的bd，直接返回false
		else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// null instance registered
			return false;
		}

		// No singleton instance found -> check bean definition.
		// 如果不存在对应的bean，且也不包含beanName对应的bd，尝试去父parentBeanFactory中匹配
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isTypeMatch(originalBeanName(name), typeToMatch);
		}

		// Retrieve corresponding bean definition.
		// 获取beanName对应的mbd
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		// 获取mbd包装的dbd
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();

		// Setup the types that we want to match against
		// 获取到typeToMatch的rawClass
		Class<?> classToMatch = typeToMatch.resolve();
		// 如果classToMatch为null的话，赋值为FactoryBean.class
		if (classToMatch == null) {
			classToMatch = FactoryBean.class;
		}
		// 获取到要匹配的类型数组
		Class<?>[] typesToMatch = (FactoryBean.class == classToMatch ?
				new Class<?>[] {classToMatch} : new Class<?>[] {FactoryBean.class, classToMatch});


		// Attempt to predict the bean type
		Class<?> predictedType = null;

		// We're looking for a regular reference but we're a factory bean that has
		// a decorated bean definition. The target bean should be the same type
		// as FactoryBean would ultimately return.
		if (!isFactoryDereference && dbd != null && isFactoryBean(beanName, mbd)) {
			// We should only attempt if the user explicitly set lazy-init to true
			// and we know the merged bean definition is for a factory bean.
			if (!mbd.isLazyInit() || allowFactoryBeanInit) {
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				Class<?> targetType = predictBeanType(dbd.getBeanName(), tbd, typesToMatch);
				if (targetType != null && !FactoryBean.class.isAssignableFrom(targetType)) {
					predictedType = targetType;
				}
			}
		}

		// If we couldn't use the target type, try regular prediction.
		if (predictedType == null) {
			predictedType = predictBeanType(beanName, mbd, typesToMatch);
			if (predictedType == null) {
				return false;
			}
		}

		// Attempt to get the actual ResolvableType for the bean.
		ResolvableType beanType = null;

		// If it's a FactoryBean, we want to look at what it creates, not the factory class.
		if (FactoryBean.class.isAssignableFrom(predictedType)) {
			if (beanInstance == null && !isFactoryDereference) {
				beanType = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit);
				predictedType = beanType.resolve();
				if (predictedType == null) {
					return false;
				}
			}
		}
		else if (isFactoryDereference) {
			// Special case: A SmartInstantiationAwareBeanPostProcessor returned a non-FactoryBean
			// type but we nevertheless are being asked to dereference a FactoryBean...
			// Let's check the original bean class and proceed with it if it is a FactoryBean.
			predictedType = predictBeanType(beanName, mbd, FactoryBean.class);
			if (predictedType == null || !FactoryBean.class.isAssignableFrom(predictedType)) {
				return false;
			}
		}

		// We don't have an exact type but if bean definition target type or the factory
		// method return type matches the predicted type then we can use that.
		if (beanType == null) {
			ResolvableType definedType = mbd.targetType;
			if (definedType == null) {
				definedType = mbd.factoryMethodReturnType;
			}
			if (definedType != null && definedType.resolve() == predictedType) {
				beanType = definedType;
			}
		}

		// If we have a bean type use it so that generics are considered
		if (beanType != null) {
			return typeToMatch.isAssignableFrom(beanType);
		}

		// If we don't have a bean type, fallback to the predicted type
		return typeToMatch.isAssignableFrom(predictedType);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, ResolvableType.forRawClass(typeToMatch));
	}

	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		return getType(name, true);
	}

	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		// 将name前面的&去掉，并且将别名映射为真实的beanName
		String beanName = transformedBeanName(name);

		// Check manually registered singletons.
		// 1.判断name是否有已经实例化好的单例，如果存在，根据单例返回类型。
		// 如果是FactoryBean类型的，FactoryBeanRegistrySupport类的getTypeForFactoryBean方法，实际调用的是FactoryBean对象的getObjectType方法

		// 从一二级缓存中根据beanName获取bean
		Object beanInstance = getSingleton(beanName, false);
		// 如果bean存在，且不是NullBean类型的
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			// 如果bean是FactoryBean类型的 且 name不以&开头，说明是获取FactoryBean生成的bean的类型。
			if (beanInstance instanceof FactoryBean && !BeanFactoryUtils.isFactoryDereference(name)) {
				// 调用getTypeForFactoryBean，具体是调用FactoryBean的getObjectType方法
				return getTypeForFactoryBean((FactoryBean<?>) beanInstance);
			} else {
				// 如果bean不是FactoryBean类型的 或者 bean是FactoryBean类型但是name前面有&，直接获取bean的类对象返回
				return beanInstance.getClass();
			}
		}

		// No singleton instance found -> check bean definition.
		// 2.如果在当前beanFactory中没有找到对应的beanDefinition，尝试去父beanFactory查找

		// 如果自身容器不存在beanName对应的bd，那么从parentBeanFactory中查找
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.getType(originalBeanName(name));
		}

		// 根据beanName获取对应的mbd
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// Check decorated bean definition, if any: We assume it'll be easier
		// to determine the decorated bean's type than the proxy's type.
		// 3.如果被装饰的beanDefinition存在，尝试使用decorated的beanDefinition来查找

		// 获取mbd的dbd，我们假设从dbd中获取beanType要比从代理对象上获取更容易
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
		// 如果dbd不为null 并且 name没有以&开头
		if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
			// 根据dbd的beanName 和 bd获取对应的mergedBeanDefinition
			RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
			// 根据beanName和tbd推断beanType
			Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd);
			// 如果推断出的类型不为null 并且 不是FactoryBean类型的，直接返回推断出的类型
			if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
				return targetClass;
			}
		}

		// 4.调用predictBeanType方法，根据beanName和mergedBeanDefinition获取到实际的bean类型。
		// 实际调用的逻辑就是determineTargetType方法，不过会使用SmartInstantiationAwareBeanPostProcessor的predictBeanType
		// 来对determineTargetType的返回结果进行增强

		// 根据beanName和mbd推断beanType
		Class<?> beanClass = predictBeanType(beanName, mbd);

		// Check bean class whether we're dealing with a FactoryBean.
		// 5.根据predictBeanType返回的类型是否是FactoryBean的，选择不同的策略进行解析。
		// 如果是FactoryBean类型的，那么调用getTypeForFactoryBean方法来获取到实际的bean类型

		// 如果beanClass不为null 并且是FactoryBean类型的
		if (beanClass != null && FactoryBean.class.isAssignableFrom(beanClass)) {
			// 如果name不是以&开头的，说明是要返回FactoryBean创建的bean的类型
			if (!BeanFactoryUtils.isFactoryDereference(name)) {
				// If it's a FactoryBean, we want to look at what it creates, not at the factory class.
				// 调用getTypeForFactoryBean根据beanName mbd获取到resolvableType，然后返回其resolved字段
				return getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit).resolve();
			}
			// 如果name是以&开头的，说明是返回FactoryBean本身，那么直接返回beanClass
			else {
				return beanClass;
			}
		}
		// 如果beanClass不是FactoryBean类型的
		else {
			// 如果name不以&开头，直接返回beanClass；否则，出现错误，返回null
			return (!BeanFactoryUtils.isFactoryDereference(name) ? beanClass : null);
		}
	}

	@Override
	public String[] getAliases(String name) {
		// 将name转换为真实的beanName，且将前面的&符号去掉
		String beanName = transformedBeanName(name);
		// 创建一个list来收集该beanName对应的别名
		List<String> aliases = new ArrayList<>();
		// 判断传入的name是否是以&开头的
		boolean factoryPrefix = name.startsWith(FACTORY_BEAN_PREFIX);
		// 如果是以&开头的，将真实的beanName前面也加上&
		String fullBeanName = beanName;
		if (factoryPrefix) {
			fullBeanName = FACTORY_BEAN_PREFIX + beanName;
		}
		// 判断fullBeanName和传入的name是否一致，如果不一致的话，将fullBeanName添加到别名集合中
		if (!fullBeanName.equals(name)) {
			aliases.add(fullBeanName);
		}
		// 调用父类的getAliases方法，获取真实的beanName对应的别名数组
		String[] retrievedAliases = super.getAliases(beanName);
		// 如果factoryPrefix为true，那么前缀为&
		String prefix = factoryPrefix ? FACTORY_BEAN_PREFIX : "";
		// 遍历检索到的别名
		for (String retrievedAlias : retrievedAliases) {
			// 在其前面都加上前缀
			String alias = prefix + retrievedAlias;
			// 如果发现别名和传入的name不相等，将其加入别名集合
			if (!alias.equals(name)) {
				aliases.add(alias);
			}
		}
		// 如果发现beanName不存在于一级缓存和beanDefinitionMap中
		if (!containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// 尝试从父容器中查找别名
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null) {
				aliases.addAll(Arrays.asList(parentBeanFactory.getAliases(fullBeanName)));
			}
		}
		// 将别名集合转换为数组返回
		return StringUtils.toStringArray(aliases);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return this.parentBeanFactory;
	}

	@Override
	public boolean containsLocalBean(String name) {
		String beanName = transformedBeanName(name);
		return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
				(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void setParentBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		if (this.parentBeanFactory != null && this.parentBeanFactory != parentBeanFactory) {
			throw new IllegalStateException("Already associated with parent BeanFactory: " + this.parentBeanFactory);
		}
		if (this == parentBeanFactory) {
			throw new IllegalStateException("Cannot set parent bean factory to self");
		}
		this.parentBeanFactory = parentBeanFactory;
	}

	@Override
	public void setBeanClassLoader(@Nullable ClassLoader beanClassLoader) {
		this.beanClassLoader = (beanClassLoader != null ? beanClassLoader : ClassUtils.getDefaultClassLoader());
	}

	@Override
	@Nullable
	public ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setTempClassLoader(@Nullable ClassLoader tempClassLoader) {
		this.tempClassLoader = tempClassLoader;
	}

	@Override
	@Nullable
	public ClassLoader getTempClassLoader() {
		return this.tempClassLoader;
	}

	@Override
	public void setCacheBeanMetadata(boolean cacheBeanMetadata) {
		this.cacheBeanMetadata = cacheBeanMetadata;
	}

	@Override
	public boolean isCacheBeanMetadata() {
		return this.cacheBeanMetadata;
	}

	@Override
	public void setBeanExpressionResolver(@Nullable BeanExpressionResolver resolver) {
		this.beanExpressionResolver = resolver;
	}

	@Override
	@Nullable
	public BeanExpressionResolver getBeanExpressionResolver() {
		return this.beanExpressionResolver;
	}

	@Override
	public void setConversionService(@Nullable ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	@Override
	@Nullable
	public ConversionService getConversionService() {
		return this.conversionService;
	}

	@Override
	public void addPropertyEditorRegistrar(PropertyEditorRegistrar registrar) {
		Assert.notNull(registrar, "PropertyEditorRegistrar must not be null");
		this.propertyEditorRegistrars.add(registrar);
	}

	/**
	 * Return the set of PropertyEditorRegistrars.
	 */
	public Set<PropertyEditorRegistrar> getPropertyEditorRegistrars() {
		return this.propertyEditorRegistrars;
	}

	@Override
	public void registerCustomEditor(Class<?> requiredType, Class<? extends PropertyEditor> propertyEditorClass) {
		Assert.notNull(requiredType, "Required type must not be null");
		Assert.notNull(propertyEditorClass, "PropertyEditor class must not be null");
		this.customEditors.put(requiredType, propertyEditorClass);
	}

	@Override
	public void copyRegisteredEditorsTo(PropertyEditorRegistry registry) {
		registerCustomEditors(registry);
	}

	/**
	 * Return the map of custom editors, with Classes as keys and PropertyEditor classes as values.
	 */
	public Map<Class<?>, Class<? extends PropertyEditor>> getCustomEditors() {
		return this.customEditors;
	}

	@Override
	public void setTypeConverter(TypeConverter typeConverter) {
		this.typeConverter = typeConverter;
	}

	/**
	 * Return the custom TypeConverter to use, if any.
	 * @return the custom TypeConverter, or {@code null} if none specified
	 */
	@Nullable
	protected TypeConverter getCustomTypeConverter() {
		return this.typeConverter;
	}

	@Override
	public TypeConverter getTypeConverter() {
		TypeConverter customConverter = getCustomTypeConverter();
		if (customConverter != null) {
			return customConverter;
		}
		else {
			// Build default TypeConverter, registering custom editors.
			SimpleTypeConverter typeConverter = new SimpleTypeConverter();
			typeConverter.setConversionService(getConversionService());
			registerCustomEditors(typeConverter);
			return typeConverter;
		}
	}

	@Override
	public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		this.embeddedValueResolvers.add(valueResolver);
	}

	@Override
	public boolean hasEmbeddedValueResolver() {
		return !this.embeddedValueResolvers.isEmpty();
	}

	@Override
	@Nullable
	public String resolveEmbeddedValue(@Nullable String value) {
		// 如果value为null，直接返回null
		if (value == null) {
			return null;
		}
		String result = value;
		// 如果没有通过beanFactoryPostProcessor添加embeddedValueResolver到beanFactory中的话，
		// 那么在applicationContext refresh的时候会默认添加 strVal -> getEnvironment().resolvePlaceHolder(strVal) 到embeddedValueResolvers中
		for (StringValueResolver resolver : this.embeddedValueResolvers) {
			result = resolver.resolveStringValue(result);
			// 如果result解析出来的结果为null，返回null
			if (result == null) {
				return null;
			}
		}
		// 遍历完成后将result返回
		return result;
	}

	@Override
	public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
		Assert.notNull(beanPostProcessor, "BeanPostProcessor must not be null");
		// Remove from old position, if any
		this.beanPostProcessors.remove(beanPostProcessor);
		// Track whether it is instantiation/destruction aware
		// 如果有bean后置处理器是InstantiationAwareBeanPostProcessor类型的，
		// 将beanFactory的hasInstantiationAwareBeanPostProcessors标志置为true
		if (beanPostProcessor instanceof InstantiationAwareBeanPostProcessor) {
			this.hasInstantiationAwareBeanPostProcessors = true;
		}
		// 如果有bean后置处理器是DestructionAwareBeanPostProcessor类型的，
		// 将beanFactory的hasDestructionAwareBeanPostProcessors标志置为true
		if (beanPostProcessor instanceof DestructionAwareBeanPostProcessor) {
			this.hasDestructionAwareBeanPostProcessors = true;
		}
		// Add to end of list
		this.beanPostProcessors.add(beanPostProcessor);
	}

	@Override
	public int getBeanPostProcessorCount() {
		return this.beanPostProcessors.size();
	}

	/**
	 * Return the list of BeanPostProcessors that will get applied
	 * to beans created with this factory.
	 */
	public List<BeanPostProcessor> getBeanPostProcessors() {
		return this.beanPostProcessors;
	}

	/**
	 * Return whether this factory holds a InstantiationAwareBeanPostProcessor
	 * that will get applied to singleton beans on creation.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
	 */
	protected boolean hasInstantiationAwareBeanPostProcessors() {
		return this.hasInstantiationAwareBeanPostProcessors;
	}

	/**
	 * Return whether this factory holds a DestructionAwareBeanPostProcessor
	 * that will get applied to singleton beans on shutdown.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean hasDestructionAwareBeanPostProcessors() {
		return this.hasDestructionAwareBeanPostProcessors;
	}

	@Override
	public void registerScope(String scopeName, Scope scope) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		Assert.notNull(scope, "Scope must not be null");
		if (SCOPE_SINGLETON.equals(scopeName) || SCOPE_PROTOTYPE.equals(scopeName)) {
			throw new IllegalArgumentException("Cannot replace existing scopes 'singleton' and 'prototype'");
		}
		Scope previous = this.scopes.put(scopeName, scope);
		if (previous != null && previous != scope) {
			if (logger.isDebugEnabled()) {
				logger.debug("Replacing scope '" + scopeName + "' from [" + previous + "] to [" + scope + "]");
			}
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Registering scope '" + scopeName + "' with implementation [" + scope + "]");
			}
		}
	}

	@Override
	public String[] getRegisteredScopeNames() {
		return StringUtils.toStringArray(this.scopes.keySet());
	}

	@Override
	@Nullable
	public Scope getRegisteredScope(String scopeName) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		return this.scopes.get(scopeName);
	}

	/**
	 * Set the security context provider for this bean factory. If a security manager
	 * is set, interaction with the user code will be executed using the privileged
	 * of the provided security context.
	 */
	public void setSecurityContextProvider(SecurityContextProvider securityProvider) {
		this.securityContextProvider = securityProvider;
	}

	/**
	 * Delegate the creation of the access control context to the
	 * {@link #setSecurityContextProvider SecurityContextProvider}.
	 */
	@Override
	public AccessControlContext getAccessControlContext() {
		return (this.securityContextProvider != null ?
				this.securityContextProvider.getAccessControlContext() :
				AccessController.getContext());
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		Assert.notNull(otherFactory, "BeanFactory must not be null");
		setBeanClassLoader(otherFactory.getBeanClassLoader());
		setCacheBeanMetadata(otherFactory.isCacheBeanMetadata());
		setBeanExpressionResolver(otherFactory.getBeanExpressionResolver());
		setConversionService(otherFactory.getConversionService());
		if (otherFactory instanceof AbstractBeanFactory) {
			AbstractBeanFactory otherAbstractFactory = (AbstractBeanFactory) otherFactory;
			this.propertyEditorRegistrars.addAll(otherAbstractFactory.propertyEditorRegistrars);
			this.customEditors.putAll(otherAbstractFactory.customEditors);
			this.typeConverter = otherAbstractFactory.typeConverter;
			this.beanPostProcessors.addAll(otherAbstractFactory.beanPostProcessors);
			this.hasInstantiationAwareBeanPostProcessors = this.hasInstantiationAwareBeanPostProcessors ||
					otherAbstractFactory.hasInstantiationAwareBeanPostProcessors;
			this.hasDestructionAwareBeanPostProcessors = this.hasDestructionAwareBeanPostProcessors ||
					otherAbstractFactory.hasDestructionAwareBeanPostProcessors;
			this.scopes.putAll(otherAbstractFactory.scopes);
			this.securityContextProvider = otherAbstractFactory.securityContextProvider;
		}
		else {
			setTypeConverter(otherFactory.getTypeConverter());
			String[] otherScopeNames = otherFactory.getRegisteredScopeNames();
			for (String scopeName : otherScopeNames) {
				this.scopes.put(scopeName, otherFactory.getRegisteredScope(scopeName));
			}
		}
	}

	/**
	 * Return a 'merged' BeanDefinition for the given bean name,
	 * merging a child bean definition with its parent if necessary.
	 * <p>This {@code getMergedBeanDefinition} considers bean definition
	 * in ancestors as well.
	 * @param name the name of the bean to retrieve the merged definition for
	 * (may be an alias)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	@Override
	public BeanDefinition getMergedBeanDefinition(String name) throws BeansException {
		String beanName = transformedBeanName(name);
		// Efficiently check whether bean definition exists in this factory.
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			return ((ConfigurableBeanFactory) getParentBeanFactory()).getMergedBeanDefinition(beanName);
		}
		// Resolve merged bean definition locally.
		return getMergedLocalBeanDefinition(beanName);
	}

	@Override
	public boolean isFactoryBean(String name) throws NoSuchBeanDefinitionException {
		// 转换beanName，将&去掉，且将别名映射为真实beanName
		String beanName = transformedBeanName(name);
		// 尝试获取singleton
		Object beanInstance = getSingleton(beanName, false);
		// 如果不为null，判断是否是FactoryBean类型的
		if (beanInstance != null) {
			// 如果是，返回true
			return (beanInstance instanceof FactoryBean);
		}
		// No singleton instance found -> check bean definition.
		// 如果没有singleton找到，那么检查beanDefinition，如果自身不包含这样的bd，且存在父ioc，尝试去父工厂查找
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			return ((ConfigurableBeanFactory) getParentBeanFactory()).isFactoryBean(name);
		}
		// 否则，获取当前工厂内的beanName对应的mbd，判断是否是factoryBean
		return isFactoryBean(beanName, getMergedLocalBeanDefinition(beanName));
	}

	@Override
	public boolean isActuallyInCreation(String beanName) {
		return (isSingletonCurrentlyInCreation(beanName) || isPrototypeCurrentlyInCreation(beanName));
	}

	/**
	 * Return whether the specified prototype bean is currently in creation
	 * (within the current thread).
	 * @param beanName the name of the bean
	 */
	protected boolean isPrototypeCurrentlyInCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		return (curVal != null &&
				(curVal.equals(beanName) || (curVal instanceof Set && ((Set<?>) curVal).contains(beanName))));
	}

	/**
	 * Callback before prototype creation.
	 * <p>The default implementation register the prototype as currently in creation.
	 * @param beanName the name of the prototype about to be created
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void beforePrototypeCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		if (curVal == null) {
			this.prototypesCurrentlyInCreation.set(beanName);
		}
		else if (curVal instanceof String) {
			Set<String> beanNameSet = new HashSet<>(2);
			beanNameSet.add((String) curVal);
			beanNameSet.add(beanName);
			this.prototypesCurrentlyInCreation.set(beanNameSet);
		}
		else {
			Set<String> beanNameSet = (Set<String>) curVal;
			beanNameSet.add(beanName);
		}
	}

	/**
	 * Callback after prototype creation.
	 * <p>The default implementation marks the prototype as not in creation anymore.
	 * @param beanName the name of the prototype that has been created
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void afterPrototypeCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		if (curVal instanceof String) {
			this.prototypesCurrentlyInCreation.remove();
		}
		else if (curVal instanceof Set) {
			Set<String> beanNameSet = (Set<String>) curVal;
			beanNameSet.remove(beanName);
			if (beanNameSet.isEmpty()) {
				this.prototypesCurrentlyInCreation.remove();
			}
		}
	}

	@Override
	public void destroyBean(String beanName, Object beanInstance) {
		destroyBean(beanName, beanInstance, getMergedLocalBeanDefinition(beanName));
	}

	/**
	 * Destroy the given bean instance (usually a prototype instance
	 * obtained from this factory) according to the given bean definition.
	 * @param beanName the name of the bean definition
	 * @param bean the bean instance to destroy
	 * @param mbd the merged bean definition
	 */
	protected void destroyBean(String beanName, Object bean, RootBeanDefinition mbd) {
		new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), getAccessControlContext()).destroy();
	}

	@Override
	public void destroyScopedBean(String beanName) {
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isSingleton() || mbd.isPrototype()) {
			throw new IllegalArgumentException(
					"Bean name '" + beanName + "' does not correspond to an object in a mutable scope");
		}
		String scopeName = mbd.getScope();
		Scope scope = this.scopes.get(scopeName);
		if (scope == null) {
			throw new IllegalStateException("No Scope SPI registered for scope name '" + scopeName + "'");
		}
		Object bean = scope.remove(beanName);
		if (bean != null) {
			destroyBean(beanName, bean, mbd);
		}
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Return the bean name, stripping out the factory dereference prefix if necessary,
	 * and resolving aliases to canonical names.
	 * @param name the user-specified name
	 * @return the transformed bean name
	 */
	protected String transformedBeanName(String name) {
		// 将name前&符号去掉，并且从aliasMap中将别名转换为实际的name
		return canonicalName(BeanFactoryUtils.transformedBeanName(name));
	}

	/**
	 * Determine the original bean name, resolving locally defined aliases to canonical names.
	 * @param name the user-specified name
	 * @return the original bean name
	 */
	protected String originalBeanName(String name) {
		// 将name去除&，以及将别名映射为真实beanName
		String beanName = transformedBeanName(name);
		// 如果原本的名称是以&开头的
		if (name.startsWith(FACTORY_BEAN_PREFIX)) {
			// 那么在beanName前加上&
			beanName = FACTORY_BEAN_PREFIX + beanName;
		}
		// 返回
		return beanName;
	}

	/**
	 * Initialize the given BeanWrapper with the custom editors registered
	 * with this factory. To be called for BeanWrappers that will create
	 * and populate bean instances.
	 * <p>The default implementation delegates to {@link #registerCustomEditors}.
	 * Can be overridden in subclasses.
	 * @param bw the BeanWrapper to initialize
	 */
	protected void initBeanWrapper(BeanWrapper bw) {
		// 将自身持有的ConversionService设置进去
		bw.setConversionService(getConversionService());
		// 注册自定义的propertyEditor到beanWrapper中
		registerCustomEditors(bw);
	}

	/**
	 * Initialize the given PropertyEditorRegistry with the custom editors
	 * that have been registered with this BeanFactory.
	 * <p>To be called for BeanWrappers that will create and populate bean
	 * instances, and for SimpleTypeConverter used for constructor argument
	 * and factory method type conversion.
	 * @param registry the PropertyEditorRegistry to initialize
	 */
	protected void registerCustomEditors(PropertyEditorRegistry registry) {
		// 判断propertyEditorRegistry是否是PropertyEditorRegistrySupport类型的
		PropertyEditorRegistrySupport registrySupport =
				(registry instanceof PropertyEditorRegistrySupport ? (PropertyEditorRegistrySupport) registry : null);
		// 如果是，将其configValueEditorsActive属性设置为true
		if (registrySupport != null) {
			registrySupport.useConfigValueEditors();
		}
		// 如果自身持有的propertyEditorRegistrars集合不是空的，那么遍历该集合内的容器
		if (!this.propertyEditorRegistrars.isEmpty()) {
			for (PropertyEditorRegistrar registrar : this.propertyEditorRegistrars) {
				try {
					// 调用每一个registrar的registerCustomEditors方法将propertyEditor注册进registry中
					registrar.registerCustomEditors(registry);
				}
				catch (BeanCreationException ex) {
					Throwable rootCause = ex.getMostSpecificCause();
					if (rootCause instanceof BeanCurrentlyInCreationException) {
						BeanCreationException bce = (BeanCreationException) rootCause;
						String bceBeanName = bce.getBeanName();
						if (bceBeanName != null && isCurrentlyInCreation(bceBeanName)) {
							if (logger.isDebugEnabled()) {
								logger.debug("PropertyEditorRegistrar [" + registrar.getClass().getName() +
										"] failed because it tried to obtain currently created bean '" +
										ex.getBeanName() + "': " + ex.getMessage());
							}
							onSuppressedException(ex);
							continue;
						}
					}
					throw ex;
				}
			}
		}
		// 如果自身持有的customerEditors集合不为空
		if (!this.customEditors.isEmpty()) {
			// 遍历customerEditors，将每一个propertyEditor注册进registry中
			this.customEditors.forEach((requiredType, editorClass) ->
					registry.registerCustomEditor(requiredType, BeanUtils.instantiateClass(editorClass)));
		}
	}


	/**
	 * Return a merged RootBeanDefinition, traversing the parent bean definition
	 * if the specified bean corresponds to a child bean definition.
	 * @param beanName the name of the bean to retrieve the merged definition for
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) throws BeansException {
		// Quick check on the concurrent map first, with minimal locking.
		// 从mergedBeanDefinitions这个map中根据beanName快速查询是否存在mergedBeanDefinition
		RootBeanDefinition mbd = this.mergedBeanDefinitions.get(beanName);
		// 如果存在，且未过时，直接返回
		if (mbd != null && !mbd.stale) {
			return mbd;
		}
		// 否则根据原始的BeanDefinition获取一个mergedBeanDefinition返回
		return getMergedBeanDefinition(beanName, getBeanDefinition(beanName));
	}

	/**
	 * Return a RootBeanDefinition for the given top-level bean, by merging with
	 * the parent if the given bean's definition is a child bean definition.
	 *
	 * 为给出的这个top-level的bean返回一个RootBeanDefinition，通过合并它的父beanDefinition，如果给出的beanDefinition是一个子beanDefinition的话。
	 * top-level的含义就是没有被其他bean包含的bean
	 *
	 * @param beanName the name of the bean definition
	 * @param bd the original bean definition (Root/ChildBeanDefinition)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition bd)
			throws BeanDefinitionStoreException {

		return getMergedBeanDefinition(beanName, bd, null);
	}

	/**
	 * Return a RootBeanDefinition for the given bean, by merging with the
	 * parent if the given bean's definition is a child bean definition.
	 * @param beanName the name of the bean definition
	 * @param bd the original bean definition (Root/ChildBeanDefinition)
	 * @param containingBd the containing bean definition in case of inner bean,
	 * or {@code null} in case of a top-level bean
	 *  包含给出的bean的beanDefinition，但如果给出的bean是一个top-level的bean的话，containingBd就为null
	 *
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedBeanDefinition(
			String beanName, BeanDefinition bd, @Nullable BeanDefinition containingBd)
			throws BeanDefinitionStoreException {

		synchronized (this.mergedBeanDefinitions) {
			RootBeanDefinition mbd = null;
			RootBeanDefinition previous = null;

			// Check with full lock now in order to enforce the same merged instance.
			if (containingBd == null) {
				// 再次从缓存中查找，这样做的原因是强制beanName对应的mbd都是同一个
				mbd = this.mergedBeanDefinitions.get(beanName);
			}

			// 如果mbd为null或者已经过时了，进行创建
			if (mbd == null || mbd.stale) {
				// 将以前的mbd赋值给previous
				previous = mbd;
				// 如果发现原始的bd没有parent
				if (bd.getParentName() == null) {
					// Use copy of given root bean definition.
					// 那么原始的bd是RootBeanDefinition类型的，调用clone方法
					if (bd instanceof RootBeanDefinition) {
						mbd = ((RootBeanDefinition) bd).cloneBeanDefinition();
					}
					// 否则将原始bd传入RootBeanDefinition的构造函数，生成一个RootBeanDefinition
					else {
						mbd = new RootBeanDefinition(bd);
					}
				}
				// 如果原始bd存在parent
				else {
					// Child bean definition: needs to be merged with parent.
					BeanDefinition pbd;
					try {
						// 将其转换为beanName，去掉前面的&符合，如果是别名，解析出别名对应的真实beanName
						String parentBeanName = transformedBeanName(bd.getParentName());
						// 如果parentBeanName和beanName不相等的话
						if (!beanName.equals(parentBeanName)) {
							// 递归调用getMergedBeanDefinition首先获取到parentBeanDefinition的mdb(MergedBeanDefinition)
							pbd = getMergedBeanDefinition(parentBeanName);
						}
						// 如果名称相等，那么尝试从父bean工厂中获取对应的parentBeanDefinition的mbd
						else {
							BeanFactory parent = getParentBeanFactory();
							if (parent instanceof ConfigurableBeanFactory) {
								pbd = ((ConfigurableBeanFactory) parent).getMergedBeanDefinition(parentBeanName);
							}
							else {
								throw new NoSuchBeanDefinitionException(parentBeanName,
										"Parent name '" + parentBeanName + "' is equal to bean name '" + beanName +
										"': cannot be resolved without a ConfigurableBeanFactory parent");
							}
						}
					}
					catch (NoSuchBeanDefinitionException ex) {
						throw new BeanDefinitionStoreException(bd.getResourceDescription(), beanName,
								"Could not resolve parent bean definition '" + bd.getParentName() + "'", ex);
					}
					// Deep copy with overridden values.
					// 对pbd进行深拷贝，初始化一个RootBeanDefinition作为mbd
					mbd = new RootBeanDefinition(pbd);
					// 然后用原始bd去覆盖mbd，实现子bd对父bd属性等元素的覆盖
					mbd.overrideFrom(bd);
				}

				// Set default singleton scope, if not configured before.
				// 如果mbd不存在scope，设置默认的singleton
				if (!StringUtils.hasLength(mbd.getScope())) {
					mbd.setScope(SCOPE_SINGLETON);
				}

				// A bean contained in a non-singleton bean cannot be a singleton itself.
				// Let's correct this on the fly here, since this might be the result of
				// parent-child merging for the outer bean, in which case the original inner bean
				// definition will not have inherited the merged outer bean's singleton status.
				// 如果包含这个bean的bean不是单例，那么它自身也不能声明为单例，因此我们需要将它的scope设置为包含它的这个bean的scope
				if (containingBd != null && !containingBd.isSingleton() && mbd.isSingleton()) {
					mbd.setScope(containingBd.getScope());
				}

				// Cache the merged bean definition for the time being
				// (it might still get re-merged later on in order to pick up metadata changes)
				// 如果不存在包含它的bd的话 且 cacheBeanMetadata标志为true，将这个mbd放入到缓存中，
				// 如果它的原数据有变动的话，它将会重新进行merge
				if (containingBd == null && isCacheBeanMetadata()) {
					this.mergedBeanDefinitions.put(beanName, mbd);
				}
			}
			// 如果之前存在过时的mbd，复制一些相关的属性给mbd
			if (previous != null) {
				copyRelevantMergedBeanDefinitionCaches(previous, mbd);
			}
			// 返回mbd
			return mbd;
		}
	}

	private void copyRelevantMergedBeanDefinitionCaches(RootBeanDefinition previous, RootBeanDefinition mbd) {
		if (ObjectUtils.nullSafeEquals(mbd.getBeanClassName(), previous.getBeanClassName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryBeanName(), previous.getFactoryBeanName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryMethodName(), previous.getFactoryMethodName())) {
			ResolvableType targetType = mbd.targetType;
			ResolvableType previousTargetType = previous.targetType;
			if (targetType == null || targetType.equals(previousTargetType)) {
				mbd.targetType = previousTargetType;
				mbd.isFactoryBean = previous.isFactoryBean;
				mbd.resolvedTargetType = previous.resolvedTargetType;
				mbd.factoryMethodReturnType = previous.factoryMethodReturnType;
				mbd.factoryMethodToIntrospect = previous.factoryMethodToIntrospect;
			}
		}
	}

	/**
	 * Check the given merged bean definition,
	 * potentially throwing validation exceptions.
	 * @param mbd the merged bean definition to check
	 * @param beanName the name of the bean
	 * @param args the arguments for bean creation, if any
	 * @throws BeanDefinitionStoreException in case of validation failure
	 */
	protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, @Nullable Object[] args)
			throws BeanDefinitionStoreException {

		if (mbd.isAbstract()) {
			throw new BeanIsAbstractException(beanName);
		}
	}

	/**
	 * Remove the merged bean definition for the specified bean,
	 * recreating it on next access.
	 * @param beanName the bean name to clear the merged definition for
	 */
	protected void clearMergedBeanDefinition(String beanName) {
		RootBeanDefinition bd = this.mergedBeanDefinitions.get(beanName);
		if (bd != null) {
			bd.stale = true;
		}
	}

	/**
	 * Clear the merged bean definition cache, removing entries for beans
	 * which are not considered eligible for full metadata caching yet.
	 * <p>Typically triggered after changes to the original bean definitions,
	 * e.g. after applying a {@code BeanFactoryPostProcessor}. Note that metadata
	 * for beans which have already been created at this point will be kept around.
	 * @since 4.2
	 */
	public void clearMetadataCache() {
		this.mergedBeanDefinitions.forEach((beanName, bd) -> {
			if (!isBeanEligibleForMetadataCaching(beanName)) {
				bd.stale = true;
			}
		});
	}

	/**
	 * Resolve the bean class for the specified bean definition,
	 * resolving a bean class name into a Class reference (if necessary)
	 * and storing the resolved Class in the bean definition for further use.
	 *
	 * 解析这个指定的beanDefinition的beanClass，将beanClassName解析为一个class引用，
	 * 并且将这个解析后的class对象存入到beanDefinition中供后续使用
	 *
	 * @param mbd the merged bean definition to determine the class for
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the resolved bean class (or {@code null} if none)
	 * @throws CannotLoadBeanClassException if we failed to load the class
	 */
	@Nullable
	protected Class<?> resolveBeanClass(RootBeanDefinition mbd, String beanName, Class<?>... typesToMatch)
			throws CannotLoadBeanClassException {

		try {
			// 如果mbd有beanClass，直接返回
			if (mbd.hasBeanClass()) {
				return mbd.getBeanClass();
			}
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedExceptionAction<Class<?>>)
						() -> doResolveBeanClass(mbd, typesToMatch), getAccessControlContext());
			}
			// 否则，解析beanClass
			else {
				return doResolveBeanClass(mbd, typesToMatch);
			}
		}
		catch (PrivilegedActionException pae) {
			ClassNotFoundException ex = (ClassNotFoundException) pae.getException();
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (ClassNotFoundException ex) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (LinkageError err) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), err);
		}
	}

	@Nullable
	private Class<?> doResolveBeanClass(RootBeanDefinition mbd, Class<?>... typesToMatch)
			throws ClassNotFoundException {

		// 获取beanFactory持有的beanClassLoader
		ClassLoader beanClassLoader = getBeanClassLoader();
		// 将beanClassLoader赋值给dynamicLoader
		ClassLoader dynamicLoader = beanClassLoader;
		// 表示beanClassName是否需要重新被dynamicLoader加载
		boolean freshResolve = false;

		// 如果typeToMatch参数不为空
		if (!ObjectUtils.isEmpty(typesToMatch)) {
			// When just doing type checks (i.e. not creating an actual instance yet),
			// use the specified temporary class loader (e.g. in a weaving scenario).
			// 当仅仅是做类型检查而不是真实的创建实例的时候，使用指定的临时类加载器
			// 获取临时的类加载器
			ClassLoader tempClassLoader = getTempClassLoader();
			// 如果临时类加载器不为null
			if (tempClassLoader != null) {
				// 将其赋值给dynamicLoader
				dynamicLoader = tempClassLoader;
				// 将freshResolve置为true，因为这里只是做类型检查，不会将解析出的class缓存到beanDefinition中
				freshResolve = true;
				// 如果临时类加载器是DecoratingClassLoader类型的
				if (tempClassLoader instanceof DecoratingClassLoader) {
					DecoratingClassLoader dcl = (DecoratingClassLoader) tempClassLoader;
					// 将typesToMatch的类名添加到dcl的excludeClasses集合中
					for (Class<?> typeToMatch : typesToMatch) {
						dcl.excludeClass(typeToMatch.getName());
					}
				}
			}
		}

		// 获取beanClassName
		String className = mbd.getBeanClassName();
		if (className != null) {
			// 解析beanClassName中存在的表达式
			Object evaluated = evaluateBeanDefinitionString(className, mbd);
			// 如果解析之后的值evaluated不等于解析前的className
			if (!className.equals(evaluated)) {
				// A dynamically resolved expression, supported as of 4.2...
				// 如果解析后的值是一个Class对象，直接返回
				if (evaluated instanceof Class) {
					return (Class<?>) evaluated;
				}
				// 如果是一个String类型的，将其赋值给beanClassName
				else if (evaluated instanceof String) {
					className = (String) evaluated;
					// 将refreshResolve设置为true，因为这里beanClassName涉及到了表达式的解析，
					// 且解析结果和原值不一样，那么可能会动态变化，因此不能将解析出的class对象缓存到beanDefinition中
					freshResolve = true;
				}
				else {
					throw new IllegalStateException("Invalid class name expression result: " + evaluated);
				}
			}
			// 如果freshResolve为true，使用动态类加载器进行加载，且不将结果缓存到BeanDefinition
			if (freshResolve) {
				// When resolving against a temporary class loader, exit early in order
				// to avoid storing the resolved Class in the bean definition.
				if (dynamicLoader != null) {
					try {
						return dynamicLoader.loadClass(className);
					}
					catch (ClassNotFoundException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Could not load class [" + className + "] from " + dynamicLoader + ": " + ex);
						}
					}
				}
				return ClassUtils.forName(className, dynamicLoader);
			}
		}

		// Resolve regularly, caching the result in the BeanDefinition...
		// 常规的解析，将结果缓存到BeanDefinition中
		return mbd.resolveBeanClass(beanClassLoader);
	}

	/**
	 * Evaluate the given String as contained in a bean definition,
	 * potentially resolving it as an expression.
	 * @param value the value to check
	 * @param beanDefinition the bean definition that the value comes from
	 * @return the resolved value
	 * @see #setBeanExpressionResolver
	 */
	@Nullable
	protected Object evaluateBeanDefinitionString(@Nullable String value, @Nullable BeanDefinition beanDefinition) {
		// 如果beanExpressionResolver为null的话，直接返回value
		if (this.beanExpressionResolver == null) {
			return value;
		}

		Scope scope = null;
		if (beanDefinition != null) {
			// 获取scopeName，然后从已注册的scopeMap中获取对应的Scope
			String scopeName = beanDefinition.getScope();
			if (scopeName != null) {
				scope = getRegisteredScope(scopeName);
			}
		}
		// 调用beanExpressionResolver的evaluate方法进行评估
		return this.beanExpressionResolver.evaluate(value, new BeanExpressionContext(this, scope));
	}


	/**
	 * Predict the eventual bean type (of the processed bean instance) for the
	 * specified bean. Called by {@link #getType} and {@link #isTypeMatch}.
	 * Does not need to handle FactoryBeans specifically, since it is only
	 * supposed to operate on the raw bean type.
	 * <p>This implementation is simplistic in that it is not able to
	 * handle factory methods and InstantiationAwareBeanPostProcessors.
	 * It only predicts the bean type correctly for a standard bean.
	 * To be overridden in subclasses, applying more sophisticated type detection.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition to determine the type for
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type of the bean, or {@code null} if not predictable
	 */
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// 获取mdb的targetType，是RootBeanDefinition的ResolvableType的resolved字段，如果不为null的话，直接返回
		Class<?> targetType = mbd.getTargetType();
		if (targetType != null) {
			return targetType;
		}
		// 如果mbd的factoryMethodName不为null的话，直接返回null
		if (mbd.getFactoryMethodName() != null) {
			return null;
		}
		// 否则，调用resolveBeanClass解析type
		return resolveBeanClass(mbd, beanName, typesToMatch);
	}

	/**
	 * Check whether the given bean is defined as a {@link FactoryBean}.
	 * @param beanName the name of the bean
	 * @param mbd the corresponding bean definition
	 */
	protected boolean isFactoryBean(String beanName, RootBeanDefinition mbd) {
		Boolean result = mbd.isFactoryBean;
		if (result == null) {
			// 判断mbd的beanType
			Class<?> beanType = predictBeanType(beanName, mbd, FactoryBean.class);
			// 如果beanType不为null且属于FactoryBean的子类，result为true
			result = (beanType != null && FactoryBean.class.isAssignableFrom(beanType));
			// 将result设置为mbd的isFactoryBean
			mbd.isFactoryBean = result;
		}
		// 返回result
		return result;
	}

	/**
	 * Determine the bean type for the given FactoryBean definition, as far as possible.
	 * Only called if there is no singleton instance registered for the target bean
	 * already. The implementation is allowed to instantiate the target factory bean if
	 * {@code allowInit} is {@code true} and the type cannot be determined another way;
	 * otherwise it is restricted to introspecting signatures and related metadata.
	 * <p>If no {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} if set on the bean definition
	 * and {@code allowInit} is {@code true}, the default implementation will create
	 * the FactoryBean via {@code getBean} to call its {@code getObjectType} method.
	 * Subclasses are encouraged to optimize this, typically by inspecting the generic
	 * signature of the factory bean class or the factory method that creates it.
	 * If subclasses do instantiate the FactoryBean, they should consider trying the
	 * {@code getObjectType} method without fully populating the bean. If this fails,
	 * a full FactoryBean creation as performed by this implementation should be used
	 * as fallback.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param allowInit if initialization of the FactoryBean is permitted if the type
	 * cannot be determined another way
	 * @return the type for the bean if determinable, otherwise {@code ResolvableType.NONE}
	 * @since 5.2
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 */
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		if (allowInit && mbd.isSingleton()) {
			try {
				FactoryBean<?> factoryBean = doGetBean(FACTORY_BEAN_PREFIX + beanName, FactoryBean.class, null, true);
				Class<?> objectType = getTypeForFactoryBean(factoryBean);
				return (objectType != null ? ResolvableType.forClass(objectType) : ResolvableType.NONE);
			}
			catch (BeanCreationException ex) {
				if (ex.contains(BeanCurrentlyInCreationException.class)) {
					logger.trace(LogMessage.format("Bean currently in creation on FactoryBean type check: %s", ex));
				}
				else if (mbd.isLazyInit()) {
					logger.trace(LogMessage.format("Bean creation exception on lazy FactoryBean type check: %s", ex));
				}
				else {
					logger.debug(LogMessage.format("Bean creation exception on eager FactoryBean type check: %s", ex));
				}
				onSuppressedException(ex);
			}
		}
		return ResolvableType.NONE;
	}

	/**
	 * Determine the bean type for a FactoryBean by inspecting its attributes for a
	 * {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} value.
	 * @param attributes the attributes to inspect
	 * @return a {@link ResolvableType} extracted from the attributes or
	 * {@code ResolvableType.NONE}
	 * @since 5.2
	 */
	ResolvableType getTypeForFactoryBeanFromAttributes(AttributeAccessor attributes) {
		// 尝试从属性中获取名为factoryBeanObjectType的值
		Object attribute = attributes.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
		// 如果属性值是ResolvableType类型的，直接返回
		if (attribute instanceof ResolvableType) {
			return (ResolvableType) attribute;
		}
		// 如果属性值是Class类型的，包装成ResolvableType返回
		if (attribute instanceof Class) {
			return ResolvableType.forClass((Class<?>) attribute);
		}
		// 否则返回NONE
		return ResolvableType.NONE;
	}

	/**
	 * Determine the bean type for the given FactoryBean definition, as far as possible.
	 * Only called if there is no singleton instance registered for the target bean already.
	 * <p>The default implementation creates the FactoryBean via {@code getBean}
	 * to call its {@code getObjectType} method. Subclasses are encouraged to optimize
	 * this, typically by just instantiating the FactoryBean but not populating it yet,
	 * trying whether its {@code getObjectType} method already returns a type.
	 * If no type found, a full FactoryBean creation as performed by this implementation
	 * should be used as fallback.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 * @deprecated since 5.2 in favor of {@link #getTypeForFactoryBean(String, RootBeanDefinition, boolean)}
	 */
	@Nullable
	@Deprecated
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		return getTypeForFactoryBean(beanName, mbd, true).resolve();
	}

	/**
	 * Mark the specified bean as already created (or about to be created).
	 * <p>This allows the bean factory to optimize its caching for repeated
	 * creation of the specified bean.
	 * @param beanName the name of the bean
	 */
	protected void markBeanAsCreated(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			synchronized (this.mergedBeanDefinitions) {
				if (!this.alreadyCreated.contains(beanName)) {
					// Let the bean definition get re-merged now that we're actually creating
					// the bean... just in case some of its metadata changed in the meantime.
					// 将mergedBeanDefinitions这个map中的已经存在的beanName的mbd置为过时状态
					// 因为我们正在创建这个bean，防止它的某些元数据发生了变化，所以我们需要重新进行merge
					clearMergedBeanDefinition(beanName);
					// 然后将beanName添加到alreadyCreated集合中
					this.alreadyCreated.add(beanName);
				}
			}
		}
	}

	/**
	 * Perform appropriate cleanup of cached metadata after bean creation failed.
	 * @param beanName the name of the bean
	 */
	protected void cleanupAfterBeanCreationFailure(String beanName) {
		synchronized (this.mergedBeanDefinitions) {
			this.alreadyCreated.remove(beanName);
		}
	}

	/**
	 * Determine whether the specified bean is eligible for having
	 * its bean definition metadata cached.
	 * @param beanName the name of the bean
	 * @return {@code true} if the bean's metadata may be cached
	 * at this point already
	 */
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return this.alreadyCreated.contains(beanName);
	}

	/**
	 * Remove the singleton instance (if any) for the given bean name,
	 * but only if it hasn't been used for other purposes than type checking.
	 * @param beanName the name of the bean
	 * @return {@code true} if actually removed, {@code false} otherwise
	 */
	protected boolean removeSingletonIfCreatedForTypeCheckOnly(String beanName) {
		// 如果alreadyCreated集合中不包含该beanName
		if (!this.alreadyCreated.contains(beanName)) {
			// 将其从单例缓存中删除，包裹FactoryBeanInstanceCache 和 FactoryBeanObjectCache 以及三级缓存
			// 因为alreadyCreated没有包含这些bean的beanName，说明它们的创建只是为了类型检查
			removeSingleton(beanName);
			// 然后返回true
			return true;
		}
		// 如果包含，返回false
		else {
			return false;
		}
	}

	/**
	 * Check whether this factory's bean creation phase already started,
	 * i.e. whether any bean has been marked as created in the meantime.
	 * @since 4.2.2
	 * @see #markBeanAsCreated
	 */
	protected boolean hasBeanCreationStarted() {
		return !this.alreadyCreated.isEmpty();
	}

	/**
	 * Get the object for the given bean instance, either the bean
	 * instance itself or its created object in case of a FactoryBean.
	 * @param beanInstance the shared bean instance
	 * @param name the name that may include factory dereference prefix
	 * @param beanName the canonical bean name
	 * @param mbd the merged bean definition
	 * @return the object to expose for the bean
	 */
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		// Don't let calling code try to dereference the factory if the bean isn't a factory.
		// 如果name以&开头，说明时获取FactoryBean对象本身
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			// 如果beanInstance是NullBean，直接返回
			if (beanInstance instanceof NullBean) {
				return beanInstance;
			}
			// 如果beanInstance不是FactoryBean类型的，报错
			if (!(beanInstance instanceof FactoryBean)) {
				throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
			}
			// 如果mbd不为null，将其isFactoryBean属性设置为true
			if (mbd != null) {
				mbd.isFactoryBean = true;
			}
			return beanInstance;
		}

		// Now we have the bean instance, which may be a normal bean or a FactoryBean.
		// If it's a FactoryBean, we use it to create a bean instance, unless the
		// caller actually wants a reference to the factory.
		// 如果走到这一步，说明name中不含&，则是返回普通的bean对象，如果beanInstance不是FactoryBean类型的，直接返回。
		if (!(beanInstance instanceof FactoryBean)) {
			return beanInstance;
		}

		// 如果beanInstance是FactoryBean类型的，那么调用其getObject方法返回实际的bean对象
		Object object = null;
		// 如果mbd不为null，将其isFactorBean设置为true
		if (mbd != null) {
			mbd.isFactoryBean = true;
		}
		// 如果mbd为null，尝试从FactoryBeanObject缓存中获取实际的bean
		else {
			object = getCachedObjectForFactoryBean(beanName);
		}
		// 如果object仍为null，调用FactoryBean.getObject获取
		if (object == null) {
			// Return bean instance from factory.
			FactoryBean<?> factory = (FactoryBean<?>) beanInstance;
			// Caches object obtained from FactoryBean if it is a singleton.
			// 如果mbd为null并且容器中存在beanName对应的bd，获取mergedBeanDefinition
			if (mbd == null && containsBeanDefinition(beanName)) {
				mbd = getMergedLocalBeanDefinition(beanName);
			}
			// 判断mbd是否是合成的
			boolean synthetic = (mbd != null && mbd.isSynthetic());
			// 从factoryBean中获取实际的bean
			object = getObjectFromFactoryBean(factory, beanName, !synthetic);
		}
		return object;
	}

	/**
	 * Determine whether the given bean name is already in use within this factory,
	 * i.e. whether there is a local bean or alias registered under this name or
	 * an inner bean created with this name.
	 * @param beanName the name to check
	 */
	public boolean isBeanNameInUse(String beanName) {
		return isAlias(beanName) || containsLocalBean(beanName) || hasDependentBean(beanName);
	}

	/**
	 * Determine whether the given bean requires destruction on shutdown.
	 * <p>The default implementation checks the DisposableBean interface as well as
	 * a specified destroy method and registered DestructionAwareBeanPostProcessors.
	 * @param bean the bean instance to check
	 * @param mbd the corresponding bean definition
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see AbstractBeanDefinition#getDestroyMethodName()
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean requiresDestruction(Object bean, RootBeanDefinition mbd) {
		// 如果bean的类对象不是NullBean.class 并且
		// (bean中含有destroy方法 或者 (beanFactory持有DestructionAwareBeanPostProcessor 并且 存在可以应用到bean上的DestructionAwareBeanPostProcessor)
		// 满足上述条件返回true，表示bean需要被摧毁
		return (bean.getClass() != NullBean.class &&
				// 判断是否有destroyMethod的逻辑是：
				// 如果bean是DisposableBean或者AutoCloseable接口的，直接返回true；
				// 否则查看mbd中是否有已解析的destroyMethodName；
				// 如果没有，查看mbd的destroyMethodName，看是否有值；
				// 如果destroyMethodName的值为(inferred)，进行推断，查找是否有名为close或shutdown的方法
				(DisposableBeanAdapter.hasDestroyMethod(bean, mbd) || (hasDestructionAwareBeanPostProcessors() &&
						DisposableBeanAdapter.hasApplicableProcessors(bean, getBeanPostProcessors()))));
	}

	/**
	 * Add the given bean to the list of disposable beans in this factory,
	 * registering its DisposableBean interface and/or the given destroy method
	 * to be called on factory shutdown (if applicable). Only applies to singletons.
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 * @param mbd the bean definition for the bean
	 * @see RootBeanDefinition#isSingleton
	 * @see RootBeanDefinition#getDependsOn
	 * @see #registerDisposableBean
	 * @see #registerDependentBean
	 */
	protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
		AccessControlContext acc = (System.getSecurityManager() != null ? getAccessControlContext() : null);
		// 如果mbd不是prototype类型的 并且 判断出bean需要destruction
		if (!mbd.isPrototype() && requiresDestruction(bean, mbd)) {
			// 如果mbd是singleton的
			if (mbd.isSingleton()) {
				// Register a DisposableBean implementation that performs all destruction
				// work for the given bean: DestructionAwareBeanPostProcessors,
				// DisposableBean interface, custom destroy method.
				// 注册一个DisposableBean，其中实现了所有的destruction操作，
				// 包括DestructionAwareBeanPostProcessors，DisposableBean接口以及自定义的destroyMethod
				registerDisposableBean(beanName,
						new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), acc));
			}
			else {
				// A bean with a custom scope...
				// 如果bean是自定义范围的
				Scope scope = this.scopes.get(mbd.getScope());
				if (scope == null) {
					throw new IllegalStateException("No Scope registered for scope name '" + mbd.getScope() + "'");
				}
				// 将其注册到范围的destructionCallback中
				scope.registerDestructionCallback(beanName,
						new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), acc));
			}
		}
	}


	//---------------------------------------------------------------------
	// Abstract methods to be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Check if this bean factory contains a bean definition with the given name.
	 * Does not consider any hierarchy this factory may participate in.
	 * Invoked by {@code containsBean} when no cached singleton instance is found.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * @param beanName the name of the bean to look for
	 * @return if this bean factory contains a bean definition with the given name
	 * @see #containsBean
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 */
	protected abstract boolean containsBeanDefinition(String beanName);

	/**
	 * Return the bean definition for the given bean name.
	 * Subclasses should normally implement caching, as this method is invoked
	 * by this class every time bean definition metadata is needed.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * @param beanName the name of the bean to find a definition for
	 * @return the BeanDefinition for this prototype name (never {@code null})
	 * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException
	 * if the bean definition cannot be resolved
	 * @throws BeansException in case of errors
	 * @see RootBeanDefinition
	 * @see ChildBeanDefinition
	 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#getBeanDefinition
	 */
	protected abstract BeanDefinition getBeanDefinition(String beanName) throws BeansException;

	/**
	 * Create a bean instance for the given merged bean definition (and arguments).
	 * The bean definition will already have been merged with the parent definition
	 * in case of a child definition.
	 * <p>All bean retrieval methods delegate to this method for actual bean creation.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 */
	protected abstract Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException;

}
