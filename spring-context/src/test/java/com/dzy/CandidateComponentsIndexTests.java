package com.dzy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.stereotype.Component;

public class CandidateComponentsIndexTests {


	@Test
	public void scanByCandidateComponentsIndex() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(beanFactory);
		scanner.scan("com.dzy");
		for (String beanDefinitionName : beanFactory.getBeanDefinitionNames()) {
			System.out.println(beanDefinitionName);
		}
	}

	@Component
	static class Foo {

	}

	@Component
	static class Bar {

	}
}
