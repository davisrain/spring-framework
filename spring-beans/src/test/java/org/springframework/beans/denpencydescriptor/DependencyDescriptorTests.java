package org.springframework.beans.denpencydescriptor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.ResolvableType;
import org.springframework.core.testfixture.stereotype.Component;

import java.lang.reflect.Field;

public class DependencyDescriptorTests {


	@Test
	public void testDependencyDescriptorResolvableType() throws Exception {
		Field f1 = Foo.class.getDeclaredField("f1");
		DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(f1, true);
		dependencyDescriptor.increaseNestingLevel();
		ResolvableType resolvableType = dependencyDescriptor.getResolvableType();
		System.out.println(resolvableType);
	}

	static class Foo<T> {

		private ObjectProvider<T[]> f1;

	}
}
