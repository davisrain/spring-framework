package com.dzy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import java.util.ArrayList;
import java.util.List;

public class DeferredImportSelectorHandlerTests {

	@Test
	public void testDeferredImportSelector() throws Exception {
		ConfigurationClassParser parser = new ConfigurationClassParser(new CachingMetadataReaderFactory(),
				new FailFastProblemReporter(), new StandardEnvironment(), new DefaultResourceLoader(), AnnotationBeanNameGenerator.INSTANCE, new DefaultListableBeanFactory());
		ConfigurationClassParser.DeferredImportSelectorHandler handler = parser.new DeferredImportSelectorHandler();
		ConfigurationClass fooConfigClass = new ConfigurationClass(Foo.class, "foo");
		Class<?>[] importedClasses = Foo.class.getAnnotation(Import.class).value();
		for (Class<?> importedClass : importedClasses) {
			handler.handle(fooConfigClass, (DeferredImportSelector) importedClass.newInstance());
		}
		handler.process();
		System.out.println("finish");
	}

	@Import({CustomImportSelector1.class, CustomImportSelector2.class})
	static class Foo {

	}


	// 会按照getImportGroup方法返回的group在DeferredImportSelectorGroupingHandler中将selectors进行分组。
	// 如果返回null的话，就以自身这个DeferredImportSelector来分组，所以一定是自己一组。

	// group类型相同的selector会被维护在同一个DeferredImportSelectorGrouping中，
	// 每个group提供了对其grouping里面维护的selector进行增强处理的接口，即Group类的process方法，
	// 然后再调用group的selectImports方法将处理后的同一group的所有selector的select的结果封装为Entry集合返回。

	// 同一group的selectors，会将filter进行或操作，然后通过聚合的filter来进行后续的过滤操作。
	// 遍历每个group下的Entry集合，递归调用processImports方法进行解析
	static class CustomImportSelector1 implements DeferredImportSelector {

		@Override
		public String[] selectImports(AnnotationMetadata importingClassMetadata) {
			return new String[] {"com.dzy.DeferredImportSelectorHandlerTests$ImportedClass1"};
		}

		@Override
		public Class<? extends Group> getImportGroup() {
			return CustomGroup.class;
		}

	}

	static class CustomImportSelector2 implements DeferredImportSelector {

		@Override
		public Class<? extends Group> getImportGroup() {
			return CustomGroup.class;
		}

		@Override
		public String[] selectImports(AnnotationMetadata importingClassMetadata) {
			return new String[] {"com.dzy.DeferredImportSelectorHandlerTests$ImportedClass2"};
		}
	}


	static class CustomGroup implements DeferredImportSelector.Group {

		private final List<Entry> groupImports = new ArrayList<>();


		// 对同一个group下的 importSelector的增强方法，比如在select前后进行日志的打印
		@Override
		public void process(AnnotationMetadata metadata, DeferredImportSelector selector) {
			System.out.println("start to invoke importSelector: " + selector);
			String[] imports = selector.selectImports(metadata);
			System.out.println("end to invoke importSelector: " + selector + ", selected " + imports.length + " elements");
			for (String i :imports){
				groupImports.add(new Entry(metadata, i));
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			return groupImports;
		}
	}


	static class ImportingClass {

	}

	static class ImportedClass1 {

	}

	static class ImportedClass2 {

	}
}
