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

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 *
 * 解析构造器和factory method的委托类
 *
 * <p>Performs constructor resolution through argument matching.
 * 通过参数匹配执行构造器解析
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	/**
	 * Marker for autowired arguments in a cached argument array, to be replaced
	 * by a {@linkplain #resolveAutowiredArgument resolved autowired argument}.
	 */
	private static final Object autowiredArgumentMarker = new Object();

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			@Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {

		// 先创建一个BeanWrapperImpl实例
		BeanWrapperImpl bw = new BeanWrapperImpl();
		// 然后调用beanFactory的initBeanWrapper方法对其进行初始化，
		// 将beanFactory中持有的customEditorRegistrars 和 customEditors注册进beanWrapper中，
		// 因为beanWrapper通过继承PropertyEditorRegistrySupport类实现了PropertyEditorRegistry接口
		this.beanFactory.initBeanWrapper(bw);

		// 声明一个变量，保存最终选中的冠军构造器
		Constructor<?> constructorToUse = null;
		// 声明一个变量，保存要使用的参数的holder
		ArgumentsHolder argsHolderToUse = null;
		// 声明一个变量，保存要使用的参数
		Object[] argsToUse = null;

		// 如果显示传入的参数不为null的话，将其赋值给argsToUse
		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		// 如果显示传入的参数为null的话，对参数进行解析
		else {
			// 声明一个变量，用于保存要去解析的构造器参数
			Object[] argsToResolve = null;
			// 对constructorArgumentLock进行加锁
			synchronized (mbd.constructorArgumentLock) {
				// 获取mbd中已经解析过的构造器或工厂方法
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				// 如果构造器不为null并且mbd中constructorArgumentsResolved为true，表示已经解析过构造器的参数
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					// 那么获取mbd中解析过的构造器参数
					argsToUse = mbd.resolvedConstructorArguments;
					// 如果argsToUse仍为null的话，获取mbd中preparedConstructorArguments准备的构造器参数用于解析
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			// 如果准备解析的参数不为null，说明mbd中存在已经解析过的构造器resolvedConstructorOrFactoryMethod，
			// 但是不存在已经解析过的构造器参数resolvedConstructorArguments，需要将准备的构造器参数preparedConstructorArguments再进行解析。
			// 调用resolvePreparedArguments，解析准备的参数，然后赋值给argsToUse
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}

		// 如果到了这一步，要使用的构造器仍为null 或者 要使用的参数仍为null的话。
		// 那么说明没有显式传入的参数 或者 mbd并没有能够完全解析，调用构造器去实例化对象的必要条件仍不够，需要进一步的解析。
		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			// 将上一步已经选择出来的构造器作为候选数组
			Constructor<?>[] candidates = chosenCtors;
			// 如果候选数组为null，那么尝试从beanClass中查找
			if (candidates == null) {
				// 从beanClass中查找构造器
				Class<?> beanClass = mbd.getBeanClass();
				try {
					// 如果mbd的isNonPublicAccessAllowed属性是true，那么可以查找该类中所有声明的构造器；否则查找能够访问到的构造器。
					// 将值赋值给候选数组
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}
       		// 如果候选数组数量为1 并且 显式的参数为null 并且 mbd不含有构造器参数
			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				// 获取唯一一个构造器
				Constructor<?> uniqueCandidate = candidates[0];
				// 如果该构造器是一个无参构造器
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						// 设置mbd中对应的属性
						// 将resolvedConstructorOrFactoryMethod设置候选数组中唯一的无参构造器
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						// 将constructorArgumentsResolved设置为true，表示已经解析过constructorArguments了
						mbd.constructorArgumentsResolved = true;
						// 然后将resolvedConstructorArguments设置为空数组，表示解析过的构造器参数为空数组
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					// 然后使用无参构造器实例化这个bean，并且set进beanWrapper中
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					// 然后返回beanWrapper
					return bw;
				}
			}

			// Need to resolve the constructor.
			// 如果不满足上述条件 并且
			// 如果传入的已选择的构造器不为null 或者 mbd的解析后的autowireMode是构造器注入，那么autowiring标志为true
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			// 声明一个变量用于保存解析后的构造器参数
			ConstructorArgumentValues resolvedValues = null;

			// 声明一个变量保存 构造器需要的最小的参数个数
			int minNrOfArgs;
			// 如果显示传入的参数不为null，获取其数组长度赋值给 minNrOfArgs
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			// 否则的话，根据mbd的constructorArgumentValues进行解析，获取参数的长度
			else {
				// 获取mbd中持有的ConstructorArgumentValues
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				// 创建一个新的ConstructorArgumentValues赋值给resolvedValues
				resolvedValues = new ConstructorArgumentValues();
				// 对原本持有的构造器参数进行解析，即使用BeanDefinitionValueResolver对indexed和generic类型的参数都进行解析，
				// 然后返回最小的参数数量，赋值给minNrOfArgs
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			// 对候选的构造器进行排序，public排在非public的前面，参数多的排在参数少的前面
			AutowireUtils.sortConstructors(candidates);
			// 声明一个变量，记录最小的类型差异权重
			int minTypeDiffWeight = Integer.MAX_VALUE;
			// 声明一个set，存储模拟两可的构造器，即这个set中的构造器中声明的参数和实际的参数的类型差异权重相同
			Set<Constructor<?>> ambiguousConstructors = null;
			// 声明一个List变量，用于保存异常
			LinkedList<UnsatisfiedDependencyException> causes = null;

			// 遍历候选的构造器
			for (Constructor<?> candidate : candidates) {
				// 获取候选构造器的参数数量
				int parameterCount = candidate.getParameterCount();

				// 如果已经选出了冠军构造器 并且 要使用的参数也不为null 并且要使用的参数长度也大于构造器的参数长度，直接跳出循环，
				// 因为构造器是按参数个数降序排列的，后续循环的构造器声明的参数一定比要使用的参数个数少，因此，没有循环的必要了
				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					break;
				}
				// 如果构造器的参数数量小于最小的参数数量，继续循环遍历，
				// 一般情况下如果没有传入指定的参数，以及bd没有声明ConstructorArgumentValue，最小的参数个数都为0，
				// 所以这个if里的代码块一般都不会执行
				if (parameterCount < minNrOfArgs) {
					continue;
				}

				ArgumentsHolder argsHolder;
				// 获取候选构造器的参数类型数组
				Class<?>[] paramTypes = candidate.getParameterTypes();
				// 如果resolvedValues不为null，说明没有显式传入的参数
				if (resolvedValues != null) {
					try {
						// 获取构造器的参数名称，通过构造器上标注的@ConstructorProperties注解的value属性
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
						// 如果根据@ConstructorProperties注解没有获取到对应构造器的参数名称数组
						if (paramNames == null) {
							// 尝试用beanFactory持有的DefaultParameterNameDiscover进行获取
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						// 根据提供的这一系列参数创建参数数组
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								// 如果候选构造器的声明类是cglib代理过的，那么获取到没被代理的类的相同参数类型的构造器
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						// 如果出现了异常，创建一个List来吞掉并保存，直接尝试下一个候选构造器
						if (causes == null) {
							causes = new LinkedList<>();
						}
						causes.add(ex);
						continue;
					}
				}
				// 如果显式的参数不为null
				else {
					// Explicit arguments given -> arguments length must match exactly.
					// 候选构造器的参数个数必须和显式的参数个数相等，否则直接跳过该构造器
					if (parameterCount != explicitArgs.length) {
						continue;
					}
					// 如果参数个数相等，将显式传入的参数封装为ArgumentsHolder
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				// 计算构造器声明的参数类型 和 实际的参数类型的差距权重
				// 根据mbd中是否启用宽松的构造器解析来决定使用哪种计算模式
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				// 如果本次计算差距权重 小于 最小的类型差距权重
				if (typeDiffWeight < minTypeDiffWeight) {
					// 那么将该构造器选为冠军构造器(要使用的构造器)，并且将解析出的参数选为要使用的参数
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				// 如果本次计算的类型差距权重 等于 最小的类型差距权重 并且 前面的循环已经选出要使用的构造器了
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					// 那么创建一个set集合用于保存类型差距权重相等的构造器
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			// 如果最后没有选出要使用的构造器
			if (constructorToUse == null) {
				// 并且异常链不为null
				if (causes != null) {
					// 移除最后一个异常
					UnsatisfiedDependencyException ex = causes.removeLast();
					// 然后将前面所有收集的异常调用beanFactory的onSuppressException方法
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					// 抛出最后一个异常
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			// 如果选出了模拟两可的构造器集合 并且 mbd的构造器解析是严格格式的，抛出异常
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			// 如果显式的参数为null 并且 要使用的参数holder类不为null
			if (explicitArgs == null && argsHolderToUse != null) {
				// 那么将构造器和参数缓存到mbd中
				// 即设置mbd的resolvedConstructorOrFactoryMethod
				// mbd的constructorArgumentsResolved
				// 根据ArgumentsHolder中的resolveNecessary属性决定是要设置mbd中的resolvedConstructorArguments 还是 preparedConstructorArguments
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		// 调用instantiate方法将bean实例化之后设置进beanWrapper中，然后返回beanWrapper
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			// 使用beanFactory的InstantiationStrategy进行实例化
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (isParamMismatch(uniqueCandidate, candidate)) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
					(mbd.isNonPublicAccessAllowed() ?
						ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
		}
		else {
			// 如果mbd是允许非public的访问的，获取类中所有声明的方法，如果不是，获取类中能访问到的方法
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		// 创建一个BeanWrapperImpl对象，并用beanFactory进行初始化
		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		// 声明一个变量表示声明@Bean方法的factoryBean
		Object factoryBean;
		// 声明一个变量表示factoryBean的类对象
		Class<?> factoryClass;
		// 声明一个变量表示@Bean方法是否是static的
		boolean isStatic;

		// 获取mbd中的factoryBeanName
		String factoryBeanName = mbd.getFactoryBeanName();
		// 存在factoryBeanName，说明@Bean方法是实例方法
		if (factoryBeanName != null) {
			// 如果factoryBeanName和自身的beanName相等的话，报错
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			// 从beanFactory中获取声明该@Bean方法的bean
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			// 如果mbd是单例的 并且 beanFactory的一级缓存中已经存在了beanName对应的单例bean，那么报错
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				throw new ImplicitlyAppearedSingletonException();
			}
			// 将factoryBeanName和beanName的依赖关系注册到beanFactory中
			// 即factoryBeanName被beanName依赖，beanName依赖了factoryBeanName
			this.beanFactory.registerDependentBean(factoryBeanName, beanName);
			// 获取factoryBean的class对象
			factoryClass = factoryBean.getClass();
			// 因为factoryBeanName存在，说明@Bean方法不是静态方法，将isStatic置为false
			isStatic = false;
		}
		// 如果factoryBeanName为null的话，说明@Bean方法是静态方法
		else {
			// It's a static factory method on the bean class.
			// 如果mbd不存在class类型的beanClass的话，报错。
			// 因为如果是静态方法，在ConfigurationClassBeanDefinitionReader解析@Bean方法的时候
			// 会将声明@Bean方法的类对象 或者 类的全限定名 存入bd的beanClass字段中，
			// 而在AbstractAutowireCapableBeanFactory的createBeanInstance方法进入的时候，就调用了resolveBeanClass方法，
			// 将mbd中String类型的beanClass解析为了Class类型
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			// 将factoryClass设置为mbd的beanClass
			factoryClass = mbd.getBeanClass();
			// 并且将isStatic设置为true
			isStatic = true;
		}

		// 声明一个变量来表示 最终要调用的factoryMethod的反射对象
		Method factoryMethodToUse = null;
		// 声明一个变量来表示 factoryMethod所需要的参数holder
		ArgumentsHolder argsHolderToUse = null;
		// 声明一个变量来表示 最终要调用factoryMethod方法所使用的参数数组
		Object[] argsToUse = null;

		// 如果显式的参数不为null的话，将其直接赋值给argsToUse
		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		// 如果不存在显式的参数
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				// 判断mbd中是否已经存在解析过的构造器或工厂方法
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				// 如果存在 并且 构造器参数也已经解析过
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					// 那么尝试获取解析过的构造器参数
					argsToUse = mbd.resolvedConstructorArguments;
					// 如果不存在已经解析过的构造器参数
					if (argsToUse == null) {
						// 使用准备好的构造器参数，在下一步进行解析
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			// 如果要解析的参数不为null的话
			if (argsToResolve != null) {
				// 调用resolvePreparedArguments方法解析准备好的参数
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}

		// 如果要使用的工厂方法 和 要使用的参数 有任一为null的话，执行下面的逻辑
		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			// 如果class是被cglib代理过的，获取其没有被代理的原始类
			factoryClass = ClassUtils.getUserClass(factoryClass);

			List<Method> candidates = null;
			// 如果mbd的isFactoryMethodUnique标志为true，表示其存在唯一的工厂方法
			if (mbd.isFactoryMethodUnique) {
				if (factoryMethodToUse == null) {
					// 直接获取mbd的factoryMethodToIntrospect属性作为要使用的工厂方法
					// 如果ConfigurationClassBeanDefinition中的methodMetadata是由asm生成SimpleMethodMetadata的话，
					// 那么bd中的factoryMethodToIntrospect是null，因为当时获取不到方法对应的反射对象
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				// 如果要使用的工厂方法不为null的话，创建一个只有一个元素的集合赋值给candidates
				if (factoryMethodToUse != null) {
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			// 如果候选方法集合为null的话，那么进行查找候选方法
			if (candidates == null) {
				candidates = new ArrayList<>();
				// 调用getCandidateMethods从类中获取候选方法数组
				// 根据mbd的nonPublicAccessAllowed属性来决定是否获取非public的方法，该属性默认是true。
				// 因此可以获取到类中所有声明的方法以及父类中所有声明的方法，然后再进行筛选
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				// 遍历原始的候选方法数组
				for (Method candidate : rawCandidates) {
					// 如果方法的static标志和之前解析出的static标志一致 并且 候选方法是factoryMethod
					// 判断候选方法是否是factoryMethod的依据是：
					// 1.方法名和factoryMethodName一致
					// 2.方法上标注了@Bean注解
					// 3.解析出来的beanName和ConfigurationClassBeanDefinition中的derivedBeanName一致
					if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
						// 如果满足上述条件，将方法添加到候选方法集合中
						candidates.add(candidate);
					}
				}
			}

			// 如果候选方法个数为1 且 不存在显式的参数 mbd中也不存在构造器参数
			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				// 获取到唯一的候选方法
				Method uniqueCandidate = candidates.get(0);
				// 如果候选方法的参数个数为0
				if (uniqueCandidate.getParameterCount() == 0) {
					// 将唯一候选方法设置进mbd的factoryMethodToIntrospect属性中
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					synchronized (mbd.constructorArgumentLock) {
						// 然后设置mbd的resolvedConstructorOrFactoryMethod constructorArgumentsResolved resolvedConstructorArguments等属性
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					// 调用instantiate方法选用beanFactory的实例化策略进行实例化，并将结果存入到beanWrapper中返回
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// 如果候选方法数量大于1，将其排序
			// 排序逻辑是：public排在非public前面，参数个数多的排在前面
			if (candidates.size() > 1) {  // explicitly skip immutable singletonList
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}

			ConstructorArgumentValues resolvedValues = null;
			// 判断自动注入模式是否是按构造器注入
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Method> ambiguousFactoryMethods = null;

			// 计算最小的参数个数
			int minNrOfArgs;
			// 如果显式参数不为null，将其长度赋值给最小参数个数
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			// 否则的话，解析mbd中的constructorArgumentValues
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				// 如果mbd中存在constructorArgumentValues，那么进行解析，并且将最小参数个数赋值
				if (mbd.hasConstructorArgumentValues()) {
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				// 否则的话，直接将最小参数个数赋值为0
				else {
					minNrOfArgs = 0;
				}
			}

			LinkedList<UnsatisfiedDependencyException> causes = null;

			// 遍历候选方法集合
			for (Method candidate : candidates) {
				// 获取候选方法的参数个数
				int parameterCount = candidate.getParameterCount();

				// 如果参数个数大于等于最小的参数个数
				if (parameterCount >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					Class<?>[] paramTypes = candidate.getParameterTypes();
					// 如果显式的参数不为null
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						// 判断候选方法的参数类型长度是否和显式的参数长度相等，如果不相等，直接进行下一次循环
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						// 如果相等的话，将显式的参数构造为一个ArgumentsHolder赋值给argsHolder
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					// 如果显式的参数不存在，进行解析
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							// 使用beanFactory持有的参数名发现器来获取候选方法的参数名数组
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							// 然后调用createArgumentArray方法解析候选方法所需要的参数
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							// 如果出现依赖解析的异常，将其收集到集合中，不报错
							if (causes == null) {
								causes = new LinkedList<>();
							}
							causes.add(ex);
							continue;
						}
					}

					// 根据mbd的lenientConstructorResolution属性来决定采用哪种 类型差距权重计算方式。
					// ConfigurationClassBeanDefinition中该参数默认为false
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					// 如果本次的类型差距权重 小于 最小的类型差距权重
					if (typeDiffWeight < minTypeDiffWeight) {
						// 将本次遍历的候选方法选为要使用的工厂方法
						factoryMethodToUse = candidate;
						// 并且将本次解析出的参数也选为要使用的参数
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						// 最小的类型差距权重更新为 本次计算出的类型差距权重
						minTypeDiffWeight = typeDiffWeight;
						// 并且将模拟两可的工厂方法集合置为null
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).

					// 1.如果已经存在要使用的工厂方法 并且
					// 2.本次的类型差距权重等于最小的类型差距权重 并且
					// 3.mbd的构造器解析模式不是宽松的 并且
					// 4.要使用的工厂方法的参数个数和候选方法参数个数一致 并且
					// 5.要使用的工厂方法的参数类型和候选方法不一致
					// 说明factoryBeanClass中有重载的@Bean方法，且参数类型的typeDiffWeight一致，
					// 那么将两个方法都添加到ambiguousFactoryMethods集合中

					// 如果mbd采用的是宽松的构造器解析策略的话，不会走这段逻辑，即使用首先遍历到的工厂方法。
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						// 将两个方法都添加到模拟两可的方法集合中
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			// 如果仍然没有找到要使用的工厂方法 或者 参数
			if (factoryMethodToUse == null || argsToUse == null) {
				// 解析收集到的异常，将最后一个异常抛出，其余异常都调用beanFactory的onSuppressedException方法进行处理
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				// 如果显式的参数不为null，将其类型名称收集到argTypes集合中
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				// 如果解析后的ConstructorArgumentValues不为null，将其持有的参数的类型名称收集到argTypes集合中
				else if (resolvedValues != null) {
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				// 将参数类型集合转换为String类型，然后抛出异常
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
						(mbd.getFactoryBeanName() != null ?
							"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			// 如果要使用的工厂方法返回类型是void，报错
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
						factoryClass.getName() + "]: needs to have a non-void return type!");
			}
			// 如果存在模拟两可的工厂方法集合 报错
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found on class [" + factoryClass.getName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			// 如果不存在显式的参数 并且 解析出的要使用的参数不为null的话
			if (explicitArgs == null && argsHolderToUse != null) {
				// 将要使用的工厂方法设置进mbd的factoryMethodToIntrospect中
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				// 然后将参数和工厂方法都缓存到RootBeanDefinition的对应字段中
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		// 调用instantiate方法采用beanFactory持有的实例化策略实例化bean，然后设置进beanWrapper中返回
		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						this.beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 *
	 * 解析这个bean的构造器参数（即beanDefinition持有的ConstructorArgumentValues对象）到resolvedValues这个对象中，
	 * 这可能会涉及到查找其他bean
	 *
	 * <p>This method is also used for handling invocations of static factory methods.
	 * 这个方法也适用于处理静态factory method的调用
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		// 获取beanFactory持有的自定义的类型转换器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		// 如果自定义的类型转换器不为null的话，就使用它作为类型转换器，否则使用beanWrapper作为类型转换器
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		// 创建一个BeanDefinitionValueResolver
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		// 获取构造器参数的个数
		int minNrOfArgs = cargs.getArgumentCount();

		// 遍历构造器参数的Indexed参数
		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			// 获取参数对应的下标位置
			int index = entry.getKey();
			// 如果index<0的话，报错，因为构造器参数index不能小于0
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			// 如果index+1 大于了 最小的参数个数
			if (index + 1 > minNrOfArgs) {
				// 将最小参数个数更新为index + 1
				minNrOfArgs = index + 1;
			}
			// 获取index对应的value，即参数的包装类ValueHolder
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			// 判断valueHolder是否已经被转换过了
			if (valueHolder.isConverted()) {
				// 如果是，添加到resolvedValues的index参数中，表示该value已经被解析
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			// 否则的话，将valueHolder进行转换
			else {
				// 使用valueResolver对valueHolder持有的value进行解析
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				// 根据name type 和解析后的value重新创建一个ValueHolder
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				// 并且将解析后的valueHolder的source设置为被解析的valueHolder
				resolvedValueHolder.setSource(valueHolder);
				// 添加进resolvedValues中
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		// 同理，遍历持有的generic类型的valueHolder
		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			// 如果已经被转换过，直接添加进resolvedValues
			if (valueHolder.isConverted()) {
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			// 否则，通过valueResolver进行解析
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		// 然后返回最小的参数个数
		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 *
	 * 创建一个参数数组去调用构造器 或者 factory method，
	 * 给出解析后的constructor argument values
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

		// 获取beanFactory的自定义类型转换器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		// 如果自定义的类型转换器不为null，就使用它，否则使用beanWrapper
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		// 根据构造器参数类型的长度创建一个ArgumentsHolder用于持有参数
		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		// 根据构造器参数类型长度创建一个ValueHolder类型的set，用于保存value
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		// 创建一个set用来保存需要自动注入的beanNames
		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);

		// 根据参数类型进行遍历
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			// 获取到对应index的参数类型
			Class<?> paramType = paramTypes[paramIndex];
			// 如果参数名不为null的话，获取对应index的参数名，否则，设置为空字符串
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			// 尝试去找到匹配的构造器参数值，包括indexed和generic类型的
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			// 如果解析过的resolvedValues不为null的话
			if (resolvedValues != null) {
				// 根据参数类型，参数index，参数名获取对应的参数值valueHolder
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				// 如果根据index name type没有找到对应的valueHolder, 并且不会去自动注入 或者 构造器需要的参数数量等于解析后的参数的总数
				// 尝试不限制name type 去genericArgumentValues里面查找
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			// 如果获取到的valueHolder不为null的话，代表我们找到了一个可能的匹配，进行尝试
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				// 将valueHolder添加进usedValueHolders这个set中
				usedValueHolders.add(valueHolder);
				// 获取到valueHolder持有的原始的value
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				// 如果valueHolder已经是转换过的了
				if (valueHolder.isConverted()) {
					// 获取其转换过的value
					convertedValue = valueHolder.getConvertedValue();
					// 在ArgumentsHolder的preparedArguments数组的对应index位置放入转换过的value
					args.preparedArguments[paramIndex] = convertedValue;
				}
				// 如果还没有转换过
				else {
					// 获取executable对应的index位置的参数，封装成MethodParameter返回
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						// 通过TypeConvert对originalValue进行转换
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
										ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
										"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					// 如果valueHolder的source仍是valueHolder
					Object sourceHolder = valueHolder.getSource();
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						// 获取sourceHolder的value作为sourceValue
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						args.resolveNecessary = true;
						// 那么将参数持有类的preparedArguments对应index的值设置为sourceValue
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				// 设置参数持有类对应index的arguments 和 rawArguments
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			// 如果没有找到对应的valueHolder的话
			else {
				// 根据executable和paramIndex创建一个MethodParameter
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				// 如果autowiring标志为false的话，报错
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					// 调用resolveAutowiredArgument方法去获取需要自动注入的参数，
					// 并且将需要注入的那些bean的beanName都收集到autowiredBeanNames集合中
					Object autowiredArgument = resolveAutowiredArgument(
							methodParam, beanName, autowiredBeanNames, converter, fallback);
					// 并且将参数持有类ArgumentsHolder的rawArguments arguments对应index都设置为解析后的参数
					args.rawArguments[paramIndex] = autowiredArgument;
					args.arguments[paramIndex] = autowiredArgument;
					// 将preparedArguments对应的index元素设置为一个标记类
					args.preparedArguments[paramIndex] = autowiredArgumentMarker;
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}

		// 遍历autowiredBeanNames集合，将需要注入的autowiredBeanName注册到依赖map中，表明其和beanName之间的依赖关系
		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + (executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve) {

		// 获取beanFactory的customConverter
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		// 如果为null的话，使用bw作为TypeConvert
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		// 创建一个BeanDefinitionValueResolver
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		// 获取方法的参数类型数组
		Class<?>[] paramTypes = executable.getParameterTypes();

		// 根据要解析的参数数组长度 创建一个 解析后的参数数组
		Object[] resolvedArgs = new Object[argsToResolve.length];
		// 遍历要解析的参数数组
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			// 获取对应下标的要解析参数
			Object argValue = argsToResolve[argIndex];
			// 生成对应下标的方法参数MethodParameter对象
			MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
			// 如果要解析的参数是autowiredArgumentMarker这个常量，说明需要对参数进行自动注入解析
			if (argValue == autowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter, true);
			}
			// 如果要解析的参数是BeanMetadataElement类型的，调用BeanDefinitionValueResolver进行解析
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			// 如果参数是String类型的，使用beanFactory的beanExpressionResolver进行spel表达式的解析
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			// 获取对应的下标的参数类型
			Class<?> paramType = paramTypes[argIndex];
			try {
				// 将 实际的参数 转换为 需要的方法参数类型，并放入解析后的参数数组对应下标中
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
						"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		// 返回解析后的参数数组
		return resolvedArgs;
	}

	protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 */
	@Nullable
	protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {

		// 获取方法参数对应的参数类型
		Class<?> paramType = param.getParameterType();
		// 如果参数类型是InjectionPoint类型的
		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			// 获取当前线程持有的injectionPoint对象
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			// 如果当前持有的injectionPoint对象为null，报错
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			// 否则返回injectionPoint
			return injectionPoint;
		}
		try {
			// 尝试从beanFactory中解析对应参数类型的依赖
			return this.beanFactory.resolveDependency(
					// 根据methodParameter创建一个DependencyDescriptor对象
					new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			// 如果在beanFactory中没找到对应的依赖，并且fallback参数为true的话。
			// 说明只有一个构造器或工厂方法，那么根据参数类型，返回空的数组或List或Map
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				}
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			// 如果参数不是数组、List、Map类型的，那么抛出异常
			throw ex;
		}
	}

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 * 私有的内部类，用于持有参数的合集
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			// 遍历参数类型
			for (int i = 0; i < paramTypes.length; i++) {
				// 如果出现对应下标的实际参数不能赋值给对应的参数类型，返回Integer的最大值
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				// 如果出现对应下标的rawArgument不能赋值给对应的参数类型，返回Integer最大值-512
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			// 如果都能赋值，返回Integer最大值-1024
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		// 用于检查和解析java6添加的@ConstructorProperties注解
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			// 获取构造器上标注的@ConstructorProperties注解
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			// 如果注解存在
			if (cp != null) {
				// 获取其value属性
				String[] names = cp.value();
				// 如果注解中声明的参数名的长度和构造器实际的参数个数不相等的话，报错
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				// 返回参数名称数组
				return names;
			}
			else {
				return null;
			}
		}
	}

}
