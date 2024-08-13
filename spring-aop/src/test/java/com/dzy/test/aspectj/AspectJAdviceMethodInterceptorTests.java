package com.dzy.test.aspectj;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.DeclareParents;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.annotation.MetadataAwareAspectInstanceFactory;
import org.springframework.aop.aspectj.annotation.ReflectiveAspectJAdvisorFactory;
import org.springframework.aop.aspectj.annotation.SingletonMetadataAwareAspectInstanceFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.cglib.core.Signature;
import org.springframework.cglib.core.TypeUtils;
import org.springframework.cglib.proxy.MethodProxy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.util.List;

public class AspectJAdviceMethodInterceptorTests {


	@Test
	public void testInvocableCloneForProceedingJoinPoint() throws Throwable {
		MetadataAwareAspectInstanceFactory aif = new SingletonMetadataAwareAspectInstanceFactory(new AspectJTestAspect(), "testAspect");
		List<Advisor> advisors = new ReflectiveAspectJAdvisorFactory().getAdvisors(aif);
		advisors.add(0, ExposeInvocationInterceptor.ADVISOR);

		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTargetSource(new SingletonTargetSource(new Foo()));
		proxyFactory.setExposeProxy(true);
		proxyFactory.setProxyTargetClass(true);
		proxyFactory.addAdvisors(advisors);
		Foo proxyFoo = (Foo) proxyFactory.getProxy();

		BigDecimal result = proxyFoo.add(BigDecimal.valueOf(1), BigDecimal.valueOf(2));
		System.out.println(result);

		FooInterface proxyFooInterface = (FooInterface) proxyFoo;
		proxyFooInterface.bar();

		// 获取对应的cglib代理出来的MethodProxy，通过CGLIB$findMethodProxy方法
		MethodType methodType = MethodType.methodType(MethodProxy.class, new Class[]{Signature.class});
		MethodHandle cglib$findMethodProxy = MethodHandles.lookup().findStatic(proxyFoo.getClass(), "CGLIB$findMethodProxy", methodType);
		MethodProxy methodProxy = (MethodProxy) cglib$findMethodProxy.invoke(TypeUtils.parseSignature("java.math.BigDecimal add(java.math.BigDecimal, java.math.BigDecimal)"));
		Object addResult = methodProxy.invokeSuper(proxyFoo, new Object[]{BigDecimal.valueOf(1), BigDecimal.valueOf(2)});
		System.out.println(addResult);
	}


	@Aspect
	static class AspectJTestAspect {

		@Around(value = "execution(* com.dzy.test.aspectj.AspectJAdviceMethodInterceptorTests.Foo.add(..)) && args(num1, ..)", argNames = "num1")
		public Object around1(ProceedingJoinPoint pjp, BigDecimal num1) throws Throwable {
			System.out.println("enter aspectJ around advice method: around1");
			Object returnVal = pjp.proceed();
			System.out.println("return aspectJ around advice method: around1");
			return returnVal;
		}

		// 当advice方法存在除了JoinPoint ProceedingJoinPoint类型的参数，或者注解中指定了returningName或者throwingName属性对应的参数时。
		// 其余参数都是需要从JoinPoint方法获取的，那么就需要进行参数绑定。生成AbstractAspectJAdvice的时候会有一个calculateArgumentBindings方法，
		// 会计算出advice方法中的多余参数的参数名到参数index的映射，并且会将其参数名和参数类型保存到AspectJExpressionPointcut对象中。

		// 1.保存的PointcutParameter会与expression中的参数进行对应，在进行Pointcut匹配的时候，就会根据对应的参数类型去进行匹配，
		// 比如下面expression中的args(param2, param1)，结合advice上声明的参数，表示的内容是需要匹配的方法是含有两个参数，且类型都是BigDecimal的方法。
		// 2.含有PointcutParameter的pointcut的isRuntime方法会返回true，表示需要进行动态的匹配，在生成MethodInterceptor的步骤的地方，会生成一个
		// InterceptorAndDynamicMethodMatcher对象，表示在执行interceptor链的时候会进行动态的方法匹配，这个时候会将方法的实际参数绑定到JoinPointMatch里面，
		// 并且将JoinPointMatch保存到MethodInvocation的userAttributes里面。
		// 3.在实际执行adviceMethod的时候，会遍历JoinPointMatch里面持有的被增强方法的参数，根据之前计算的argumentBindings，根据参数名拿到对应的参数数组的index，
		// 连同JointPoint、returnVal、throwException一起构造出advice的参数数组，然后进行调用
		@Around(value = "execution(* com.dzy.test.aspectj.AspectJAdviceMethodInterceptorTests.Foo.add(..)) && args(param2, param1)")
		public Object around2(ProceedingJoinPoint pjp, BigDecimal param1, BigDecimal param2) throws Throwable {
			System.out.println("enter aspectJ around advice method: around2");
			System.out.println("param1: " + param1  + ", param2: " + param2);
			Object returnVal = pjp.proceed();
			System.out.println("return aspectJ around advice method: around2");
			return returnVal;
		}


		// this和target表达式含义的不同在于，target是匹配的被代理对象，this匹配的是代理后的对象，大多数情况下是等效的。
		// 但是在存在IntroductionAdvisor的时候会有区别，this可以通过introduction添加的接口来匹配，但是target就不行，因为它匹配的是被代理的对象
		@AfterReturning(returning = "returnVal", pointcut = "target(com.dzy.test.aspectj.AspectJAdviceMethodInterceptorTests.FooInterface) && args(param1, param2)")
		public void afterReturning1(JoinPoint jp, BigDecimal param1, BigDecimal param2, BigDecimal returnVal) {
			System.out.println("enter aspectJ after returning advice method: afterReturning1");
			System.out.println("param1: " + param1 + ", param2: " + param2 + ", returnVal: " + returnVal);
			System.out.println("JoinPoint: " + jp);
			System.out.println("return aspectJ after returning advice method: afterReturning1");
		}

		@AfterReturning(returning = "returnVal", pointcut = "this(com.dzy.test.aspectj.AspectJAdviceMethodInterceptorTests.FooInterface) && args(param1, param2)")
		public void afterReturning2(JoinPoint jp, BigDecimal param1, BigDecimal param2, BigDecimal returnVal) {
			System.out.println("enter aspectJ after returning advice method: afterReturning2");
			System.out.println("param1: " + param1 + ", param2: " + param2 + ", returnVal: " + returnVal);
			System.out.println("JoinPoint: " + jp);
			System.out.println("return aspectJ after returning advice method: afterReturning2");
		}

		@DeclareParents(value = "com.dzy.test.aspectj.AspectJAdviceMethodInterceptorTests.Foo", defaultImpl = FooInterfaceImpl.class)
		private FooInterface fooInterface;


	}

	static class Foo {

		public BigDecimal add(BigDecimal num1, BigDecimal num2) throws RuntimeException {
			System.out.println(this);
			return num1.add(num2);
		}
	}

	interface FooInterface {

		void bar();
	}

	static class FooInterfaceImpl implements FooInterface {

		@Override
		public void bar() {
			System.out.println("invoke bar by FooInterfaceImpl");
		}
	}
}
