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

package org.springframework.context.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.scope.ScopedProxyFactoryBean;
import org.springframework.asm.Type;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.SimpleInstantiationStrategy;
import org.springframework.cglib.core.ClassGenerator;
import org.springframework.cglib.core.ClassLoaderAwareGeneratorStrategy;
import org.springframework.cglib.core.Constants;
import org.springframework.cglib.core.SpringNamingPolicy;
import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.CallbackFilter;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.cglib.proxy.NoOp;
import org.springframework.cglib.transform.ClassEmitterTransformer;
import org.springframework.cglib.transform.TransformingClassGenerator;
import org.springframework.lang.Nullable;
import org.springframework.objenesis.ObjenesisException;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Enhances {@link Configuration} classes by generating a CGLIB subclass which
 * interacts with the Spring container to respect bean scoping semantics for
 * {@code @Bean} methods. Each such {@code @Bean} method will be overridden in
 * the generated subclass, only delegating to the actual {@code @Bean} method
 * implementation if the container actually requests the construction of a new
 * instance. Otherwise, a call to such an {@code @Bean} method serves as a
 * reference back to the container, obtaining the corresponding bean by name.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.0
 * @see #enhance
 * @see ConfigurationClassPostProcessor
 */
class ConfigurationClassEnhancer {

	// The callbacks to use. Note that these callbacks must be stateless.
	private static final Callback[] CALLBACKS = new Callback[] {
			new BeanMethodInterceptor(),
			new BeanFactoryAwareMethodInterceptor(),
			NoOp.INSTANCE
	};

	private static final ConditionalCallbackFilter CALLBACK_FILTER = new ConditionalCallbackFilter(CALLBACKS);

	private static final String BEAN_FACTORY_FIELD = "$$beanFactory";


	private static final Log logger = LogFactory.getLog(ConfigurationClassEnhancer.class);

	private static final SpringObjenesis objenesis = new SpringObjenesis();


	/**
	 * Loads the specified class and generates a CGLIB subclass of it equipped with
	 * container-aware callbacks capable of respecting scoping and other bean semantics.
	 * @return the enhanced subclass
	 */
	public Class<?> enhance(Class<?> configClass, @Nullable ClassLoader classLoader) {
		// 如果要被代理的类已经是属于EnhancedConfiguration类型了，那么直接返回。说明它已经被增强过了，
		// 产生的原因可能是容器中注册了多个ConfigurationClassPostProcessor
		if (EnhancedConfiguration.class.isAssignableFrom(configClass)) {
			if (logger.isDebugEnabled()) {
				logger.debug(String.format("Ignoring request to enhance %s as it has " +
						"already been enhanced. This usually indicates that more than one " +
						"ConfigurationClassPostProcessor has been registered (e.g. via " +
						"<context:annotation-config>). This is harmless, but you may " +
						"want check your configuration and remove one CCPP if possible",
						configClass.getName()));
			}
			return configClass;
		}
		// 对configClass进行cglib代理
		Class<?> enhancedClass = createClass(newEnhancer(configClass, classLoader));
		if (logger.isTraceEnabled()) {
			logger.trace(String.format("Successfully enhanced %s; enhanced class name is: %s",
					configClass.getName(), enhancedClass.getName()));
		}
		// 返回代理类
		return enhancedClass;
	}

	/**
	 * Creates a new CGLIB {@link Enhancer} instance.
	 */
	private Enhancer newEnhancer(Class<?> configSuperClass, @Nullable ClassLoader classLoader) {
		// 创建一个Enhancer
		Enhancer enhancer = new Enhancer();
		// 将父类设置为要被代理的类
		enhancer.setSuperclass(configSuperClass);
		// 设置要实现的接口EnhancedConfiguration，EnhancedConfiguration继承了BeanFactoryAware接口
		enhancer.setInterfaces(new Class<?>[] {EnhancedConfiguration.class});
		// 设置enhancer的useFactory属性为false，在生成类文件的时候就不会实现cglib的Factory接口，也不会生成Factory接口的相应方法
		enhancer.setUseFactory(false);
		// 设置命名策略为SpringNamingPolicy，该命名策略tag会变成BySpringCGLIB
		enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);
		// 设置GeneratorStrategy为BeanFactoryAwareGeneratorStrategy
		// 用于在类生成结束的时候，向类中声明一个public BeanFactory $$beanFactory类型的字段
		enhancer.setStrategy(new BeanFactoryAwareGeneratorStrategy(classLoader));
		// 设置CallbackFilter和CallbackTypes
		enhancer.setCallbackFilter(CALLBACK_FILTER);
		enhancer.setCallbackTypes(CALLBACK_FILTER.getCallbackTypes());
		return enhancer;
	}

	/**
	 * Uses enhancer to generate a subclass of superclass,
	 * ensuring that callbacks are registered for the new subclass.
	 */
	private Class<?> createClass(Enhancer enhancer) {
		// 创建出代理类
		Class<?> subclass = enhancer.createClass();
		// Registering callbacks statically (as opposed to thread-local)
		// is critical for usage in an OSGi environment (SPR-5932)...
		// 调用代理类的public static void CGLIB$SET_STATIC_CALLBACKS方法将callbacks数组设置到
		// 代理类的private static final CALLBACK[] CGLIB$STATIC_CALLBACKS属性中
		// 这样在每次实例化代理类的时候，构造方法里面都会调用private static final void CGLIB$BIND_CALLBACKS方法，
		// 将CGLIB$STATIC_CALLBACKS 或者 CGLIB$THREAD_CALLBACKS里面的callbacks数组绑定到自身对应的实例属性private Callback CGLIB$CALLBACK_i上
		Enhancer.registerStaticCallbacks(subclass, CALLBACKS);
		return subclass;
	}


	/**
	 * Marker interface to be implemented by all @Configuration CGLIB subclasses.
	 * Facilitates idempotent behavior for {@link ConfigurationClassEnhancer#enhance}
	 * through checking to see if candidate classes are already assignable to it, e.g.
	 * have already been enhanced.
	 * <p>Also extends {@link BeanFactoryAware}, as all enhanced {@code @Configuration}
	 * classes require access to the {@link BeanFactory} that created them.
	 * <p>Note that this interface is intended for framework-internal use only, however
	 * must remain public in order to allow access to subclasses generated from other
	 * packages (i.e. user code).
	 */
	public interface EnhancedConfiguration extends BeanFactoryAware {
	}


	/**
	 * Conditional {@link Callback}.
	 * @see ConditionalCallbackFilter
	 */
	private interface ConditionalCallback extends Callback {

		boolean isMatch(Method candidateMethod);
	}


	/**
	 * A {@link CallbackFilter} that works by interrogating {@link Callback Callbacks} in the order
	 * that they are defined via {@link ConditionalCallback}.
	 */
	private static class ConditionalCallbackFilter implements CallbackFilter {

		private final Callback[] callbacks;

		private final Class<?>[] callbackTypes;

		public ConditionalCallbackFilter(Callback[] callbacks) {
			this.callbacks = callbacks;
			this.callbackTypes = new Class<?>[callbacks.length];
			for (int i = 0; i < callbacks.length; i++) {
				this.callbackTypes[i] = callbacks[i].getClass();
			}
		}

		@Override
		public int accept(Method method) {
			// 遍历持有的callback集合
			for (int i = 0; i < this.callbacks.length; i++) {
				Callback callback = this.callbacks[i];
				// 如果callback不是ConditionalCallback类型的 或者 是ConditionalCallback类型但是和方法匹配，返回当前callback的index
				if (!(callback instanceof ConditionalCallback) || ((ConditionalCallback) callback).isMatch(method)) {
					return i;
				}
			}
			throw new IllegalStateException("No callback available for method " + method.getName());
		}

		public Class<?>[] getCallbackTypes() {
			return this.callbackTypes;
		}
	}


	/**
	 * Custom extension of CGLIB's DefaultGeneratorStrategy, introducing a {@link BeanFactory} field.
	 * Also exposes the application ClassLoader as thread context ClassLoader for the time of
	 * class generation (in order for ASM to pick it up when doing common superclass resolution).
	 */
	private static class BeanFactoryAwareGeneratorStrategy extends
			ClassLoaderAwareGeneratorStrategy {

		public BeanFactoryAwareGeneratorStrategy(@Nullable ClassLoader classLoader) {
			super(classLoader);
		}

		@Override
		// 重写GeneratorStrategy的对ClassGenerator的transform方法
		protected ClassGenerator transform(ClassGenerator cg) throws Exception {
			// 声明一个ClassEmitterTransformer，实现了ClassVisitor接口，也是一个ClassVisitor
			ClassEmitterTransformer transformer = new ClassEmitterTransformer() {
				@Override
				public void end_class() {
					declare_field(Constants.ACC_PUBLIC, BEAN_FACTORY_FIELD, Type.getType(BeanFactory.class), null);
					super.end_class();
				}
			};
			// 然后根据传入的ClassGenerator 和 刚才声明的transformer，封装成一个TransformingClassGenerator返回。
			// 该generator的逻辑就是将generate方法传入的classVisitor，包装到transformer里面，其中transformer自定义了一部分逻辑，
			// (这里自定义的逻辑就是在类声明完成后的end_class方法中声明一个public BeanFactory $$beanFactory的字段)
			// 然后再调用原本的classGenerator的generate方法，将包装完成后的transformer传入
			return new TransformingClassGenerator(cg, transformer);
		}

	}


	/**
	 * Intercepts the invocation of any {@link BeanFactoryAware#setBeanFactory(BeanFactory)} on
	 * {@code @Configuration} class instances for the purpose of recording the {@link BeanFactory}.
	 * @see EnhancedConfiguration
	 */
	private static class BeanFactoryAwareMethodInterceptor implements MethodInterceptor, ConditionalCallback {

		@Override
		@Nullable
		public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
			// 获取代理对象中的$$beanFactory字段
			Field field = ReflectionUtils.findField(obj.getClass(), BEAN_FACTORY_FIELD);
			Assert.state(field != null, "Unable to find generated BeanFactory field");
			// 然后将第一个参数设置进该字段中，第一个参数必定是BeanFactory
			field.set(obj, args[0]);

			// Does the actual (non-CGLIB) superclass implement BeanFactoryAware?
			// If so, call its setBeanFactory() method. If not, just exit.
			// 如果代理类的父类，即targetClass也实现了BeanFactoryAware接口，调用父类的setBeanFactory方法
			if (BeanFactoryAware.class.isAssignableFrom(ClassUtils.getUserClass(obj.getClass().getSuperclass()))) {
				return proxy.invokeSuper(obj, args);
			}
			return null;
		}

		@Override
		public boolean isMatch(Method candidateMethod) {
			// 判断方法是否是setBeanFactory
			return isSetBeanFactory(candidateMethod);
		}

		public static boolean isSetBeanFactory(Method candidateMethod) {
			// 如果方法名是setBeanFactory 且 方法参数只有1个 并且 参数类型是BeanFactory 并且 方法的声明类是BeanFactoryAware，
			// 如果满足上述条件，返回true，说明候选方法是setBeanFactory
			return (candidateMethod.getName().equals("setBeanFactory") &&
					candidateMethod.getParameterCount() == 1 &&
					BeanFactory.class == candidateMethod.getParameterTypes()[0] &&
					BeanFactoryAware.class.isAssignableFrom(candidateMethod.getDeclaringClass()));
		}
	}


	/**
	 * Intercepts the invocation of any {@link Bean}-annotated methods in order to ensure proper
	 * handling of bean semantics such as scoping and AOP proxying.
	 * @see Bean
	 * @see ConfigurationClassEnhancer
	 */
	private static class BeanMethodInterceptor implements MethodInterceptor, ConditionalCallback {

		/**
		 * Enhance a {@link Bean @Bean} method to check the supplied BeanFactory for the
		 * existence of this bean object.
		 * @throws Throwable as a catch-all for any exception that may be thrown when invoking the
		 * super implementation of the proxied method i.e., the actual {@code @Bean} method
		 */
		@Override
		@Nullable
		public Object intercept(Object enhancedConfigInstance, Method beanMethod, Object[] beanMethodArgs,
					MethodProxy cglibMethodProxy) throws Throwable {

			// 获取到代理对象持有的$$beanFactory字段
			ConfigurableBeanFactory beanFactory = getBeanFactory(enhancedConfigInstance);
			// 根据@Bean注解获取对应的beanName，默认为@Bean注解的name属性的第一个元素，如果不存在，那么使用@Bean标注的方法的方法名
			String beanName = BeanAnnotationHelper.determineBeanNameFor(beanMethod);

			// Determine whether this bean is a scoped-proxy
			// 判断该bean是否是scopedProxy的，即判断方法上是否标注了@Scope注解并且scopedProxyMode不为No
			// 如果是true，执行以下的逻辑
			if (BeanAnnotationHelper.isScopedProxy(beanMethod)) {
				// 将beanName前面加上scopedTarget. 形成scopedBeanName
				String scopedBeanName = ScopedProxyCreator.getTargetBeanName(beanName);
				// 如果对应的scopedBeanName正在创建中，那么将beanName设置为scopedBeanName
				if (beanFactory.isCurrentlyInCreation(scopedBeanName)) {
					beanName = scopedBeanName;
				}
			}

			// To handle the case of an inter-bean method reference, we must explicitly check the
			// container for already cached instances.

			// First, check to see if the requested bean is a FactoryBean. If so, create a subclass
			// proxy that intercepts calls to getObject() and returns any cached bean instance.
			// This ensures that the semantics of calling a FactoryBean from within @Bean methods
			// is the same as that of referring to a FactoryBean within XML. See SPR-6602.
			// 如果beanName对应的bean是FactoryBean类型的，那么需要返回一个FactoryBean的代理对象
			if (factoryContainsBean(beanFactory, BeanFactory.FACTORY_BEAN_PREFIX + beanName) &&
					factoryContainsBean(beanFactory, beanName)) {
				// 调用getBean方法从容器中获取FactoryBean对象
				Object factoryBean = beanFactory.getBean(BeanFactory.FACTORY_BEAN_PREFIX + beanName);
				// 如果它是ScopedProxyFactoryBean类型的，不做任何处理
				if (factoryBean instanceof ScopedProxyFactoryBean) {
					// Scoped proxy factory beans are a special case and should not be further proxied
				}
				// 否则创建代理对象进行增强
				else {
					// It is a candidate FactoryBean - go ahead with enhancement
					return enhanceFactoryBean(factoryBean, beanMethod.getReturnType(), beanFactory, beanName);
				}
			}

			// 如果当前beanMethod是正在调用的FactoryMethod，说明正在通过getBean方法调用实际的beanMethod用于创建bean
			if (isCurrentlyInvokedFactoryMethod(beanMethod)) {
				// The factory is calling the bean method in order to instantiate and register the bean
				// (i.e. via a getBean() call) -> invoke the super implementation of the method to actually
				// create the bean instance.
				if (logger.isInfoEnabled() &&
						BeanFactoryPostProcessor.class.isAssignableFrom(beanMethod.getReturnType())) {
					logger.info(String.format("@Bean method %s.%s is non-static and returns an object " +
									"assignable to Spring's BeanFactoryPostProcessor interface. This will " +
									"result in a failure to process annotations such as @Autowired, " +
									"@Resource and @PostConstruct within the method's declaring " +
									"@Configuration class. Add the 'static' modifier to this method to avoid " +
									"these container lifecycle issues; see @Bean javadoc for complete details.",
							beanMethod.getDeclaringClass().getSimpleName(), beanMethod.getName()));
				}
				return cglibMethodProxy.invokeSuper(enhancedConfigInstance, beanMethodArgs);
			}

			return resolveBeanReference(beanMethod, beanMethodArgs, beanFactory, beanName);
		}

		private Object resolveBeanReference(Method beanMethod, Object[] beanMethodArgs,
				ConfigurableBeanFactory beanFactory, String beanName) {

			// The user (i.e. not the factory) is requesting this bean through a call to
			// the bean method, direct or indirect. The bean may have already been marked
			// as 'in creation' in certain autowiring scenarios; if so, temporarily set
			// the in-creation status to false in order to avoid an exception.
			boolean alreadyInCreation = beanFactory.isCurrentlyInCreation(beanName);
			try {
				if (alreadyInCreation) {
					beanFactory.setCurrentlyInCreation(beanName, false);
				}
				boolean useArgs = !ObjectUtils.isEmpty(beanMethodArgs);
				if (useArgs && beanFactory.isSingleton(beanName)) {
					// Stubbed null arguments just for reference purposes,
					// expecting them to be autowired for regular singleton references?
					// A safe assumption since @Bean singleton arguments cannot be optional...
					for (Object arg : beanMethodArgs) {
						if (arg == null) {
							useArgs = false;
							break;
						}
					}
				}
				Object beanInstance = (useArgs ? beanFactory.getBean(beanName, beanMethodArgs) :
						beanFactory.getBean(beanName));
				if (!ClassUtils.isAssignableValue(beanMethod.getReturnType(), beanInstance)) {
					// Detect package-protected NullBean instance through equals(null) check
					if (beanInstance.equals(null)) {
						if (logger.isDebugEnabled()) {
							logger.debug(String.format("@Bean method %s.%s called as bean reference " +
									"for type [%s] returned null bean; resolving to null value.",
									beanMethod.getDeclaringClass().getSimpleName(), beanMethod.getName(),
									beanMethod.getReturnType().getName()));
						}
						beanInstance = null;
					}
					else {
						String msg = String.format("@Bean method %s.%s called as bean reference " +
								"for type [%s] but overridden by non-compatible bean instance of type [%s].",
								beanMethod.getDeclaringClass().getSimpleName(), beanMethod.getName(),
								beanMethod.getReturnType().getName(), beanInstance.getClass().getName());
						try {
							BeanDefinition beanDefinition = beanFactory.getMergedBeanDefinition(beanName);
							msg += " Overriding bean of same name declared in: " + beanDefinition.getResourceDescription();
						}
						catch (NoSuchBeanDefinitionException ex) {
							// Ignore - simply no detailed message then.
						}
						throw new IllegalStateException(msg);
					}
				}
				Method currentlyInvoked = SimpleInstantiationStrategy.getCurrentlyInvokedFactoryMethod();
				if (currentlyInvoked != null) {
					String outerBeanName = BeanAnnotationHelper.determineBeanNameFor(currentlyInvoked);
					beanFactory.registerDependentBean(beanName, outerBeanName);
				}
				return beanInstance;
			}
			finally {
				if (alreadyInCreation) {
					beanFactory.setCurrentlyInCreation(beanName, true);
				}
			}
		}

		@Override
		public boolean isMatch(Method candidateMethod) {
			// 如果候选方法的声明类不是Object类型 并且 候选方法不是setBeanFactory方法 并且 候选方法被@Bean注解标注了，返回true
			return (candidateMethod.getDeclaringClass() != Object.class &&
					!BeanFactoryAwareMethodInterceptor.isSetBeanFactory(candidateMethod) &&
					BeanAnnotationHelper.isBeanAnnotated(candidateMethod));
		}

		private ConfigurableBeanFactory getBeanFactory(Object enhancedConfigInstance) {
			// 获取到代理类中的$$beanFactory字段的反射对象
			Field field = ReflectionUtils.findField(enhancedConfigInstance.getClass(), BEAN_FACTORY_FIELD);
			Assert.state(field != null, "Unable to find generated bean factory field");
			// 然后通过反射获取到字段引用指向的BeanFactory字段
			Object beanFactory = ReflectionUtils.getField(field, enhancedConfigInstance);
			// 判断beanFactory不为null 和 其具体类型
			Assert.state(beanFactory != null, "BeanFactory has not been injected into @Configuration class");
			Assert.state(beanFactory instanceof ConfigurableBeanFactory,
					"Injected BeanFactory is not a ConfigurableBeanFactory");
			// 返回beanFactory
			return (ConfigurableBeanFactory) beanFactory;
		}

		/**
		 * Check the BeanFactory to see whether the bean named <var>beanName</var> already
		 * exists. Accounts for the fact that the requested bean may be "in creation", i.e.:
		 * we're in the middle of servicing the initial request for this bean. From an enhanced
		 * factory method's perspective, this means that the bean does not actually yet exist,
		 * and that it is now our job to create it for the first time by executing the logic
		 * in the corresponding factory method.
		 * <p>Said another way, this check repurposes
		 * {@link ConfigurableBeanFactory#isCurrentlyInCreation(String)} to determine whether
		 * the container is calling this method or the user is calling this method.
		 * @param beanName name of bean to check for
		 * @return whether <var>beanName</var> already exists in the factory
		 */
		private boolean factoryContainsBean(ConfigurableBeanFactory beanFactory, String beanName) {
			return (beanFactory.containsBean(beanName) && !beanFactory.isCurrentlyInCreation(beanName));
		}

		/**
		 * Check whether the given method corresponds to the container's currently invoked
		 * factory method. Compares method name and parameter types only in order to work
		 * around a potential problem with covariant return types (currently only known
		 * to happen on Groovy classes).
		 */
		private boolean isCurrentlyInvokedFactoryMethod(Method method) {
			Method currentlyInvoked = SimpleInstantiationStrategy.getCurrentlyInvokedFactoryMethod();
			return (currentlyInvoked != null && method.getName().equals(currentlyInvoked.getName()) &&
					Arrays.equals(method.getParameterTypes(), currentlyInvoked.getParameterTypes()));
		}

		/**
		 * Create a subclass proxy that intercepts calls to getObject(), delegating to the current BeanFactory
		 * instead of creating a new instance. These proxies are created only when calling a FactoryBean from
		 * within a Bean method, allowing for proper scoping semantics even when working against the FactoryBean
		 * instance directly. If a FactoryBean instance is fetched through the container via &-dereferencing,
		 * it will not be proxied. This too is aligned with the way XML configuration works.
		 */
		private Object enhanceFactoryBean(Object factoryBean, Class<?> exposedType,
				ConfigurableBeanFactory beanFactory, String beanName) {

			try {
				Class<?> clazz = factoryBean.getClass();
				boolean finalClass = Modifier.isFinal(clazz.getModifiers());
				boolean finalMethod = Modifier.isFinal(clazz.getMethod("getObject").getModifiers());
				if (finalClass || finalMethod) {
					if (exposedType.isInterface()) {
						if (logger.isTraceEnabled()) {
							logger.trace("Creating interface proxy for FactoryBean '" + beanName + "' of type [" +
									clazz.getName() + "] for use within another @Bean method because its " +
									(finalClass ? "implementation class" : "getObject() method") +
									" is final: Otherwise a getObject() call would not be routed to the factory.");
						}
						return createInterfaceProxyForFactoryBean(factoryBean, exposedType, beanFactory, beanName);
					}
					else {
						if (logger.isDebugEnabled()) {
							logger.debug("Unable to proxy FactoryBean '" + beanName + "' of type [" +
									clazz.getName() + "] for use within another @Bean method because its " +
									(finalClass ? "implementation class" : "getObject() method") +
									" is final: A getObject() call will NOT be routed to the factory. " +
									"Consider declaring the return type as a FactoryBean interface.");
						}
						return factoryBean;
					}
				}
			}
			catch (NoSuchMethodException ex) {
				// No getObject() method -> shouldn't happen, but as long as nobody is trying to call it...
			}

			return createCglibProxyForFactoryBean(factoryBean, beanFactory, beanName);
		}

		private Object createInterfaceProxyForFactoryBean(Object factoryBean, Class<?> interfaceType,
				ConfigurableBeanFactory beanFactory, String beanName) {

			return Proxy.newProxyInstance(
					factoryBean.getClass().getClassLoader(), new Class<?>[] {interfaceType},
					(proxy, method, args) -> {
						if (method.getName().equals("getObject") && args == null) {
							return beanFactory.getBean(beanName);
						}
						return ReflectionUtils.invokeMethod(method, factoryBean, args);
					});
		}

		private Object createCglibProxyForFactoryBean(Object factoryBean,
				ConfigurableBeanFactory beanFactory, String beanName) {

			Enhancer enhancer = new Enhancer();
			enhancer.setSuperclass(factoryBean.getClass());
			enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);
			enhancer.setCallbackType(MethodInterceptor.class);

			// Ideally create enhanced FactoryBean proxy without constructor side effects,
			// analogous to AOP proxy creation in ObjenesisCglibAopProxy...
			Class<?> fbClass = enhancer.createClass();
			Object fbProxy = null;

			if (objenesis.isWorthTrying()) {
				try {
					fbProxy = objenesis.newInstance(fbClass, enhancer.getUseCache());
				}
				catch (ObjenesisException ex) {
					logger.debug("Unable to instantiate enhanced FactoryBean using Objenesis, " +
							"falling back to regular construction", ex);
				}
			}

			if (fbProxy == null) {
				try {
					fbProxy = ReflectionUtils.accessibleConstructor(fbClass).newInstance();
				}
				catch (Throwable ex) {
					throw new IllegalStateException("Unable to instantiate enhanced FactoryBean using Objenesis, " +
							"and regular FactoryBean instantiation via default constructor fails as well", ex);
				}
			}

			((Factory) fbProxy).setCallback(0, (MethodInterceptor) (obj, method, args, proxy) -> {
				if (method.getName().equals("getObject") && args.length == 0) {
					return beanFactory.getBean(beanName);
				}
				return proxy.invoke(factoryBean, args);
			});

			return fbProxy;
		}
	}

}
