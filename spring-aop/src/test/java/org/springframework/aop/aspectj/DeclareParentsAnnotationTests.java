package org.springframework.aop.aspectj;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.DeclareParents;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.annotation.ReflectiveAspectJAdvisorFactory;
import org.springframework.aop.aspectj.annotation.SimpleMetadataAwareAspectInstanceFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.SingletonTargetSource;

import java.util.List;

public class DeclareParentsAnnotationTests {


	@Test
	public void testDeclareParentsAnnotationInAspect() {
		ReflectiveAspectJAdvisorFactory aspectJAdvisorFactory = new ReflectiveAspectJAdvisorFactory();
		List<Advisor> advisors = aspectJAdvisorFactory.getAdvisors(new SimpleMetadataAwareAspectInstanceFactory(DeclareParentsAspect.class, "declareParentsAspect"));
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTargetSource(new SingletonTargetSource(new Dog()));
		proxyFactory.setProxyTargetClass(true);
		proxyFactory.addAdvisors(advisors);
		Dog dog = (Dog) proxyFactory.getProxy();
		dog.sleep();
		RegionInfo regionInfo = (RegionInfo) dog;
		System.out.println(regionInfo.getRegion());
	}

	static class Dog {
		public void sleep() {
			System.out.println("dog is sleeping");
		}
	}

	@Aspect
	static class DeclareParentsAspect {

		@DeclareParents(value = "org.springframework.aop.aspectj.DeclareParentsAnnotationTests.Dog", defaultImpl = China.class)
		private RegionInfo regionInfo;

		@Pointcut("execution(* org.springframework.aop.aspectj.DeclareParentsAnnotationTests.Dog.*(..))")
		public void pointcut() {

		}

		@Around("pointcut()")
		public Object around(ProceedingJoinPoint pjp) throws Throwable {
			String methodName = pjp.getSignature().getName();
			System.out.println("before method: " + methodName + " invoke");
			Object result = pjp.proceed();
			System.out.println("after method: " + methodName + " invoke");
			return result;
		}
	}

	interface RegionInfo {
		String getRegion();
	}

	static class China implements RegionInfo {

		@Override
		public String getRegion() {
			return "China";
		}
	}
}
