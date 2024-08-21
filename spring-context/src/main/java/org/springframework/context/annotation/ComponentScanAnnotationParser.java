/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AbstractTypeHierarchyTraversingFilter;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AspectJTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Parser for the @{@link ComponentScan} annotation.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @see ClassPathBeanDefinitionScanner#scan(String...)
 * @see ComponentScanBeanDefinitionParser
 */
class ComponentScanAnnotationParser {

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final BeanNameGenerator beanNameGenerator;

	private final BeanDefinitionRegistry registry;


	public ComponentScanAnnotationParser(Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator beanNameGenerator, BeanDefinitionRegistry registry) {

		// ConfigurationClassPostProcessor中通过Aware注入的ApplicationContext的Environment
		this.environment = environment;
		// ConfigurationClassPostProcessor中通过Aware注入的ApplicationContext作为ResourceLoader
		this.resourceLoader = resourceLoader;
		// ConfigurationClassPostProcessor中创建的AnnotationBeanNameGenerator，获取@Component、@MangedBean、@Named注解对应的value属性，
		// 如果value属性不为空的话，取其作为beanName，如果为空，使用对应类型的简单类名，首字母小写
		this.beanNameGenerator = beanNameGenerator;
		// DefaultListableBeanFactory
		this.registry = registry;
	}


	public Set<BeanDefinitionHolder> parse(AnnotationAttributes componentScan, String declaringClass) {
		// 根据所持有的属性初始化一个类路径BeanDefinition扫描器
		ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(this.registry,
				// 获取注解的useDefaultFilters属性，默认为true
				componentScan.getBoolean("useDefaultFilters"), this.environment, this.resourceLoader);

		// 获取其nameGenerator属性
		Class<? extends BeanNameGenerator> generatorClass = componentScan.getClass("nameGenerator");
		// 如果注解中的nameGenerator属性是默认的BeanNameGenerator类，那么useInheritedGenerator标志为true
		boolean useInheritedGenerator = (BeanNameGenerator.class == generatorClass);
		// 如果useInheritedGenerator标志是true，使用parser持有的beanNameGenerator，否则实例化注解属性配置的beanNameGenerator
		scanner.setBeanNameGenerator(useInheritedGenerator ? this.beanNameGenerator :
				BeanUtils.instantiateClass(generatorClass));

		// 如果注解的scopedProxy属性不是DEFAULT，那么也设置进扫描器中
		ScopedProxyMode scopedProxyMode = componentScan.getEnum("scopedProxy");
		if (scopedProxyMode != ScopedProxyMode.DEFAULT) {
			// 调用scanner的setScopedProxyMode方法会为scanner创建一个defaultScopedProxyMode为指定scopedProxyMode的
			// AnnotationScopeMetadataResolver
			scanner.setScopedProxyMode(scopedProxyMode);
		}
		// 如果是DEFAULT，获取注解的scopeResolver属性，并且实例化设置进扫描器中
		else {
			// 注解里面默认是AnnotationScopeMetadataResolver，AnnotationScopeMetadataResolver默认的scopedProxyMode是NO
			Class<? extends ScopeMetadataResolver> resolverClass = componentScan.getClass("scopeResolver");
			scanner.setScopeMetadataResolver(BeanUtils.instantiateClass(resolverClass));
		}

		// 将注解的resourcePattern属性设置进扫描器中，默认为**/*.class，表示扫描所有class文件
		scanner.setResourcePattern(componentScan.getString("resourcePattern"));

		// 遍历注解的includeFilters属性，因为其属性方法返回的是一个注解数组，在解析的时候已经根据注解的属性解析为一个map的集合。
		// 对这个集合进行遍历
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("includeFilters")) {
			// 针对每个一个@Filter注解的属性所组成的map，解析其属性，生成TypeFilter组成的集合，添加扫描器的includeFilters中
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				scanner.addIncludeFilter(typeFilter);
			}
		}
		// 同理解析注解的excludeFilters属性
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("excludeFilters")) {
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				scanner.addExcludeFilter(typeFilter);
			}
		}

		// 获取注解的lazyInit属性，默认是false
		boolean lazyInit = componentScan.getBoolean("lazyInit");
		// 如果为true，设置扫描器的BeanDefinitionDefaults中的lazyInit属性，表示扫描出的bd默认的lazyInit都为true
		if (lazyInit) {
			scanner.getBeanDefinitionDefaults().setLazyInit(true);
		}

		// 然后解析注解的basePackages属性
		Set<String> basePackages = new LinkedHashSet<>();
		String[] basePackagesArray = componentScan.getStringArray("basePackages");
		// 遍历基本的包名数组
		for (String pkg : basePackagesArray) {
			// 将每个包名通过environment解析占位符后，根据提供,; \t\n等标志拆分为数组
			String[] tokenized = StringUtils.tokenizeToStringArray(this.environment.resolvePlaceholders(pkg),
					ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
			// 将得到的结果全部存入basePackages这个set中
			Collections.addAll(basePackages, tokenized);
		}
		// 解析注解的basePackageClasses属性，根据提供的类，获取其包名，然后添加进set中
		for (Class<?> clazz : componentScan.getClassArray("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}
		// 如果基础包的set仍为空的
		if (basePackages.isEmpty()) {
			// 获取标注@ComponentScan注解的类的包名，添加进去
			basePackages.add(ClassUtils.getPackageName(declaringClass));
		}

		// 向scanner中添加一个excludeFilter，将声明当前@ComponentScan注解的类排除在外
		scanner.addExcludeFilter(new AbstractTypeHierarchyTraversingFilter(false, false) {
			@Override
			protected boolean matchClassName(String className) {
				return declaringClass.equals(className);
			}
		});
		// 调用scanner的doScan方法，针对basePackages进行具体的扫描逻辑
		return scanner.doScan(StringUtils.toStringArray(basePackages));
	}

	private List<TypeFilter> typeFiltersFor(AnnotationAttributes filterAttributes) {
		List<TypeFilter> typeFilters = new ArrayList<>();
		// 获取@Filter注解的type属性
		FilterType filterType = filterAttributes.getEnum("type");

		// 获取@Filter注解的classes属性进行遍历，即classes数组里面的每一个元素都会生成一个TypeFilter
		for (Class<?> filterClass : filterAttributes.getClassArray("classes")) {
			// 根据type属性的不同，通过filterClass生成不同类型的TypeFilter添加进集合中
			switch (filterType) {
				case ANNOTATION:
					Assert.isAssignable(Annotation.class, filterClass,
							"@ComponentScan ANNOTATION type filter requires an annotation type");
					@SuppressWarnings("unchecked")
					Class<Annotation> annotationType = (Class<Annotation>) filterClass;
					typeFilters.add(new AnnotationTypeFilter(annotationType));
					break;
				case ASSIGNABLE_TYPE:
					typeFilters.add(new AssignableTypeFilter(filterClass));
					break;
				case CUSTOM:
					Assert.isAssignable(TypeFilter.class, filterClass,
							"@ComponentScan CUSTOM type filter requires a TypeFilter implementation");

					TypeFilter filter = ParserStrategyUtils.instantiateClass(filterClass, TypeFilter.class,
							this.environment, this.resourceLoader, this.registry);
					typeFilters.add(filter);
					break;
				default:
					throw new IllegalArgumentException("Filter type not supported with Class value: " + filterType);
			}
		}

		// 获取@Filter注解的pattern属性
		for (String expression : filterAttributes.getStringArray("pattern")) {
			// 根据type属性的不同，通过expression生成不同TypeFilter添加进集合
			switch (filterType) {
				case ASPECTJ:
					typeFilters.add(new AspectJTypeFilter(expression, this.resourceLoader.getClassLoader()));
					break;
				case REGEX:
					typeFilters.add(new RegexPatternTypeFilter(Pattern.compile(expression)));
					break;
				default:
					throw new IllegalArgumentException("Filter type not supported with String pattern: " + filterType);
			}
		}

		// 返回TypeFilter集合
		return typeFilters;
	}

}
