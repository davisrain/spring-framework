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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.groovy.GroovyBeanDefinitionReader;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Reads a given fully-populated set of ConfigurationClass instances, registering bean
 * definitions with the given {@link BeanDefinitionRegistry} based on its contents.
 *
 * <p>This class was modeled after the {@link BeanDefinitionReader} hierarchy, but does
 * not implement/extend any of its artifacts as a set of configuration classes is not a
 * {@link Resource}.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 3.0
 * @see ConfigurationClassParser
 */
class ConfigurationClassBeanDefinitionReader {

	private static final Log logger = LogFactory.getLog(ConfigurationClassBeanDefinitionReader.class);

	private static final ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();

	private final BeanDefinitionRegistry registry;

	private final SourceExtractor sourceExtractor;

	private final ResourceLoader resourceLoader;

	private final Environment environment;

	private final BeanNameGenerator importBeanNameGenerator;

	private final ImportRegistry importRegistry;

	private final ConditionEvaluator conditionEvaluator;


	/**
	 * Create a new {@link ConfigurationClassBeanDefinitionReader} instance
	 * that will be used to populate the given {@link BeanDefinitionRegistry}.
	 */
	ConfigurationClassBeanDefinitionReader(BeanDefinitionRegistry registry, SourceExtractor sourceExtractor,
			ResourceLoader resourceLoader, Environment environment, BeanNameGenerator importBeanNameGenerator,
			ImportRegistry importRegistry) {

		this.registry = registry;
		this.sourceExtractor = sourceExtractor;
		this.resourceLoader = resourceLoader;
		this.environment = environment;
		this.importBeanNameGenerator = importBeanNameGenerator;
		this.importRegistry = importRegistry;
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	/**
	 * Read {@code configurationModel}, registering bean definitions
	 * with the registry based on its contents.
	 */
	public void loadBeanDefinitions(Set<ConfigurationClass> configurationModel) {
		// 创建一个TrackedConditionEvaluator，对@Conditional注解进行解析，
		// 但是要先判断导入这个ConfigurationClass的类是否是需要跳过的，
		// 如果所有导入它的类都需要跳过的话，那么自身也跳过
		TrackedConditionEvaluator trackedConditionEvaluator = new TrackedConditionEvaluator();
		// 遍历传入的ConfigurationClass集合，根据它加载BeanDefinition
		for (ConfigurationClass configClass : configurationModel) {
			loadBeanDefinitionsForConfigurationClass(configClass, trackedConditionEvaluator);
		}
	}

	/**
	 * Read a particular {@link ConfigurationClass}, registering bean definitions
	 * for the class itself and all of its {@link Bean} methods.
	 */
	private void loadBeanDefinitionsForConfigurationClass(
			ConfigurationClass configClass, TrackedConditionEvaluator trackedConditionEvaluator) {

		// 首先判断该configClass是否需要跳过
		if (trackedConditionEvaluator.shouldSkip(configClass)) {
			// 如果是的话，获取beanName，将其从容器中对应的BeanDefinition删除
			String beanName = configClass.getBeanName();
			if (StringUtils.hasLength(beanName) && this.registry.containsBeanDefinition(beanName)) {
				this.registry.removeBeanDefinition(beanName);
			}
			// 将其在importRegistry中作为导入其他类的类删除，即从map的value中删除
			this.importRegistry.removeImportingClass(configClass.getMetadata().getClassName());
			return;
		}

		// 如果configClass是被导入的，调用被导入的configClass的注册BeanDefinition的方法。
		// 因为这里只有被导入的configClass才有注册bd的必要，
		// 即通过@Import注解(非ImportBeanDefinitionRegistrar方式)导入的或者内部类方式导入的才会没有生成bd，
		// 通过@ComponentScan扫描进来的都已经通过ClassPathBeanDefinitionScanner注册进了容器中。
		if (configClass.isImported()) {
			registerBeanDefinitionForImportedConfigurationClass(configClass);
		}
		// 解析configClass中持有的@Bean方法，将其注册为BeanDefinition
		for (BeanMethod beanMethod : configClass.getBeanMethods()) {
			loadBeanDefinitionsForBeanMethod(beanMethod);
		}

		// 解析configClass中持有的@ImportResource注解的信息，将其对应的文件解析，注册为BeanDefinition
		loadBeanDefinitionsFromImportedResources(configClass.getImportedResources());
		// 解析configClass中持有的由@Import注解导入的ImportBeanDefinitionRegistrar类型，向容器中注册BeanDefinition
		loadBeanDefinitionsFromRegistrars(configClass.getImportBeanDefinitionRegistrars());
	}

	/**
	 * Register the {@link Configuration} class itself as a bean definition.
	 */
	private void registerBeanDefinitionForImportedConfigurationClass(ConfigurationClass configClass) {
		// 获取configClass的AnnotationMetadata
		AnnotationMetadata metadata = configClass.getMetadata();
		// 根据metadata创建一个AnnotatedGenericBeanDefinition
		AnnotatedGenericBeanDefinition configBeanDef = new AnnotatedGenericBeanDefinition(metadata);

		// 使用AnnotationScopeMetadataResolver解析标注在类上的@Scope注解，生成ScopeMetadata
		ScopeMetadata scopeMetadata = scopeMetadataResolver.resolveScopeMetadata(configBeanDef);
		// 向bd中设置scope
		configBeanDef.setScope(scopeMetadata.getScopeName());
		// 根据beanNameGenerator生成beanName
		String configBeanName = this.importBeanNameGenerator.generateBeanName(configBeanDef, this.registry);
		// 然后处理一些常规的注解，@Lazy @Primary @DependsOn @Role @Description
		AnnotationConfigUtils.processCommonDefinitionAnnotations(configBeanDef, metadata);

		// 根据beanName和bd封装一个BeanDefinitionHolder
		BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(configBeanDef, configBeanName);
		// 应用Scoped代理模式
		definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
		// 将bd注册进容器中
		this.registry.registerBeanDefinition(definitionHolder.getBeanName(), definitionHolder.getBeanDefinition());
		// 将beanName设置回configClass中
		configClass.setBeanName(configBeanName);

		if (logger.isTraceEnabled()) {
			logger.trace("Registered bean definition for imported class '" + configBeanName + "'");
		}
	}

	/**
	 * Read the given {@link BeanMethod}, registering bean definitions
	 * with the BeanDefinitionRegistry based on its contents.
	 */
	@SuppressWarnings("deprecation")  // for RequiredAnnotationBeanPostProcessor.SKIP_REQUIRED_CHECK_ATTRIBUTE
	private void loadBeanDefinitionsForBeanMethod(BeanMethod beanMethod) {
		// 获取声明@Bean方法的ConfigurationClass
		ConfigurationClass configClass = beanMethod.getConfigurationClass();
		// 获取到@Bean方法对应的元数据
		MethodMetadata metadata = beanMethod.getMetadata();
		// 获取@Bean方法的名称
		String methodName = metadata.getMethodName();

		// Do we need to mark the bean as skipped by its condition?
		// 根据@Bean方法上标注的@Conditional注解判断@Bean方法是否需要被过滤
		if (this.conditionEvaluator.shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN)) {
			// 如果是的话，将方法名添加到configClass中持有
			configClass.skippedBeanMethods.add(methodName);
			return;
		}
		// 如果方法名包含在需要被跳过的集合中，直接返回，不解析
		if (configClass.skippedBeanMethods.contains(methodName)) {
			return;
		}

		// 获取方法上标注的@Bean注解的属性
		AnnotationAttributes bean = AnnotationConfigUtils.attributesFor(metadata, Bean.class);
		Assert.state(bean != null, "No @Bean annotation attributes");

		// Consider name and any aliases
		// 获取@Bean注解的name属性
		List<String> names = new ArrayList<>(Arrays.asList(bean.getStringArray("name")));
		// 如果name属性不为空，取第一个作为beanName，否则取方法名作为beanName
		String beanName = (!names.isEmpty() ? names.remove(0) : methodName);

		// Register aliases even when overridden
		// 如果name属性集合还有值，将其剩下的注册为beanName的别名
		for (String alias : names) {
			this.registry.registerAlias(beanName, alias);
		}

		// Has this effectively been overridden before (e.g. via XML)?
		// 判断bean方法是否被已经存在的bd覆盖
		if (isOverriddenByExistingDefinition(beanMethod, beanName)) {
			// 如果是的话，判断以下beanName是否和声明bean方法的configClass的beanName相等，如果相等则报错
			if (beanName.equals(beanMethod.getConfigurationClass().getBeanName())) {
				throw new BeanDefinitionStoreException(beanMethod.getConfigurationClass().getResource().getDescription(),
						beanName, "Bean name derived from @Bean method '" + beanMethod.getMetadata().getMethodName() +
						"' clashes with bean name for containing configuration class; please make those names unique!");
			}
			return;
		}

		// 根据声明@Bean方法的configClass，@Bean方法的元数据和beanName创建一个ConfigurationClassBeanDefinition
		ConfigurationClassBeanDefinition beanDef = new ConfigurationClassBeanDefinition(configClass, metadata, beanName);
		// 将bd的source设置为bean方法的metadata
		beanDef.setSource(this.sourceExtractor.extractSource(metadata, configClass.getResource()));

		// 如果bean方法是静态的
		if (metadata.isStatic()) {
			// static @Bean method
			// 如果声明bean方法的configClass的元数据是由反射获取的
			if (configClass.getMetadata() instanceof StandardAnnotationMetadata) {
				// 设置bd的beanClass为类对象
				beanDef.setBeanClass(((StandardAnnotationMetadata) configClass.getMetadata()).getIntrospectedClass());
			}
			// 如果元数据是由asm获取的，设置bd的beanClass为声明bean方法的类的全限定名
			else {
				beanDef.setBeanClassName(configClass.getMetadata().getClassName());
			}
			// 设置bd的factoryMethodName为bean方法的方法名，并且将唯一标志设置为true
			beanDef.setUniqueFactoryMethodName(methodName);
		}
		// 如果bean方法是实例方法
		else {
			// instance @Bean method
			// 设置bd的factoryBeanName为声明bean方法的configClass的beanName
			beanDef.setFactoryBeanName(configClass.getBeanName());
			// 并且设置bd的factoryMethodName为bean方法的名称，并且将唯一标志设置为true
			beanDef.setUniqueFactoryMethodName(methodName);
		}

		// 如果bean方法的原数组是由反射获取的
		if (metadata instanceof StandardMethodMetadata) {
			// 设置bd的已解析的factoryMethod为反射方法对象
			beanDef.setResolvedFactoryMethod(((StandardMethodMetadata) metadata).getIntrospectedMethod());
		}

		// 设置bd的自动注入的模式为构造器模式
		beanDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
		// TODO 该参数不知道有何作用，设置bd的属性skipRequiredCheck为true
		beanDef.setAttribute(org.springframework.beans.factory.annotation.RequiredAnnotationBeanPostProcessor.
				SKIP_REQUIRED_CHECK_ATTRIBUTE, Boolean.TRUE);

		// 处理一些通过的注解，@Lazy @Primary @DependsOn @Role @Description
		AnnotationConfigUtils.processCommonDefinitionAnnotations(beanDef, metadata);

		// 根据@Bean注解的autowire属性设置bd的autowireMode值
		Autowire autowire = bean.getEnum("autowire");
		// 如果autowire模式是byName或byType的，才会修改bd的autowireMode，否则还是默认的按constructor来
		if (autowire.isAutowire()) {
			beanDef.setAutowireMode(autowire.value());
		}

		// 根据@Bean注解的autowireCandidate属性设置bd的autowireCandidate
		boolean autowireCandidate = bean.getBoolean("autowireCandidate");
		if (!autowireCandidate) {
			beanDef.setAutowireCandidate(false);
		}

		// 根据@Bean注解设置bd的initMethodName和destroyMethodName
		String initMethodName = bean.getString("initMethod");
		if (StringUtils.hasText(initMethodName)) {
			beanDef.setInitMethodName(initMethodName);
		}

		String destroyMethodName = bean.getString("destroyMethod");
		beanDef.setDestroyMethodName(destroyMethodName);

		// Consider scoping
		ScopedProxyMode proxyMode = ScopedProxyMode.NO;
		// 获取bean方法上标注的@Scope注解的属性
		AnnotationAttributes attributes = AnnotationConfigUtils.attributesFor(metadata, Scope.class);
		// 如果属性不为null
		if (attributes != null) {
			// 获取value属性设置进bd的scope中
			beanDef.setScope(attributes.getString("value"));
			// 获取@Scope的proxyMode属性，如果是DEFAULT，转换为NO
			proxyMode = attributes.getEnum("proxyMode");
			if (proxyMode == ScopedProxyMode.DEFAULT) {
				proxyMode = ScopedProxyMode.NO;
			}
		}

		// Replace the original bean definition with the target one, if necessary
		BeanDefinition beanDefToRegister = beanDef;
		// 如果代理模式不为NO，创建一个BeanDefinitionHolder代理
		if (proxyMode != ScopedProxyMode.NO) {
			BeanDefinitionHolder proxyDef = ScopedProxyCreator.createScopedProxy(
					new BeanDefinitionHolder(beanDef, beanName), this.registry,
					proxyMode == ScopedProxyMode.TARGET_CLASS);
			// 然后用代理的bd生成一个新的ConfigurationClassBeanDefinition
			beanDefToRegister = new ConfigurationClassBeanDefinition(
					(RootBeanDefinition) proxyDef.getBeanDefinition(), configClass, metadata, beanName);
		}

		if (logger.isTraceEnabled()) {
			logger.trace(String.format("Registering bean definition for @Bean method %s.%s()",
					configClass.getMetadata().getClassName(), beanName));
		}
		// 将bd根据beanName注解进容器中
		this.registry.registerBeanDefinition(beanName, beanDefToRegister);
	}

	protected boolean isOverriddenByExistingDefinition(BeanMethod beanMethod, String beanName) {
		// 判断容器中是否已经存在beanName对应的bd，如果不存在，直接返回false
		if (!this.registry.containsBeanDefinition(beanName)) {
			return false;
		}
		// 获取已经存在的bd
		BeanDefinition existingBeanDef = this.registry.getBeanDefinition(beanName);

		// Is the existing bean definition one that was created from a configuration class?
		// -> allow the current bean method to override, since both are at second-pass level.
		// However, if the bean method is an overloaded case on the same configuration class,
		// preserve the existing bean definition.
		// 如果已经存在的bd也是通过@Bean方法声明的
		if (existingBeanDef instanceof ConfigurationClassBeanDefinition) {
			ConfigurationClassBeanDefinition ccbd = (ConfigurationClassBeanDefinition) existingBeanDef;
			// 判断声明它的configClass的类名和声明当前@Bean方法的类名是否相等，如果相等，说明这两个@Bean方法是一个类中的重载方法
			if (ccbd.getMetadata().getClassName().equals(
					beanMethod.getConfigurationClass().getMetadata().getClassName())) {
				// 如果已存在的bd的factoryMethodName和factoryMethodMetadata的methodName相等，将其的唯一标志设置为false
				if (ccbd.getFactoryMethodMetadata().getMethodName().equals(ccbd.getFactoryMethodName())) {
					ccbd.setNonUniqueFactoryMethodName(ccbd.getFactoryMethodMetadata().getMethodName());
				}
				// 返回true，表示用已存在的覆盖新的
				return true;
			}
			// 否则返回false，表示用新的覆盖已存在的
			else {
				return false;
			}
		}

		// A bean definition resulting from a component scan can be silently overridden
		// by an @Bean method, as of 4.2...
		// 如果已存在的bd是通过@ComponentScan扫描进来的，那么用@Bean方法生成的bd去覆盖已存在的
		if (existingBeanDef instanceof ScannedGenericBeanDefinition) {
			return false;
		}

		// Has the existing bean definition bean marked as a framework-generated bean?
		// -> allow the current bean method to override it, since it is application-level
		// 如果已存在的bd的role不是应用级的，是框架生成的，那么用新的覆盖已存在的，因为新生成的是应用级的。
		if (existingBeanDef.getRole() > BeanDefinition.ROLE_APPLICATION) {
			return false;
		}

		// At this point, it's a top-level override (probably XML), just having been parsed
		// before configuration class processing kicks in...
		// 如果以上条件都不满足，走到这一步。发现容器是不支持bd覆盖的，那么直接报错
		if (this.registry instanceof DefaultListableBeanFactory &&
				!((DefaultListableBeanFactory) this.registry).isAllowBeanDefinitionOverriding()) {
			throw new BeanDefinitionStoreException(beanMethod.getConfigurationClass().getResource().getDescription(),
					beanName, "@Bean definition illegally overridden by existing bean definition: " + existingBeanDef);
		}
		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Skipping bean definition for %s: a definition for bean '%s' " +
					"already exists. This top-level bean definition is considered as an override.",
					beanMethod, beanName));
		}
		// 否则，返回true，表示用已存在的覆盖新的
		return true;
	}

	private void loadBeanDefinitionsFromImportedResources(
			Map<String, Class<? extends BeanDefinitionReader>> importedResources) {

		Map<Class<?>, BeanDefinitionReader> readerInstanceCache = new HashMap<>();

		importedResources.forEach((resource, readerClass) -> {
			// Default reader selection necessary?
			// 遍历configClass所持有的importedResources的map，如果value对应的是BeanDefinitionReader.class的话。
			// 按照resource路径的结尾是否是.groovy，选用不同的BeanDefinitionReader实现类
			if (BeanDefinitionReader.class == readerClass) {
				if (StringUtils.endsWithIgnoreCase(resource, ".groovy")) {
					// When clearly asking for Groovy, that's what they'll get...
					readerClass = GroovyBeanDefinitionReader.class;
				}
				else {
					// Primarily ".xml" files but for any other extension as well
					readerClass = XmlBeanDefinitionReader.class;
				}
			}

			// 从缓存中根据类对象获取类的实例，如果缓存未命中
			BeanDefinitionReader reader = readerInstanceCache.get(readerClass);
			if (reader == null) {
				try {
					// Instantiate the specified BeanDefinitionReader
					// 调用构造器进行实例化，其中传入registry作为参数
					reader = readerClass.getConstructor(BeanDefinitionRegistry.class).newInstance(this.registry);
					// Delegate the current ResourceLoader to it if possible
					// 如果reader的类型是AbstractBeanDefinitionReader类型的，将resourceLoader和environment添加进去
					if (reader instanceof AbstractBeanDefinitionReader) {
						AbstractBeanDefinitionReader abdr = ((AbstractBeanDefinitionReader) reader);
						abdr.setResourceLoader(this.resourceLoader);
						abdr.setEnvironment(this.environment);
					}
					// 然后将实现类放入缓存中
					readerInstanceCache.put(readerClass, reader);
				}
				catch (Throwable ex) {
					throw new IllegalStateException(
							"Could not instantiate BeanDefinitionReader class [" + readerClass.getName() + "]");
				}
			}

			// TODO SPR-6310: qualify relative path locations as done in AbstractContextLoader.modifyLocations
			// 然后调用reader进行beanDefinition的读取
			reader.loadBeanDefinitions(resource);
		});
	}

	private void loadBeanDefinitionsFromRegistrars(Map<ImportBeanDefinitionRegistrar, AnnotationMetadata> registrars) {
		// 遍历configClass所持有的registrar的map，调用registrar的register方法，根据metadata注解bd进容器中
		registrars.forEach((registrar, metadata) ->
				registrar.registerBeanDefinitions(metadata, this.registry, this.importBeanNameGenerator));
	}


	/**
	 * {@link RootBeanDefinition} marker subclass used to signify that a bean definition
	 * was created from a configuration class as opposed to any other configuration source.
	 * Used in bean overriding cases where it's necessary to determine whether the bean
	 * definition was created externally.
	 */
	@SuppressWarnings("serial")
	private static class ConfigurationClassBeanDefinition extends RootBeanDefinition implements AnnotatedBeanDefinition {

		private final AnnotationMetadata annotationMetadata;

		private final MethodMetadata factoryMethodMetadata;

		private final String derivedBeanName;

		public ConfigurationClassBeanDefinition(
				ConfigurationClass configClass, MethodMetadata beanMethodMetadata, String derivedBeanName) {
			// 持有声明该bean方法的configClass的注解元数据
			this.annotationMetadata = configClass.getMetadata();
			// 持有bean方法对应的元数据
			this.factoryMethodMetadata = beanMethodMetadata;
			// 持有beanName
			this.derivedBeanName = derivedBeanName;
			// 将当前bd的resource设置为configClass的resource
			setResource(configClass.getResource());
			// 设置lenientConstructorResolution标志为false
			setLenientConstructorResolution(false);
		}

		public ConfigurationClassBeanDefinition(RootBeanDefinition original,
				ConfigurationClass configClass, MethodMetadata beanMethodMetadata, String derivedBeanName) {
			super(original);
			this.annotationMetadata = configClass.getMetadata();
			this.factoryMethodMetadata = beanMethodMetadata;
			this.derivedBeanName = derivedBeanName;
		}

		private ConfigurationClassBeanDefinition(ConfigurationClassBeanDefinition original) {
			super(original);
			this.annotationMetadata = original.annotationMetadata;
			this.factoryMethodMetadata = original.factoryMethodMetadata;
			this.derivedBeanName = original.derivedBeanName;
		}

		@Override
		public AnnotationMetadata getMetadata() {
			return this.annotationMetadata;
		}

		@Override
		@NonNull
		public MethodMetadata getFactoryMethodMetadata() {
			return this.factoryMethodMetadata;
		}

		@Override
		public boolean isFactoryMethod(Method candidate) {
			// 如果候选方法名称和factoryMethodName一致 并且 候选方法上标注了@Bean注解 并且 候选方法解析出来的beanName同bd中持有的beanName一致的话，返回true。
			// 表示该候选方法是工厂方法
			return (super.isFactoryMethod(candidate) && BeanAnnotationHelper.isBeanAnnotated(candidate) &&
					BeanAnnotationHelper.determineBeanNameFor(candidate).equals(this.derivedBeanName));
		}

		@Override
		public ConfigurationClassBeanDefinition cloneBeanDefinition() {
			return new ConfigurationClassBeanDefinition(this);
		}
	}


	/**
	 * Evaluate {@code @Conditional} annotations, tracking results and taking into
	 * account 'imported by'.
	 */
	private class TrackedConditionEvaluator {

		private final Map<ConfigurationClass, Boolean> skipped = new HashMap<>();

		public boolean shouldSkip(ConfigurationClass configClass) {
			// 查看configClass是否存在于skipped中
			Boolean skip = this.skipped.get(configClass);
			// 如果不存在的话，进行判断，然后缓存
			if (skip == null) {
				// 如果configClass是被导入的
				if (configClass.isImported()) {
					boolean allSkipped = true;
					// 判断导入它的那么configClass
					for (ConfigurationClass importedBy : configClass.getImportedBy()) {
						// 如果存在任何一个导入它的configClass是不应该被跳过的，那么将allSkipped设为false，跳出循环
						if (!shouldSkip(importedBy)) {
							allSkipped = false;
							break;
						}
					}
					// 即如果不是所有导入它的类都应该被跳过，那么它自身也应该被跳过
					if (allSkipped) {
						// The config classes that imported this one were all skipped, therefore we are skipped...
						skip = true;
					}
				}
				// 如果根据导入它的configClass判断出它不应该被跳过，那么根据其@Conditional注解来判断它是否应该被跳过
				if (skip == null) {
					skip = conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN);
				}
				// 将结果缓存中map中
				this.skipped.put(configClass, skip);
			}
			// 返回configClass是否应该被跳过
			return skip;
		}
	}

}
