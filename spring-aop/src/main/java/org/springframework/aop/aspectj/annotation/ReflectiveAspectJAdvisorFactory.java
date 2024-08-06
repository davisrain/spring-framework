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

package org.springframework.aop.aspectj.annotation;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.DeclareParents;
import org.aspectj.lang.annotation.Pointcut;

import org.springframework.aop.Advisor;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.aspectj.AspectJAfterAdvice;
import org.springframework.aop.aspectj.AspectJAfterReturningAdvice;
import org.springframework.aop.aspectj.AspectJAfterThrowingAdvice;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.AspectJMethodBeforeAdvice;
import org.springframework.aop.aspectj.DeclareParentsAdvisor;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConvertingComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.comparator.InstanceComparator;

/**
 * Factory that can create Spring AOP Advisors given AspectJ classes from
 * classes honoring AspectJ's annotation syntax, using reflection to invoke the
 * corresponding advice methods.
 *
 * @author Rod Johnson
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 2.0
 */
@SuppressWarnings("serial")
public class ReflectiveAspectJAdvisorFactory extends AbstractAspectJAdvisorFactory implements Serializable {

	private static final Comparator<Method> METHOD_COMPARATOR;

	static {
		// Note: although @After is ordered before @AfterReturning and @AfterThrowing,
		// an @After advice method will actually be invoked after @AfterReturning and
		// @AfterThrowing methods due to the fact that AspectJAfterAdvice.invoke(MethodInvocation)
		// invokes proceed() in a `try` block and only invokes the @After advice method
		// in a corresponding `finally` block.
		Comparator<Method> adviceKindComparator = new ConvertingComparator<>(
				// 将Annotation按照声明的顺序进行排序
				new InstanceComparator<>(
						Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class),
				// 将方法转换为AspectJAnnotation类型
				(Converter<Method, Annotation>) method -> {
					AspectJAnnotation<?> ann = AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(method);
					return (ann != null ? ann.getAnnotation() : null);
				});
		// 将方法转换为方法名，然后按照方法名进行排序
		Comparator<Method> methodNameComparator = new ConvertingComparator<>(Method::getName);
		// 因此METHOD_COMPARATOR的排序逻辑就是先根据方法上标注的注解类型进行排序，如果注解类型相同，再根据方法名进行排序
		METHOD_COMPARATOR = adviceKindComparator.thenComparing(methodNameComparator);
	}


	@Nullable
	private final BeanFactory beanFactory;


	/**
	 * Create a new {@code ReflectiveAspectJAdvisorFactory}.
	 */
	public ReflectiveAspectJAdvisorFactory() {
		this(null);
	}

	/**
	 * Create a new {@code ReflectiveAspectJAdvisorFactory}, propagating the given
	 * {@link BeanFactory} to the created {@link AspectJExpressionPointcut} instances,
	 * for bean pointcut handling as well as consistent {@link ClassLoader} resolution.
	 * @param beanFactory the BeanFactory to propagate (may be {@code null}}
	 * @since 4.3.6
	 * @see AspectJExpressionPointcut#setBeanFactory
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#getBeanClassLoader()
	 */
	public ReflectiveAspectJAdvisorFactory(@Nullable BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}


	@Override
	public List<Advisor> getAdvisors(MetadataAwareAspectInstanceFactory aspectInstanceFactory) {
		// 获取aspectInstanceFactory中持有的aspectMetadata中的aspectClass
		Class<?> aspectClass = aspectInstanceFactory.getAspectMetadata().getAspectClass();
		// 并且获取到aspectMetadata中的aspectName
		String aspectName = aspectInstanceFactory.getAspectMetadata().getAspectName();
		// 验证aspectClass
		validate(aspectClass);

		// We need to wrap the MetadataAwareAspectInstanceFactory with a decorator
		// so that it will only instantiate once.
		// 将aspectInstanceFactory进行装饰，将其装饰为LazySingletonAspectInstanceFactoryDecorator类型的。
		// 这样会使得aspect只用实例化一次，因为该装饰器里面缓存了第一个实例化的aspect对象
		MetadataAwareAspectInstanceFactory lazySingletonAspectInstanceFactory =
				new LazySingletonAspectInstanceFactoryDecorator(aspectInstanceFactory);

		List<Advisor> advisors = new ArrayList<>();
		// 根据aspectClass获取到advisorMethods，获取逻辑是排除标注了@Pointcut注解的方法，
		// 并且方法按照特定的注解顺序进行排序，如果标注的注解类型一致，按照方法名进行排序
		for (Method method : getAdvisorMethods(aspectClass)) {
			// Prior to Spring Framework 5.2.7, advisors.size() was supplied as the declarationOrderInAspect
			// to getAdvisor(...) to represent the "current position" in the declared methods list.
			// However, since Java 7 the "current position" is not valid since the JDK no longer
			// returns declared methods in the order in which they are declared in the source code.
			// Thus, we now hard code the declarationOrderInAspect to 0 for all advice methods
			// discovered via reflection in order to support reliable advice ordering across JVM launches.
			// Specifically, a value of 0 aligns with the default value used in
			// AspectJPrecedenceComparator.getAspectDeclarationOrder(Advisor).

			// 根据adviceMethod AspectJExpressionPointcut aspectJAdvice创建了一个InstantiationModelAwarePointcutAdvisorImpl对象返回
			Advisor advisor = getAdvisor(method, lazySingletonAspectInstanceFactory, 0, aspectName);
			// 如果获取到的advisor不为null的话，添加进advisors集合中
			if (advisor != null) {
				advisors.add(advisor);
			}
		}

		// If it's a per target aspect, emit the dummy instantiating aspect.
		// 如果advisors集合不为空 并且 aspectClass的perClause不是SINGLETON的，创建一个SyntheticInstantiationAdvisor插入到advisors集合的第一个。
		// 里面维护了一个MethodBeforeAdvice，在方法调用之前调用aif的getAspectInstance方法将aspect实例化
		if (!advisors.isEmpty() && lazySingletonAspectInstanceFactory.getAspectMetadata().isLazilyInstantiated()) {
			Advisor instantiationAdvisor = new SyntheticInstantiationAdvisor(lazySingletonAspectInstanceFactory);
			advisors.add(0, instantiationAdvisor);
		}

		// Find introduction fields.
		// 遍历aspectClass声明的字段
		for (Field field : aspectClass.getDeclaredFields()) {
			// 根据字段上的标注的@DelareParents注解生成对应的DeclareParentsAdvisor
			Advisor advisor = getDeclareParentsAdvisor(field);
			// 如果生成的advisor不为null，添加到advisors集合中
			if (advisor != null) {
				advisors.add(advisor);
			}
		}

		// 返回advisors集合
		return advisors;
	}

	private List<Method> getAdvisorMethods(Class<?> aspectClass) {
		final List<Method> methods = new ArrayList<>();
		// 排除标注了@Pointcut注解的方法，将其他方法添加到methods中
		ReflectionUtils.doWithMethods(aspectClass, method -> {
			// Exclude pointcuts
			if (AnnotationUtils.getAnnotation(method, Pointcut.class) == null) {
				methods.add(method);
			}
		}, ReflectionUtils.USER_DECLARED_METHODS);
		// 如果methods的长度大于1
		if (methods.size() > 1) {
			// 将其根据METHOD_COMPARATOR排序
			methods.sort(METHOD_COMPARATOR);
		}
		return methods;
	}

	/**
	 * Build a {@link org.springframework.aop.aspectj.DeclareParentsAdvisor}
	 * for the given introduction field.
	 * <p>Resulting Advisors will need to be evaluated for targets.
	 * @param introductionField the field to introspect
	 * @return the Advisor instance, or {@code null} if not an Advisor
	 */
	@Nullable
	private Advisor getDeclareParentsAdvisor(Field introductionField) {
		// 获取字段上标注的@DeclareParents注解
		DeclareParents declareParents = introductionField.getAnnotation(DeclareParents.class);
		// 如果注解不存在，返回null
		if (declareParents == null) {
			// Not an introduction field
			return null;
		}

		// 如果注解的defaultImpl属性为DeclareParents.class 报错
		if (DeclareParents.class == declareParents.defaultImpl()) {
			throw new IllegalStateException("'defaultImpl' attribute must be set on DeclareParents");
		}

		// 根据字段的类型 注解的value属性 和注解的defaultImpl属性生成一个DeclareParentsAdvisor返回
		return new DeclareParentsAdvisor(
				introductionField.getType(), declareParents.value(), declareParents.defaultImpl());
	}


	@Override
	@Nullable
	public Advisor getAdvisor(Method candidateAdviceMethod, MetadataAwareAspectInstanceFactory aspectInstanceFactory,
			int declarationOrderInAspect, String aspectName) {

		// 验证aspectClass的合法性
		validate(aspectInstanceFactory.getAspectMetadata().getAspectClass());

		// 根据adviceMethod和aspectClass获取pointcut
		AspectJExpressionPointcut expressionPointcut = getPointcut(
				candidateAdviceMethod, aspectInstanceFactory.getAspectMetadata().getAspectClass());
		// 如果返回的expressionPointcut为null，直接返回null
		if (expressionPointcut == null) {
			return null;
		}

		// 根据expressionPointcut candidateAdviceMethod aspectJAdvisorFactory aspectInstanceFactory declarationOrderInAspect aspectName
		// 创建一个InstantiationModelAwarePointcutAdvisorImpl返回
		return new InstantiationModelAwarePointcutAdvisorImpl(expressionPointcut, candidateAdviceMethod,
				this, aspectInstanceFactory, declarationOrderInAspect, aspectName);
	}

	@Nullable
	private AspectJExpressionPointcut getPointcut(Method candidateAdviceMethod, Class<?> candidateAspectClass) {
		// 获取候选方法上的AspectJ相关的注解，封装成AspectJAnnotation返回
		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
		// 如果方法上没有标注AspectJ相关的注解，直接返回null
		if (aspectJAnnotation == null) {
			return null;
		}

		// 根据候选的aspectClass生成一个AspectJExpressionPointcut对象
		AspectJExpressionPointcut ajexp =
				new AspectJExpressionPointcut(candidateAspectClass, new String[0], new Class<?>[0]);
		// 并且将aspectJAnnotation中的expression字段赋值给pointcut的expression
		ajexp.setExpression(aspectJAnnotation.getPointcutExpression());
		// 如果自身的beanFactory不为null，将beanFactory也设置进pointcut中
		if (this.beanFactory != null) {
			ajexp.setBeanFactory(this.beanFactory);
		}
		// 返回pointcut
		return ajexp;
	}


	@Override
	@Nullable
	public Advice getAdvice(Method candidateAdviceMethod, AspectJExpressionPointcut expressionPointcut,
			MetadataAwareAspectInstanceFactory aspectInstanceFactory, int declarationOrder, String aspectName) {

		// 获取aspectInstanceFactory持有的aspectMetadata中的aspectClass
		Class<?> candidateAspectClass = aspectInstanceFactory.getAspectMetadata().getAspectClass();
		// 验证候选的aspectClass的合理性
		validate(candidateAspectClass);

		// 获取候选adviceMethod上的AspectJ相关的注解，并封装成AspectJAnnotation返回
		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
		// 如果不存在对应的AspectJAnnotation，直接返回null
		if (aspectJAnnotation == null) {
			return null;
		}

		// If we get here, we know we have an AspectJ method.
		// Check that it's an AspectJ-annotated class
		// 如果候选的aspectClass没有标注@Aspect注解，报错
		if (!isAspect(candidateAspectClass)) {
			throw new AopConfigException("Advice must be declared inside an aspect type: " +
					"Offending method '" + candidateAdviceMethod + "' in class [" +
					candidateAspectClass.getName() + "]");
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Found AspectJ method: " + candidateAdviceMethod);
		}

		AbstractAspectJAdvice springAdvice;

		// 判断AspectJAnnotation的注解类型，根据不同的类型，生成不同的AspectJAdvice赋值给springAdvice
		switch (aspectJAnnotation.getAnnotationType()) {
			// 如果是标注的是@Pointcut注解，跳过，直接返回null
			case AtPointcut:
				if (logger.isDebugEnabled()) {
					logger.debug("Processing pointcut '" + candidateAdviceMethod.getName() + "'");
				}
				return null;
				// 如果标注的是@Around注解，创建一个AspectJAroundAdvice
			case AtAround:
				springAdvice = new AspectJAroundAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
				// 如果标注的是@Before注解，创建一个AspectJMethodBeforeAdvice
			case AtBefore:
				springAdvice = new AspectJMethodBeforeAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
				// 如果标注的是@After注解，创建一个AspectJAfterAdvice
			case AtAfter:
				springAdvice = new AspectJAfterAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
				// 如果标注的是@AfterReturning注解，创建一个AspectJAfterReturningAdvice
			case AtAfterReturning:
				springAdvice = new AspectJAfterReturningAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				// 获取@AfterReturnning注解
				AfterReturning afterReturningAnnotation = (AfterReturning) aspectJAnnotation.getAnnotation();
				// 如果注解的returning属性有值
				if (StringUtils.hasText(afterReturningAnnotation.returning())) {
					// 将其设置进advice的returningName中
					springAdvice.setReturningName(afterReturningAnnotation.returning());
				}
				break;
				// 如果标注的是@AfterThrowing注解，创建一个AspectJAfterThrowingAdvice
			case AtAfterThrowing:
				springAdvice = new AspectJAfterThrowingAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				// 获取@AfterThrowing注解
				AfterThrowing afterThrowingAnnotation = (AfterThrowing) aspectJAnnotation.getAnnotation();
				// 如果注解的throwing属性有值
				if (StringUtils.hasText(afterThrowingAnnotation.throwing())) {
					// 将其设置进advice的throwingName中
					springAdvice.setThrowingName(afterThrowingAnnotation.throwing());
				}
				break;
			default:
				throw new UnsupportedOperationException(
						"Unsupported advice type on method: " + candidateAdviceMethod);
		}

		// Now to configure the advice...
		// 设置aspectName进advice中
		springAdvice.setAspectName(aspectName);
		// 设置declarationOrder进advice中
		springAdvice.setDeclarationOrder(declarationOrder);
		// 使用AspectJAnnotationParameterNameDiscover获取出方法的变量名，具体逻辑就是解析方法上标注的AspectJ相关注解的argNames属性，
		// 并且按逗号分隔为String的数组
		String[] argNames = this.parameterNameDiscoverer.getParameterNames(candidateAdviceMethod);
		// 如果解析出来的参数名数组不为null，将其设置进advice中
		if (argNames != null) {
			springAdvice.setArgumentNamesFromStringArray(argNames);
		}
		// 调用advice的calculateArgumentBindings方法，计算参数绑定
		springAdvice.calculateArgumentBindings();

		return springAdvice;
	}


	/**
	 * Synthetic advisor that instantiates the aspect.
	 * Triggered by per-clause pointcut on non-singleton aspect.
	 * The advice has no effect.
	 */
	@SuppressWarnings("serial")
	protected static class SyntheticInstantiationAdvisor extends DefaultPointcutAdvisor {

		public SyntheticInstantiationAdvisor(final MetadataAwareAspectInstanceFactory aif) {
			super(aif.getAspectMetadata().getPerClausePointcut(), (MethodBeforeAdvice)
					(method, args, target) -> aif.getAspectInstance());
		}
	}

}
