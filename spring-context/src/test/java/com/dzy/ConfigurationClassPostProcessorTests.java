package com.dzy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.stereotype.Component;

public class ConfigurationClassPostProcessorTests {

	private final ConfigurationClassPostProcessor processor = new ConfigurationClassPostProcessor();

	private final DefaultListableBeanFactory registry = new DefaultListableBeanFactory();


	// 符合ConfigurationClass条件的非静态内部类也会被ConfigurationClassPostProcessor解析为BeanDefinition保存进registry中。
	// 但是在getBean创建真正的bean实例的时候会报错，因为默认会使用无参构造器去实例化，但是非静态内部类不存在无参构造器。

	// 通过javap反编译出内部类的字节码可以看到，
	// 非静态内部类里面存在的是一个有参构造器，参数就是其外部类对象，并且自身还持有了一个指向外部类实例对象的属性，属性名为this$0,
	// 这些操作都是编译器完成的，因此可以算作是语法糖。

	// optimize1：这里使用无参构造器去创建的主要原始是 determineCandidateConstructors方法返回的是null，没有对应的SmartInstantiationAwareBeanPostProcessor来提供候选的构造器。
	// 所以尝试添加一个AutowiredAnnotationBeanPostProcessor到beanFactory里面，这样就能够找到类中唯一的一个有参构造器作为候选去实例化类，并且会通过resolveDependency去
	// ioc里查找对应的OuterClass这个bean作为参数。

	// 综上，得出结论，非静态内部类是可以注册进ioc并且实例化成功的，前提是能够选择到编译器为它自动生成的有参构造器去进行实例化
	@Test
	public void testNonStaticInnerConfigurationClass() throws Exception {
		GenericBeanDefinition bd = new GenericBeanDefinition();
		bd.setBeanClass(OuterClass.class);
		registry.registerBeanDefinition("outerClass", bd);
		processor.processConfigBeanDefinitions(registry);
		for (String beanDefinitionName : registry.getBeanDefinitionNames()) {
			System.out.println(beanDefinitionName);
		}
		// optimize1
		registry.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor());
		OuterClass.NonStaticInnerClass nonStaticInnerBean = registry.getBean(OuterClass.NonStaticInnerClass.class);
		System.out.println(nonStaticInnerBean);

		// 验证innerBean里面持有的outerBean是否是ioc里面注册的那个outerBean
		OuterClass outerBean = registry.getBean(OuterClass.class);
		System.out.println(outerBean);
		// this$0属性就是编译器默认生成的持有外部类实例的属性名
		System.out.println(OuterClass.NonStaticInnerClass.class.getDeclaredField("this$0").get(nonStaticInnerBean));
	}

	// 扫描的时候，ClassPathScanningCandidateComponentProvider不会扫描非静态内部类，但是在ConfigurationClassParser对ConfigurationClass进行解析的时候，
	// 会将非静态内部类给导入进来，因此可以看出NonStaticInnerClass的beanName是类的全限定名，说明是导入进来的，而不是扫描进来的
	@Test
	public void testScanNonStaticInnerClass() throws Exception {
		GenericBeanDefinition bd = new GenericBeanDefinition();
		bd.setBeanClass(ComponentScanClass.class);
		registry.registerBeanDefinition("componentScanClass", bd);
		processor.processConfigBeanDefinitions(registry);

		for (String beanDefinitionName : registry.getBeanDefinitionNames()) {
			System.out.println("beanName：" + beanDefinitionName);
		}
	}

	// 通过@Bean方法添加的beanDefinition不会被当作ConfigurationClass进行解析，
	// 因为ConfigurationClassUtils的checkConfigurationClassCandidate方法里面，如果bd的beanClassName为null或者存在factoryMethodName的话，都会返回false，
	// 不会当作ConfigurationClass来解析
	@Test
	public void testParseConfigurationClassForBeanMethod() throws Exception {
		GenericBeanDefinition bd = new GenericBeanDefinition();
		bd.setBeanClass(ContainingBeanMethodClass.class);
		registry.registerBeanDefinition("containingBeanMethodClass", bd);
		processor.processConfigBeanDefinitions(registry);

		for (String beanDefinitionName : registry.getBeanDefinitionNames()) {
			System.out.println(beanDefinitionName);
		}
	}

	@Component
	static class OuterClass {

		@Component
		class NonStaticInnerClass {

		}

	}

	@ComponentScan(basePackages = "com.dzy")
	static class ComponentScanClass {

	}

	static class ContainingBeanMethodClass {

		@Bean
		public OuterClass nonStaticFactoryMethod() {
			return new OuterClass();
		}

		@Bean
		public static OuterClass staticFactoryMethod() {
			return new OuterClass();
		}
	}


}
