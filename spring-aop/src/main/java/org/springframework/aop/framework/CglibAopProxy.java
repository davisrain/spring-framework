/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.AopInvocationException;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.RawTargetAccess;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.cglib.core.ClassLoaderAwareGeneratorStrategy;
import org.springframework.cglib.core.CodeGenerationException;
import org.springframework.cglib.core.SpringNamingPolicy;
import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.CallbackFilter;
import org.springframework.cglib.proxy.Dispatcher;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.cglib.proxy.NoOp;
import org.springframework.core.KotlinDetector;
import org.springframework.core.SmartClassLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * CGLIB-based {@link AopProxy} implementation for the Spring AOP framework.
 *
 * <p>Objects of this type should be obtained through proxy factories,
 * configured by an {@link AdvisedSupport} object. This class is internal
 * to Spring's AOP framework and need not be used directly by client code.
 *
 * <p>{@link DefaultAopProxyFactory} will automatically create CGLIB-based
 * proxies if necessary, for example in case of proxying a target class
 * (see the {@link DefaultAopProxyFactory attendant javadoc} for details).
 *
 * <p>Proxies created using this class are thread-safe if the underlying
 * (target) class is thread-safe.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @author Chris Beams
 * @author Dave Syer
 * @see org.springframework.cglib.proxy.Enhancer
 * @see AdvisedSupport#setProxyTargetClass
 * @see DefaultAopProxyFactory
 */
@SuppressWarnings("serial")
class CglibAopProxy implements AopProxy, Serializable {

	// Constants for CGLIB callback array indices
	private static final int AOP_PROXY = 0;
	private static final int INVOKE_TARGET = 1;
	private static final int NO_OVERRIDE = 2;
	private static final int DISPATCH_TARGET = 3;
	private static final int DISPATCH_ADVISED = 4;
	private static final int INVOKE_EQUALS = 5;
	private static final int INVOKE_HASHCODE = 6;


	/** Logger available to subclasses; static to optimize serialization. */
	protected static final Log logger = LogFactory.getLog(CglibAopProxy.class);

	/** Keeps track of the Classes that we have validated for final methods. */
	// 用于缓存对应的class是否进行过final方法的校验
	private static final Map<Class<?>, Boolean> validatedClasses = new WeakHashMap<>();


	/** The configuration used to configure this proxy. */
	// 配置这个proxy对象的配置类，默认就是ProxyFactory对象，因为它继承了AdvisedSupport类。
	// 里面持有了代理需要的interfaces 和 advisors 以及targetSource等关键信息
	protected final AdvisedSupport advised;

	@Nullable
	protected Object[] constructorArgs;

	@Nullable
	protected Class<?>[] constructorArgTypes;

	/** Dispatcher used for methods on Advised. */
	private final transient AdvisedDispatcher advisedDispatcher;

	// 当targetSource是static 并且 advised配置已经frozen的情况下，会为targetClass里面的每个方法创建一个fixedInterceptor
	// key为对应的方法，value是对应的fixedInterceptor在整个fixedInterceptorList里面的index，value + fixedInterceptorOffset就是
	// 实际在callback数组里面的index。
	private transient Map<Method, Integer> fixedInterceptorMap = Collections.emptyMap();

	// fixedInterceptorMap里面的fixedInterceptor在callback数组里的index偏移量
	private transient int fixedInterceptorOffset;


	/**
	 * Create a new CglibAopProxy for the given AOP configuration.
	 * @param config the AOP configuration as AdvisedSupport object
	 * @throws AopConfigException if the config is invalid. We try to throw an informative
	 * exception in this case, rather than let a mysterious failure happen later.
	 */
	public CglibAopProxy(AdvisedSupport config) throws AopConfigException {
		Assert.notNull(config, "AdvisedSupport must not be null");
		if (config.getAdvisors().length == 0 && config.getTargetSource() == AdvisedSupport.EMPTY_TARGET_SOURCE) {
			throw new AopConfigException("No advisors and no TargetSource specified");
		}
		// 将advisedSupport对象赋值给自身属性
		this.advised = config;
		// 创建一个cglib的Dispatcher类型的Callback，
		// 用于将代理对象的Advised接口的方法调用转发到advised对象去执行
		this.advisedDispatcher = new AdvisedDispatcher(this.advised);
	}

	/**
	 * Set constructor arguments to use for creating the proxy.
	 * @param constructorArgs the constructor argument values
	 * @param constructorArgTypes the constructor argument types
	 */
	public void setConstructorArguments(@Nullable Object[] constructorArgs, @Nullable Class<?>[] constructorArgTypes) {
		if (constructorArgs == null || constructorArgTypes == null) {
			throw new IllegalArgumentException("Both 'constructorArgs' and 'constructorArgTypes' need to be specified");
		}
		if (constructorArgs.length != constructorArgTypes.length) {
			throw new IllegalArgumentException("Number of 'constructorArgs' (" + constructorArgs.length +
					") must match number of 'constructorArgTypes' (" + constructorArgTypes.length + ")");
		}
		this.constructorArgs = constructorArgs;
		this.constructorArgTypes = constructorArgTypes;
	}


	@Override
	public Object getProxy() {
		return getProxy(null);
	}

	@Override
	public Object getProxy(@Nullable ClassLoader classLoader) {
		if (logger.isTraceEnabled()) {
			logger.trace("Creating CGLIB proxy: " + this.advised.getTargetSource());
		}

		try {
			// 获取要代理的targetClass赋值给rootClass
			Class<?> rootClass = this.advised.getTargetClass();
			Assert.state(rootClass != null, "Target class must be available for creating a CGLIB proxy");

			// 先将rootClass赋值给proxySuperClass，表示最终要代理的父类
			Class<?> proxySuperClass = rootClass;
			// 获取rootClass的全限定名，如果发现名称中包含$$符号，说明是被cglib代理过的类
			if (rootClass.getName().contains(ClassUtils.CGLIB_CLASS_SEPARATOR)) {
				// 那么获取rootClass的父类，将其赋值给proxySuperClass
				proxySuperClass = rootClass.getSuperclass();
				// 然后获取rootClass实现的接口
				Class<?>[] additionalInterfaces = rootClass.getInterfaces();
				// 将接口都添加到advisedSupport中让其持有
				for (Class<?> additionalInterface : additionalInterfaces) {
					this.advised.addInterface(additionalInterface);
				}
			}

			// Validate the class, writing log messages as necessary.
			// 验证这个即将被代理的类。
			// 1.如果存在final修饰的方法，无法被代理，且无法路由到被代理的方法，因为不存在父类实例的引用；
			// 2.如果类加载器和proxySuperClass的类加载器是不同的，那么可能会导致无法访问到proxySuperClass的package-visible的方法，导致无法代理该方法。
			validateClassIfNecessary(proxySuperClass, classLoader);

			// Configure CGLIB Enhancer...
			// 配置CGLIB的enhancer

			// 创建enhancer
			Enhancer enhancer = createEnhancer();
			// 如果类加载器不为null
			if (classLoader != null) {
				// 将类加载器设置进enhancer张
				enhancer.setClassLoader(classLoader);
				// 如果类加载器是SmartClassLoader类型的，且isClassReloadable返回true
				if (classLoader instanceof SmartClassLoader &&
						((SmartClassLoader) classLoader).isClassReloadable(proxySuperClass)) {
					// 设置enhancer的useCache为false
					enhancer.setUseCache(false);
				}
			}
			// 设置enhancer的superClass为proxySuperClass
			enhancer.setSuperclass(proxySuperClass);
			// 完善advised持有的需要被代理的接口，即添加SpringProxy接口、Advised接口或者DecoratingProxy接口
			enhancer.setInterfaces(AopProxyUtils.completeProxiedInterfaces(this.advised));
			// 设置enhancer的NamingPolicy为SpringNamingPolicy
			enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);
			// 设置enhancer的strategy为ClassLoaderAwareGeneratorStrategy
			enhancer.setStrategy(new ClassLoaderAwareGeneratorStrategy(classLoader));

			// 根据rootClass获取到callback数组
			Callback[] callbacks = getCallbacks(rootClass);
			// 根据callback数组生成对应的callbackType数组
			Class<?>[] types = new Class<?>[callbacks.length];
			for (int x = 0; x < types.length; x++) {
				types[x] = callbacks[x].getClass();
			}
			// fixedInterceptorMap only populated at this point, after getCallbacks call above
			// 设置callbackFilter，以决定每个方法调用哪一种callback
			enhancer.setCallbackFilter(new ProxyCallbackFilter(
					// 将advised的配置信息进行复制，该advised并不持有实际的TargetSource的target
					this.advised.getConfigurationOnlyCopy(), this.fixedInterceptorMap, this.fixedInterceptorOffset));
			// 设置enhancer的callbackTypes
			enhancer.setCallbackTypes(types);

			// Generate the proxy class and create a proxy instance.
			// 创建出代理类和代理对象
			return createProxyClassAndInstance(enhancer, callbacks);
		}
		catch (CodeGenerationException | IllegalArgumentException ex) {
			throw new AopConfigException("Could not generate CGLIB subclass of " + this.advised.getTargetClass() +
					": Common causes of this problem include using a final class or a non-visible class",
					ex);
		}
		catch (Throwable ex) {
			// TargetSource.getTarget() failed
			throw new AopConfigException("Unexpected AOP exception", ex);
		}
	}

	protected Object createProxyClassAndInstance(Enhancer enhancer, Callback[] callbacks) {
		enhancer.setInterceptDuringConstruction(false);
		enhancer.setCallbacks(callbacks);
		return (this.constructorArgs != null && this.constructorArgTypes != null ?
				enhancer.create(this.constructorArgTypes, this.constructorArgs) :
				enhancer.create());
	}

	/**
	 * Creates the CGLIB {@link Enhancer}. Subclasses may wish to override this to return a custom
	 * {@link Enhancer} implementation.
	 */
	protected Enhancer createEnhancer() {
		return new Enhancer();
	}

	/**
	 * Checks to see whether the supplied {@code Class} has already been validated and
	 * validates it if not.
	 */
	private void validateClassIfNecessary(Class<?> proxySuperClass, @Nullable ClassLoader proxyClassLoader) {
		if (logger.isInfoEnabled()) {
			synchronized (validatedClasses) {
				// 如果发现被验证过的类map中不包含proxySuperClass
				if (!validatedClasses.containsKey(proxySuperClass)) {
					// 调用doValidateClass方法，然后将结果缓存到被验证过的类map中
					doValidateClass(proxySuperClass, proxyClassLoader,
							// 获取proxySuperClass及其父类实现的接口
							ClassUtils.getAllInterfacesForClassAsSet(proxySuperClass));
					validatedClasses.put(proxySuperClass, Boolean.TRUE);
				}
			}
		}
	}

	/**
	 * Checks for final methods on the given {@code Class}, as well as package-visible
	 * methods across ClassLoaders, and writes warnings to the log for each one found.
	 */
	private void doValidateClass(Class<?> proxySuperClass, @Nullable ClassLoader proxyClassLoader, Set<Class<?>> ifcs) {
		// 如果proxySuperClass不等于Object.class
		if (proxySuperClass != Object.class) {
			// 获取类中声明的方法
			Method[] methods = proxySuperClass.getDeclaredMethods();
			// 遍历方法数组
			for (Method method : methods) {
				// 获取方法的访问修饰符
				int mod = method.getModifiers();
				// 如果不是static 且 不是private的
				if (!Modifier.isStatic(mod) && !Modifier.isPrivate(mod)) {
					// 并且是final的，那么会打印日志提示final方法无法被cglib代理，
					// 调用它的时候不会路由到target对象上面去，那么就可能产生一个空指针异常，因为生成的代理对象是没有target对象里面持有的那些属性的。
					// cglib是以继承的形式生成代理类，而final方法没办法进行重写，所以无法代理
					if (Modifier.isFinal(mod)) {
						if (logger.isInfoEnabled() && implementsInterface(method, ifcs)) {
							logger.info("Unable to proxy interface-implementing method [" + method + "] because " +
									"it is marked as final: Consider using interface-based JDK proxies instead!");
						}
						if (logger.isDebugEnabled()) {
							logger.debug("Final method [" + method + "] cannot get proxied via CGLIB: " +
									"Calls to this method will NOT be routed to the target instance and " +
									"might lead to NPEs against uninitialized fields in the proxy instance.");
						}
					}
					// 如果访问标识符不是public 且 不是protected，那么即是package类型的；
					// 并且要加载代理类的类加载器 不等于 加载proxySuperClass的类加载器。
					// 会打印日志提示package级别的方法没法通过不同的类加载器来代理，如果想要从代理对象访问到该方法的话，需要将访问修饰符改为public或者protected。

					// 因为如果加载代理类proxyClass 和 加载被代理类targetClass的 类加载器不同，那么可能会出现 proxyClass继承的父类不是targetClass。
					// 因为proxyClass在类加载的解析阶段会解析class文件中的super_class这个符号引用，
					// 因为加载两个类的类加载器的不同，在当前类加载器中的findLoadedClass方法中就找不到对应的父类，那么就会选择用当前类加载器再去加载一遍父类，
					// 由于是不同的类加载器加载出来的类，因此proxyClass实际继承的父类就不等于targetClass，因此proxyClass和targetClass也不在同一个包下。
					// 所以没办法访问到targetClass的package-visible的方法。
					else if (logger.isDebugEnabled() && !Modifier.isPublic(mod) && !Modifier.isProtected(mod) &&
							proxyClassLoader != null && proxySuperClass.getClassLoader() != proxyClassLoader) {
						logger.debug("Method [" + method + "] is package-visible across different ClassLoaders " +
								"and cannot get proxied via CGLIB: Declare this method as public or protected " +
								"if you need to support invocations through the proxy.");
					}
				}
			}
			// 然后递归调用doValidateClass，将proxySuperClass的父类传入
			doValidateClass(proxySuperClass.getSuperclass(), proxyClassLoader, ifcs);
		}
	}

	private Callback[] getCallbacks(Class<?> rootClass) throws Exception {
		// Parameters used for optimization choices...
		// 获取到advised的相关属性
		boolean exposeProxy = this.advised.isExposeProxy();
		boolean isFrozen = this.advised.isFrozen();
		// 该参数是表示每次targetSource调用getTarget的时候返回的是否都是同一个对象
		boolean isStatic = this.advised.getTargetSource().isStatic();

		// Choose an "aop" interceptor (used for AOP calls).
		// 创建一个cglib的MethodInterceptor作为Callback，用于对方法执行对应的interceptorChain增强
		Callback aopInterceptor = new DynamicAdvisedInterceptor(this.advised);

		// Choose a "straight to target" interceptor. (used for calls that are
		// unadvised but can return this). May be required to expose the proxy.
		// 对于那些没有被增强的并且能够返回this的方法，根据targetSource是否是static以及是否需要暴露代理对象，
		// 创建不同的MethodInterceptor用于这些方法的调用
		Callback targetInterceptor;
		// 如果需要暴露代理对象
		if (exposeProxy) {
			// 根据targetSource是否是static的，创建出不同的实例
			targetInterceptor = (isStatic ?
					new StaticUnadvisedExposedInterceptor(this.advised.getTargetSource().getTarget()) :
					new DynamicUnadvisedExposedInterceptor(this.advised.getTargetSource()));
		}
		// 如果不需要暴露代理对象
		else {
			// 根据targetSource是否是static的，创建出不同的实例
			targetInterceptor = (isStatic ?
					new StaticUnadvisedInterceptor(this.advised.getTargetSource().getTarget()) :
					new DynamicUnadvisedInterceptor(this.advised.getTargetSource()));
		}

		// Choose a "direct to target" dispatcher (used for
		// unadvised calls to static targets that cannot return this).
		// 对于那些没有被增强且不能返回this的方法，根据targetSource是否是static的，
		// 创建一个Dispatcher进行方法转发
		Callback targetDispatcher = (isStatic ?
				new StaticDispatcher(this.advised.getTargetSource().getTarget()) : new SerializableNoOp());

		// 创建一个callback数组
		Callback[] mainCallbacks = new Callback[]{
				// 用于正常的需要增强的方法
				aopInterceptor,  // for normal advice
				// 用于不需要增强且能够返回this的方法
				targetInterceptor,  // invoke target without considering advice, if optimized
				new SerializableNoOp(),  // no override for methods mapped to this
				// 用于不需要增强且不能返回this的方法，将方法直接转发到target对象处理
				targetDispatcher,
				// 用于调用advised接口的相关方法，将方法转发到自身持有的advisedSupport对象处理
				this.advisedDispatcher,
				// 用于equals方法
				new EqualsInterceptor(this.advised),
				// 用于hashcode方法
				new HashCodeInterceptor(this.advised)
		};

		Callback[] callbacks;

		// If the target is a static one and the advice chain is frozen,
		// then we can make some optimizations by sending the AOP calls
		// direct to the target using the fixed chain for that method.
		// 如果targetSource是static的 并且 adviceChain已经被冻结了。
		// 那么我们使用固定的interceptor去优化每个aop调用到target方法的调用
		if (isStatic && isFrozen) {
			// 获取到rootClass及其父类的public方法
			Method[] methods = rootClass.getMethods();
			// 根据methods的长度创建一个Callback数组
			Callback[] fixedCallbacks = new Callback[methods.length];
			// 创建一个固定interceptor的map
			this.fixedInterceptorMap = new HashMap<>(methods.length);

			// TODO: small memory optimization here (can skip creation for methods with no advice)
			// 遍历这些方法
			for (int x = 0; x < methods.length; x++) {
				Method method = methods[x];
				// 根据方法和rootClass获取到能够适用于该方法的adviceChain
				List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, rootClass);
				// 创建一个固定adviceChain和持有target的MethodInterceptor放入到fixCallbacks的对应下标中
				fixedCallbacks[x] = new FixedChainStaticTargetInterceptor(
						chain, this.advised.getTargetSource().getTarget(), this.advised.getTargetClass());
				// 将对应的下标作为value，method作为key，缓存到fixedInterceptorMap中
				this.fixedInterceptorMap.put(method, x);
			}

			// Now copy both the callbacks from mainCallbacks
			// and fixedCallbacks into the callbacks array.
			// 将mainCallbacks和fixedCallbacks整合起来，并且继续fixedInterceptor开始的偏移量
			callbacks = new Callback[mainCallbacks.length + fixedCallbacks.length];
			System.arraycopy(mainCallbacks, 0, callbacks, 0, mainCallbacks.length);
			System.arraycopy(fixedCallbacks, 0, callbacks, mainCallbacks.length, fixedCallbacks.length);
			this.fixedInterceptorOffset = mainCallbacks.length;
		}
		// 如果不满足targetSource是static 以及 adviceChain已经frozen的要求，那么直接将mainCallbacks作为最终的callbacks返回
		else {
			callbacks = mainCallbacks;
		}
		return callbacks;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		return (this == other || (other instanceof CglibAopProxy &&
				AopProxyUtils.equalsInProxy(this.advised, ((CglibAopProxy) other).advised)));
	}

	@Override
	public int hashCode() {
		return CglibAopProxy.class.hashCode() * 13 + this.advised.getTargetSource().hashCode();
	}


	/**
	 * Check whether the given method is declared on any of the given interfaces.
	 */
	private static boolean implementsInterface(Method method, Set<Class<?>> ifcs) {
		for (Class<?> ifc : ifcs) {
			if (ClassUtils.hasMethod(ifc, method)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Invoke the given method with a CGLIB MethodProxy if possible, falling back
	 * to a plain reflection invocation in case of a fast-class generation failure.
	 *
	 * 通过cglib的MethodProxy调用给出的方法，如果fast class生成失败的话，使用反射调用来兜底
	 */
	@Nullable
	private static Object invokeMethod(@Nullable Object target, Method method, Object[] args, MethodProxy methodProxy)
			throws Throwable {
		try {
			// 尝试使用methodProxy进行调用
			return methodProxy.invoke(target, args);
		}
		catch (CodeGenerationException ex) {
			CglibMethodInvocation.logFastClassGenerationFailure(method);
			// 如果调用失败，fallback到method的反射调用
			return AopUtils.invokeJoinpointUsingReflection(target, method, args);
		}
	}

	/**
	 * Process a return value. Wraps a return of {@code this} if necessary to be the
	 * {@code proxy} and also verifies that {@code null} is not returned as a primitive.
	 *
	 * 处理返回值
	 * 1、如果方法的返回值等于target对象的话，替换为proxy对象返回；
	 * 2、如果方法返回值为null，但是方法的返回值类型是原始类型，且不是void的情况，报错
	 */
	@Nullable
	private static Object processReturnType(
			Object proxy, @Nullable Object target, Method method, @Nullable Object returnValue) {

		// Massage return value if necessary
		// 如果返回值是target，将其转换为proxy返回
		if (returnValue != null && returnValue == target &&
				!RawTargetAccess.class.isAssignableFrom(method.getDeclaringClass())) {
			// Special case: it returned "this". Note that we can't help
			// if the target sets a reference to itself in another returned object.
			returnValue = proxy;
		}
		Class<?> returnType = method.getReturnType();
		// 如果返回值为null 但是方法的返回值类型不是void且是原始类型，报错
		if (returnValue == null && returnType != Void.TYPE && returnType.isPrimitive()) {
			throw new AopInvocationException(
					"Null return value from advice does not match primitive return type for: " + method);
		}
		return returnValue;
	}


	/**
	 * Serializable replacement for CGLIB's NoOp interface.
	 * Public to allow use elsewhere in the framework.
	 */
	public static class SerializableNoOp implements NoOp, Serializable {
	}


	/**
	 * Method interceptor used for static targets with no advice chain. The call
	 * is passed directly back to the target. Used when the proxy needs to be
	 * exposed and it can't be determined that the method won't return
	 * {@code this}.
	 */
	private static class StaticUnadvisedInterceptor implements MethodInterceptor, Serializable {

		@Nullable
		private final Object target;

		public StaticUnadvisedInterceptor(@Nullable Object target) {
			this.target = target;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object retVal = invokeMethod(this.target, method, args, methodProxy);
			return processReturnType(proxy, this.target, method, retVal);
		}
	}


	/**
	 * Method interceptor used for static targets with no advice chain, when the
	 * proxy is to be exposed.
	 */
	private static class StaticUnadvisedExposedInterceptor implements MethodInterceptor, Serializable {

		@Nullable
		private final Object target;

		public StaticUnadvisedExposedInterceptor(@Nullable Object target) {
			// 将target持有
			this.target = target;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object oldProxy = null;
			try {
				// 将proxy设置进AopContext中
				oldProxy = AopContext.setCurrentProxy(proxy);
				// 调用target对应的method
				Object retVal = invokeMethod(this.target, method, args, methodProxy);
				// 处理返回值，如果返回this，替换为proxy
				return processReturnType(proxy, this.target, method, retVal);
			}
			finally {
				// 将AopContext中ThreadLocal的值替换为原值
				AopContext.setCurrentProxy(oldProxy);
			}
		}
	}


	/**
	 * Interceptor used to invoke a dynamic target without creating a method
	 * invocation or evaluating an advice chain. (We know there was no advice
	 * for this method.)
	 */
	private static class DynamicUnadvisedInterceptor implements MethodInterceptor, Serializable {

		private final TargetSource targetSource;

		public DynamicUnadvisedInterceptor(TargetSource targetSource) {
			this.targetSource = targetSource;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object target = this.targetSource.getTarget();
			try {
				Object retVal = invokeMethod(target, method, args, methodProxy);
				return processReturnType(proxy, target, method, retVal);
			}
			finally {
				if (target != null) {
					this.targetSource.releaseTarget(target);
				}
			}
		}
	}


	/**
	 * Interceptor for unadvised dynamic targets when the proxy needs exposing.
	 */
	private static class DynamicUnadvisedExposedInterceptor implements MethodInterceptor, Serializable {

		private final TargetSource targetSource;

		public DynamicUnadvisedExposedInterceptor(TargetSource targetSource) {
			// 将targetSource持有
			this.targetSource = targetSource;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object oldProxy = null;
			// 每次调用的时候都从targetSource中获取对应的target
			Object target = this.targetSource.getTarget();
			try {
				oldProxy = AopContext.setCurrentProxy(proxy);
				Object retVal = invokeMethod(target, method, args, methodProxy);
				return processReturnType(proxy, target, method, retVal);
			}
			finally {
				AopContext.setCurrentProxy(oldProxy);
				// 如果target不为null
				if (target != null) {
					// 将targetSource中的target释放掉
					this.targetSource.releaseTarget(target);
				}
			}
		}
	}


	/**
	 * Dispatcher for a static target. Dispatcher is much faster than
	 * interceptor. This will be used whenever it can be determined that a
	 * method definitely does not return "this"
	 */
	private static class StaticDispatcher implements Dispatcher, Serializable {

		@Nullable
		private final Object target;

		public StaticDispatcher(@Nullable Object target) {
			this.target = target;
		}

		@Override
		@Nullable
		public Object loadObject() {
			return this.target;
		}
	}


	/**
	 * Dispatcher for any methods declared on the Advised class.
	 */
	private static class AdvisedDispatcher implements Dispatcher, Serializable {

		private final AdvisedSupport advised;

		public AdvisedDispatcher(AdvisedSupport advised) {
			this.advised = advised;
		}

		@Override
		public Object loadObject() {
			return this.advised;
		}
	}


	/**
	 * Dispatcher for the {@code equals} method.
	 * Ensures that the method call is always handled by this class.
	 */
	private static class EqualsInterceptor implements MethodInterceptor, Serializable {

		private final AdvisedSupport advised;

		public EqualsInterceptor(AdvisedSupport advised) {
			this.advised = advised;
		}

		@Override
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) {
			// 获取第一个参数
			Object other = args[0];
			// 如果other等于proxy，返回true
			if (proxy == other) {
				return true;
			}
			// 如果other是cglib代理出来的Factory类
			if (other instanceof Factory) {
				// 获取到equals方法对应的Callback
				Callback callback = ((Factory) other).getCallback(INVOKE_EQUALS);
				// 判断callback是否是EqualsInterceptor类型的，如果不是，直接返回false
				if (!(callback instanceof EqualsInterceptor)) {
					return false;
				}
				// 如果是，对比EqualsInterceptor持有的advised里面的元素是否相等
				AdvisedSupport otherAdvised = ((EqualsInterceptor) callback).advised;
				return AopProxyUtils.equalsInProxy(this.advised, otherAdvised);
			}
			// 如果不是Factory类型的，直接返回false
			else {
				return false;
			}
		}
	}


	/**
	 * Dispatcher for the {@code hashCode} method.
	 * Ensures that the method call is always handled by this class.
	 */
	private static class HashCodeInterceptor implements MethodInterceptor, Serializable {

		private final AdvisedSupport advised;

		public HashCodeInterceptor(AdvisedSupport advised) {
			this.advised = advised;
		}

		@Override
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) {
			// 根据advised里持有的targetSource的hashcode来决定
			return CglibAopProxy.class.hashCode() * 13 + this.advised.getTargetSource().hashCode();
		}
	}


	/**
	 * Interceptor used specifically for advised methods on a frozen, static proxy.
	 */
	private static class FixedChainStaticTargetInterceptor implements MethodInterceptor, Serializable {

		private final List<Object> adviceChain;

		@Nullable
		private final Object target;

		@Nullable
		private final Class<?> targetClass;

		public FixedChainStaticTargetInterceptor(
				List<Object> adviceChain, @Nullable Object target, @Nullable Class<?> targetClass) {

			this.adviceChain = adviceChain;
			this.target = target;
			this.targetClass = targetClass;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			MethodInvocation invocation = new CglibMethodInvocation(
					proxy, this.target, method, args, this.targetClass, this.adviceChain, methodProxy);
			// If we get here, we need to create a MethodInvocation.
			Object retVal = invocation.proceed();
			retVal = processReturnType(proxy, this.target, method, retVal);
			return retVal;
		}
	}


	/**
	 * General purpose AOP callback. Used when the target is dynamic or when the
	 * proxy is not frozen.
	 *
	 * 通用的aop callback，当target是dynamic或者proxy没有被frozen的时候使用
	 */
	private static class DynamicAdvisedInterceptor implements MethodInterceptor, Serializable {

		private final AdvisedSupport advised;

		public DynamicAdvisedInterceptor(AdvisedSupport advised) {
			this.advised = advised;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object oldProxy = null;
			boolean setProxyContext = false;
			Object target = null;
			// 获取advised的targetSource
			TargetSource targetSource = this.advised.getTargetSource();
			try {
				// 如果需要暴露代理对象
				if (this.advised.exposeProxy) {
					// Make invocation available if necessary.
					// 那么将代理对象存入到AopContext的ThreadLocal中
					oldProxy = AopContext.setCurrentProxy(proxy);
					// 将setProxyContext设置为true
					setProxyContext = true;
				}
				// Get as late as possible to minimize the time we "own" the target, in case it comes from a pool...
				// 获取target对象，即被代理的对象
				target = targetSource.getTarget();
				// 获取targetClass
				Class<?> targetClass = (target != null ? target.getClass() : null);
				// 根据method和targetClass从advisedSupport里持有的advisor集合中获取需要应用给该method的advisor，
				// 再将这些advisor中的advice转换成MethodInterceptor或者DynamicInterceptor形成interceptorChain返回
				List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
				Object retVal;
				// Check whether we only have one InvokerInterceptor: that is,
				// no real advice, but just reflective invocation of the target.
				// 如果interceptorChain是空的 并且 方法是public的并且不是声明在Object.class中的方法且方法不是equals hashcode toString方法。
				// 那么可以通过反射调用到target的对应方法
				if (chain.isEmpty() && CglibMethodInvocation.isMethodProxyCompatible(method)) {
					// We can skip creating a MethodInvocation: just invoke the target directly.
					// Note that the final invoker must be an InvokerInterceptor, so we know
					// it does nothing but a reflective operation on the target, and no hot
					// swapping or fancy proxying.
					Object[] argsToUse = AopProxyUtils.adaptArgumentsIfNecessary(method, args);
					// 调用invokeMethod实现方法调用，并获取返回值
					retVal = invokeMethod(target, method, argsToUse, methodProxy);
				}
				// 如果增强链不为空，创建一个CglibMethodInvocation，然后调用其proceed方法
				else {
					// We need to create a method invocation...
					retVal = new CglibMethodInvocation(proxy, target, method, args, targetClass, chain, methodProxy).proceed();
				}
				// 处理返回值
				retVal = processReturnType(proxy, target, method, retVal);
				return retVal;
			}
			finally {
				// 如果target不为null且targetSource不是static的
				if (target != null && !targetSource.isStatic()) {
					// 将target从targetSource中释放
					targetSource.releaseTarget(target);
				}
				// 如果之前设置了proxy进threadLocal
				if (setProxyContext) {
					// Restore old proxy.
					// 需要将oldProxy重新设置回去
					AopContext.setCurrentProxy(oldProxy);
				}
			}
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return (this == other ||
					(other instanceof DynamicAdvisedInterceptor &&
							this.advised.equals(((DynamicAdvisedInterceptor) other).advised)));
		}

		/**
		 * CGLIB uses this to drive proxy creation.
		 */
		@Override
		public int hashCode() {
			return this.advised.hashCode();
		}
	}


	/**
	 * Implementation of AOP Alliance MethodInvocation used by this AOP proxy.
	 */
	private static class CglibMethodInvocation extends ReflectiveMethodInvocation {

		@Nullable
		private final MethodProxy methodProxy;

		public CglibMethodInvocation(Object proxy, @Nullable Object target, Method method,
				Object[] arguments, @Nullable Class<?> targetClass,
				List<Object> interceptorsAndDynamicMethodMatchers, MethodProxy methodProxy) {

			super(proxy, target, method, arguments, targetClass, interceptorsAndDynamicMethodMatchers);

			// Only use method proxy for public methods not derived from java.lang.Object
			this.methodProxy = (isMethodProxyCompatible(method) ? methodProxy : null);
		}

		@Override
		@Nullable
		public Object proceed() throws Throwable {
			try {
				return super.proceed();
			}
			catch (RuntimeException ex) {
				throw ex;
			}
			catch (Exception ex) {
				if (ReflectionUtils.declaresException(getMethod(), ex.getClass()) ||
						KotlinDetector.isKotlinType(getMethod().getDeclaringClass())) {
					// Propagate original exception if declared on the target method
					// (with callers expecting it). Always propagate it for Kotlin code
					// since checked exceptions do not have to be explicitly declared there.
					throw ex;
				}
				else {
					// Checked exception thrown in the interceptor but not declared on the
					// target method signature -> apply an UndeclaredThrowableException,
					// aligned with standard JDK dynamic proxy behavior.
					throw new UndeclaredThrowableException(ex);
				}
			}
		}

		/**
		 * Gives a marginal performance improvement versus using reflection to
		 * invoke the target when invoking public methods.
		 */
		@Override
		protected Object invokeJoinpoint() throws Throwable {
			// 如果methodProxy不为null
			if (this.methodProxy != null) {
				try {
					// 调用methodProxy的invoke方法
					return this.methodProxy.invoke(this.target, this.arguments);
				}
				catch (CodeGenerationException ex) {
					logFastClassGenerationFailure(this.method);
				}
			}
			// 如果methodProxy为null的话，调用父类的invokeJoinPoint方法，通过反射调用method
			return super.invokeJoinpoint();
		}

		static boolean isMethodProxyCompatible(Method method) {
			// 如果方法是public的 并且 方法的声明类不是Object.class 并且 不是equals hashcode toString方法，返回true
			return (Modifier.isPublic(method.getModifiers()) &&
					method.getDeclaringClass() != Object.class && !AopUtils.isEqualsMethod(method) &&
					!AopUtils.isHashCodeMethod(method) && !AopUtils.isToStringMethod(method));
		}

		static void logFastClassGenerationFailure(Method method) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to generate CGLIB fast class for method: " + method);
			}
		}
	}


	/**
	 * CallbackFilter to assign Callbacks to methods.
	 */
	private static class ProxyCallbackFilter implements CallbackFilter {

		private final AdvisedSupport advised;

		private final Map<Method, Integer> fixedInterceptorMap;

		private final int fixedInterceptorOffset;

		public ProxyCallbackFilter(
				AdvisedSupport advised, Map<Method, Integer> fixedInterceptorMap, int fixedInterceptorOffset) {

			this.advised = advised;
			this.fixedInterceptorMap = fixedInterceptorMap;
			this.fixedInterceptorOffset = fixedInterceptorOffset;
		}

		/**
		 * Implementation of CallbackFilter.accept() to return the index of the
		 * callback we need.
		 * <p>The callbacks for each proxy are built up of a set of fixed callbacks
		 * for general use and then a set of callbacks that are specific to a method
		 * for use on static targets with a fixed advice chain.
		 * <p>The callback used is determined thus:
		 * <dl>
		 * <dt>For exposed proxies</dt>
		 * <dd>Exposing the proxy requires code to execute before and after the
		 * method/chain invocation. This means we must use
		 * DynamicAdvisedInterceptor, since all other interceptors can avoid the
		 * need for a try/catch block</dd>
		 * <dt>For Object.finalize():</dt>
		 * <dd>No override for this method is used.</dd>
		 * <dt>For equals():</dt>
		 * <dd>The EqualsInterceptor is used to redirect equals() calls to a
		 * special handler to this proxy.</dd>
		 * <dt>For methods on the Advised class:</dt>
		 * <dd>the AdvisedDispatcher is used to dispatch the call directly to
		 * the target</dd>
		 * <dt>For advised methods:</dt>
		 * <dd>If the target is static and the advice chain is frozen then a
		 * FixedChainStaticTargetInterceptor specific to the method is used to
		 * invoke the advice chain. Otherwise a DynamicAdvisedInterceptor is
		 * used.</dd>
		 * <dt>For non-advised methods:</dt>
		 * <dd>Where it can be determined that the method will not return {@code this}
		 * or when {@code ProxyFactory.getExposeProxy()} returns {@code false},
		 * then a Dispatcher is used. For static targets, the StaticDispatcher is used;
		 * and for dynamic targets, a DynamicUnadvisedInterceptor is used.
		 * If it possible for the method to return {@code this} then a
		 * StaticUnadvisedInterceptor is used for static targets - the
		 * DynamicUnadvisedInterceptor already considers this.</dd>
		 * </dl>
		 */

		/**
		 * Callback[] mainCallbacks = new Callback[] {
		 * 				// 用于正常的需要增强的方法
		 * 				aopInterceptor,  // for normal advice											AOP_PROXY 0
		 * 				// 用于不需要增强且能够返回this的方法
		 * 				targetInterceptor,  // invoke target without considering advice, if optimized	INVOKE_TARGET 1
		 * 				new SerializableNoOp(),  // no override for methods mapped to this   			NO_OVERRIDE 2
		 * 				// 用于不需要增强且不能返回this的方法，将方法直接转发到target对象处理
		 * 				targetDispatcher,																DISPATCH_TARGET 3
		 * 				// 用于调用advised接口的相关方法，将方法转发到自身持有的advisedSupport对象处理
		 * 				this.advisedDispatcher,															DISPATCH_ADVISED 4
		 * 				// 用于equals方法
		 * 				new EqualsInterceptor(this.advised),											INVOKE_EQUALS 5
		 * 				// 用于hashcode方法
		 * 				new HashCodeInterceptor(this.advised)											INVOKE_HASHCODE 6
		 * };
		 */
		@Override
		public int accept(Method method) {
			// 如果是finalize方法，返回SerializableNoOp
			if (AopUtils.isFinalizeMethod(method)) {
				logger.trace("Found finalize() method - using NO_OVERRIDE");
				return NO_OVERRIDE;
			}
			// 如果是Advised接口的方法
			if (!this.advised.isOpaque() && method.getDeclaringClass().isInterface() &&
					method.getDeclaringClass().isAssignableFrom(Advised.class)) {
				if (logger.isTraceEnabled()) {
					logger.trace("Method is declared on Advised interface: " + method);
				}
				// 使用AdvisedDispatcher
				return DISPATCH_ADVISED;
			}
			// We must always proxy equals, to direct calls to this.
			// 如果是equals方法，使用EqualsInterceptor
			if (AopUtils.isEqualsMethod(method)) {
				if (logger.isTraceEnabled()) {
					logger.trace("Found 'equals' method: " + method);
				}
				return INVOKE_EQUALS;
			}
			// We must always calculate hashCode based on the proxy.
			// 如果是hashcode方法，使用HashcodeInterceptor
			if (AopUtils.isHashCodeMethod(method)) {
				if (logger.isTraceEnabled()) {
					logger.trace("Found 'hashCode' method: " + method);
				}
				return INVOKE_HASHCODE;
			}
			Class<?> targetClass = this.advised.getTargetClass();
			// Proxy is not yet available, but that shouldn't matter.
			// 获取方法能够应用的adviceChain
			List<?> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
			// 如果存在可以应用的advice，将haveAdvice设置为true
			boolean haveAdvice = !chain.isEmpty();
			// 获取advised的exposeProxy
			boolean exposeProxy = this.advised.isExposeProxy();
			// 获取advised的targetSource是否是static的
			boolean isStatic = this.advised.getTargetSource().isStatic();
			// 获取advised的frozen状态
			boolean isFrozen = this.advised.isFrozen();
			// 1.方法存在可以应用的advice
			// 2.或者advised配置没有被frozen
			// 那么说明能够应用到方法上的advice的可能，只能选择使用aopInterceptor或者fixedInterceptor
			if (haveAdvice || !isFrozen) {
				// If exposing the proxy, then AOP_PROXY must be used.
				// 如果需要暴露代理对象，只有aopInterceptor能够满足
				if (exposeProxy) {
					if (logger.isTraceEnabled()) {
						logger.trace("Must expose proxy on advised method: " + method);
					}
					// 那么调用DynamicAdvisedInterceptor
					return AOP_PROXY;
				}
				// Check to see if we have fixed interceptor to serve this method.
				// Else use the AOP_PROXY.
				// 如果advised的配置已经frozen了 且 target是static的 并且fixedInterceptorMap中存在对应的method
				if (isStatic && isFrozen && this.fixedInterceptorMap.containsKey(method)) {
					if (logger.isTraceEnabled()) {
						logger.trace("Method has advice and optimizations are enabled: " + method);
					}
					// We know that we are optimizing so we can use the FixedStaticChainInterceptors.
					int index = this.fixedInterceptorMap.get(method);
					// 那么使用对应的FixedChainStaticTargetInterceptor
					return (index + this.fixedInterceptorOffset);
				}
				// 其他情况都使用aopInterceptor
				// note：实际情况下aop的动态代理，advised配置的frozen几乎都是false，因此几乎都只会命中这一种情况，即使用aopInterceptor
				else {
					if (logger.isTraceEnabled()) {
						logger.trace("Unable to apply any optimizations to advised method: " + method);
					}
					return AOP_PROXY;
				}
			}
			// 1.方法不存在可以应用的advice
			// 2.advised配置已经frozen了
			// 说明该方法一定不会有可以应用的advice出现的，所以可以放心的直接调用target的方法
			else {
				// See if the return type of the method is outside the class hierarchy of the target type.
				// If so we know it never needs to have return type massage and can use a dispatcher.
				// If the proxy is being exposed, then must use the interceptor the correct one is already
				// configured. If the target is not static, then we cannot use a dispatcher because the
				// target needs to be explicitly released after the invocation.
				// 如果要暴露代理对象 或者 targetSource不是static的
				if (exposeProxy || !isStatic) {
					// 使用之前解析出来的targetInterceptor
					return INVOKE_TARGET;
				}
				// 走到这个位置，说明：
				// 1.方法没有可以应用的advice
				// 2.advised配置已经frozen
				// 3.不需要暴露代理对象
				// 4.targetSource是static的
				Class<?> returnType = method.getReturnType();
				// 如果是返回targetClass类型的对象，即方法是返回this的，那么会包装为proxy返回，因此需要使用targetInterceptor
				if (targetClass != null && returnType.isAssignableFrom(targetClass)) {
					if (logger.isTraceEnabled()) {
						logger.trace("Method return type is assignable from target type and " +
								"may therefore return 'this' - using INVOKE_TARGET: " + method);
					}
					// 那么也只能使用targetInterceptor
					return INVOKE_TARGET;
				}
				// 如果方法不会返回this，那么直接使用targetDispatcher就行
				else {
					if (logger.isTraceEnabled()) {
						logger.trace("Method return type ensures 'this' cannot be returned - " +
								"using DISPATCH_TARGET: " + method);
					}
					// 使用callback数组中生成的targetDispatcher
					return DISPATCH_TARGET;
				}
			}
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof ProxyCallbackFilter)) {
				return false;
			}
			ProxyCallbackFilter otherCallbackFilter = (ProxyCallbackFilter) other;
			AdvisedSupport otherAdvised = otherCallbackFilter.advised;
			if (this.advised.isFrozen() != otherAdvised.isFrozen()) {
				return false;
			}
			if (this.advised.isExposeProxy() != otherAdvised.isExposeProxy()) {
				return false;
			}
			if (this.advised.getTargetSource().isStatic() != otherAdvised.getTargetSource().isStatic()) {
				return false;
			}
			if (!AopProxyUtils.equalsProxiedInterfaces(this.advised, otherAdvised)) {
				return false;
			}
			// Advice instance identity is unimportant to the proxy class:
			// All that matters is type and ordering.
			Advisor[] thisAdvisors = this.advised.getAdvisors();
			Advisor[] thatAdvisors = otherAdvised.getAdvisors();
			if (thisAdvisors.length != thatAdvisors.length) {
				return false;
			}
			for (int i = 0; i < thisAdvisors.length; i++) {
				Advisor thisAdvisor = thisAdvisors[i];
				Advisor thatAdvisor = thatAdvisors[i];
				if (!equalsAdviceClasses(thisAdvisor, thatAdvisor)) {
					return false;
				}
				if (!equalsPointcuts(thisAdvisor, thatAdvisor)) {
					return false;
				}
			}
			return true;
		}

		private static boolean equalsAdviceClasses(Advisor a, Advisor b) {
			return (a.getAdvice().getClass() == b.getAdvice().getClass());
		}

		private static boolean equalsPointcuts(Advisor a, Advisor b) {
			// If only one of the advisor (but not both) is PointcutAdvisor, then it is a mismatch.
			// Takes care of the situations where an IntroductionAdvisor is used (see SPR-3959).
			return (!(a instanceof PointcutAdvisor) ||
					(b instanceof PointcutAdvisor &&
							ObjectUtils.nullSafeEquals(((PointcutAdvisor) a).getPointcut(), ((PointcutAdvisor) b).getPointcut())));
		}

		@Override
		public int hashCode() {
			int hashCode = 0;
			Advisor[] advisors = this.advised.getAdvisors();
			for (Advisor advisor : advisors) {
				Advice advice = advisor.getAdvice();
				hashCode = 13 * hashCode + advice.getClass().hashCode();
			}
			hashCode = 13 * hashCode + (this.advised.isFrozen() ? 1 : 0);
			hashCode = 13 * hashCode + (this.advised.isExposeProxy() ? 1 : 0);
			hashCode = 13 * hashCode + (this.advised.isOptimize() ? 1 : 0);
			hashCode = 13 * hashCode + (this.advised.isOpaque() ? 1 : 0);
			return hashCode;
		}
	}

}
