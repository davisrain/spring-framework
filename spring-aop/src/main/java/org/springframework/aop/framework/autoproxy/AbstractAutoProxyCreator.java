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

package org.springframework.aop.framework.autoproxy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.aopalliance.aop.Advice;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.ProxyProcessorSupport;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that wraps each eligible bean with an AOP proxy, delegating to specified interceptors
 * before invoking the bean itself.
 *
 * BeanPostProcessor的一个实现，用于包装每个符合条件的bean为一个aop代理对象，在调用到bean自身的方法之前委托到指定的拦截器
 *
 * <p>This class distinguishes between "common" interceptors: shared for all proxies it
 * creates, and "specific" interceptors: unique per bean instance. There need not be any
 * common interceptors. If there are, they are set using the interceptorNames property.
 * As with {@link org.springframework.aop.framework.ProxyFactoryBean}, interceptors names
 * in the current factory are used rather than bean references to allow correct handling
 * of prototype advisors and interceptors: for example, to support stateful mixins.
 * Any advice type is supported for {@link #setInterceptorNames "interceptorNames"} entries.
 *
 * 这个类区分了公共的拦截器和指定的拦截器，公共的拦截器共享给这个类创建的所有代理对象，而指定的拦截对于每个bean实例来说是唯一的。
 * 这里不需要任何公共的拦截器，如果需要的话，他们会被设置使用interceptorNames属性。
 *
 *
 * <p>Such auto-proxying is particularly useful if there's a large number of beans that
 * need to be wrapped with similar proxies, i.e. delegating to the same interceptors.
 * Instead of x repetitive proxy definitions for x target beans, you can register
 * one single such post processor with the bean factory to achieve the same effect.
 * 这样一个自动代理是特别有用的，当有大量的bean需要进行相似的代理包装。比如，委托给同样的拦截器。
 * 而不是为x个bean定义x个重复的代理定义，你能够注册一个单独的postProcessor去达到同样的效果
 *
 * <p>Subclasses can apply any strategy to decide if a bean is to be proxied, e.g. by type,
 * by name, by definition details, etc. They can also return additional interceptors that
 * should just be applied to the specific bean instance. A simple concrete implementation is
 * {@link BeanNameAutoProxyCreator}, identifying the beans to be proxied via given names.
 * 子类可以应用任何的策略去决定一个bean是否应该被代理。
 * 比如，根据类型、根据名称、根据定义的细节等等。
 * 他们也能够返回附加的需要被应用到指定的bean实例上的拦截器。
 * 一个简单的具体的实现就是BeanNameAutoProxyCreator，指定了bean应该通过给出的name进行代理。
 *
 * <p>Any number of {@link TargetSourceCreator} implementations can be used to create
 * a custom target source: for example, to pool prototype objects. Auto-proxying will
 * occur even if there is no advice, as long as a TargetSourceCreator specifies a custom
 * {@link org.springframework.aop.TargetSource}. If there are no TargetSourceCreators set,
 * or if none matches, a {@link org.springframework.aop.target.SingletonTargetSource}
 * will be used by default to wrap the target bean instance.
 *
 * 任意数量的TargetSourceCreator的实现能够被用来去创建一个自定义的target source。比如，去持有一个原型对象。
 * 自动代理会发生即使这里没有任何增强，只要TargetSourceCreator指定了一个自定义的TargetSource。
 * 如果这里没有TargetSourceCreator被设置 或者 没有匹配的，那么一个SingletonTargetSource将会被默认使用去包装target bean实例
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Rob Harrop
 * @since 13.10.2003
 * @see #setInterceptorNames
 * @see #getAdvicesAndAdvisorsForBean
 * @see BeanNameAutoProxyCreator
 * @see DefaultAdvisorAutoProxyCreator
 */
@SuppressWarnings("serial")
public abstract class AbstractAutoProxyCreator extends ProxyProcessorSupport
		implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {

	/**
	 * Convenience constant for subclasses: Return value for "do not proxy".
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Nullable
	protected static final Object[] DO_NOT_PROXY = null;

	/**
	 * Convenience constant for subclasses: Return value for
	 * "proxy without additional interceptors, just the common ones".
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	protected static final Object[] PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS = new Object[0];


	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Default is global AdvisorAdapterRegistry. */
	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	/**
	 * Indicates whether or not the proxy should be frozen. Overridden from super
	 * to prevent the configuration from becoming frozen too early.
	 */
	private boolean freezeProxy = false;

	/** Default is no common interceptors. */
	private String[] interceptorNames = new String[0];

	private boolean applyCommonInterceptorsFirst = true;

	@Nullable
	private TargetSourceCreator[] customTargetSourceCreators;

	@Nullable
	private BeanFactory beanFactory;

	private final Set<String> targetSourcedBeans = Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	private final Map<Object, Object> earlyProxyReferences = new ConcurrentHashMap<>(16);

	private final Map<Object, Class<?>> proxyTypes = new ConcurrentHashMap<>(16);

	private final Map<Object, Boolean> advisedBeans = new ConcurrentHashMap<>(256);


	/**
	 * Set whether or not the proxy should be frozen, preventing advice
	 * from being added to it once it is created.
	 * <p>Overridden from the super class to prevent the proxy configuration
	 * from being frozen before the proxy is created.
	 */
	@Override
	public void setFrozen(boolean frozen) {
		this.freezeProxy = frozen;
	}

	@Override
	public boolean isFrozen() {
		return this.freezeProxy;
	}

	/**
	 * Specify the {@link AdvisorAdapterRegistry} to use.
	 * <p>Default is the global {@link AdvisorAdapterRegistry}.
	 * @see org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry
	 */
	public void setAdvisorAdapterRegistry(AdvisorAdapterRegistry advisorAdapterRegistry) {
		this.advisorAdapterRegistry = advisorAdapterRegistry;
	}

	/**
	 * Set custom {@code TargetSourceCreators} to be applied in this order.
	 * If the list is empty, or they all return null, a {@link SingletonTargetSource}
	 * will be created for each bean.
	 * <p>Note that TargetSourceCreators will kick in even for target beans
	 * where no advices or advisors have been found. If a {@code TargetSourceCreator}
	 * returns a {@link TargetSource} for a specific bean, that bean will be proxied
	 * in any case.
	 * <p>{@code TargetSourceCreators} can only be invoked if this post processor is used
	 * in a {@link BeanFactory} and its {@link BeanFactoryAware} callback is triggered.
	 * @param targetSourceCreators the list of {@code TargetSourceCreators}.
	 * Ordering is significant: The {@code TargetSource} returned from the first matching
	 * {@code TargetSourceCreator} (that is, the first that returns non-null) will be used.
	 */
	public void setCustomTargetSourceCreators(TargetSourceCreator... targetSourceCreators) {
		this.customTargetSourceCreators = targetSourceCreators;
	}

	/**
	 * Set the common interceptors. These must be bean names in the current factory.
	 * They can be of any advice or advisor type Spring supports.
	 * <p>If this property isn't set, there will be zero common interceptors.
	 * This is perfectly valid, if "specific" interceptors such as matching
	 * Advisors are all we want.
	 */
	public void setInterceptorNames(String... interceptorNames) {
		this.interceptorNames = interceptorNames;
	}

	/**
	 * Set whether the common interceptors should be applied before bean-specific ones.
	 * Default is "true"; else, bean-specific interceptors will get applied first.
	 */
	public void setApplyCommonInterceptorsFirst(boolean applyCommonInterceptorsFirst) {
		this.applyCommonInterceptorsFirst = applyCommonInterceptorsFirst;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Return the owning {@link BeanFactory}.
	 * May be {@code null}, as this post-processor doesn't need to belong to a bean factory.
	 */
	@Nullable
	protected BeanFactory getBeanFactory() {
		return this.beanFactory;
	}


	@Override
	@Nullable
	public Class<?> predictBeanType(Class<?> beanClass, String beanName) {
		// 如果proxyTypes为空的，直接返回null，
		if (this.proxyTypes.isEmpty()) {
			return null;
		}
		// 否则根据beanClass或者beanName获取cacheKey
		Object cacheKey = getCacheKey(beanClass, beanName);
		// 从proxyTypes中根据cacheKey取值并返回
		return this.proxyTypes.get(cacheKey);
	}

	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
		return null;
	}

	@Override
	public Object getEarlyBeanReference(Object bean, String beanName) {
		// 根据beanClass或者beanName获取cacheKey
		Object cacheKey = getCacheKey(bean.getClass(), beanName);
		// 将bean根据cacheKey放入到earlyProxyReferences缓存中
		this.earlyProxyReferences.put(cacheKey, bean);
		// 然后根据bean是否需要进行包装，返回代理后的对象或者原始bean
		return wrapIfNecessary(bean, beanName, cacheKey);
	}

	@Override
	public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
		Object cacheKey = getCacheKey(beanClass, beanName);

		if (!StringUtils.hasLength(beanName) || !this.targetSourcedBeans.contains(beanName)) {
			if (this.advisedBeans.containsKey(cacheKey)) {
				return null;
			}
			if (isInfrastructureClass(beanClass) || shouldSkip(beanClass, beanName)) {
				this.advisedBeans.put(cacheKey, Boolean.FALSE);
				return null;
			}
		}

		// Create proxy here if we have a custom TargetSource.
		// Suppresses unnecessary default instantiation of the target bean:
		// The TargetSource will handle target instances in a custom fashion.
		TargetSource targetSource = getCustomTargetSource(beanClass, beanName);
		if (targetSource != null) {
			if (StringUtils.hasLength(beanName)) {
				this.targetSourcedBeans.add(beanName);
			}
			Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(beanClass, beanName, targetSource);
			Object proxy = createProxy(beanClass, beanName, specificInterceptors, targetSource);
			this.proxyTypes.put(cacheKey, proxy.getClass());
			return proxy;
		}

		return null;
	}

	@Override
	public boolean postProcessAfterInstantiation(Object bean, String beanName) {
		return true;
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		return pvs;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		return bean;
	}

	/**
	 * Create a proxy with the configured interceptors if the bean is
	 * identified as one to proxy by the subclass.
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Override
	public Object postProcessAfterInitialization(@Nullable Object bean, String beanName) {
		// 如果bean不等于null
		if (bean != null) {
			// 根据beanName或者bean的class对象获取缓存键
			Object cacheKey = getCacheKey(bean.getClass(), beanName);
			// 尝试从earlyProxyReference中根据cacheKey获取对象，并与bean进行比较，如果不相等的话，执行if里面的逻辑。

			// 在调用getEarlyBeanReference方法的时候，会将bean根据cacheKey放入到earlyProxyReferences缓存中，
			// 即当存在循环依赖的时候，获取提前暴露的未初始化完成的bean时，会调用getEarlyBeanReference方法，如果需要进行aop代理，
			// 在那一步就已经代理了。
			// 所以此时在afterInitialization阶段判断缓存的bean和此时传入的bean如果不相等的话，只有两种情况：
			// 1.不存在循环依赖，没有提前调用过该bean对应的getEarlyBeanReference方法，此时earlyProxyReferences缓存中不存在cacheKey这个键，
			// 那么会执行wrapIfNecessary根据情况选择是否要进行aop代理
			// 2.存在循环依赖，但是populateBean和initializeBean的过程中bean的引用发生了改变，bean已经和原本缓存的bean不是一个对象了
			// ，那么需要再次调用wrapIfNecessary根据情况选择是否进行aop代理

			// 如果已经在getEarlyBeanReference方法中进行过代理了，说明存在循环依赖，
			// 此时bean的代理对象或者自身已经保存在IOC容器的二级缓存earlySingletonObjects中了，
			// 在doCreateBean方法中会尝试从二级缓存中获取并赋值给最终要返回的对象，因此这里会直接返回原始bean，不做任何处理。
			if (this.earlyProxyReferences.remove(cacheKey) != bean) {
				// 调用wrapIfNecessary对bean进行包装，返回包装对象
				return wrapIfNecessary(bean, beanName, cacheKey);
			}
		}
		// 如果上述步骤没有返回，直接返回bean
		return bean;
	}


	/**
	 * Build a cache key for the given bean class and bean name.
	 * <p>Note: As of 4.2.3, this implementation does not return a concatenated
	 * class/name String anymore but rather the most efficient cache key possible:
	 * a plain bean name, prepended with {@link BeanFactory#FACTORY_BEAN_PREFIX}
	 * in case of a {@code FactoryBean}; or if no bean name specified, then the
	 * given bean {@code Class} as-is.
	 * @param beanClass the bean class
	 * @param beanName the bean name
	 * @return the cache key for the given class and name
	 */
	protected Object getCacheKey(Class<?> beanClass, @Nullable String beanName) {
		// 如果beanName不为空字符串的话
		if (StringUtils.hasLength(beanName)) {
			// 判断beanClass是否是FactoryBean类型的，如果是，在beanName前面加上&，否则直接使用beanName作为cacheKey
			return (FactoryBean.class.isAssignableFrom(beanClass) ?
					BeanFactory.FACTORY_BEAN_PREFIX + beanName : beanName);
		}
		// 如果beanName为空字符串或者null
		else {
			// 返回beanClass作为cacheKey
			return beanClass;
		}
	}

	/**
	 * Wrap the given bean if necessary, i.e. if it is eligible for being proxied.
	 * @param bean the raw bean instance
	 * @param beanName the name of the bean
	 * @param cacheKey the cache key for metadata access
	 * @return a proxy wrapping the bean, or the raw bean instance as-is
	 */
	protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
		// 如果beanName不为空字符串 并且 targetSourceBeans中包含beanName，直接返回bean
		if (StringUtils.hasLength(beanName) && this.targetSourcedBeans.contains(beanName)) {
			return bean;
		}
		// 如果advisedBeans中cacheKey对应的value是false，也直接返回bean
		if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
			return bean;
		}
		// isInfrastructureClass的逻辑是：
		// 1.如果beanClass是Advice PointCut Advisor AopInfrastructureBean这几种类型的，说明是aop的基建类
		// 2.如果类是被@Aspect注解标注的，且属性中没有ajc$开头的属性，说明是spring声明的切面类，也属于基建类

		// shouldSkip的逻辑是：
		// 1.先通过findCandidateAdvisors解析出ioc中所有的advisor类
		// 2.然后遍历这些advisor，如果发现它们是AspectJPointcutAdvisor类型的，获取它们的aspectName和beanName进行比较，
		// 如果相等，说明对应的bean是advisor类，需要跳过(这个条件基本不会触发，因为通过@Aspect注解声明的切面解析出来的advisor都是InstantiationModelAwarePointcutAdvisor类型的)
		// 3.判断beanName是否是以beanClass的全限定名 + .ORIGINAL命名的，如果是，也需要跳过

		// 只要满足上述两个方法其中一个，就不会被自动代理，将advisedBeans的缓存结果维护为false，直接返回bean
		if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName)) {
			// 将cacheKey存入advisedBeans中，并且value为false，表示不需要进行包装
			this.advisedBeans.put(cacheKey, Boolean.FALSE);
			// 并且返回原本的bean
			return bean;
		}

		// Create proxy if we have advice.
		// getAdvicesAndAdvisorsForBean的逻辑是：
		// 1.通过findCandidateAdvisors方法解析出所有候选的advisor类
		// 	1.1 AbstractAdvisorAutoProxyCreator的实现是通过BeanFactoryAdvisorRetrievalHelper类去遍历beanFactory中所有Advisor类型的bean，并添加到集合中
		// 	1.2 AnnotationAwareAspectJAutoProxyCreator的实现是通过BeanFactoryAspectJAdvisorsBuilder，根据beanFactory中被@Aspect注解标注的那些bean，
		// 	以及bean中标注了AspectJ相关注解的方法，来生成对应的advisor对象，每个符合条件的方法都会生成一个advisor对象。
		// 	具体的创建逻辑是在ReflectiveAspectJAdvisorFactory中实现的，会根据每个aspect类的工厂类AspectInstanceFactory去创建对应的advisor，
		//  它实现了getAdvisors getAdvisor getAdvice等方法，并且aspectJAdvisorsBuilder中会缓存解析过的那些aspect的beanName，
		//  并且会将每个aspect解析出来的advisors都缓存到advisorsCache中，当aspect是单例的情况；
		//	如果aspect不是单例，会缓存aspectInstanceFactory到aspectFactoryCache中，然后每次再传入aspectJAdvisorFactory的getAdvisors方法进行解析

		// 2.获取到所有候选的advisors之后，会调用findAdvisorsThatCanApply方法，选择出能够应用于对应beanClass的那些advisor，即合格的advisor。
		// 	2.1 首先判断advisor是否是IntroductionAdvisor，如果是的话，获取它持有的ClassFilter对beanClass进行筛选
		// 	2.2 然后再判断是否是PointcutAdvisor类型的，如果是，获取它的Pointcut的ClassFilter和MethodMatcher对beanClass和beanClass中的方法进行匹配
		//  2.3 如果是其他类型的Advisor，直接返回true，表示是合格的advisor

		// 3.然后对advisor集合进行扩展，主要是判断如果合格的advisor中存在AspectJ相关的advisor，那么添加一个advice是ExposeInvocationInterceptor的advisor到集合的最前面

		// 4.然后对合格的advisor集合进行排序，主要是根据Ordered接口进行排序，
		// 那些由@Aspect的bean解析出来的advisor的order值主要是通过bean上面标注的@Order注解或者实现的Ordered接口方法的返回值得到的

		// 5.然后判断合格的advisor集合，如果为空，返回DO_NOT_PROXY，然后赋值给下面的specificInterceptors数组
		Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
		// 如果查找到的advisors不为null的话
		if (specificInterceptors != DO_NOT_PROXY) {
			// 将advisedBeans中的cacheKey对应的value设置为true
			this.advisedBeans.put(cacheKey, Boolean.TRUE);
			// 创建代理对象
			Object proxy = createProxy(
					bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
			// 将proxyTypes中的cacheKey对应的value设置为代理对象的类型
			this.proxyTypes.put(cacheKey, proxy.getClass());
			// 返回代理对象
			return proxy;
		}

		// 如果查找到的advisors为null，将cacheKey=false存入advisedBeans，表示该bean不用进行代理
		this.advisedBeans.put(cacheKey, Boolean.FALSE);
		// 返回原始对象bean
		return bean;
	}

	/**
	 * Return whether the given bean class represents an infrastructure class
	 * that should never be proxied.
	 * <p>The default implementation considers Advices, Advisors and
	 * AopInfrastructureBeans as infrastructure classes.
	 * @param beanClass the class of the bean
	 * @return whether the bean represents an infrastructure class
	 * @see org.aopalliance.aop.Advice
	 * @see org.springframework.aop.Advisor
	 * @see org.springframework.aop.framework.AopInfrastructureBean
	 * @see #shouldSkip
	 */
	protected boolean isInfrastructureClass(Class<?> beanClass) {
		// 判断beanClass是否是Advice PointCut Advisor AopInfrastructureBean 这几种类型的，如果是，返回true
		boolean retVal = Advice.class.isAssignableFrom(beanClass) ||
				Pointcut.class.isAssignableFrom(beanClass) ||
				Advisor.class.isAssignableFrom(beanClass) ||
				AopInfrastructureBean.class.isAssignableFrom(beanClass);
		if (retVal && logger.isTraceEnabled()) {
			logger.trace("Did not attempt to auto-proxy infrastructure class [" + beanClass.getName() + "]");
		}
		return retVal;
	}

	/**
	 * Subclasses should override this method to return {@code true} if the
	 * given bean should not be considered for auto-proxying by this post-processor.
	 * <p>Sometimes we need to be able to avoid this happening, e.g. if it will lead to
	 * a circular reference or if the existing target instance needs to be preserved.
	 * This implementation returns {@code false} unless the bean name indicates an
	 * "original instance" according to {@code AutowireCapableBeanFactory} conventions.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @return whether to skip the given bean
	 * @see org.springframework.beans.factory.config.AutowireCapableBeanFactory#ORIGINAL_INSTANCE_SUFFIX
	 */
	protected boolean shouldSkip(Class<?> beanClass, String beanName) {
		return AutoProxyUtils.isOriginalInstance(beanName, beanClass);
	}

	/**
	 * Create a target source for bean instances. Uses any TargetSourceCreators if set.
	 * Returns {@code null} if no custom TargetSource should be used.
	 * <p>This implementation uses the "customTargetSourceCreators" property.
	 * Subclasses can override this method to use a different mechanism.
	 * @param beanClass the class of the bean to create a TargetSource for
	 * @param beanName the name of the bean
	 * @return a TargetSource for this bean
	 * @see #setCustomTargetSourceCreators
	 */
	@Nullable
	protected TargetSource getCustomTargetSource(Class<?> beanClass, String beanName) {
		// We can't create fancy target sources for directly registered singletons.
		if (this.customTargetSourceCreators != null &&
				this.beanFactory != null && this.beanFactory.containsBean(beanName)) {
			for (TargetSourceCreator tsc : this.customTargetSourceCreators) {
				TargetSource ts = tsc.getTargetSource(beanClass, beanName);
				if (ts != null) {
					// Found a matching TargetSource.
					if (logger.isTraceEnabled()) {
						logger.trace("TargetSourceCreator [" + tsc +
								"] found custom TargetSource for bean with name '" + beanName + "'");
					}
					return ts;
				}
			}
		}

		// No custom TargetSource found.
		return null;
	}

	/**
	 * Create an AOP proxy for the given bean.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @param specificInterceptors the set of interceptors that is
	 * specific to this bean (may be empty, but not null)
	 * @param targetSource the TargetSource for the proxy,
	 * already pre-configured to access the bean
	 * @return the AOP proxy for the bean
	 * @see #buildAdvisors
	 */
	protected Object createProxy(Class<?> beanClass, @Nullable String beanName,
			@Nullable Object[] specificInterceptors, TargetSource targetSource) {

		// 如果自身持有的beanFactory是属于ConfigurableListableBeanFactory的
		if (this.beanFactory instanceof ConfigurableListableBeanFactory) {
			// 将beanClass作为targetClass暴露在beanName对应的mbd的attribute中，其中属性名为AopProxyUtils类的全限定名 + originalTargetClass
			AutoProxyUtils.exposeTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName, beanClass);
		}

		// 创建一个ProxyFactory
		ProxyFactory proxyFactory = new ProxyFactory();
		// 复制自身的ProxyConfig中的属性给新创建的proxyFactory，因为AbstractAutoProxyCreator继承了ProxyConfig
		proxyFactory.copyFrom(this);

		// 如果proxyFactory的proxyTargetClass属性为true
		if (proxyFactory.isProxyTargetClass()) {
			// Explicit handling of JDK proxy targets (for introduction advice scenarios)
			// 如果beanClass是被JDK代理过的
			if (Proxy.isProxyClass(beanClass)) {
				// Must allow for introductions; can't just set interfaces to the proxy's interfaces only.
				// 将beanClass实现的接口都添加到proxyFactory中
				for (Class<?> ifc : beanClass.getInterfaces()) {
					proxyFactory.addInterface(ifc);
				}
			}
		}
		// 如果proxyTargetClass属性为false
		else {
			// No proxyTargetClass flag enforced, let's apply our default checks...
			// 检查是否需要代理targetClass，逻辑是beanName在beanFactory中对应的bd的preserveTargetClass属性为true
			if (shouldProxyTargetClass(beanClass, beanName)) {
				// 如果是的话，将proxyFactory的proxyTargetClass设置为true
				proxyFactory.setProxyTargetClass(true);
			}
			// 如果不需要代理targetClass
			else {
				// 评估需要代理的接口
				evaluateProxyInterfaces(beanClass, proxyFactory);
			}
		}

		// 调用buildAdvisors构建Advisor数组
		// 具体逻辑就是如果自身持有的interceptorNames不为空的话，将其作为beanName查找到对应的bean，作为commonInterceptor。
		// 然后将commonInterceptors和specificInterceptors整合起来，且都包装为Advisor类型返回
		Advisor[] advisors = buildAdvisors(beanName, specificInterceptors);
		// 向proxyFactory中添加这些advisors
		proxyFactory.addAdvisors(advisors);
		// 向proxyFactory中添加targetSource
		proxyFactory.setTargetSource(targetSource);
		// 然后自定义proxyFactory，是一个模板方法，留给子类去实现，默认为空
		customizeProxyFactory(proxyFactory);

		// 设置proxyFactory的freeze状态
		proxyFactory.setFrozen(this.freezeProxy);
		// 如果advisorsPreFiltered为true的话，将proxyFactory中的preFiltered设置为true。
		// 该参数的作用是在advisor链调用的时候跳过classFilter的类型检查，该参数默认为false，
		// 但是子类将其修改为了true，因为在选取advisors的时候就已经对targetClass应用过classFilter了
		if (advisorsPreFiltered()) {
			proxyFactory.setPreFiltered(true);
		}

		// 调用proxyFactory的getProxy方法构建代理对象
		return proxyFactory.getProxy(getProxyClassLoader());
	}

	/**
	 * Determine whether the given bean should be proxied with its target class rather than its interfaces.
	 * <p>Checks the {@link AutoProxyUtils#PRESERVE_TARGET_CLASS_ATTRIBUTE "preserveTargetClass" attribute}
	 * of the corresponding bean definition.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @return whether the given bean should be proxied with its target class
	 * @see AutoProxyUtils#shouldProxyTargetClass
	 */
	protected boolean shouldProxyTargetClass(Class<?> beanClass, @Nullable String beanName) {
		// 如果beanFactory是属于ConfigurableListableBeanFactory类型的 并且
		// beanFactory中包含beanName对应的bd并且bd的preserveTargetClass这个attribute的值为true
		return (this.beanFactory instanceof ConfigurableListableBeanFactory &&
				AutoProxyUtils.shouldProxyTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName));
	}

	/**
	 * Return whether the Advisors returned by the subclass are pre-filtered
	 * to match the bean's target class already, allowing the ClassFilter check
	 * to be skipped when building advisors chains for AOP invocations.
	 * <p>Default is {@code false}. Subclasses may override this if they
	 * will always return pre-filtered Advisors.
	 * @return whether the Advisors are pre-filtered
	 * @see #getAdvicesAndAdvisorsForBean
	 * @see org.springframework.aop.framework.Advised#setPreFiltered
	 */
	protected boolean advisorsPreFiltered() {
		return false;
	}

	/**
	 * Determine the advisors for the given bean, including the specific interceptors
	 * as well as the common interceptor, all adapted to the Advisor interface.
	 * @param beanName the name of the bean
	 * @param specificInterceptors the set of interceptors that is
	 * specific to this bean (may be empty, but not null)
	 * @return the list of Advisors for the given bean
	 */
	protected Advisor[] buildAdvisors(@Nullable String beanName, @Nullable Object[] specificInterceptors) {
		// Handle prototypes correctly...
		// 解析自身持有的interceptorNames，将其转换为Advisor类型的对象，这些是通用的interceptor，默认是没有的
		Advisor[] commonInterceptors = resolveInterceptorNames();

		List<Object> allInterceptors = new ArrayList<>();
		// 如果传入的specificInterceptors不为null
		if (specificInterceptors != null) {
			// 并且长度大于0
			if (specificInterceptors.length > 0) {
				// specificInterceptors may equals PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
				// 将其添加进allInterceptors集合中
				allInterceptors.addAll(Arrays.asList(specificInterceptors));
			}
			// 如果commonInterceptors的长度大于0
			if (commonInterceptors.length > 0) {
				// 如果applyCommonInterceptorsFirst属性为true，表示先应用通用的interceptor，因此将commonInterceptors添加到集合的最前面
				if (this.applyCommonInterceptorsFirst) {
					allInterceptors.addAll(0, Arrays.asList(commonInterceptors));
				}
				// 否则将commonInterceptors添加到集合的最后面
				else {
					allInterceptors.addAll(Arrays.asList(commonInterceptors));
				}
			}
		}
		if (logger.isTraceEnabled()) {
			int nrOfCommonInterceptors = commonInterceptors.length;
			int nrOfSpecificInterceptors = (specificInterceptors != null ? specificInterceptors.length : 0);
			logger.trace("Creating implicit proxy for bean '" + beanName + "' with " + nrOfCommonInterceptors +
					" common interceptors and " + nrOfSpecificInterceptors + " specific interceptors");
		}

		// 创建一个Advisor数组
		Advisor[] advisors = new Advisor[allInterceptors.size()];
		// 遍历allInterceptors
		for (int i = 0; i < allInterceptors.size(); i++) {
			// 将Advice类型的interceptor包装成Advisor类型的返回
			advisors[i] = this.advisorAdapterRegistry.wrap(allInterceptors.get(i));
		}
		return advisors;
	}

	/**
	 * Resolves the specified interceptor names to Advisor objects.
	 * @see #setInterceptorNames
	 */
	private Advisor[] resolveInterceptorNames() {
		BeanFactory bf = this.beanFactory;
		ConfigurableBeanFactory cbf = (bf instanceof ConfigurableBeanFactory ? (ConfigurableBeanFactory) bf : null);
		List<Advisor> advisors = new ArrayList<>();
		// 遍历持有的interceptorNames数组
		for (String beanName : this.interceptorNames) {
			// 如果cbf为null 或者 对应的beanName不在创建中
			if (cbf == null || !cbf.isCurrentlyInCreation(beanName)) {
				Assert.state(bf != null, "BeanFactory required for resolving interceptor names");
				// 调用getBean根据beanName获取bean
				Object next = bf.getBean(beanName);
				// 将其适配为Advisor类型的对象，添加进集合中
				advisors.add(this.advisorAdapterRegistry.wrap(next));
			}
		}
		return advisors.toArray(new Advisor[0]);
	}

	/**
	 * Subclasses may choose to implement this: for example,
	 * to change the interfaces exposed.
	 * <p>The default implementation is empty.
	 * @param proxyFactory a ProxyFactory that is already configured with
	 * TargetSource and interfaces and will be used to create the proxy
	 * immediately after this method returns
	 */
	protected void customizeProxyFactory(ProxyFactory proxyFactory) {
	}


	/**
	 * Return whether the given bean is to be proxied, what additional
	 * advices (e.g. AOP Alliance interceptors) and advisors to apply.
	 * @param beanClass the class of the bean to advise
	 * @param beanName the name of the bean
	 * @param customTargetSource the TargetSource returned by the
	 * {@link #getCustomTargetSource} method: may be ignored.
	 * Will be {@code null} if no custom target source is in use.
	 * @return an array of additional interceptors for the particular bean;
	 * or an empty array if no additional interceptors but just the common ones;
	 * or {@code null} if no proxy at all, not even with the common interceptors.
	 * See constants DO_NOT_PROXY and PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS.
	 * @throws BeansException in case of errors
	 * @see #DO_NOT_PROXY
	 * @see #PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
	 */
	@Nullable
	protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName,
			@Nullable TargetSource customTargetSource) throws BeansException;

}
