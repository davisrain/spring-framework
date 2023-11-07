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

package org.springframework.context.annotation;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.context.annotation.DeferredImportSelector.Group;
import org.springframework.core.NestedIOException;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.DefaultPropertySourceFactory;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Parses a {@link Configuration} class definition, populating a collection of
 * {@link ConfigurationClass} objects (parsing a single Configuration class may result in
 * any number of ConfigurationClass objects because one Configuration class may import
 * another using the {@link Import} annotation).
 *
 * <p>This class helps separate the concern of parsing the structure of a Configuration
 * class from the concern of registering BeanDefinition objects based on the content of
 * that model (with the exception of {@code @ComponentScan} annotations which need to be
 * registered immediately).
 *
 * <p>This ASM-based implementation avoids reflection and eager class loading in order to
 * interoperate effectively with lazy class loading in a Spring ApplicationContext.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 3.0
 * @see ConfigurationClassBeanDefinitionReader
 */
class ConfigurationClassParser {

	private static final PropertySourceFactory DEFAULT_PROPERTY_SOURCE_FACTORY = new DefaultPropertySourceFactory();

	private static final Predicate<String> DEFAULT_EXCLUSION_FILTER = className ->
			(className.startsWith("java.lang.annotation.") || className.startsWith("org.springframework.stereotype."));

	private static final Comparator<DeferredImportSelectorHolder> DEFERRED_IMPORT_COMPARATOR =
			(o1, o2) -> AnnotationAwareOrderComparator.INSTANCE.compare(o1.getImportSelector(), o2.getImportSelector());


	private final Log logger = LogFactory.getLog(getClass());

	private final MetadataReaderFactory metadataReaderFactory;

	private final ProblemReporter problemReporter;

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final BeanDefinitionRegistry registry;

	private final ComponentScanAnnotationParser componentScanParser;

	private final ConditionEvaluator conditionEvaluator;

	private final Map<ConfigurationClass, ConfigurationClass> configurationClasses = new LinkedHashMap<>();

	private final Map<String, ConfigurationClass> knownSuperclasses = new HashMap<>();

	private final List<String> propertySourceNames = new ArrayList<>();

	private final ImportStack importStack = new ImportStack();

	private final DeferredImportSelectorHandler deferredImportSelectorHandler = new DeferredImportSelectorHandler();

	private final SourceClass objectSourceClass = new SourceClass(Object.class);


	/**
	 * Create a new {@link ConfigurationClassParser} instance that will be used
	 * to populate the set of configuration classes.
	 */
	public ConfigurationClassParser(MetadataReaderFactory metadataReaderFactory,
			ProblemReporter problemReporter, Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator componentScanBeanNameGenerator, BeanDefinitionRegistry registry) {

		// 用于生成元数据的reader
		this.metadataReaderFactory = metadataReaderFactory;
		this.problemReporter = problemReporter;
		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.registry = registry;
		// 初始化一个ComponentScanAnnotationParser，即@ComponentScan注解的解析器
		this.componentScanParser = new ComponentScanAnnotationParser(
				environment, resourceLoader, componentScanBeanNameGenerator, registry);
		// 初始化一个ConditionEvaluator，用于解析@Conditional注解
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	public void parse(Set<BeanDefinitionHolder> configCandidates) {
		// 遍历ConfigurationClass的候选bd组成的set
		for (BeanDefinitionHolder holder : configCandidates) {
			// 获取到当前的bd
			BeanDefinition bd = holder.getBeanDefinition();
			try {
				// 如果bd是AnnotatedBeanDefinition类型的，获取到其持有的元数据调用parse的重载方法进行解析
				if (bd instanceof AnnotatedBeanDefinition) {
					parse(((AnnotatedBeanDefinition) bd).getMetadata(), holder.getBeanName());
				}
				// 如果bd是AbstractBeanDefinition类型的，且存在beanClass，那么根据beanClass生成StandardAnnotationMetadata元数据进行解析
				else if (bd instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) bd).hasBeanClass()) {
					parse(((AbstractBeanDefinition) bd).getBeanClass(), holder.getBeanName());
				}
				// 如果是其他情况，根据beanClassName通过MetadataReader使用asm读取对应类的class文件，生成SimpleAnnotationMetadata进行解析
				else {
					parse(bd.getBeanClassName(), holder.getBeanName());
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to parse configuration class [" + bd.getBeanClassName() + "]", ex);
			}
		}

		// 调用处理延迟的importSelector
		this.deferredImportSelectorHandler.process();
	}

	protected final void parse(@Nullable String className, String beanName) throws IOException {
		Assert.notNull(className, "No bean class name for configuration class bean definition");
		// 通过元数据读取器工厂生成一个元数据读取器SimpleMetadataReader
		MetadataReader reader = this.metadataReaderFactory.getMetadataReader(className);
		// 将元数据读取器和beanName封装成一个ConfigurationClass，调用processConfigurationClass方法进行处理
		processConfigurationClass(new ConfigurationClass(reader, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	protected final void parse(Class<?> clazz, String beanName) throws IOException {
		// 根据beanClass和beanName封装一个ConfigurationClass进行处理
		processConfigurationClass(new ConfigurationClass(clazz, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	protected final void parse(AnnotationMetadata metadata, String beanName) throws IOException {
		// 根据AnnotationMetadata和beanName封装一个ConfigurationClass进行处理
		processConfigurationClass(new ConfigurationClass(metadata, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	/**
	 * Validate each {@link ConfigurationClass} object.
	 * @see ConfigurationClass#validate
	 */
	public void validate() {
		for (ConfigurationClass configClass : this.configurationClasses.keySet()) {
			configClass.validate(this.problemReporter);
		}
	}

	public Set<ConfigurationClass> getConfigurationClasses() {
		return this.configurationClasses.keySet();
	}


	protected void processConfigurationClass(ConfigurationClass configClass, Predicate<String> filter) throws IOException {
		// 根据条件评估器判断该ConfigurationClass是否需要被跳过，具体逻辑是根据ConfigurationClass上标注的@Conditional注解中的value值Condition类来确定的
		if (this.conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.PARSE_CONFIGURATION)) {
			return;
		}

		// 查看该ConfigurationClass是否已存在
		ConfigurationClass existingClass = this.configurationClasses.get(configClass);
		// 如果已存在
		if (existingClass != null) {
			// 并且当前这个ConfigurationClass是被导入的，即通过@Import注解从别的ConfigurationClass上导入的。
			if (configClass.isImported()) {
				// 并且存在的ConfigurationClass也是被导入的
				if (existingClass.isImported()) {
					// 那么将当前cc的导入信息合并到已存在的cc的导入信息中
					existingClass.mergeImportedBy(configClass);
				}
				// Otherwise ignore new imported config class; existing non-imported class overrides it.
				// 如果已存在的cc不是被导入的，那么忽略当前的cc，保留不是被导入的cc
				return;
			}
			// 如果当前的cc不是被导入的
			else {
				// Explicit bean definition found, probably replacing an import.
				// Let's remove the old one and go with the new one.
				// 那么将已存在的cc删除，后续会将当前的cc添加进configurationClasses中，这样可以最大程度地保证集合中持有的是 不是被@Import注解导入的ConfigurationClass
				this.configurationClasses.remove(configClass);
				this.knownSuperclasses.values().removeIf(configClass::equals);
			}
		}

		// Recursively process the configuration class and its superclass hierarchy.
		// 将ConfigurationClass转换为SourceClass
		SourceClass sourceClass = asSourceClass(configClass, filter);
		do {
			// 然后将ConfigurationClass和SourceClass都作为参数，调用doProcessConfigurationClass执行具体的处理逻辑，并且递归到父类
			sourceClass = doProcessConfigurationClass(configClass, sourceClass, filter);
		}
		while (sourceClass != null);

		// 处理完成后将ConfigurationClass放入configurationClasses中
		this.configurationClasses.put(configClass, configClass);
	}

	/**
	 * Apply processing and build a complete {@link ConfigurationClass} by reading the
	 * annotations, members and methods from the source class. This method can be called
	 * multiple times as relevant sources are discovered.
	 * @param configClass the configuration class being build
	 * @param sourceClass a source class
	 * @return the superclass, or {@code null} if none found or previously processed
	 */
	@Nullable
	protected final SourceClass doProcessConfigurationClass(
			ConfigurationClass configClass, SourceClass sourceClass, Predicate<String> filter)
			throws IOException {

		// 判断configClass上是否标注了@Component注解，如果是的话，首先递归地处理成员类，即查看是否有嵌套的configClass
		if (configClass.getMetadata().isAnnotated(Component.class.getName())) {
			// Recursively process any member (nested) classes first
			processMemberClasses(configClass, sourceClass, filter);
		}

		// Process any @PropertySource annotations
		// 处理标注在configClass上的@PropertySource注解，attributesForRepeatable方法会返回一个AnnotationAttributes类型的Set，
		// 其中每一个元素代表了一个
		for (AnnotationAttributes propertySource : AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), PropertySources.class,
				org.springframework.context.annotation.PropertySource.class)) {
			// 如果environment是属于ConfigurableEnvironment类型的，对@PropertySource注解进行处理，
			// 根据注解配置的信息，创建出PropertySource实例对象给environment持有
			if (this.environment instanceof ConfigurableEnvironment) {
				processPropertySource(propertySource);
			}
			else {
				logger.info("Ignoring @PropertySource annotation on [" + sourceClass.getMetadata().getClassName() +
						"]. Reason: Environment must implement ConfigurableEnvironment");
			}
		}

		// Process any @ComponentScan annotations
		// 然后处理标注在configClass上的@ComponentScan注解，返回所有@ComponentScan注解的属性集合
		Set<AnnotationAttributes> componentScans = AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), ComponentScans.class, ComponentScan.class);
		// 如果注解属性不为空，并且根据@Condition注解判断出不应该被跳过，执行if里的逻辑对@ComponentScan注解进行解析
		if (!componentScans.isEmpty() &&
				!this.conditionEvaluator.shouldSkip(sourceClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN)) {
			// 遍历循环每一个ComponentScan注解的属性
			for (AnnotationAttributes componentScan : componentScans) {
				// The config class is annotated with @ComponentScan -> perform the scan immediately
				// 通过ComponentScanAnnotationParser类去解析对应的注解信息，然后根据注解配置生成一个ClassPathBeanDefinitionScanner，
				// 调用扫描器对指定的basePackages进行扫描，扫描到指定的class文件，通过asm的方法读取class文件生成metadataReader，
				// 然后根据metadataReader生成对应的ScannedGenericBeanDefinition类型的bd，然后根据beanNameGenerator生成beanName。
				// 将beanName和bd注册进容器中，然后返回该注解扫描到的所有BeanDefinitionHolder的集合
				Set<BeanDefinitionHolder> scannedBeanDefinitions =
						this.componentScanParser.parse(componentScan, sourceClass.getMetadata().getClassName());
				// Check the set of scanned definitions for any further config classes and parse recursively if needed
				// 检查这些扫描到的bd中是否有是ConfigurationClass的，如果有的话，递归调用parse方法进行解析
				for (BeanDefinitionHolder holder : scannedBeanDefinitions) {
					BeanDefinition bdCand = holder.getBeanDefinition().getOriginatingBeanDefinition();
					if (bdCand == null) {
						bdCand = holder.getBeanDefinition();
					}
					if (ConfigurationClassUtils.checkConfigurationClassCandidate(bdCand, this.metadataReaderFactory)) {
						parse(bdCand.getBeanClassName(), holder.getBeanName());
					}
				}
			}
		}

		// Process any @Import annotations
		// 处理标注在configClass上的@Import注解，
		// 其中getImports方法会收集sourceClass上标注或元标注的@Import注解中的value属性所指向的所有类的集合，并且会将类转换为SourceClass的形式
		processImports(configClass, sourceClass, getImports(sourceClass), filter, true);

		// Process any @ImportResource annotations
		// 处理标注在configClass上的@ImportResource注解，返回@ImportResource的所有属性
		AnnotationAttributes importResource =
				AnnotationConfigUtils.attributesFor(sourceClass.getMetadata(), ImportResource.class);
		// 如果存在@ImportResource注解
		if (importResource != null) {
			// 获取注解的locations属性，得到资源所在的地址
			String[] resources = importResource.getStringArray("locations");
			// 获取注解的reader属性，得到BeanDefinitionReader的类型
			Class<? extends BeanDefinitionReader> readerClass = importResource.getClass("reader");
			// 遍历地址数组
			for (String resource : resources) {
				// 使用environment对地址中的占位符进行解析
				String resolvedResource = this.environment.resolveRequiredPlaceholders(resource);
				// 然后将解析后的地址和BeanDefinitionReader类型一并放入configClass中持有，以便后续的处理
				configClass.addImportedResource(resolvedResource, readerClass);
			}
		}

		// Process individual @Bean methods
		// 处理类中标注了@Bean注解的方法
		// 首先检索@Bean方法的metadata
		Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(sourceClass);
		// 遍历获取到的@Bean方法的元数据
		for (MethodMetadata methodMetadata : beanMethods) {
			// 将其和configClass一起封装成一个BeanMethod，添加到configClass中持有起来，以便后续处理
			configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
		}

		// Process default methods on interfaces
		// 处理接口中的默认方法
		processInterfaces(configClass, sourceClass);

		// Process superclass, if any
		// 处理父类，如果存在父类的话
		if (sourceClass.getMetadata().hasSuperClass()) {
			// 获取父类的全限定名称
			String superclass = sourceClass.getMetadata().getSuperClassName();
			// 如果名称不为null且不是以java开头的话，并且knownSuperClasses中不包含该父类的名称
			if (superclass != null && !superclass.startsWith("java") &&
					!this.knownSuperclasses.containsKey(superclass)) {
				// 将其父类名称放入knownSuperClasses
				this.knownSuperclasses.put(superclass, configClass);
				// Superclass found, return its annotation metadata and recurse
				// 然后返回父类对应的SourceClass
				return sourceClass.getSuperClass();
			}
		}

		// No superclass -> processing is complete
		// 如果没有父类，说明处理已经完成，返回null
		return null;
	}

	/**
	 * Register member (nested) classes that happen to be configuration classes themselves.
	 */
	private void processMemberClasses(ConfigurationClass configClass, SourceClass sourceClass,
			Predicate<String> filter) throws IOException {

		// 获取到其持有的成员类SourceClass集合
		Collection<SourceClass> memberClasses = sourceClass.getMemberClasses();
		// 如果集合不为空，说明存在内部类
		if (!memberClasses.isEmpty()) {
			List<SourceClass> candidates = new ArrayList<>(memberClasses.size());
			for (SourceClass memberClass : memberClasses) {
				// 检查内部类是否是ConfigClass并且内部类的名称和外部类名称不一致，如果是，加入候选集合
				if (ConfigurationClassUtils.isConfigurationCandidate(memberClass.getMetadata()) &&
						!memberClass.getMetadata().getClassName().equals(configClass.getMetadata().getClassName())) {
					candidates.add(memberClass);
				}
			}
			// 根据PriorityOrdered接口和Ordered接口排序
			OrderComparator.sort(candidates);
			// 遍历候选集合
			for (SourceClass candidate : candidates) {
				// 如果导入栈中已经包含了该外部类的话，报错
				if (this.importStack.contains(configClass)) {
					this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
				}
				else {
					// 将外部类入栈
					this.importStack.push(configClass);
					try {
						// 将候选类转换为ConfigurationClass，并且将外部类configClass作为参数，表示该类是由外部类导入的，存入importedBy字段，然后递归处理
						processConfigurationClass(candidate.asConfigClass(configClass), filter);
					}
					finally {
						// 将外部类出栈
						this.importStack.pop();
					}
				}
			}
		}
	}

	/**
	 * Register default methods on interfaces implemented by the configuration class.
	 */
	private void processInterfaces(ConfigurationClass configClass, SourceClass sourceClass) throws IOException {
		// 遍历所实现的接口对应的SourceClass
		for (SourceClass ifc : sourceClass.getInterfaces()) {
			// 在接口类中查找标注了@Bean注解的方法
			Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(ifc);
			for (MethodMetadata methodMetadata : beanMethods) {
				// 如果方法不是抽象的，封装成BeanMethod放入configClass中
				if (!methodMetadata.isAbstract()) {
					// A default method or other concrete method on a Java 8+ interface...
					configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
				}
			}
			// 递归调用processInterfaces，解析接口的接口
			processInterfaces(configClass, ifc);
		}
	}

	/**
	 * Retrieve the metadata for all <code>@Bean</code> methods.
	 */
	private Set<MethodMetadata> retrieveBeanMethodMetadata(SourceClass sourceClass) {
		AnnotationMetadata original = sourceClass.getMetadata();
		// 根据AnnotationMetadata获取到标注了@Bean注解方法的MethodMetadata集合
		Set<MethodMetadata> beanMethods = original.getAnnotatedMethods(Bean.class.getName());
		// 如果标注了@Bean注解的方法数量大于1 并且 类型注解原数据是StandardAnnotationMetadata类型，即是反射获取的
		if (beanMethods.size() > 1 && original instanceof StandardAnnotationMetadata) {
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.、
			// 尝试使用asm读取class文件的方式获取一个确定性的声明方法的顺序。
			// 因为java反射机制返回的方式是以任意顺序的，有时候一个jvm同一个应用中不同的调用返回的顺序都不一致
			try {
				// 获取到由asm读取class文件生成的SimpleAnnotationMetadata
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(original.getClassName()).getAnnotationMetadata();
				// 获取标注了@Bean注解的方法元数据
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Bean.class.getName());
				// 如果asm读取出来的@Bean方法个数大于等于反射读取的个数
				if (asmMethods.size() >= beanMethods.size()) {
					Set<MethodMetadata> selectedMethods = new LinkedHashSet<>(asmMethods.size());
					// 遍历asm读取出来的方法元数据
					for (MethodMetadata asmMethod : asmMethods) {
						// 遍历反射的方法元数据
						for (MethodMetadata beanMethod : beanMethods) {
							// 比较二者方法名称是否一致，如果是，添加selectedMethods集合中，然后跳出内层循环
							if (beanMethod.getMethodName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(beanMethod);
								break;
							}
						}
					}
					// 如果最后选择出来的方法个数同反射获取到相等，将selectedMethods直接赋值给beanMethods，相当于将方法按照class文件中声明的顺序排序了
					if (selectedMethods.size() == beanMethods.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						beanMethods = selectedMethods;
					}
				}
			}
			catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Bean method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return beanMethods;
	}


	/**
	 * Process the given <code>@PropertySource</code> annotation metadata.
	 * @param propertySource metadata for the <code>@PropertySource</code> annotation found
	 * @throws IOException if loading a property source failed
	 */
	private void processPropertySource(AnnotationAttributes propertySource) throws IOException {
		// 获取注解对应的name属性
		String name = propertySource.getString("name");
		// 如果name为空字符串的话，赋值为null
		if (!StringUtils.hasLength(name)) {
			name = null;
		}
		// 获取注解的encoding属性
		String encoding = propertySource.getString("encoding");
		// 如果encoding为空字符串，变为null
		if (!StringUtils.hasLength(encoding)) {
			encoding = null;
		}
		// 获取其value属性，返回一个String数组，表示属性来源的地址
		String[] locations = propertySource.getStringArray("value");
		// 判断数组长度是否大于0
		Assert.isTrue(locations.length > 0, "At least one @PropertySource(value) location is required");
		// 获取ignoreResourceNotFound标志
		boolean ignoreResourceNotFound = propertySource.getBoolean("ignoreResourceNotFound");

		// 获取用于根据资源创建PropertySource类的PropertySource工厂类
		Class<? extends PropertySourceFactory> factoryClass = propertySource.getClass("factory");
		// 如果类型是默认的PropertySource.class，那么获取默认的工厂DefaultPropertySourceFactory，否则的话，创建其实例
		PropertySourceFactory factory = (factoryClass == PropertySourceFactory.class ?
				DEFAULT_PROPERTY_SOURCE_FACTORY : BeanUtils.instantiateClass(factoryClass));

		// 遍历地址数组
		for (String location : locations) {
			try {
				// 根据environment对象解析地址中的占位符
				String resolvedLocation = this.environment.resolveRequiredPlaceholders(location);
				// 使用resourceLoader去加载资源
				Resource resource = this.resourceLoader.getResource(resolvedLocation);
				// 使用工厂类创建一个PropertySource实例，然后调用addPropertySource进行添加
				addPropertySource(factory.createPropertySource(name, new EncodedResource(resource, encoding)));
			}
			catch (IllegalArgumentException | FileNotFoundException | UnknownHostException | SocketException ex) {
				// Placeholders not resolvable or resource not found when trying to open it
				if (ignoreResourceNotFound) {
					if (logger.isInfoEnabled()) {
						logger.info("Properties location [" + location + "] not resolvable: " + ex.getMessage());
					}
				}
				else {
					throw ex;
				}
			}
		}
	}

	private void addPropertySource(PropertySource<?> propertySource) {
		// 获取PropertySource的名称
		String name = propertySource.getName();
		// 获取到environment中PropertySource的聚合对象
		MutablePropertySources propertySources = ((ConfigurableEnvironment) this.environment).getPropertySources();

		// 如果propertySourceNames中已经存在该name，说明已经解析过一个版本了，那么我们需要扩展它
		if (this.propertySourceNames.contains(name)) {
			// We've already added a version, we need to extend it
			// 获取已经存在的PropertySource
			PropertySource<?> existing = propertySources.get(name);
			// 如果不为null的话
			if (existing != null) {
				// 判断传入的propertySource的类型
				// 如果是ResourcePropertySource类型的，创建一个新的ResourcePropertySource，将resourceName作为其propertySource的name；
				// 如果不是，直接赋值
				PropertySource<?> newSource = (propertySource instanceof ResourcePropertySource ?
						((ResourcePropertySource) propertySource).withResourceName() : propertySource);
				// 如果存在的propertySource已经是一个聚合对象了，那么将新的ps添加到第一个
				if (existing instanceof CompositePropertySource) {
					((CompositePropertySource) existing).addFirstPropertySource(newSource);
				}
				else {
					// 如果存在的ps是ResourcePropertySource类型的，同样的，将其name替换为resourceName
					if (existing instanceof ResourcePropertySource) {
						existing = ((ResourcePropertySource) existing).withResourceName();
					}
					// 然后创建根据name创建一个CompositePropertySource，用于持有多个name相同的PropertySource
					CompositePropertySource composite = new CompositePropertySource(name);
					composite.addPropertySource(newSource);
					composite.addPropertySource(existing);
					// 然后根据name将propertySources中的对象替换掉
					propertySources.replace(name, composite);
				}
				return;
			}
		}

		// 如果propertySourceNames还为空，将propertySource添加到末尾
		if (this.propertySourceNames.isEmpty()) {
			propertySources.addLast(propertySource);
		}
		// 如果不为空的话，获取最近的处理的propertySource的name，然后将当前PropertySource添加到name对应的PropertySource的前面
		else {
			String firstProcessed = this.propertySourceNames.get(this.propertySourceNames.size() - 1);
			propertySources.addBefore(firstProcessed, propertySource);
		}
		// 将name添加进propertySourceNames中
		this.propertySourceNames.add(name);
	}


	/**
	 * Returns {@code @Import} class, considering all meta-annotations.
	 */
	private Set<SourceClass> getImports(SourceClass sourceClass) throws IOException {
		// 创建两个set用于保存 被@Import注解的value属性所导入的类的SourceClass 和 已经访问过的SourceClass
		Set<SourceClass> imports = new LinkedHashSet<>();
		Set<SourceClass> visited = new LinkedHashSet<>();
		collectImports(sourceClass, imports, visited);
		return imports;
	}

	/**
	 * Recursively collect all declared {@code @Import} values. Unlike most
	 * meta-annotations it is valid to have several {@code @Import}s declared with
	 * different values; the usual process of returning values from the first
	 * meta-annotation on a class is not sufficient.
	 * <p>For example, it is common for a {@code @Configuration} class to declare direct
	 * {@code @Import}s in addition to meta-imports originating from an {@code @Enable}
	 * annotation.
	 * @param sourceClass the class to search
	 * @param imports the imports collected so far
	 * @param visited used to track visited classes to prevent infinite recursion
	 * @throws IOException if there is any problem reading metadata from the named class
	 */
	private void collectImports(SourceClass sourceClass, Set<SourceClass> imports, Set<SourceClass> visited)
			throws IOException {

		// 将当前sourceClass添加进visited集合中
		if (visited.add(sourceClass)) {
			// 获取当前sourceClass上标注的所有注解，并且转换为SourceClass的类型，进行遍历
			for (SourceClass annotation : sourceClass.getAnnotations()) {
				// 获取注解的类名
				String annName = annotation.getMetadata().getClassName();
				// 如果注解名称不等于@Import的全限定名
				if (!annName.equals(Import.class.getName())) {
					// 递归查找当前注解的元注解上是否有@Import注解
					collectImports(annotation, imports, visited);
				}
			}
			// 将当前SourceClass上标注的@Import注解的value属性的值转换为SourceClass集合添加进imports集合中
			imports.addAll(sourceClass.getAnnotationAttributes(Import.class.getName(), "value"));
		}
	}

	private void processImports(ConfigurationClass configClass, SourceClass currentSourceClass,
			Collection<SourceClass> importCandidates, Predicate<String> exclusionFilter,
			boolean checkForCircularImports) {

		// 如果导入候选集合是空的，直接返回
		if (importCandidates.isEmpty()) {
			return;
		}

		// 如果检查循环导入的标志为true，并且确实存在循环导入，比如A导入了B，而B又导入了A，报错
		if (checkForCircularImports && isChainedImportOnStack(configClass)) {
			this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
		}
		else {
			// 将当前configClass压入导入栈中
			this.importStack.push(configClass);
			try {
				// 遍历所有的导入候选类
				for (SourceClass candidate : importCandidates) {
					// 如果候选类是ImportSelector类型的
					if (candidate.isAssignable(ImportSelector.class)) {
						// Candidate class is an ImportSelector -> delegate to it to determine imports
						// 加载对应的候选类
						Class<?> candidateClass = candidate.loadClass();
						// 初始化ImportSelector实例，并且根据实现的Aware接口类型将相应的元素set进去
						ImportSelector selector = ParserStrategyUtils.instantiateClass(candidateClass, ImportSelector.class,
								this.environment, this.resourceLoader, this.registry);
						// 获取selector的排除过滤器
						Predicate<String> selectorFilter = selector.getExclusionFilter();
						// 如果排除过滤器不为null的话，将其和原本参数中的排除过滤器进行or操作合并起来
						if (selectorFilter != null) {
							exclusionFilter = exclusionFilter.or(selectorFilter);
						}
						// 如果selector是属于DeferredImportSelector类型的，使用对应的handler对其进行处理，
						// 根据handler中是否有集合用于持有DeferredImportSelector来决定是否要延迟导入
						if (selector instanceof DeferredImportSelector) {
							this.deferredImportSelectorHandler.handle(configClass, (DeferredImportSelector) selector);
						}
						// 如果selector是普通的ImportSelector类型的
						else {
							// 调用selector的selectImports方法，得到要需要导入的类名数组
							String[] importClassNames = selector.selectImports(currentSourceClass.getMetadata());
							// 然后将这些类名数组都转换为SourceClass集合
							Collection<SourceClass> importSourceClasses = asSourceClasses(importClassNames, exclusionFilter);
							// 递归调用processImports方法
							processImports(configClass, currentSourceClass, importSourceClasses, exclusionFilter, false);
						}
					}
					// 如果候选类是ImportBeanDefinitionRegistrar类型的
					else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) {
						// Candidate class is an ImportBeanDefinitionRegistrar ->
						// delegate to it to register additional bean definitions
						// 加载对应的候选类
						Class<?> candidateClass = candidate.loadClass();
						// 将其实例化，并且根据实现的Aware接口调用对应的set方法将元素设置进去
						ImportBeanDefinitionRegistrar registrar =
								ParserStrategyUtils.instantiateClass(candidateClass, ImportBeanDefinitionRegistrar.class,
										this.environment, this.resourceLoader, this.registry);
						// 向configClass中添加ImportBeanDefinitionRegistrar，其中metadata
						configClass.addImportBeanDefinitionRegistrar(registrar, currentSourceClass.getMetadata());
					}
					// 如果候选类是其他类型的，将其当作一个@Configuration注解标注的类来对待
					else {
						// Candidate class not an ImportSelector or ImportBeanDefinitionRegistrar ->
						// process it as an @Configuration class
						// 将当前sourceClass的metadata和候选类的类名注册进导入栈中，
						// 其中候选类类名为key，表示被导入的类型，
						// metadata为value，表示正在导入的类型
						this.importStack.registerImport(
								currentSourceClass.getMetadata(), candidate.getMetadata().getClassName());
						// 将其转换为ConfigurationClass，递归调用processConfigurationClass方法进行解析
						processConfigurationClass(candidate.asConfigClass(configClass), exclusionFilter);
					}
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to process import candidates for configuration class [" +
						configClass.getMetadata().getClassName() + "]", ex);
			}
			finally {
				// 将configClass从导入栈中出栈
				this.importStack.pop();
			}
		}
	}

	private boolean isChainedImportOnStack(ConfigurationClass configClass) {
		// 如果configClass存在于导入栈中
		if (this.importStack.contains(configClass)) {
			// 获取类名，假设当前类为A
			String configClassName = configClass.getMetadata().getClassName();
			// 从导入栈中根据类A的名称获取到导入当前类A的类B的metadata
			AnnotationMetadata importingClass = this.importStack.getImportingClassFor(configClassName);
			// 如果类B的metadata不为null
			while (importingClass != null) {
				// 且类A就是类B，说明循环导入存在，返回true
				if (configClassName.equals(importingClass.getClassName())) {
					return true;
				}
				// 否则根据类B的名称，查找到导入B的类C的metadata，将其赋值给importingClass，继续循环比较
				importingClass = this.importStack.getImportingClassFor(importingClass.getClassName());
			}
		}
		// 如果不存在于导入栈中，直接返回false，说明不存在循环导入
		return false;
	}

	ImportRegistry getImportRegistry() {
		return this.importStack;
	}


	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link ConfigurationClass}.
	 */
	private SourceClass asSourceClass(ConfigurationClass configurationClass, Predicate<String> filter) throws IOException {
		// 获取ConfigurationClass中的AnnotationMetadata
		AnnotationMetadata metadata = configurationClass.getMetadata();
		// 如果是StandardAnnotationMetadata类型的，直接获取其introspectedClass转换为SourceClass
		if (metadata instanceof StandardAnnotationMetadata) {
			return asSourceClass(((StandardAnnotationMetadata) metadata).getIntrospectedClass(), filter);
		}
		// 否则获取其className转换成SourceClass
		return asSourceClass(metadata.getClassName(), filter);
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link Class}.
	 */
	SourceClass asSourceClass(@Nullable Class<?> classType, Predicate<String> filter) throws IOException {
		// 如果类型为null或者符合过滤条件，那么返回持有的objectSourceClass
		if (classType == null || filter.test(classType.getName())) {
			return this.objectSourceClass;
		}
		try {
			// Sanity test that we can reflectively read annotations,
			// including Class attributes; if not -> fall back to ASM
			// 获取其类上标注的所有注解，判断是否可以通过反射对注解进行读取，如果不行的话，回退到asm的方式
			for (Annotation ann : classType.getDeclaredAnnotations()) {
				AnnotationUtils.validateAnnotation(ann);
			}
			// 如果没有异常抛出，通过类型初始化SourceClass
			return new SourceClass(classType);
		}
		catch (Throwable ex) {
			// Enforce ASM via class name resolution
			// 强制通过类全限定名的asm方式读取字节码生成SourceClass
			return asSourceClass(classType.getName(), filter);
		}
	}

	/**
	 * Factory method to obtain a {@link SourceClass} collection from class names.
	 */
	private Collection<SourceClass> asSourceClasses(String[] classNames, Predicate<String> filter) throws IOException {
		List<SourceClass> annotatedClasses = new ArrayList<>(classNames.length);
		for (String className : classNames) {
			annotatedClasses.add(asSourceClass(className, filter));
		}
		return annotatedClasses;
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a class name.
	 */
	SourceClass asSourceClass(@Nullable String className, Predicate<String> filter) throws IOException {
		// 如果类名为null或者符合过滤条件，直接返回objectSourceClass
		if (className == null || filter.test(className)) {
			return this.objectSourceClass;
		}
		// 如果类名是以java开头的，不适用asm框架去读取core java的字节码，直接尝试进行类加载
		if (className.startsWith("java")) {
			// Never use ASM for core java types
			try {
				return new SourceClass(ClassUtils.forName(className, this.resourceLoader.getClassLoader()));
			}
			catch (ClassNotFoundException ex) {
				throw new NestedIOException("Failed to load class [" + className + "]", ex);
			}
		}
		// 否则的话，使用asm框架读取对应类名的字节码，解析成SimpleAnnotationMetadata，被SimpleMetadataReader持有
		return new SourceClass(this.metadataReaderFactory.getMetadataReader(className));
	}


	@SuppressWarnings("serial")
	private static class ImportStack extends ArrayDeque<ConfigurationClass> implements ImportRegistry {

		private final MultiValueMap<String, AnnotationMetadata> imports = new LinkedMultiValueMap<>();

		public void registerImport(AnnotationMetadata importingClass, String importedClass) {
			this.imports.add(importedClass, importingClass);
		}

		@Override
		@Nullable
		public AnnotationMetadata getImportingClassFor(String importedClass) {
			// 获取最后一个导入importedClass的类的AnnotationMetadata返回
			return CollectionUtils.lastElement(this.imports.get(importedClass));
		}

		@Override
		public void removeImportingClass(String importingClass) {
			for (List<AnnotationMetadata> list : this.imports.values()) {
				for (Iterator<AnnotationMetadata> iterator = list.iterator(); iterator.hasNext();) {
					if (iterator.next().getClassName().equals(importingClass)) {
						iterator.remove();
						break;
					}
				}
			}
		}

		/**
		 * Given a stack containing (in order)
		 * <ul>
		 * <li>com.acme.Foo</li>
		 * <li>com.acme.Bar</li>
		 * <li>com.acme.Baz</li>
		 * </ul>
		 * return "[Foo->Bar->Baz]".
		 */
		@Override
		public String toString() {
			StringJoiner joiner = new StringJoiner("->", "[", "]");
			for (ConfigurationClass configurationClass : this) {
				joiner.add(configurationClass.getSimpleName());
			}
			return joiner.toString();
		}
	}


	private class DeferredImportSelectorHandler {

		@Nullable
		private List<DeferredImportSelectorHolder> deferredImportSelectors = new ArrayList<>();

		/**
		 * Handle the specified {@link DeferredImportSelector}. If deferred import
		 * selectors are being collected, this registers this instance to the list. If
		 * they are being processed, the {@link DeferredImportSelector} is also processed
		 * immediately according to its {@link DeferredImportSelector.Group}.
		 * @param configClass the source configuration class
		 * @param importSelector the selector to handle
		 */
		public void handle(ConfigurationClass configClass, DeferredImportSelector importSelector) {
			// 将configClass和importSelector一起封装成一个DeferredImportSelectorHolder
			DeferredImportSelectorHolder holder = new DeferredImportSelectorHolder(configClass, importSelector);
			// 如果handler持有的用于保存DeferredImportSelectorHolder的集合为null，没办法进行延迟处理，那么分组进行导入
			if (this.deferredImportSelectors == null) {
				// 创建一个grouping处理器
				DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
				// 将holder注册进去
				handler.register(holder);
				// 对注册进去的holder进行处理
				handler.processGroupImports();
			}
			// 将holder添加进集合中，进行延迟处理
			else {
				this.deferredImportSelectors.add(holder);
			}
		}

		public void process() {
			List<DeferredImportSelectorHolder> deferredImports = this.deferredImportSelectors;
			this.deferredImportSelectors = null;
			try {
				if (deferredImports != null) {
					// 创建一个GroupingHandler
					DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
					// 将持有的延迟importSelectorHolder按照importSelector的PriorityOrdered接口、Ordered等接口、@Order注解、@Priority注解进行排序
					deferredImports.sort(DEFERRED_IMPORT_COMPARATOR);
					// 遍历持有的holder，调用handler的register方法将他们都注册到GroupingHandler中，并且根据自身的Group进行分类
					deferredImports.forEach(handler::register);
					// 然后调用processGroupImports方法对注册进GroupingHandler的importSelector进行处理。
					// 根据不同的分组，分别获取每一组的exclusionFilter的或集，然后遍历同一个Group下的ImportSelector的selectImports方法，将获得的要导入的类名
					// 和AnnotationMetadata一起封装成一个Entry，收集到一起。遍历Group中收集到的要导入Entry的集合，针对每个一个类名，都递归的调用processImports方法对其进行解析。
					handler.processGroupImports();
				}
			}
			finally {
				this.deferredImportSelectors = new ArrayList<>();
			}
		}
	}


	private class DeferredImportSelectorGroupingHandler {

		private final Map<Object, DeferredImportSelectorGrouping> groupings = new LinkedHashMap<>();

		private final Map<AnnotationMetadata, ConfigurationClass> configurationClasses = new HashMap<>();

		// 具体的逻辑就是根据DeferredImportSelector的持有的Group类型为key，以DeferredImportSelectorGrouping为value，
		// 其中DeferredImportSelectorGrouping持有了Group以及与Group对应的所有的DeferredImportSelectorHolder。
		public void register(DeferredImportSelectorHolder deferredImport) {
			// 返回导入组的类型
			Class<? extends Group> group = deferredImport.getImportSelector().getImportGroup();
			DeferredImportSelectorGrouping grouping = this.groupings.computeIfAbsent(
					// 如果group不为null，就以group为key，否则以holder为key
					(group != null ? group : deferredImport),
					// 如果value不存在，就创建一个grouping存入map，构造器参数传入group类型的实例对象
					key -> new DeferredImportSelectorGrouping(createGroup(group)));
			// 然后将holder添加进grouping中
			grouping.add(deferredImport);
			// 将configClass的metadata和自身存入map中。
			this.configurationClasses.put(deferredImport.getConfigurationClass().getMetadata(),
					deferredImport.getConfigurationClass());
		}

		public void processGroupImports() {
			// 遍历所持有的所有groupings
			for (DeferredImportSelectorGrouping grouping : this.groupings.values()) {
				// 获取对应grouping的条件过滤器
				Predicate<String> exclusionFilter = grouping.getCandidateFilter();
				// 获取要导入的类名所封装的Entry集合进行遍历
				grouping.getImports().forEach(entry -> {
					// 根据metadata获取到对应的ConfigurationClass
					ConfigurationClass configurationClass = this.configurationClasses.get(entry.getMetadata());
					try {
						// 递归调用processImports方法，其中导入候选类就是通过selectImports方法选择出来的类名
						processImports(configurationClass, asSourceClass(configurationClass, exclusionFilter),
								Collections.singleton(asSourceClass(entry.getImportClassName(), exclusionFilter)),
								exclusionFilter, false);
					}
					catch (BeanDefinitionStoreException ex) {
						throw ex;
					}
					catch (Throwable ex) {
						throw new BeanDefinitionStoreException(
								"Failed to process import candidates for configuration class [" +
										configurationClass.getMetadata().getClassName() + "]", ex);
					}
				});
			}
		}

		private Group createGroup(@Nullable Class<? extends Group> type) {
			// 如果type不为null，使用type，否则使用DefaultDeferredImportSelectorGroup类型，初始化其实例，并且设置Aware接口的值。返回
			Class<? extends Group> effectiveType = (type != null ? type : DefaultDeferredImportSelectorGroup.class);
			return ParserStrategyUtils.instantiateClass(effectiveType, Group.class,
					ConfigurationClassParser.this.environment,
					ConfigurationClassParser.this.resourceLoader,
					ConfigurationClassParser.this.registry);
		}
	}


	private static class DeferredImportSelectorHolder {

		private final ConfigurationClass configurationClass;

		private final DeferredImportSelector importSelector;

		public DeferredImportSelectorHolder(ConfigurationClass configClass, DeferredImportSelector selector) {
			this.configurationClass = configClass;
			this.importSelector = selector;
		}

		public ConfigurationClass getConfigurationClass() {
			return this.configurationClass;
		}

		public DeferredImportSelector getImportSelector() {
			return this.importSelector;
		}
	}


	private static class DeferredImportSelectorGrouping {

		private final DeferredImportSelector.Group group;

		private final List<DeferredImportSelectorHolder> deferredImports = new ArrayList<>();

		DeferredImportSelectorGrouping(Group group) {
			this.group = group;
		}

		public void add(DeferredImportSelectorHolder deferredImport) {
			this.deferredImports.add(deferredImport);
		}

		/**
		 * Return the imports defined by the group.
		 * @return each import with its associated configuration class
		 */
		public Iterable<Group.Entry> getImports() {
			// 遍历所持有的DeferredImportSelectorHolder集合
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				// 使用group对DeferredImportSelector和ConfigurationClass进行处理
				this.group.process(deferredImport.getConfigurationClass().getMetadata(),
						deferredImport.getImportSelector());
			}
			// 返回处理过后的要导入的类名对应的Entry的集合
			return this.group.selectImports();
		}

		public Predicate<String> getCandidateFilter() {
			Predicate<String> mergedFilter = DEFAULT_EXCLUSION_FILTER;
			// 获取所有持有的DeferredImportSelector的exclusionFilter，如果不为null，就将其和原本的进行or操作
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				Predicate<String> selectorFilter = deferredImport.getImportSelector().getExclusionFilter();
				if (selectorFilter != null) {
					mergedFilter = mergedFilter.or(selectorFilter);
				}
			}
			// 然后返回最终的过滤器
			return mergedFilter;
		}
	}


	private static class DefaultDeferredImportSelectorGroup implements Group {

		private final List<Entry> imports = new ArrayList<>();

		@Override
		public void process(AnnotationMetadata metadata, DeferredImportSelector selector) {
			// 调用selector的selectImports方法，返回要选择出的要导入的类名，进行遍历
			for (String importClassName : selector.selectImports(metadata)) {
				// 将每个要导入的类名和configClass的metadata一起封装成一个Entry添加进集合中持有
				this.imports.add(new Entry(metadata, importClassName));
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			return this.imports;
		}
	}


	/**
	 * Simple wrapper that allows annotated source classes to be dealt with
	 * in a uniform manner, regardless of how they are loaded.
	 */
	private class SourceClass implements Ordered {

		private final Object source;  // Class or MetadataReader

		private final AnnotationMetadata metadata;

		public SourceClass(Object source) {
			// 将传入的参数赋值给source字段，该对象类型可能是Class或MetadataReader
			this.source = source;
			// 如果是Class类型，生成一个StandardAnnotationMetadata赋值给metadata
			if (source instanceof Class) {
				this.metadata = AnnotationMetadata.introspect((Class<?>) source);
			}
			else {
				// 如果是MetadataReader，获取其持有的注解元数据赋值给metadata
				this.metadata = ((MetadataReader) source).getAnnotationMetadata();
			}
		}

		public final AnnotationMetadata getMetadata() {
			return this.metadata;
		}

		@Override
		public int getOrder() {
			Integer order = ConfigurationClassUtils.getOrder(this.metadata);
			return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
		}

		public Class<?> loadClass() throws ClassNotFoundException {
			// 如果source是Class类型的，直接返回
			if (this.source instanceof Class) {
				return (Class<?>) this.source;
			}
			// 否则获取metadataReader持有的classMetadata，然后获取类型名称，通过类加载器进行加载类返回
			String className = ((MetadataReader) this.source).getClassMetadata().getClassName();
			return ClassUtils.forName(className, resourceLoader.getClassLoader());
		}

		public boolean isAssignable(Class<?> clazz) throws IOException {
			// 如果source是Class类型，那么判断其是否可以赋值给传入的clazz
			if (this.source instanceof Class) {
				return clazz.isAssignableFrom((Class<?>) this.source);
			}
			// 如果source是MetadataReader类型，那么使用AssignableTypeFilter来判断其是否可以赋值给传入的clazz
			return new AssignableTypeFilter(clazz).match((MetadataReader) this.source, metadataReaderFactory);
		}

		public ConfigurationClass asConfigClass(ConfigurationClass importedBy) {
			// 根据source的不同类型，调用ConfigurationClass的不同构造方法，并且将importedBy传入
			if (this.source instanceof Class) {
				return new ConfigurationClass((Class<?>) this.source, importedBy);
			}
			return new ConfigurationClass((MetadataReader) this.source, importedBy);
		}

		public Collection<SourceClass> getMemberClasses() throws IOException {
			Object sourceToProcess = this.source;
			// 如果source是Class类型的
			if (sourceToProcess instanceof Class) {
				Class<?> sourceClass = (Class<?>) sourceToProcess;
				try {
					// 获取该类型中声明的其他类型数组
					Class<?>[] declaredClasses = sourceClass.getDeclaredClasses();
					List<SourceClass> members = new ArrayList<>(declaredClasses.length);
					// 然后将都转换为SourceClass类型的集合返回
					for (Class<?> declaredClass : declaredClasses) {
						members.add(asSourceClass(declaredClass, DEFAULT_EXCLUSION_FILTER));
					}
					return members;
				}
				catch (NoClassDefFoundError err) {
					// getDeclaredClasses() failed because of non-resolvable dependencies
					// -> fall back to ASM below
					// 如果出现异常了，使用asm读取字节码的方式
					sourceToProcess = metadataReaderFactory.getMetadataReader(sourceClass.getName());
				}
			}

			// ASM-based resolution - safe for non-resolvable classes as well
			// 如果source字段是MetadataReader类型的
			MetadataReader sourceReader = (MetadataReader) sourceToProcess;
			// 获取其持有的SimpleAnnotationMetadata中持有的memberClassNames字段
			String[] memberClassNames = sourceReader.getClassMetadata().getMemberClassNames();
			List<SourceClass> members = new ArrayList<>(memberClassNames.length);
			// 同样将其转换为SourceClass类型的集合，这里是使用asm的方式
			for (String memberClassName : memberClassNames) {
				try {
					members.add(asSourceClass(memberClassName, DEFAULT_EXCLUSION_FILTER));
				}
				catch (IOException ex) {
					// Let's skip it if it's not resolvable - we're just looking for candidates
					if (logger.isDebugEnabled()) {
						logger.debug("Failed to resolve member class [" + memberClassName +
								"] - not considering it as a configuration class candidate");
					}
				}
			}
			return members;
		}

		public SourceClass getSuperClass() throws IOException {
			// 如果source是Class类型的，通过反射获取父类类对象，转换为SourceClass返回
			if (this.source instanceof Class) {
				return asSourceClass(((Class<?>) this.source).getSuperclass(), DEFAULT_EXCLUSION_FILTER);
			}
			// 如果source是metadataReader类型的，通过获取父类全限定名通过asm读取class文件转换为SourceClass返回
			return asSourceClass(
					((MetadataReader) this.source).getClassMetadata().getSuperClassName(), DEFAULT_EXCLUSION_FILTER);
		}

		public Set<SourceClass> getInterfaces() throws IOException {
			Set<SourceClass> result = new LinkedHashSet<>();
			// 如果source是class类型的
			if (this.source instanceof Class) {
				Class<?> sourceClass = (Class<?>) this.source;
				// 通过反射获取接口进行遍历
				for (Class<?> ifcClass : sourceClass.getInterfaces()) {
					// 根据接口类的类对象转化成SourceClass加入结果集合
					result.add(asSourceClass(ifcClass, DEFAULT_EXCLUSION_FILTER));
				}
			}
			// 如果source是metadataReader类型的
			else {
				// 根据接口的类名名称通过asm转换成SourceClass，加入结果集合
				for (String className : this.metadata.getInterfaceNames()) {
					result.add(asSourceClass(className, DEFAULT_EXCLUSION_FILTER));
				}
			}
			return result;
		}

		public Set<SourceClass> getAnnotations() {
			Set<SourceClass> result = new LinkedHashSet<>();
			// 如果source是Class类型的
			if (this.source instanceof Class) {
				Class<?> sourceClass = (Class<?>) this.source;
				// 获取类上标注的所有注解并且遍历
				for (Annotation ann : sourceClass.getDeclaredAnnotations()) {
					// 获取注解类型
					Class<?> annType = ann.annotationType();
					// 如果注解类名不是以java开头的，将其转换为SourceClass类型添加进结果集合中
					if (!annType.getName().startsWith("java")) {
						try {
							result.add(asSourceClass(annType, DEFAULT_EXCLUSION_FILTER));
						}
						catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			// 如果source是MetadataReader类型的
			else {
				// 获取类上标注的所有注解类型名称并遍历
				for (String className : this.metadata.getAnnotationTypes()) {
					// 如果类名不是以java开头的，将其转换为SourceClass加入到结果集合中
					if (!className.startsWith("java")) {
						try {
							result.add(getRelated(className));
						}
						catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			return result;
		}

		public Collection<SourceClass> getAnnotationAttributes(String annType, String attribute) throws IOException {
			// 获取到metadata上对应注解的属性map
			Map<String, Object> annotationAttributes = this.metadata.getAnnotationAttributes(annType, true);
			// 如果属性map为null或者不存在对应的属性，直接返回空集合
			if (annotationAttributes == null || !annotationAttributes.containsKey(attribute)) {
				return Collections.emptySet();
			}
			// 如果存在的话，将所有类名都转换为SourceClass，放入集合中返回
			String[] classNames = (String[]) annotationAttributes.get(attribute);
			Set<SourceClass> result = new LinkedHashSet<>();
			for (String className : classNames) {
				result.add(getRelated(className));
			}
			return result;
		}

		private SourceClass getRelated(String className) throws IOException {
			// 如果当前source是Class类型的，使用类加载的方式加载类，并且初始化SourceClass
			if (this.source instanceof Class) {
				try {
					Class<?> clazz = ClassUtils.forName(className, ((Class<?>) this.source).getClassLoader());
					return asSourceClass(clazz, DEFAULT_EXCLUSION_FILTER);
				}
				// 一旦类加载失败，转用asm的方式
				catch (ClassNotFoundException ex) {
					// Ignore -> fall back to ASM next, except for core java types.
					if (className.startsWith("java")) {
						throw new NestedIOException("Failed to load class [" + className + "]", ex);
					}
					return new SourceClass(metadataReaderFactory.getMetadataReader(className));
				}
			}
			// 使用asm的方式转换为SourceClass
			return asSourceClass(className, DEFAULT_EXCLUSION_FILTER);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return (this == other || (other instanceof SourceClass &&
					this.metadata.getClassName().equals(((SourceClass) other).metadata.getClassName())));
		}

		@Override
		public int hashCode() {
			return this.metadata.getClassName().hashCode();
		}

		@Override
		public String toString() {
			return this.metadata.getClassName();
		}
	}


	/**
	 * {@link Problem} registered upon detection of a circular {@link Import}.
	 */
	private static class CircularImportProblem extends Problem {

		public CircularImportProblem(ConfigurationClass attemptedImport, Deque<ConfigurationClass> importStack) {
			super(String.format("A circular @Import has been detected: " +
					"Illegal attempt by @Configuration class '%s' to import class '%s' as '%s' is " +
					"already present in the current import stack %s", importStack.element().getSimpleName(),
					attemptedImport.getSimpleName(), attemptedImport.getSimpleName(), importStack),
					new Location(importStack.element().getResource(), attemptedImport.getMetadata()));
		}
	}

}
