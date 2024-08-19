package com.dzy.test.lazy;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Lazy;

import javax.annotation.Resource;

public class ResourceAndLazyAnnotationTests {


	/**
	 * 测试当@Lazy和@Resource注解同时存在某个属性上时，并且该属性的name和实际的beanName不匹配，会降级为通过类型查找。
	 * 此时会getResourceToInject和resolveDependency都会解析@Lazy注解，会进行两次动态代理。
	 * 第一次是在注入的属性的时候，注入的是一个懒加载的代理对象，每次调用方法时才会调用getResource方法获取实际的bean；
	 * 第二次是在getResource进行解析的时候，由于根据name解析不到，会通过resolveDependency的方法根据类型获取bean，
	 * resolveDependency方法也会解析注入点上的@Lazy注解，生成一个代理对象以实现懒加载，每次调用方法时才会调用doResolveDependency获取实际的bean。
	 *
	 * 因此这种场景下，每次调用该注入的对象的方法，都会因为调用了resolveDependency方法而生成一个动态代理对象，会导致运行时的性能损耗。
	 */
	@Test
	public void testResourceAndLazyAnnotation() {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		GenericBeanDefinition fooBeanDefinition = new GenericBeanDefinition();
		fooBeanDefinition.setBeanClass(Foo.class);

		GenericBeanDefinition barBeanDefinition = new GenericBeanDefinition();
		barBeanDefinition.setBeanClass(Bar.class);
		context.registerBeanDefinition("foo", fooBeanDefinition);
		context.registerBeanDefinition("bar", barBeanDefinition);
		context.refresh();

		Foo foo = context.getBean(Foo.class);

		// 每次调用方法都会生成一个新的动态代理对象
		foo.beanNameNotEqualsBar.proxyBar();
		foo.beanNameNotEqualsBar.proxyBar();
		foo.beanNameNotEqualsBar.proxyBar();
	}

	static class Foo {

		@Lazy
		@Resource
		private Bar beanNameNotEqualsBar;
	}

	static class Bar {

		public void proxyBar() {
			// 获取代理对象，打印出来是否是同一个，如果是上述的情况下，每调用一次方法代理对象都是一个新的对象
			// 要获取这个bar的代理类，需要在创建的时候在ProxyConfig里面将exposeProxy参数设置为true，因此需要修改下resolveDependency方法创建lazy代理的源代码，
			// 即ContextAnnotationAutowireCandidateResolver里面的buildLazyResolutionProxy方法里面的proxyFactory的exposeProxy属性。
			Bar proxy = (Bar) AopContext.currentProxy();
			// 这里打印出的使用是target对象的类名和内存地址，原因是由于没有advisor进行增强，toString方法会调用methodProxy.invoke(target, args)，
			// 会通过invokevirtual调用到target对象的toString方法，因此打印出来的值都一样，因为target对象只有一个，即beanFactory中持有的单例
			System.out.println(proxy);
			System.out.println("this bar target's proxy object is " + proxy.getClass().getName() + "@" + Integer.toHexString(proxy.hashCode()));
		}
	}
}
