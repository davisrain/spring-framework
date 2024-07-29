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

package org.springframework.beans.factory.support;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 *
 * 通用的共享bean实例的注册器，实现了SingletonBeanRegistry接口，
 * 允许注册那些应该被所有注册器调用方共享的单例，通过beanName持有它们
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 *
 * 同样支持DisposableBean实例的注册，这些DisposableBean实例可能会也可能不会关联到已注册单例上，
 * 在注册器关闭的时候被摧毁。bean之间的依赖关系可以被注册，用于强制一个合适的关闭顺序。
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 *
 * 这个类主要作为BeanFactory实现类的基类来提供服务，用来提供通用的单例管理能力。
 * ConfigurableBeanFactory接口继承了SingletonBeanRegistry接口
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 *
 * 注意这个类对比AbstractBeanFactory和DefaultListableBeanFactory，即没有beanDefinition的概念也没有一个明确的bean的创建过程。
 * 能够被选择性的作为一个内嵌的委托类去使用
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/** Maximum number of suppressed exceptions to preserve. */
	// 最大被抑制的异常数量
	private static final int SUPPRESSED_EXCEPTIONS_LIMIT = 100;


	/** Cache of singleton objects: bean name to bean instance. */
	// 单例对象的缓存，key为beanName value为bean实例，一级缓存
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/** Cache of singleton factories: bean name to ObjectFactory. */
	// 单例工厂的缓存，key为beanName， value为ObjectFactory，三级缓存
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/** Cache of early singleton objects: bean name to bean instance. */
	// early singleton对象的缓存，key为beanName, value为还未构建完成的bean实例，二级缓存
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/** Set of registered singletons, containing the bean names in registration order. */
	// 注册过的单例beanName集合
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/** Names of beans that are currently in creation. */
	// 正处于创建中的单例的beanName集合
	private final Set<String> singletonsCurrentlyInCreation =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** Names of beans currently excluded from in creation checks. */
	// 需要排除在单例创建检查之外的那些beanName集合
	private final Set<String> inCreationCheckExclusions =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** Collection of suppressed Exceptions, available for associating related causes. */
	@Nullable
	// 所以被抑制的异常集合
	private Set<Exception> suppressedExceptions;

	/** Flag that indicates whether we're currently within destroySingletons. */
	// 用于表示当前是否正处于destroySingleton过程的标志
	private boolean singletonsCurrentlyInDestruction = false;

	/** Disposable bean instances: bean name to disposable instance. */
	// 保存DisposableBean实例的map，key为beanName，value为封装好的DisposableBean
	private final Map<String, Object> disposableBeans = new LinkedHashMap<>();

	/** Map between containing bean names: bean name to Set of bean names that the bean contains. */
	// 用于表示bean和被包含的bean之间关系的map，key为beanName，value为key对应的bean包含的bean的beanName集合
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	// 下面两个map的具体表现就是，比如一个BeanA，有一个实例属性是BeanB，
	// 那么就说A依赖了B，B被A依赖了
	// 所以dependentBeanMap里面B的beanName为key，A的beanName在value集合中
	// dependenciesBeanMap里面A的beanName为key，B的beanName在value集合中

	/** Map between dependent bean names: bean name to Set of dependent bean names. */
	// 这个map的key是一个beanName，value是一个set，里面保存的是key对应的bean被哪些bean(beanName)依赖了
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/** Map between depending bean names: bean name to Set of bean names for the bean's dependencies. */
	// 这个map的key是beanName，value是一个set，保存的是key对应的bean依赖了哪些bean(beanName)
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		synchronized (this.singletonObjects) {
			Object oldObject = this.singletonObjects.get(beanName);
			if (oldObject != null) {
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * Add the given singleton object to the singleton cache of this factory.
	 * <p>To be called for eager registration of singletons.
	 * @param beanName the name of the bean
	 * @param singletonObject the singleton object
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.put(beanName, singletonObject);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			// 将beanName添加到registeredSingletons中
			this.registeredSingletons.add(beanName);
		}
	}

	/**
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 *
	 * 添加给出的必要情况下为了构建指定的单例的singleton factory
	 * 当迫切需要注册单例的时候调用该方法，比如使得能够解析循环引用的场景
	 *
	 * @param beanName the name of the bean
	 * @param singletonFactory the factory for the singleton object
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		synchronized (this.singletonObjects) {
			if (!this.singletonObjects.containsKey(beanName)) {
				this.singletonFactories.put(beanName, singletonFactory);
				this.earlySingletonObjects.remove(beanName);
				this.registeredSingletons.add(beanName);
			}
		}
	}

	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		return getSingleton(beanName, true);
	}

	/**
	 * Return the (raw) singleton object registered under the given name.
	 * <p>Checks already instantiated singletons and also allows for an early
	 * reference to a currently created singleton (resolving a circular reference).
	 * @param beanName the name of the bean to look for
	 * @param allowEarlyReference whether early references should be created or not 是否earlyReference应该被创建出来
	 * @return the registered singleton object, or {@code null} if none found
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		// Quick check for existing instance without full singleton lock
		// 不加锁快速检查一下singletonObjects中是否有beanName对应的bean
		Object singletonObject = this.singletonObjects.get(beanName);
		// 如果不存在于singletonObjects中，并且处于正在创建的状态
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			// 尝试从earlySingletonObjects中获取
			singletonObject = this.earlySingletonObjects.get(beanName);
			// 如果仍然为null并且 allowEarlyReference标志位true
			if (singletonObject == null && allowEarlyReference) {
				// 以singletonObjects为锁
				synchronized (this.singletonObjects) {
					// Consistent creation of early reference within full singleton lock
					// 再次尝试去一级缓存中查找
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						// 尝试去二级缓存中查找
						singletonObject = this.earlySingletonObjects.get(beanName);
						if (singletonObject == null) {
							// 尝试去三级缓存中查找
							ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
							if (singletonFactory != null) {
								// 如果存在，调用getObject获取bean
								singletonObject = singletonFactory.getObject();
								// 放入二级缓存中
								this.earlySingletonObjects.put(beanName, singletonObject);
								// 删除三级缓存对应的factory
								this.singletonFactories.remove(beanName);
							}
						}
					}
				}
			}
		}
		return singletonObject;
	}

	/**
	 * Return the (raw) singleton object registered under the given name,
	 * creating and registering a new one if none registered yet.
	 * @param beanName the name of the bean
	 * @param singletonFactory the ObjectFactory to lazily create the singleton
	 * with, if necessary
	 * @return the registered singleton object
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(beanName, "Bean name must not be null");
		// 对singletonObjects加锁
		synchronized (this.singletonObjects) {
			// 尝试从一级缓存中获取beanName对应的bean
			Object singletonObject = this.singletonObjects.get(beanName);
			// 如果一级缓存中不存在
			if (singletonObject == null) {
				// 判断singletonsCurrentlyInDestruction标志是true，报错，不要在BeanFactory的destroy方法实现里请求获取bean
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
							"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				// 打印日志 正在为beanName创建一个共享的实例
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				// 如果beanName不在 创建检查排除名单(inCreationCheckExclusions) 里面，
				// 那么将beanName添加到 正在创建的单例名单(singletonsCurrentlyInCreation) 中，
				// 表示该beanName正在创建，如果添加失败，报错
				beforeSingletonCreation(beanName);
				boolean newSingleton = false;
				// 判断是否要记录被抑制的异常
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				if (recordSuppressedExceptions) {
					// 如果是，生成一个set赋值给suppressedExceptions
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				try {
					// 尝试调用singletonFactory的getObject方法，默认实现是调用abstractBeanFactory的createBean方法。
					// 将创建出的单例赋值给singletonObject
					singletonObject = singletonFactory.getObject();
					// 并且将newSingleton标志设置为true
					newSingleton = true;
				}
				catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					// 判断单例对象是否在这期间隐式地出现了，如果是，获取一级缓存中的单例对象，并且将异常吞掉
					singletonObject = this.singletonObjects.get(beanName);
					// 如果没有的话，继续抛出异常
					if (singletonObject == null) {
						throw ex;
					}
				}
				catch (BeanCreationException ex) {
					// 如果recordSuppressedExceptions为true的话
					if (recordSuppressedExceptions) {
						// 遍历suppressExceptions集合，将其添加到ex的relatedCause中
						for (Exception suppressedException : this.suppressedExceptions) {
							ex.addRelatedCause(suppressedException);
						}
					}
					// 然后抛出异常
					throw ex;
				}
				finally {
					// 如果recordSuppressedExceptions为true，将suppressExceptions置为null
					if (recordSuppressedExceptions) {
						this.suppressedExceptions = null;
					}
					// 如果beanName不在 创建检查排除名单(inCreationCheckExclusions) 里面，
					// 那么将beanName从 正在创建的单例名单(singletonsCurrentlyInCreation) 删除，如果删除失败，报错
					afterSingletonCreation(beanName);
				}
				// 如果newSingleton标志为true，将生成的singletonObject放入一级缓存中，并且将二三级缓存中的内容都删除。
				// 并且将beanName放入到registeredSingletons集合中。

				// newSingleton为true，表示单例对象是本次调用创建的，而不是通过捕获IllegalStateException赋值的，说明
				// 单例对象还未在一级缓存中，因此需要添加进去，如果是false的话，不用添加
				if (newSingleton) {
					addSingleton(beanName, singletonObject);
				}
			}
			// 返回创建出的singletonObject
			return singletonObject;
		}
	}

	/**
	 * Register an exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * <p>The default implementation preserves any given exception in this registry's
	 * collection of suppressed exceptions, up to a limit of 100 exceptions, adding
	 * them as related causes to an eventual top-level {@link BeanCreationException}.
	 * @param ex the Exception to register
	 * @see BeanCreationException#getRelatedCauses()
	 */
	protected void onSuppressedException(Exception ex) {
		synchronized (this.singletonObjects) {
			if (this.suppressedExceptions != null && this.suppressedExceptions.size() < SUPPRESSED_EXCEPTIONS_LIMIT) {
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * @param beanName the name of the bean
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.remove(beanName);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.remove(beanName);
		}
	}

	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * Return whether the specified singleton bean is currently in creation
	 * (within the entire factory).
	 * @param beanName the name of the bean
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * Callback after singleton creation.
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 * @param beanName the name of the singleton that has been created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * @param containedBeanName the name of the contained (inner) bean
	 * @param containingBeanName the name of the containing (outer) bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		// 注册两个bean的contain关系，containedBeanName表示被contain的bean，containingBeanName表示contain其他bean的bean
		synchronized (this.containedBeanMap) {
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		// 存在contain关系，那么就存在dependent关系，注册进去。
		// 关系是被contain的bean也就被contain它的bean依赖了
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * @param beanName the name of the bean
	 * @param dependentBeanName the name of the dependent bean
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		// 将beanName进行别名解析，转换为实际的beanName
		String canonicalName = canonicalName(beanName);

		// 根据dependentBeanMap加锁
		synchronized (this.dependentBeanMap) {
			// 获取map中beanName对应的set
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			// 将依赖了beanName的dependentBeanName添加到set里，如果添加失败了，直接返回
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}

		// 根据dependenciesForBeanMap加锁
		synchronized (this.dependenciesForBeanMap) {
			// 获取map中dependentBeanName对应的set
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			// 将被dependentBeanName依赖的beanName添加到set中
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * @param beanName the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		// 以dependentBeanMap来加锁
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	// 相当于一个树状结构，从beanName开始，查找其叶子节点是否有等于dependentBeanName的，
	// 该方法相等于进行dfs，并且记录路径，一旦发现有环，直接返回false
	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		// 如果alreadySeen不为null并且alreadySeen中包含了beanName，直接返回false。
		// 出现这种情况代表形成了依赖环，并且没有找到beanName和dependentBeanName的依赖关系，直接返回false
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		// 将beanName进行别名映射，确保其不是别名而是真实的beanName
		String canonicalName = canonicalName(beanName);
		// 查找dependentBeanMap中beanName对应的集合
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		// 如果为null的话，返回false，表示beanName没有被任何其他bean依赖，因此直接返回false
		if (dependentBeans == null) {
			return false;
		}
		// 如果集合中包含了dependentBeanName，表示beanName被dependentBeanName依赖了
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		// 如果集合中不包含dependentBeanName，那么递归查看是否有深层的依赖关系
		// 遍历依赖了beanName的那些bean的name
		for (String transitiveDependency : dependentBeans) {
			// 如果alreadySeen为null，初始化为一个HashSet
			if (alreadySeen == null) {
				alreadySeen = new HashSet<>();
			}
			// 将beanName放入alreadySeen中
			alreadySeen.add(beanName);
			// 递归调用isDependent，查看依赖了beanName的bean有没有被dependentBeanName对应的bean依赖
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		// 如果都没有匹配，返回false
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		synchronized (this.dependentBeanMap) {
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		if (dependenciesForBean == null) {
			return new String[0];
		}
		synchronized (this.dependenciesForBeanMap) {
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}
		synchronized (this.singletonObjects) {
			this.singletonsCurrentlyInDestruction = true;
		}

		String[] disposableBeanNames;
		synchronized (this.disposableBeans) {
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames[i]);
		}

		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();

		clearSingletonCache();
	}

	/**
	 * Clear all cached singleton instances in this registry.
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		synchronized (this.singletonObjects) {
			this.singletonObjects.clear();
			this.singletonFactories.clear();
			this.earlySingletonObjects.clear();
			this.registeredSingletons.clear();
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Remove a registered singleton of the given name, if any.
		// 根据beanName从三级缓存中删除对应的bean
		removeSingleton(beanName);

		// Destroy the corresponding DisposableBean instance.
		// 从disposableBeans集合从尝试删除对应的beanName
		DisposableBean disposableBean;
		synchronized (this.disposableBeans) {
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		// 如果disposableBean不为null的话，说明bean存在destroyMethod需要调用
		// 调用destroyBean方法摧毁bean
		destroyBean(beanName, disposableBean);
	}

	/**
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * @param beanName the name of the bean
	 * @param bean the bean instance to destroy
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// Trigger destruction of dependent beans first...
		Set<String> dependencies;
		// 根据beanName删除dependentBeanMap中的数据，获取到dependencies，即依赖了当前bean的所有其他bean的beanNames
		synchronized (this.dependentBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			dependencies = this.dependentBeanMap.remove(beanName);
		}
		// 如果存在依赖了当前bean的其他bean
		if (dependencies != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			// 遍历这些beanName
			for (String dependentBeanName : dependencies) {
				// 调用destroySingleton将这些bean也摧毁
				destroySingleton(dependentBeanName);
			}
		}

		// Actually destroy the bean now...
		// 如果存在DisposableBean，即初始化之后注册进DefaultSingletonBeanRegistry的DisposableBeanAdapter
		if (bean != null) {
			try {
				// 调用DisposableBeanAdapter的destroy方法，执行顺序如下
				// 1.执行满足条件的DestructionAwareBeanPostProcessor的postProcessBeforeDestruction
				// 2.如果bean实现了DisposableBean接口，执行bean的destroy方法
				// 3.如果bean存在自定义的destroyMethod，执行它
				bean.destroy();
			}
			catch (Throwable ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// Trigger destruction of contained beans...
		Set<String> containedBeans;
		// 如果当前bean存在于containedBeanMap中，将其从containedBeanMap中删除
		synchronized (this.containedBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		// 如果存在被当前bean包含的bean集合，将它们也destroy掉
		if (containedBeans != null) {
			// 遍历内部bean的beanName，调用destroySingleton方法
			for (String containedBeanName : containedBeans) {
				destroySingleton(containedBeanName);
			}
		}

		// Remove destroyed bean from other beans' dependencies.
		// 找到该bean在dependentBeanMap中存在于value的位置，将其删除，
		// 因为这个bean被摧毁了，那么原本被它依赖的那些bean就不再被它依赖，所以需要更新dependentBeanMap中的信息
		synchronized (this.dependentBeanMap) {
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				dependenciesToClean.remove(beanName);
				if (dependenciesToClean.isEmpty()) {
					it.remove();
				}
			}
		}

		// Remove destroyed bean's prepared dependency information.
		// 删除dependenciesForBeanMap中beanName对应的元素，不再保留该bean依赖的bean的信息。
		this.dependenciesForBeanMap.remove(beanName);
	}

	/**
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 */
	@Override
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
