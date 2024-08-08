package com.dzy.test.aspectj;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.annotation.MetadataAwareAspectInstanceFactory;
import org.springframework.aop.aspectj.annotation.ReflectiveAspectJAdvisorFactory;
import org.springframework.aop.aspectj.annotation.SingletonMetadataAwareAspectInstanceFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;
import org.springframework.aop.target.SingletonTargetSource;

import java.math.BigDecimal;
import java.util.List;

public class AspectJAdviceMethodInterceptorTests {


	@Test
	public void testInvocableCloneForProceedingJoinPoint() {
		MetadataAwareAspectInstanceFactory aif = new SingletonMetadataAwareAspectInstanceFactory(new AspectJTestAspect(), "testAspect");
		List<Advisor> advisors = new ReflectiveAspectJAdvisorFactory().getAdvisors(aif);
		advisors.add(0, ExposeInvocationInterceptor.ADVISOR);

		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTargetSource(new SingletonTargetSource(new Foo()));
		proxyFactory.setExposeProxy(true);
		proxyFactory.addAdvisors(advisors);
		Foo proxyFoo = (Foo) proxyFactory.getProxy();

		BigDecimal result = proxyFoo.add(BigDecimal.valueOf(1), BigDecimal.valueOf(2));
		System.out.println(result);
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

		@Around(value = "execution(* com.dzy.test.aspectj.AspectJAdviceMethodInterceptorTests.Foo.add(..)) && args(param2, param1)")
		public Object around2(ProceedingJoinPoint pjp, BigDecimal param1, BigDecimal param2) throws Throwable {
			System.out.println("enter aspectJ around advice method: around2");
			System.out.println("param1: " + param1  + ", param2: " + param2);
			Object returnVal = pjp.proceed();
			System.out.println("return aspectJ around advice method: around2");
			return returnVal;
		}


	}

	static class Foo {

		public BigDecimal add(BigDecimal num1, BigDecimal num2) {
			return num1.add(num2);
		}
	}
}
