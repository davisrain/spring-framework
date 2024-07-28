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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessor}
 * implementation that autowires annotated fields, setter methods, and arbitrary
 * config methods. Such members to be injected are detected through annotations:
 * by default, Spring's {@link Autowired @Autowired} and {@link Value @Value}
 * annotations.
 *
 * BeanPostProcessor的一个实现，用于自动注入被注解的字段，setter方法和任意的config members。
 * 这些member被注入和被检测通过默认的@Autowired 和 @Value注解
 *
 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
 * if available, as a direct alternative to Spring's own {@code @Autowired}.
 *
 * 同样支持JSR330里面的@Inject注解，如果是可以获得的，会作为一个@Autowired注解的一个直接的替换
 *
 * <h3>Autowired Constructors</h3>
 * <p>Only one constructor of any given bean class may declare this annotation with
 * the 'required' attribute set to {@code true}, indicating <i>the</i> constructor
 * to autowire when used as a Spring bean. Furthermore, if the 'required' attribute
 * is set to {@code true}, only a single constructor may be annotated with
 * {@code @Autowired}. If multiple <i>non-required</i> constructors declare the
 * annotation, they will be considered as candidates for autowiring. The constructor
 * with the greatest number of dependencies that can be satisfied by matching beans
 * in the Spring container will be chosen. If none of the candidates can be satisfied,
 * then a primary/default constructor (if present) will be used. If a class only
 * declares a single constructor to begin with, it will always be used, even if not
 * annotated. An annotated constructor does not have to be public.
 *
 *  对于用于自动注入的构造器来说
 *  任意一个给出的beanClass里面只能有一个required属性为true的标注了autowire相关注解的构造器。
 *  此外，如果required属性被设置为了true，那么只能有一个构造器能够被autowire相关注解标注。
 *  如果多个非required的构造器上声明了autowire相关注解，那么都会被选为自动注入的候选者。
 *  那么拥有最好数量依赖的构造器会被选择用作实例化bean，如果没有候选构造器满足，那么会使用默认的构造器。
 *  如果一个类只声明了一个单独的构造器，那么它总会被使用，就算它没有被注解标注。一个被注解标注的构造器不用被声明为public
 *
 * <h3>Autowired Fields</h3>
 * <p>Fields are injected right after construction of a bean, before any
 * config methods are invoked. Such a config field does not have to be public.
 *
 * 字段会在bean构建后被注入，先于任何配置的方法被调用之前，这些配置字段不需要被声明为public
 *
 * <h3>Autowired Methods</h3>
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a
 * general config method. Config methods do not have to be public.
 *
 * 配置方法可能有任意的名字和任意数量的参数，每一个参数都会被spring容器中的一个匹配的bean自动注入。
 * bean的属性setter方法实际上是这种通用配置方法的一种特殊情况。配置方法不需要被声明为public
 *
 * <h3>Annotation Config vs. XML Config</h3>
 * <p>A default {@code AutowiredAnnotationBeanPostProcessor} will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom {@code AutowiredAnnotationBeanPostProcessor} bean definition.
 *
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection;
 * thus the latter configuration will override the former for properties wired through
 * both approaches.
 *
 * <h3>{@literal @}Lookup Methods</h3>
 * <p>In addition to regular injection points as discussed above, this post-processor
 * also handles Spring's {@link Lookup @Lookup} annotation which identifies lookup
 * methods to be replaced by the container at runtime. This is essentially a type-safe
 * version of {@code getBean(Class, args)} and {@code getBean(String, args)}.
 * See {@link Lookup @Lookup's javadoc} for details.
 * 除了上面讨论的常规的注入点，这个postProcessor也能处理@Lookup注解，该注解指明了一个lookup方法需要在运行时被容器替换。
 * 这个本质上是getBean方法的一个安全版本。
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 2.5
 * @see #setAutowiredAnnotationType
 * @see Autowired
 * @see Value
 */
public class AutowiredAnnotationBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter
		implements MergedBeanDefinitionPostProcessor, PriorityOrdered, BeanFactoryAware {

	protected final Log logger = LogFactory.getLog(getClass());

	private final Set<Class<? extends Annotation>> autowiredAnnotationTypes = new LinkedHashSet<>(4);

	private String requiredParameterName = "required";

	private boolean requiredParameterValue = true;

	private int order = Ordered.LOWEST_PRECEDENCE - 2;

	@Nullable
	private ConfigurableListableBeanFactory beanFactory;

	// 用于保存解析过lookup注解的那些bean的beanName
	private final Set<String> lookupMethodsChecked = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	// 用于保存解析之后的beanClass对应的 候选构造器数组
	private final Map<Class<?>, Constructor<?>[]> candidateConstructorsCache = new ConcurrentHashMap<>(256);

	private final Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new {@code AutowiredAnnotationBeanPostProcessor} for Spring's
	 * standard {@link Autowired @Autowired} and {@link Value @Value} annotations.
	 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
	 * if available.
	 */
	@SuppressWarnings("unchecked")
	public AutowiredAnnotationBeanPostProcessor() {
		// 该类初始化的时候会添加Autowired.class Value.class Inject.class这三个类型到autowiredAnnotationTypes属性中
		this.autowiredAnnotationTypes.add(Autowired.class);
		this.autowiredAnnotationTypes.add(Value.class);
		try {
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("javax.inject.Inject", AutowiredAnnotationBeanPostProcessor.class.getClassLoader()));
			logger.trace("JSR-330 'javax.inject.Inject' annotation found and supported for autowiring");
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - simply skip.
		}
	}


	/**
	 * Set the 'autowired' annotation type, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as JSR-330's {@link javax.inject.Inject @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a member is supposed
	 * to be autowired.
	 *
	 * 这个方法支持设置自定义的autowired相关注解，默认的autowired相关注解是@Autowired @Value @Inject
	 * 但如果调用这个方法，会将当前默认的autowired相关注解清空，所以如果是想添加自定义的注解的话，记得将默认的也添加进来
	 */
	public void setAutowiredAnnotationType(Class<? extends Annotation> autowiredAnnotationType) {
		Assert.notNull(autowiredAnnotationType, "'autowiredAnnotationType' must not be null");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.add(autowiredAnnotationType);
	}

	/**
	 * Set the 'autowired' annotation types, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as JSR-330's {@link javax.inject.Inject @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation types to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationTypes(Set<Class<? extends Annotation>> autowiredAnnotationTypes) {
		Assert.notEmpty(autowiredAnnotationTypes, "'autowiredAnnotationTypes' must not be empty");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.addAll(autowiredAnnotationTypes);
	}

	/**
	 * Set the name of an attribute of the annotation that specifies whether it is required.
	 * @see #setRequiredParameterValue(boolean)
	 */
	public void setRequiredParameterName(String requiredParameterName) {
		this.requiredParameterName = requiredParameterName;
	}

	/**
	 * Set the boolean value that marks a dependency as required.
	 * <p>For example if using 'required=true' (the default), this value should be
	 * {@code true}; but if using 'optional=false', this value should be {@code false}.
	 * @see #setRequiredParameterName(String)
	 */
	public void setRequiredParameterValue(boolean requiredParameterValue) {
		this.requiredParameterValue = requiredParameterValue;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
		metadata.checkConfigMembers(beanDefinition);
	}

	@Override
	public void resetBeanDefinition(String beanName) {
		// 清除lookupMethodsChecked中beanName对应的缓存
		this.lookupMethodsChecked.remove(beanName);
		// 清除beanName对应的InjectionMetadata的缓存
		this.injectionMetadataCache.remove(beanName);
	}

	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, final String beanName)
			throws BeanCreationException {

		// Let's check for lookup methods here...
		// 从缓存中查找beanName对应的lookupMethods，如果缓存中不存在，进行解析放入缓存
		if (!this.lookupMethodsChecked.contains(beanName)) {
			// 如果beanClass是@Lookup注解的候选类
			if (AnnotationUtils.isCandidateClass(beanClass, Lookup.class)) {
				try {
					Class<?> targetClass = beanClass;
					do {
						// 尝试去当前类中查找标注了@Lookup注解的方法，并且实例化一个LookupOverride对象放入其beanName对应的mbd中
						ReflectionUtils.doWithLocalMethods(targetClass, method -> {
							// 查找方法上标注的@Lookup注解
							Lookup lookup = method.getAnnotation(Lookup.class);
							// 如果存在
							if (lookup != null) {
								Assert.state(this.beanFactory != null, "No BeanFactory available");
								// 根据方法和注解的value属性创建一个LookupOverride对象
								LookupOverride override = new LookupOverride(method, lookup.value());
								try {
									// 然后查找到beanName对应的mbd
									RootBeanDefinition mbd = (RootBeanDefinition)
											this.beanFactory.getMergedBeanDefinition(beanName);
									// 将其放入到mbd的methodOverrides属性中持有
									mbd.getMethodOverrides().addOverride(override);
								}
								catch (NoSuchBeanDefinitionException ex) {
									throw new BeanCreationException(beanName,
											"Cannot apply @Lookup to beans without corresponding bean definition");
								}
							}
						});
						// 然后查找其父类是否存在被@Lookup注解标注的方法
						targetClass = targetClass.getSuperclass();
					}
					// 当targetClass是null或者targetClass是Object.class的时候，结束循环
					while (targetClass != null && targetClass != Object.class);

				}
				catch (IllegalStateException ex) {
					throw new BeanCreationException(beanName, "Lookup method resolution failed", ex);
				}
			}
			// 然后将beanName缓存到lookupMethodsChecked缓存中，代表该beanName已经解析过lookup方法了
			this.lookupMethodsChecked.add(beanName);
		}

		// Quick check on the concurrent map first, with minimal locking.
		// 查看对应的beanClass在缓存中是否有对应的候选构造器，如果没有，进行解析
		Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
		if (candidateConstructors == null) {
			// Fully synchronized resolution now...
			// 这里对candidateConstructorsCache加锁，防止有多个线程同时去对候选构造器进行解析
			synchronized (this.candidateConstructorsCache) {
				// 并且这里采用了double-check，保证了只有一个线程对其解析
				candidateConstructors = this.candidateConstructorsCache.get(beanClass);
				if (candidateConstructors == null) {
					Constructor<?>[] rawCandidates;
					try {
						// 获取beanClass中声明的构造器数组
						rawCandidates = beanClass.getDeclaredConstructors();
					}
					catch (Throwable ex) {
						throw new BeanCreationException(beanName,
								"Resolution of declared constructors on bean Class [" + beanClass.getName() +
								"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
					}
					// 创建一个候选构造器的集合
					List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
					// 声明一个变量用于保存required=true的构造器
					Constructor<?> requiredConstructor = null;
					// 声明一个变量用于保存default的构造器
					Constructor<?> defaultConstructor = null;
					// 如果是kotlin语言，找到primaryConstructor
					Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(beanClass);
					// 记录非合成的构造器的数量
					int nonSyntheticConstructors = 0;
					// 遍历上一步取到的声明的构造器数组
					for (Constructor<?> candidate : rawCandidates) {
						// 如果构造器不是合成的，即编译器自动生成的，将计数器+1
						if (!candidate.isSynthetic()) {
							nonSyntheticConstructors++;
						}
						// 如果构造器是编译器自动生成的，且kotlin的PrimaryConstructor不为null，进入下一次循环。
						// 一般情况下，是不会存在primaryConstructor的，所以不会走到这一步
						else if (primaryConstructor != null) {
							continue;
						}
						// 查找构造器上是否标注了autowired相关的注解，即@Autowired @Value @Inject
						MergedAnnotation<?> ann = findAutowiredAnnotation(candidate);
						// 如果不存在autowired相关的注解
						if (ann == null) {
							// 尝试获取cglib代理前的类
							Class<?> userClass = ClassUtils.getUserClass(beanClass);
							// 如果发现userClass和beanClass不相等，那么说明beanClass已经被cglib代理过了，我们获取到没有被代理的父类
							if (userClass != beanClass) {
								try {
									// 尝试获取父类中相同参数的构造器
									Constructor<?> superCtor =
											userClass.getDeclaredConstructor(candidate.getParameterTypes());
									// 然后从父类构造器上获取autowired相关的注解
									ann = findAutowiredAnnotation(superCtor);
								}
								catch (NoSuchMethodException ex) {
									// Simply proceed, no equivalent superclass constructor found...
								}
							}
						}
						// 如果在构造器上找到了autowired相关的注解
						if (ann != null) {
							// 如果此时requiredConstructor已经不为null了，说明已经存在required=true的构造器了，
							// 不能再在其他构造器上标注autowired相关的注解了。如果出现这种情况，抛出异常
							if (requiredConstructor != null) {
								throw new BeanCreationException(beanName,
										"Invalid autowire-marked constructor: " + candidate +
										". Found constructor with 'required' Autowired annotation already: " +
										requiredConstructor);
							}
							// 判断注解的required属性，如果是@Autowired注解，判断其required属性的值，如果是@Value @Inject注解，required默认为true
							boolean required = determineRequiredStatus(ann);
							// 如果发现注解的required属性是true
							if (required) {
								// 并且此时候选集合不为空的话，报错。
								// 因为如果存在required=true的构造器，那么是不允许出现其他标注了autowired相关注解的构造器的，而candidates不为空，
								// 说明在遍历到该构造器之前，已经存在标注了autowired相关注解的构造器了，所以报错。
								if (!candidates.isEmpty()) {
									throw new BeanCreationException(beanName,
											"Invalid autowire-marked constructors: " + candidates +
											". Found constructor with 'required' Autowired annotation: " +
											candidate);
								}
								// 将当前遍历到的candidate赋值给requiredConstructor，表示已经找到了required=true的构造器
								requiredConstructor = candidate;
							}
							// 将当前遍历的candidate加入到candidates集合中。
							// 根据上述的判断条件，可以确定的是，当存在required=true的构造器时，candidates集合中只能有一个元素，就是required=true的构造器；
							// 而candidates存在多个元素的必要条件是，这些构造器的required属性都为false
							candidates.add(candidate);
						}
						// 如果在构造器上没有找到autowired相关的注解，但该构造器的参数为0，将其赋值给defaultConstructor，说明是默认的构造器
						else if (candidate.getParameterCount() == 0) {
							defaultConstructor = candidate;
						}
					}
					// 如果最后候选集合不为空的话，说明存在标注了@Autowired 或 @Value 或 @Inject注解的构造器
					if (!candidates.isEmpty()) {
						// Add default constructor to list of optional constructors, as fallback.
						// 如果不存在required属性为true的构造器
						if (requiredConstructor == null) {
							// 并且存在默认构造器，将其也添加到候选集合中
							if (defaultConstructor != null) {
								candidates.add(defaultConstructor);
							}
							// 如果不存在默认的构造器 且 候选集合数量为1的话，那么该构造器就可以作为注入构造器使用
							else if (candidates.size() == 1 && logger.isInfoEnabled()) {
								logger.info("Inconsistent constructor declaration on bean with name '" + beanName +
										"': single autowire-marked constructor flagged as optional - " +
										"this constructor is effectively required since there is no " +
										"default constructor to fall back to: " + candidates.get(0));
							}
						}
						// 将候选集合转换为数组
						candidateConstructors = candidates.toArray(new Constructor<?>[0]);
					}
					// 如果candidates集合为空，说明不存在标注了autowired相关注解的构造器。
					// 那么如果声明的构造器只有一个 且是有参构造器的话，将其作为最后候选的构造器
					else if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
						candidateConstructors = new Constructor<?>[] {rawCandidates[0]};
					}
					// 如果不是合成的构造器数量为2，并且primary和default构造器都存在，并且二者不相等，那么将二者作为候选构造器
					else if (nonSyntheticConstructors == 2 && primaryConstructor != null &&
							defaultConstructor != null && !primaryConstructor.equals(defaultConstructor)) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor, defaultConstructor};
					}
					// 如果不是合成的构造器数量为1，且primary构造器不为null，将其作为最后的候选构造器
					else if (nonSyntheticConstructors == 1 && primaryConstructor != null) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor};
					}
					// 否则创建一个空的数组
					else {
						candidateConstructors = new Constructor<?>[0];
					}
					// 将候选构造器放入缓存
					this.candidateConstructorsCache.put(beanClass, candidateConstructors);
				}
			}
		}
		// 如果数组长度大于0，返回数组，否则返回null
		return (candidateConstructors.length > 0 ? candidateConstructors : null);
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		// 根据beanName从缓存中获取postProcessMergedBeanDefinition方法构建的InjectionMetadata
		InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
		try {
			// 调用medata的inject方法
			metadata.inject(bean, beanName, pvs);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
		}
		return pvs;
	}

	@Deprecated
	@Override
	public PropertyValues postProcessPropertyValues(
			PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) {

		return postProcessProperties(pvs, bean, beanName);
	}

	/**
	 * 'Native' processing method for direct calls with an arbitrary target instance,
	 * resolving all of its fields and methods which are annotated with one of the
	 * configured 'autowired' annotation types.
	 * @param bean the target instance to process
	 * @throws BeanCreationException if autowiring failed
	 * @see #setAutowiredAnnotationTypes(Set)
	 */
	public void processInjection(Object bean) throws BeanCreationException {
		Class<?> clazz = bean.getClass();
		InjectionMetadata metadata = findAutowiringMetadata(clazz.getName(), clazz, null);
		try {
			metadata.inject(bean, null, null);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					"Injection of autowired dependencies failed for class [" + clazz + "]", ex);
		}
	}


	private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
		// Fall back to class name as cache key, for backwards compatibility with custom callers.
		// 如果beanName不是空字符串，用其作为缓存键，否则使用类的全限定名作为缓存键
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
		// Quick check on the concurrent map first, with minimal locking.
		// 尝试从缓存中获取InjectionMetadata
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
		// 如果缓存未命中 或者 metadata的targetClass和clazz不相等，进行metadata的获取操作
		if (InjectionMetadata.needsRefresh(metadata, clazz)) {
			// 加锁
			synchronized (this.injectionMetadataCache) {
				// double check，防止有多个线程执行了获取逻辑
				metadata = this.injectionMetadataCache.get(cacheKey);
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {
					// 如果缓存中存在metadata，只是需要刷新，那么将pvs中对应的processProperty清理掉
					if (metadata != null) {
						metadata.clear(pvs);
					}
					// 构建autowiring的InjectionMetadata
					metadata = buildAutowiringMetadata(clazz);
					// 将构建好的metadata放入缓存中
					this.injectionMetadataCache.put(cacheKey, metadata);
				}
			}
		}
		return metadata;
	}

	private InjectionMetadata buildAutowiringMetadata(Class<?> clazz) {
		// 判断clazz是否是autowiredAnnotationTypes中的注解类型的候选标注类，如果不是，直接返回空的InjectionMetadata
		if (!AnnotationUtils.isCandidateClass(clazz, this.autowiredAnnotationTypes)) {
			return InjectionMetadata.EMPTY;
		}

		List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
		// 将clazz赋值给targetClass，后续会继续遍历targetClass的父类
		Class<?> targetClass = clazz;

		do {
			final List<InjectionMetadata.InjectedElement> currElements = new ArrayList<>();

			// 遍历targetClass中的field
			ReflectionUtils.doWithLocalFields(targetClass, field -> {
				// 查找字段上标注的autowiredAnnotationTypes中包含的注解类型对应的注解
				MergedAnnotation<?> ann = findAutowiredAnnotation(field);
				// 如果注解存在
				if (ann != null) {
					// 判断字段是否是static的，如果是，直接跳过
					if (Modifier.isStatic(field.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static fields: " + field);
						}
						return;
					}
					// 判断注解的required属性，如果注解不包含required属性或者require属性的值为true，方法返回true，表示该属性的注入是必须的
					boolean required = determineRequiredStatus(ann);
					// 根据field和required属性构建一个AutowiredFieldElement放入集合中
					currElements.add(new AutowiredFieldElement(field, required));
				}
			});

			// 遍历targetClass的method
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				// 获取桥接方法的被桥接方法，如果方法不是桥接方法，返回方法本身
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				// 如果不是用于扩展可见性的桥接方法对的话，直接跳过
				if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					return;
				}
				// 找到对应方法上标注的@Autowired @Value 或 @Inject注解
				MergedAnnotation<?> ann = findAutowiredAnnotation(bridgedMethod);
				// 如果注解存在 且 方法没有被clazz重写，执行if里面的逻辑
				if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					// 如果方法是static的，直接返回
					if (Modifier.isStatic(method.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static methods: " + method);
						}
						return;
					}
					// 如果方法的参数个数为0
					if (method.getParameterCount() == 0) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation should only be used on methods with parameters: " +
									method);
						}
					}
					// 判定注解中required属性的值
					boolean required = determineRequiredStatus(ann);
					// 找到方法对应的clazz中的propertyDescriptor
					PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
					// 创建一个AutowiredMethodElement放入集合中
					currElements.add(new AutowiredMethodElement(method, required, pd));
				}
			});

			// 将当前elements的集合添加到总集合的顶端
			elements.addAll(0, currElements);
			// 并且将targetClass赋值为其父类
			targetClass = targetClass.getSuperclass();
		}
		// 如果targetClass为null或者等于Object.class，就结束循环
		while (targetClass != null && targetClass != Object.class);

		// 根据elements集合和clazz生成一个InjectionMetadata返回
		return InjectionMetadata.forElements(elements, clazz);
	}

	@Nullable
	private MergedAnnotation<?> findAutowiredAnnotation(AccessibleObject ao) {
		// 根据构造器创建一个TypeMappedAnnotations
		MergedAnnotations annotations = MergedAnnotations.from(ao);
		// 然后遍历自身持有的autowiredAnnotationTypes，查看MergeAnnotations中是否包含有对应的注解
		for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
			MergedAnnotation<?> annotation = annotations.get(type);
			// 一旦注解存在，直接返回
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		return null;
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 */
	@SuppressWarnings({"deprecation", "cast"})
	protected boolean determineRequiredStatus(MergedAnnotation<?> ann) {
		// The following (AnnotationAttributes) cast is required on JDK 9+.
		// 如果注解中存在required属性，且值为true 或者 不存在required属性，返回true，否则返回false
		return determineRequiredStatus((AnnotationAttributes)
				ann.asMap(mergedAnnotation -> new AnnotationAttributes(mergedAnnotation.getType())));
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 * @deprecated since 5.2, in favor of {@link #determineRequiredStatus(MergedAnnotation)}
	 */
	@Deprecated
	protected boolean determineRequiredStatus(AnnotationAttributes ann) {
		return (!ann.containsKey(this.requiredParameterName) ||
				this.requiredParameterValue == ann.getBoolean(this.requiredParameterName));
	}

	/**
	 * Obtain all beans of the given type as autowire candidates.
	 * @param type the type of the bean
	 * @return the target beans, or an empty Collection if no bean of this type is found
	 * @throws BeansException if bean retrieval failed
	 */
	protected <T> Map<String, T> findAutowireCandidates(Class<T> type) throws BeansException {
		if (this.beanFactory == null) {
			throw new IllegalStateException("No BeanFactory configured - " +
					"override the getBeanOfType method or specify the 'beanFactory' property");
		}
		return BeanFactoryUtils.beansOfTypeIncludingAncestors(this.beanFactory, type);
	}

	/**
	 * Register the specified bean as dependent on the autowired beans.
	 */
	private void registerDependentBeans(@Nullable String beanName, Set<String> autowiredBeanNames) {
		if (beanName != null) {
			for (String autowiredBeanName : autowiredBeanNames) {
				if (this.beanFactory != null && this.beanFactory.containsBean(autowiredBeanName)) {
					this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Autowiring by type from bean name '" + beanName +
							"' to bean named '" + autowiredBeanName + "'");
				}
			}
		}
	}

	/**
	 * Resolve the specified cached method argument or field value.
	 */
	@Nullable
	private Object resolvedCachedArgument(@Nullable String beanName, @Nullable Object cachedArgument) {
		// 如果缓存的参数是DependencyDescriptor类型的
		if (cachedArgument instanceof DependencyDescriptor) {
			DependencyDescriptor descriptor = (DependencyDescriptor) cachedArgument;
			Assert.state(this.beanFactory != null, "No BeanFactory available");
			// 调用beanFactory的resolveDependency方法进行解析
			return this.beanFactory.resolveDependency(descriptor, beanName, null, null);
		}
		// 否则，直接返回缓存的参数
		else {
			return cachedArgument;
		}
	}


	/**
	 * Class representing injection information about an annotated field.
	 */
	private class AutowiredFieldElement extends InjectionMetadata.InjectedElement {

		private final boolean required;

		private volatile boolean cached;

		@Nullable
		private volatile Object cachedFieldValue;

		public AutowiredFieldElement(Field field, boolean required) {
			super(field, null);
			this.required = required;
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			Field field = (Field) this.member;
			Object value;
			// 如果cached属性为true的话
			if (this.cached) {
				try {
					// 尝试获取缓存过的值
					value = resolvedCachedArgument(beanName, this.cachedFieldValue);
				}
				catch (NoSuchBeanDefinitionException ex) {
					// Unexpected removal of target bean for cached argument -> re-resolve
					// 如果获取失败了，调用resolveFieldValue方法进行解析字段的值
					value = resolveFieldValue(field, bean, beanName);
				}
			}
			// 如果cached为false
			else {
				// 解析字段的值
				value = resolveFieldValue(field, bean, beanName);
			}
			// 如果获取到的value不为null的话
			if (value != null) {
				// 设置进bean对象的field中
				ReflectionUtils.makeAccessible(field);
				field.set(bean, value);
			}
		}

		@Nullable
		private Object resolveFieldValue(Field field, Object bean, @Nullable String beanName) {
			// 通过field创建一个DependencyDescriptor
			DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
			// 设置desc的containingClass为bean对应的class对象
			desc.setContainingClass(bean.getClass());
			Set<String> autowiredBeanNames = new LinkedHashSet<>(1);
			Assert.state(beanFactory != null, "No BeanFactory available");
			// 获取beanFactory的typeConverter
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			Object value;
			try {
				// 调用beanFactory的resolveDependency方法获取对应的bean
				value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(field), ex);
			}
			synchronized (this) {
				// 如果自身的cached属性为false
				if (!this.cached) {
					Object cachedFieldValue = null;
					// value不为null 或者 required是true的
					if (value != null || this.required) {
						// 将cachedFieldValue设置为DependencyDescriptor
						cachedFieldValue = desc;
						// 将beanName和autowiredBeanNames之间的依赖关系注册进容器
						registerDependentBeans(beanName, autowiredBeanNames);
						// 如果自动注入的beanName只有一个
						if (autowiredBeanNames.size() == 1) {
							String autowiredBeanName = autowiredBeanNames.iterator().next();
							// 且beanFactory中包含该beanName 且 bean的类型和字段的类型匹配
							if (beanFactory.containsBean(autowiredBeanName) &&
									beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
								// 创建一个ShortcutDependencyDescriptor的装饰对象赋值给cachedFieldValue，
								// 里面持有了对应的beanName和beanType，用作解析依赖的快捷方式
								cachedFieldValue = new ShortcutDependencyDescriptor(
										desc, autowiredBeanName, field.getType());
							}
						}
					}
					// 将cachedFieldValue赋值给自身的cachedFieldValue属性
					this.cachedFieldValue = cachedFieldValue;
					// 并且将cached属性置为true，表示已经缓存过结果了
					this.cached = true;
				}
			}
			return value;
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 */
	private class AutowiredMethodElement extends InjectionMetadata.InjectedElement {

		private final boolean required;

		private volatile boolean cached;

		@Nullable
		private volatile Object[] cachedMethodArguments;

		public AutowiredMethodElement(Method method, boolean required, @Nullable PropertyDescriptor pd) {
			super(method, pd);
			this.required = required;
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			// 检查属性是否需要被跳过，如果skip没有被解析过，那么解析之后设置skip属性的值。
			// 如果需要被跳过，直接返回，该属性会在PropertyValues被解析的时候注入
			if (checkPropertySkipping(pvs)) {
				return;
			}
			Method method = (Method) this.member;
			Object[] arguments;
			// 如果cached为true，表示已经被缓存过
			if (this.cached) {
				try {
					// 解析缓存过的参数
					arguments = resolveCachedArguments(beanName);
				}
				catch (NoSuchBeanDefinitionException ex) {
					// Unexpected removal of target bean for cached argument -> re-resolve
					// 如果出现异常，回调到解析方法参数的方法
					arguments = resolveMethodArguments(method, bean, beanName);
				}
			}
			// 如果没有被缓存过
			else {
				// 调用resolveMethodArguments方法对方法参数进行解析
				arguments = resolveMethodArguments(method, bean, beanName);
			}
			// 如果参数不为null
			if (arguments != null) {
				try {
					// 调用方法，将值设置进属性中
					ReflectionUtils.makeAccessible(method);
					method.invoke(bean, arguments);
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		@Nullable
		private Object[] resolveCachedArguments(@Nullable String beanName) {
			Object[] cachedMethodArguments = this.cachedMethodArguments;
			// 如果缓存的方法参数为null，直接返回null
			if (cachedMethodArguments == null) {
				return null;
			}
			Object[] arguments = new Object[cachedMethodArguments.length];
			// 遍历缓存的参数数组
			for (int i = 0; i < arguments.length; i++) {
				// 依次解析每个缓存的参数
				arguments[i] = resolvedCachedArgument(beanName, cachedMethodArguments[i]);
			}
			// 返回解析后的参数数组
			return arguments;
		}

		@Nullable
		private Object[] resolveMethodArguments(Method method, Object bean, @Nullable String beanName) {
			// 获取方法需要的参数个数
			int argumentCount = method.getParameterCount();
			// 根据参数个数创建一个对应长度的参数数组
			Object[] arguments = new Object[argumentCount];
			// 根据参数个数创建对应长度的DependencyDescriptor类型的数组
			DependencyDescriptor[] descriptors = new DependencyDescriptor[argumentCount];
			Set<String> autowiredBeans = new LinkedHashSet<>(argumentCount);
			Assert.state(beanFactory != null, "No BeanFactory available");
			// 获取beanFactory的typeConverter
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			// 遍历参数数组
			for (int i = 0; i < arguments.length; i++) {
				// 根据参数下标创建对应的MethodParameter
				MethodParameter methodParam = new MethodParameter(method, i);
				// 根据MethodParameter和required创建一个DependencyDescriptor
				DependencyDescriptor currDesc = new DependencyDescriptor(methodParam, this.required);
				// 设置currDesc的containingClass为bean的class对象
				currDesc.setContainingClass(bean.getClass());
				// 将currDesc设置进DependencyDescriptor类型的数组的对应下标中
				descriptors[i] = currDesc;
				try {
					// 调用beanFactory的resolveDependency方法解析依赖获取对应的bean
					Object arg = beanFactory.resolveDependency(currDesc, beanName, autowiredBeans, typeConverter);
					// 如果获取到的参数为null 并且 required是false
					if (arg == null && !this.required) {
						// 那么将参数数组置为null，并且跳出循环
						arguments = null;
						break;
					}
					// 否则将参数设置进参数数组的对应下标中
					arguments[i] = arg;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(methodParam), ex);
				}
			}
			synchronized (this) {
				// 如果cached属性为false
				if (!this.cached) {
					// 并且参数数组不为null，那么将解析结果缓存下来
					if (arguments != null) {
						// 复制DependencyDescriptor数组
						DependencyDescriptor[] cachedMethodArguments = Arrays.copyOf(descriptors, arguments.length);
						// 将beanName和autowiredBeans之间的依赖关系注册进容器
						registerDependentBeans(beanName, autowiredBeans);
						// 如果依赖的bean的个数 等于 参数的个数
						if (autowiredBeans.size() == argumentCount) {
							Iterator<String> it = autowiredBeans.iterator();
							Class<?>[] paramTypes = method.getParameterTypes();
							for (int i = 0; i < paramTypes.length; i++) {
								// 获取每个依赖的beanName
								String autowiredBeanName = it.next();
								// 如果容器中包含这个beanName 并且 该beanName对应的bean和对应下标的参数类型匹配
								if (beanFactory.containsBean(autowiredBeanName) &&
										beanFactory.isTypeMatch(autowiredBeanName, paramTypes[i])) {
									// 将对应下标的DependencyDescriptor装饰为ShortcutDependencyDescriptor，
									// 让其内部持有beanName和beanType，使得其在解析的时候可以使用快捷方法，直接根据beanName从beanFactory中
									// 获取bean
									cachedMethodArguments[i] = new ShortcutDependencyDescriptor(
											descriptors[i], autowiredBeanName, paramTypes[i]);
								}
							}
						}
						// 将cachedMethodArguments复制给自身属性
						this.cachedMethodArguments = cachedMethodArguments;
					}
					// 如果参数数组为null的话
					else {
						// 将cachedMethodArguments属性置为null
						this.cachedMethodArguments = null;
					}
					// 将cached属性置为true，表示已经缓存过
					this.cached = true;
				}
			}
			// 返回方法参数数组
			return arguments;
		}
	}


	/**
	 * DependencyDescriptor variant with a pre-resolved target bean name.
	 */
	@SuppressWarnings("serial")
	private static class ShortcutDependencyDescriptor extends DependencyDescriptor {

		private final String shortcut;

		private final Class<?> requiredType;

		public ShortcutDependencyDescriptor(DependencyDescriptor original, String shortcut, Class<?> requiredType) {
			super(original);
			this.shortcut = shortcut;
			this.requiredType = requiredType;
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			return beanFactory.getBean(this.shortcut, this.requiredType);
		}
	}

}
