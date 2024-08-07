/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.aop.aspectj;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.weaver.tools.JoinPointMatch;
import org.aspectj.weaver.tools.PointcutParameter;

import org.springframework.aop.AopInvocationException;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.MethodMatchers;
import org.springframework.aop.support.StaticMethodMatcher;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Base class for AOP Alliance {@link org.aopalliance.aop.Advice} classes
 * wrapping an AspectJ aspect or an AspectJ-annotated advice method.
 *
 * AOP Advice的基础类，包装了一个AspectJ的切面或者一个被AspectJ注解标注的advice方法
 *
 * @author Rod Johnson
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @since 2.0
 */
@SuppressWarnings("serial")
public abstract class AbstractAspectJAdvice implements Advice, AspectJPrecedenceInformation, Serializable {

	/**
	 * Key used in ReflectiveMethodInvocation userAttributes map for the current joinpoint.
	 */
	protected static final String JOIN_POINT_KEY = JoinPoint.class.getName();


	/**
	 * Lazily instantiate joinpoint for the current invocation.
	 * Requires MethodInvocation to be bound with ExposeInvocationInterceptor.
	 * <p>Do not use if access is available to the current ReflectiveMethodInvocation
	 * (in an around advice).
	 * @return current AspectJ joinpoint, or through an exception if we're not in a
	 * Spring AOP invocation.
	 */
	public static JoinPoint currentJoinPoint() {
		// 获取ExposeInvocationInterceptor中的threadLocal保存的当前的MethodInvocation
		MethodInvocation mi = ExposeInvocationInterceptor.currentInvocation();
		// 如果mi不是ProxyMethodInvocation类型的，报错
		if (!(mi instanceof ProxyMethodInvocation)) {
			throw new IllegalStateException("MethodInvocation is not a Spring ProxyMethodInvocation: " + mi);
		}
		ProxyMethodInvocation pmi = (ProxyMethodInvocation) mi;
		// 尝试通过JoinPoint类的全限定名获取pmi中对应的JoinPoint类型的属性
		JoinPoint jp = (JoinPoint) pmi.getUserAttribute(JOIN_POINT_KEY);
		// 如果不存在对应的属性
		if (jp == null) {
			// 那么通过pmi生成一个MethodInvocationProceedingJoinPoint
			jp = new MethodInvocationProceedingJoinPoint(pmi);
			// 并且存入MethodInvocation的属性中
			pmi.setUserAttribute(JOIN_POINT_KEY, jp);
		}
		// 返回jp
		return jp;
	}


	private final Class<?> declaringClass;

	private final String methodName;

	private final Class<?>[] parameterTypes;

	protected transient Method aspectJAdviceMethod;

	private final AspectJExpressionPointcut pointcut;

	private final AspectInstanceFactory aspectInstanceFactory;

	/**
	 * The name of the aspect (ref bean) in which this advice was defined
	 * (used when determining advice precedence so that we can determine
	 * whether two pieces of advice come from the same aspect).
	 */
	private String aspectName = "";

	/**
	 * The order of declaration of this advice within the aspect.
	 */
	private int declarationOrder;

	/**
	 * This will be non-null if the creator of this advice object knows the argument names
	 * and sets them explicitly.
	 */
	// 如果advice的创建对象知道参数名称，并且显示地设置了，那么这个字段不会为null
	@Nullable
	private String[] argumentNames;

	/** Non-null if after throwing advice binds the thrown value. */
	// 如果afterThrowing advice绑定了抛出的变量，这个属性也不会null
	@Nullable
	private String throwingName;

	/** Non-null if after returning advice binds the return value. */
	// 如果afterReturning advice 绑定了返回值，这个属性也不为null
	@Nullable
	private String returningName;

	// 发现的返回类型，当返回值匹配这个类型的时候，afterReturningAdvice才生效
	private Class<?> discoveredReturningType = Object.class;

	// 发现的异常抛出类型，当抛出的异常匹配这个类型的时候，afterThrowingAdvice才生效
	private Class<?> discoveredThrowingType = Object.class;

	/**
	 * Index for thisJoinPoint argument (currently only
	 * supported at index 0 if present at all).
	 */
	// JoinPoint参数的位置，只支持在index为0的位置
	private int joinPointArgumentIndex = -1;

	/**
	 * Index for thisJoinPointStaticPart argument (currently only
	 * supported at index 0 if present at all).
	 */
	// JoinPoint.StaticPart参数的位置，只支持在index为0的位置
	private int joinPointStaticPartArgumentIndex = -1;

	// 表示AspectJ注解里面的argNames属性所声明的参数名 到 adviceMethod上面的参数index的映射map
	@Nullable
	private Map<String, Integer> argumentBindings;

	private boolean argumentsIntrospected = false;

	// 发现的返回值的泛型类型
	@Nullable
	private Type discoveredReturningGenericType;
	// Note: Unlike return type, no such generic information is needed for the throwing type,
	// since Java doesn't allow exception types to be parameterized.

	// 抛出异常不存在泛型类型是因为java不允许异常类型的泛型参数化


	/**
	 * Create a new AbstractAspectJAdvice for the given advice method.
	 * @param aspectJAdviceMethod the AspectJ-style advice method
	 * @param pointcut the AspectJ expression pointcut
	 * @param aspectInstanceFactory the factory for aspect instances
	 */
	public AbstractAspectJAdvice(
			Method aspectJAdviceMethod, AspectJExpressionPointcut pointcut, AspectInstanceFactory aspectInstanceFactory) {

		Assert.notNull(aspectJAdviceMethod, "Advice method must not be null");
		// advice方法的声明类
		this.declaringClass = aspectJAdviceMethod.getDeclaringClass();
		// advice方法名称
		this.methodName = aspectJAdviceMethod.getName();
		// advice方法参数类型
		this.parameterTypes = aspectJAdviceMethod.getParameterTypes();
		// advice方法
		this.aspectJAdviceMethod = aspectJAdviceMethod;
		// pointcut
		this.pointcut = pointcut;
		// aspectInstanceFactory
		this.aspectInstanceFactory = aspectInstanceFactory;
	}


	/**
	 * Return the AspectJ-style advice method.
	 */
	public final Method getAspectJAdviceMethod() {
		return this.aspectJAdviceMethod;
	}

	/**
	 * Return the AspectJ expression pointcut.
	 */
	public final AspectJExpressionPointcut getPointcut() {
		calculateArgumentBindings();
		return this.pointcut;
	}

	/**
	 * Build a 'safe' pointcut that excludes the AspectJ advice method itself.
	 * @return a composable pointcut that builds on the original AspectJ expression pointcut
	 * @see #getPointcut()
	 */
	public final Pointcut buildSafePointcut() {
		Pointcut pc = getPointcut();
		MethodMatcher safeMethodMatcher = MethodMatchers.intersection(
				new AdviceExcludingMethodMatcher(this.aspectJAdviceMethod), pc.getMethodMatcher());
		return new ComposablePointcut(pc.getClassFilter(), safeMethodMatcher);
	}

	/**
	 * Return the factory for aspect instances.
	 */
	public final AspectInstanceFactory getAspectInstanceFactory() {
		return this.aspectInstanceFactory;
	}

	/**
	 * Return the ClassLoader for aspect instances.
	 */
	@Nullable
	public final ClassLoader getAspectClassLoader() {
		return this.aspectInstanceFactory.getAspectClassLoader();
	}

	@Override
	public int getOrder() {
		return this.aspectInstanceFactory.getOrder();
	}


	/**
	 * Set the name of the aspect (bean) in which the advice was declared.
	 */
	public void setAspectName(String name) {
		this.aspectName = name;
	}

	@Override
	public String getAspectName() {
		return this.aspectName;
	}

	/**
	 * Set the declaration order of this advice within the aspect.
	 */
	public void setDeclarationOrder(int order) {
		this.declarationOrder = order;
	}

	@Override
	public int getDeclarationOrder() {
		return this.declarationOrder;
	}

	/**
	 * Set by creator of this advice object if the argument names are known.
	 * <p>This could be for example because they have been explicitly specified in XML,
	 * or in an advice annotation.
	 * @param argNames comma delimited list of arg names
	 */
	public void setArgumentNames(String argNames) {
		String[] tokens = StringUtils.commaDelimitedListToStringArray(argNames);
		setArgumentNamesFromStringArray(tokens);
	}

	public void setArgumentNamesFromStringArray(String... args) {
		// 将advice持有的argumentNames初始化为String数组
		this.argumentNames = new String[args.length];
		// 遍历传入的参数数组
		for (int i = 0; i < args.length; i++) {
			// 将其trim
			this.argumentNames[i] = StringUtils.trimWhitespace(args[i]);
			// 如果发现对应的参数名不是变量名，报错
			if (!isVariableName(this.argumentNames[i])) {
				throw new IllegalArgumentException(
						"'argumentNames' property of AbstractAspectJAdvice contains an argument name '" +
								this.argumentNames[i] + "' that is not a valid Java identifier");
			}
		}
		// 如果argumentNames不为null
		if (this.argumentNames != null) {
			// 检查方法的参数个数是否比参数名数组的长度大1
			if (this.aspectJAdviceMethod.getParameterCount() == this.argumentNames.length + 1) {
				// May need to add implicit join point arg name...
				// 如果是的话，我们需要添加隐式的连接点的参数名
				// 获取adviceMethod的第一个参数类型
				Class<?> firstArgType = this.aspectJAdviceMethod.getParameterTypes()[0];
				// 如果参数类型是连接点相关的类型的话
				if (firstArgType == JoinPoint.class ||
						firstArgType == ProceedingJoinPoint.class ||
						firstArgType == JoinPoint.StaticPart.class) {
					// 将参数数组的第一个元素设置为THIS_JOIN_POINT，然后将原本的参数数组的内容赋值到新数组的第二个元素开始的位置
					String[] oldNames = this.argumentNames;
					this.argumentNames = new String[oldNames.length + 1];
					this.argumentNames[0] = "THIS_JOIN_POINT";
					System.arraycopy(oldNames, 0, this.argumentNames, 1, oldNames.length);
				}
			}
		}
	}

	public void setReturningName(String name) {
		throw new UnsupportedOperationException("Only afterReturning advice can be used to bind a return value");
	}

	/**
	 * We need to hold the returning name at this level for argument binding calculations,
	 * this method allows the afterReturning advice subclass to set the name.
	 */
	protected void setReturningNameNoCheck(String name) {
		// name could be a variable or a type...
		// 判断传入的name是否是合法的变量名
		if (isVariableName(name)) {
			// 如果是，将其赋值给returningName
			this.returningName = name;
		}
		// 如果不是合法的变量名，假定它是一个类型
		else {
			// assume a type
			try {
				// 尝试加载这个类对象，赋值给discoveredReturningType
				this.discoveredReturningType = ClassUtils.forName(name, getAspectClassLoader());
			}
			catch (Throwable ex) {
				throw new IllegalArgumentException("Returning name '" + name  +
						"' is neither a valid argument name nor the fully-qualified " +
						"name of a Java type on the classpath. Root cause: " + ex);
			}
		}
	}

	protected Class<?> getDiscoveredReturningType() {
		return this.discoveredReturningType;
	}

	@Nullable
	protected Type getDiscoveredReturningGenericType() {
		return this.discoveredReturningGenericType;
	}

	public void setThrowingName(String name) {
		throw new UnsupportedOperationException("Only afterThrowing advice can be used to bind a thrown exception");
	}

	/**
	 * We need to hold the throwing name at this level for argument binding calculations,
	 * this method allows the afterThrowing advice subclass to set the name.
	 */
	protected void setThrowingNameNoCheck(String name) {
		// name could be a variable or a type...
		// 检查name是否是合法的变量
		if (isVariableName(name)) {
			// 如果是，赋值给throwingName
			this.throwingName = name;
		}
		// 否则，假设它是一个类型，尝试加载它
		else {
			// assume a type
			try {
				// 将加载出的类赋值给discoveredThrowingType
				this.discoveredThrowingType = ClassUtils.forName(name, getAspectClassLoader());
			}
			catch (Throwable ex) {
				throw new IllegalArgumentException("Throwing name '" + name  +
						"' is neither a valid argument name nor the fully-qualified " +
						"name of a Java type on the classpath. Root cause: " + ex);
			}
		}
	}

	protected Class<?> getDiscoveredThrowingType() {
		return this.discoveredThrowingType;
	}

	private boolean isVariableName(String name) {
		char[] chars = name.toCharArray();
		if (!Character.isJavaIdentifierStart(chars[0])) {
			return false;
		}
		for (int i = 1; i < chars.length; i++) {
			if (!Character.isJavaIdentifierPart(chars[i])) {
				return false;
			}
		}
		return true;
	}


	/**
	 * Do as much work as we can as part of the set-up so that argument binding
	 * on subsequent advice invocations can be as fast as possible.
	 *
	 * 尽可能的多做工作作为setup的一部分以至于后续advice调用的时候参数绑定能够尽可能的快
	 *
	 * <p>If the first argument is of type JoinPoint or ProceedingJoinPoint then we
	 * pass a JoinPoint in that position (ProceedingJoinPoint for around advice).
	 *
	 * 如果第一个参数是JoinPoint或者ProceedingJoinPoint，那么我们在那个位置放置一个JoinPoint
	 *
	 * <p>If the first argument is of type {@code JoinPoint.StaticPart}
	 * then we pass a {@code JoinPoint.StaticPart} in that position.
	 *
	 * 如果第一个参数是JoinPoint.StaticPart，那么我们在那个位置放置一个JoinPoint.StaticPart
	 *
	 * <p>Remaining arguments have to be bound by pointcut evaluation at
	 * a given join point. We will get back a map from argument name to
	 * value. We need to calculate which advice parameter needs to be bound
	 * to which argument name. There are multiple strategies for determining
	 * this binding, which are arranged in a ChainOfResponsibility.
	 *
	 * 剩余的参数需要去在一个给出的join point去绑定通过pointcut的解析。
	 * 我们将会返回一个map，是参数名到值的映射。
	 * 我们需要去计算哪一个advice的参数需要去绑定到哪一个参数名。
	 * 对于找出这个绑定关系有许多策略，其中一个是按ChainOfResponsibility来排序
	 */
	public final synchronized void calculateArgumentBindings() {
		// The simple case... nothing to bind.
		// 如果argumentsIntrospected为true 或者 参数类型长度为0，直接返回
		if (this.argumentsIntrospected || this.parameterTypes.length == 0) {
			return;
		}

		// 获取参数类型的长度
		int numUnboundArgs = this.parameterTypes.length;
		// 获取adviceMethod的参数类型数组
		Class<?>[] parameterTypes = this.aspectJAdviceMethod.getParameterTypes();
		// 判断第一个参数类型是否是JoinPoint.class 或者
		// ProceedingJoinPoint.class(这种类型只有AspectJAroundAdvice才可以存在) 或者 JoinPoint.StaticPart.class
		if (maybeBindJoinPoint(parameterTypes[0]) || maybeBindProceedingJoinPoint(parameterTypes[0]) ||
				maybeBindJoinPointStaticPart(parameterTypes[0])) {
			// 如果是的话，将未绑定的参数个数-1
			numUnboundArgs--;
		}

		// 如果未绑定的参数个数大于0的话
		if (numUnboundArgs > 0) {
			// need to bind arguments by name as returned from the pointcut match
			// 那么根据name去绑定参数
			bindArgumentsByName(numUnboundArgs);
		}

		// 将argumentsIntrospected设置为true
		this.argumentsIntrospected = true;
	}

	private boolean maybeBindJoinPoint(Class<?> candidateParameterType) {
		if (JoinPoint.class == candidateParameterType) {
			this.joinPointArgumentIndex = 0;
			return true;
		}
		else {
			return false;
		}
	}

	private boolean maybeBindProceedingJoinPoint(Class<?> candidateParameterType) {
		if (ProceedingJoinPoint.class == candidateParameterType) {
			if (!supportsProceedingJoinPoint()) {
				throw new IllegalArgumentException("ProceedingJoinPoint is only supported for around advice");
			}
			this.joinPointArgumentIndex = 0;
			return true;
		}
		else {
			return false;
		}
	}

	protected boolean supportsProceedingJoinPoint() {
		return false;
	}

	private boolean maybeBindJoinPointStaticPart(Class<?> candidateParameterType) {
		if (JoinPoint.StaticPart.class == candidateParameterType) {
			this.joinPointStaticPartArgumentIndex = 0;
			return true;
		}
		else {
			return false;
		}
	}

	private void bindArgumentsByName(int numArgumentsExpectingToBind) {
		// 如果参数名数组为null的话
		if (this.argumentNames == null) {
			// 创建参数名发现器去查找adviceMethod的参数名
			this.argumentNames = createParameterNameDiscoverer().getParameterNames(this.aspectJAdviceMethod);
		}
		// 如果参数名数组不为null
		if (this.argumentNames != null) {
			// We have been able to determine the arg names.
			// 调用bindExplicitArguments去绑定参数
			bindExplicitArguments(numArgumentsExpectingToBind);
		}
		else {
			throw new IllegalStateException("Advice method [" + this.aspectJAdviceMethod.getName() + "] " +
					"requires " + numArgumentsExpectingToBind + " arguments to be bound by name, but " +
					"the argument names were not specified and could not be discovered.");
		}
	}

	/**
	 * Create a ParameterNameDiscoverer to be used for argument binding.
	 * <p>The default implementation creates a {@link DefaultParameterNameDiscoverer}
	 * and adds a specifically configured {@link AspectJAdviceParameterNameDiscoverer}.
	 */
	protected ParameterNameDiscoverer createParameterNameDiscoverer() {
		// We need to discover them, or if that fails, guess,
		// and if we can't guess with 100% accuracy, fail.
		// 先创建一个DefaultParameterNameDiscoverer
		DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
		// 根据pointcut的expression创建一个AspectJAdviceParameterNameDiscover
		AspectJAdviceParameterNameDiscoverer adviceParameterNameDiscoverer =
				new AspectJAdviceParameterNameDiscoverer(this.pointcut.getExpression());
		// 向AspectJAdviceParameterNameDiscover中设置自身的returningName和throwingName
		adviceParameterNameDiscoverer.setReturningName(this.returningName);
		adviceParameterNameDiscoverer.setThrowingName(this.throwingName);
		// Last in chain, so if we're called and we fail, that's bad...
		// 设置raiseException为true
		adviceParameterNameDiscoverer.setRaiseExceptions(true);
		discoverer.addDiscoverer(adviceParameterNameDiscoverer);
		return discoverer;
	}

	private void bindExplicitArguments(int numArgumentsLeftToBind) {
		Assert.state(this.argumentNames != null, "No argument names available");
		// 创建一个HashMap赋值给argumentBindings
		this.argumentBindings = new HashMap<>();

		// 获取adviceMethod的参数个数
		int numExpectedArgumentNames = this.aspectJAdviceMethod.getParameterCount();
		// 如果参数名数组的长度不等于adviceMethod的参数个数，报错
		if (this.argumentNames.length != numExpectedArgumentNames) {
			throw new IllegalStateException("Expecting to find " + numExpectedArgumentNames +
					" arguments to bind by name in advice, but actually found " +
					this.argumentNames.length + " arguments.");
		}

		// So we match in number...
		// 用参数类型个数-剩余的要绑定的参数个数=需要绑定的参数的偏移量
		int argumentIndexOffset = this.parameterTypes.length - numArgumentsLeftToBind;
		// 将参数名和对应下标存入到argumentBindings中
		for (int i = argumentIndexOffset; i < this.argumentNames.length; i++) {
			this.argumentBindings.put(this.argumentNames[i], i);
		}

		// Check that returning and throwing were in the argument names list if
		// specified, and find the discovered argument types.
		// 如果returningName存在
		if (this.returningName != null) {
			// 但是在参数绑定的map中不包含returningName这个key，报错
			if (!this.argumentBindings.containsKey(this.returningName)) {
				throw new IllegalStateException("Returning argument name '" + this.returningName +
						"' was not bound in advice arguments");
			}
			// 如果returningName存在于argumentBindings的key中
			else {
				// 找到对应的参数下标
				Integer index = this.argumentBindings.get(this.returningName);
				// 将discoveredReturningType和discoveredReturningGenericType都从adviceMethod这个方法反射对象中解析出来
				this.discoveredReturningType = this.aspectJAdviceMethod.getParameterTypes()[index];
				this.discoveredReturningGenericType = this.aspectJAdviceMethod.getGenericParameterTypes()[index];
			}
		}
		// throwingName的解析同上
		if (this.throwingName != null) {
			if (!this.argumentBindings.containsKey(this.throwingName)) {
				throw new IllegalStateException("Throwing argument name '" + this.throwingName +
						"' was not bound in advice arguments");
			}
			else {
				Integer index = this.argumentBindings.get(this.throwingName);
				this.discoveredThrowingType = this.aspectJAdviceMethod.getParameterTypes()[index];
			}
		}

		// configure the pointcut expression accordingly.
		// 配置pointcut相关的参数
		configurePointcutParameters(this.argumentNames, argumentIndexOffset);
	}

	/**
	 * All parameters from argumentIndexOffset onwards are candidates for
	 * pointcut parameters - but returning and throwing vars are handled differently
	 * and must be removed from the list if present.
	 *
	 * 从argumentIndexOffset开始向后的参数都是pointcut参数的候选。
	 * 但是returning和throwing参数需要作不同的处理，要将它们从pointcut的参数中剔除
	 */
	private void configurePointcutParameters(String[] argumentNames, int argumentIndexOffset) {
		// 将argumentIndexOffset作为要删除的参数个数，因为JointPoint相关的参数不会配置到pointcut中
		int numParametersToRemove = argumentIndexOffset;
		// 同时，如果存在returningName或者throwingName，都将要删除的参数个数+1
		if (this.returningName != null) {
			numParametersToRemove++;
		}
		if (this.throwingName != null) {
			numParametersToRemove++;
		}
		// 根据剩余的参数个数创建pointcut相关的参数名数组
		String[] pointcutParameterNames = new String[argumentNames.length - numParametersToRemove];
		// 以及pointcut的参数类型数组
		Class<?>[] pointcutParameterTypes = new Class<?>[pointcutParameterNames.length];
		// 获取adviceMethod的参数类型数组
		Class<?>[] methodParameterTypes = this.aspectJAdviceMethod.getParameterTypes();

		int index = 0;
		// 遍历adviceMethod的参数类型数组
		for (int i = 0; i < argumentNames.length; i++) {
			// 当下标小于argumentIndexOffset时，跳过
			if (i < argumentIndexOffset) {
				continue;
			}
			// 当参数名等于returningName或者throwingName时也跳过
			if (argumentNames[i].equals(this.returningName) ||
					argumentNames[i].equals(this.throwingName)) {
				continue;
			}
			// 将下标i对应的参数放入到pointcut参数名和类型数组的index位置去
			pointcutParameterNames[index] = argumentNames[i];
			pointcutParameterTypes[index] = methodParameterTypes[i];
			index++;
		}

		// 将参数名和参数类型数组设置进pointcut
		this.pointcut.setParameterNames(pointcutParameterNames);
		this.pointcut.setParameterTypes(pointcutParameterTypes);
	}

	/**
	 * Take the arguments at the method execution join point and output a set of arguments
	 * to the advice method.
	 * @param jp the current JoinPoint
	 * @param jpMatch the join point match that matched this execution join point
	 * @param returnValue the return value from the method execution (may be null)
	 * @param ex the exception thrown by the method execution (may be null)
	 * @return the empty array if there are no arguments
	 */
	protected Object[] argBinding(JoinPoint jp, @Nullable JoinPointMatch jpMatch,
			@Nullable Object returnValue, @Nullable Throwable ex) {

		calculateArgumentBindings();

		// AMC start
		// 根据adviceMethod声明的参数类型的长度创建一个数组
		Object[] adviceInvocationArgs = new Object[this.parameterTypes.length];
		int numBound = 0;

		// 如果joinPoint或者joinPointStaticPart参数的index不为-1，将joinPoint或者staticPart放入到对应数组的对应index位置中
		if (this.joinPointArgumentIndex != -1) {
			adviceInvocationArgs[this.joinPointArgumentIndex] = jp;
			numBound++;
		}
		else if (this.joinPointStaticPartArgumentIndex != -1) {
			adviceInvocationArgs[this.joinPointStaticPartArgumentIndex] = jp.getStaticPart();
			numBound++;
		}

		// 如果参数绑定map不为空，根据argumentBindings里面的name到index的映射，对数组对应index的元素进行填充
		if (!CollectionUtils.isEmpty(this.argumentBindings)) {
			// binding from pointcut match
			// 如果joinPointMatch不为null，获取其绑定的参数进行填充
			if (jpMatch != null) {
				PointcutParameter[] parameterBindings = jpMatch.getParameterBindings();
				for (PointcutParameter parameter : parameterBindings) {
					String name = parameter.getName();
					Integer index = this.argumentBindings.get(name);
					adviceInvocationArgs[index] = parameter.getBinding();
					numBound++;
				}
			}
			// 如果存在returningName，获取returningName对应的index，将返回值存入到对应的index
			// binding from returning clause
			if (this.returningName != null) {
				Integer index = this.argumentBindings.get(this.returningName);
				adviceInvocationArgs[index] = returnValue;
				numBound++;
			}
			// 同理，将抛出的异常存入到对应的index
			// binding from thrown exception
			if (this.throwingName != null) {
				Integer index = this.argumentBindings.get(this.throwingName);
				adviceInvocationArgs[index] = ex;
				numBound++;
			}
		}

		// 最后判断绑定成功的参数个数 和 advice方法声明的参数个数是否相等
		if (numBound != this.parameterTypes.length) {
			throw new IllegalStateException("Required to bind " + this.parameterTypes.length +
					" arguments, but only bound " + numBound + " (JoinPointMatch " +
					(jpMatch == null ? "was NOT" : "WAS") + " bound in invocation)");
		}

		// 返回参数数组
		return adviceInvocationArgs;
	}


	/**
	 * Invoke the advice method.
	 * @param jpMatch the JoinPointMatch that matched this execution join point
	 * @param returnValue the return value from the method execution (may be null)
	 * @param ex the exception thrown by the method execution (may be null)
	 * @return the invocation result
	 * @throws Throwable in case of invocation failure
	 */
	protected Object invokeAdviceMethod(
			@Nullable JoinPointMatch jpMatch, @Nullable Object returnValue, @Nullable Throwable ex)
			throws Throwable {
		// 调用对应的advice方法
		// 获取到当前MethodInvocation的attribute里面持有的joinpoint，如果不存在，创建一个存入到attribute里
		// 以及JoinPointMatch 返回值 和 抛出的异常 一起 进行参数绑定
		return invokeAdviceMethodWithGivenArgs(argBinding(getJoinPoint(), jpMatch, returnValue, ex));
	}

	// As above, but in this case we are given the join point.
	protected Object invokeAdviceMethod(JoinPoint jp, @Nullable JoinPointMatch jpMatch,
			@Nullable Object returnValue, @Nullable Throwable t) throws Throwable {

		return invokeAdviceMethodWithGivenArgs(argBinding(jp, jpMatch, returnValue, t));
	}

	protected Object invokeAdviceMethodWithGivenArgs(Object[] args) throws Throwable {
		Object[] actualArgs = args;
		if (this.aspectJAdviceMethod.getParameterCount() == 0) {
			actualArgs = null;
		}
		try {
			// 反射调用aspect里面的advice方法
			ReflectionUtils.makeAccessible(this.aspectJAdviceMethod);
			// TODO AopUtils.invokeJoinpointUsingReflection
			return this.aspectJAdviceMethod.invoke(this.aspectInstanceFactory.getAspectInstance(), actualArgs);
		}
		catch (IllegalArgumentException ex) {
			throw new AopInvocationException("Mismatch on arguments to advice method [" +
					this.aspectJAdviceMethod + "]; pointcut expression [" +
					this.pointcut.getPointcutExpression() + "]", ex);
		}
		catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
	}

	/**
	 * Overridden in around advice to return proceeding join point.
	 */
	protected JoinPoint getJoinPoint() {
		return currentJoinPoint();
	}

	/**
	 * Get the current join point match at the join point we are being dispatched on.
	 */
	@Nullable
	protected JoinPointMatch getJoinPointMatch() {
		// 获取暴露在ExposeInvocationInterceptor中的threadLocal中的MethodInvocation
		MethodInvocation mi = ExposeInvocationInterceptor.currentInvocation();
		if (!(mi instanceof ProxyMethodInvocation)) {
			throw new IllegalStateException("MethodInvocation is not a Spring ProxyMethodInvocation: " + mi);
		}
		// 调用getJoinPointMatch方法根据MethodInvocation获取到当前连接点的匹配
		return getJoinPointMatch((ProxyMethodInvocation) mi);
	}

	// Note: We can't use JoinPointMatch.getClass().getName() as the key, since
	// Spring AOP does all the matching at a join point, and then all the invocations.
	// Under this scenario, if we just use JoinPointMatch as the key, then
	// 'last man wins' which is not what we want at all.
	// Using the expression is guaranteed to be safe, since 2 identical expressions
	// are guaranteed to bind in exactly the same way.
	@Nullable
	protected JoinPointMatch getJoinPointMatch(ProxyMethodInvocation pmi) {
		// 获取到当前aspectJAdvice持有的pointcut的expression
		String expression = this.pointcut.getExpression();
		// 以expression作为key尝试从ProxyMethodInvocation的属性中获取JoinPointMatch
		return (expression != null ? (JoinPointMatch) pmi.getUserAttribute(expression) : null);
	}


	@Override
	public String toString() {
		return getClass().getName() + ": advice method [" + this.aspectJAdviceMethod + "]; " +
				"aspect name '" + this.aspectName + "'";
	}

	private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
		inputStream.defaultReadObject();
		try {
			this.aspectJAdviceMethod = this.declaringClass.getMethod(this.methodName, this.parameterTypes);
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException("Failed to find advice method on deserialization", ex);
		}
	}


	/**
	 * MethodMatcher that excludes the specified advice method.
	 * @see AbstractAspectJAdvice#buildSafePointcut()
	 */
	private static class AdviceExcludingMethodMatcher extends StaticMethodMatcher {

		private final Method adviceMethod;

		public AdviceExcludingMethodMatcher(Method adviceMethod) {
			this.adviceMethod = adviceMethod;
		}

		@Override
		public boolean matches(Method method, Class<?> targetClass) {
			return !this.adviceMethod.equals(method);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof AdviceExcludingMethodMatcher)) {
				return false;
			}
			AdviceExcludingMethodMatcher otherMm = (AdviceExcludingMethodMatcher) other;
			return this.adviceMethod.equals(otherMm.adviceMethod);
		}

		@Override
		public int hashCode() {
			return this.adviceMethod.hashCode();
		}

		@Override
		public String toString() {
			return getClass().getName() + ": " + this.adviceMethod;
		}
	}

}
