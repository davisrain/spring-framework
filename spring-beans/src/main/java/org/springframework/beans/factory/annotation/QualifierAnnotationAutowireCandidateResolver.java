/*
 * Copyright 2002-2018 the original author or authors.
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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.GenericTypeAwareAutowireCandidateResolver;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * {@link AutowireCandidateResolver} implementation that matches bean definition qualifiers
 * against {@link Qualifier qualifier annotations} on the field or parameter to be autowired.
 * Also supports suggested expression values through a {@link Value value} annotation.
 *
 * <p>Also supports JSR-330's {@link javax.inject.Qualifier} annotation, if available.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 2.5
 * @see AutowireCandidateQualifier
 * @see Qualifier
 * @see Value
 */
public class QualifierAnnotationAutowireCandidateResolver extends GenericTypeAwareAutowireCandidateResolver {

	private final Set<Class<? extends Annotation>> qualifierTypes = new LinkedHashSet<>(2);

	private Class<? extends Annotation> valueAnnotationType = Value.class;


	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for Spring's standard {@link Qualifier} annotation.
	 * <p>Also supports JSR-330's {@link javax.inject.Qualifier} annotation, if available.
	 */
	@SuppressWarnings("unchecked")
	public QualifierAnnotationAutowireCandidateResolver() {
		this.qualifierTypes.add(Qualifier.class);
		try {
			this.qualifierTypes.add((Class<? extends Annotation>) ClassUtils.forName("javax.inject.Qualifier",
							QualifierAnnotationAutowireCandidateResolver.class.getClassLoader()));
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - simply skip.
		}
	}

	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for the given qualifier annotation type.
	 * @param qualifierType the qualifier annotation to look for
	 */
	public QualifierAnnotationAutowireCandidateResolver(Class<? extends Annotation> qualifierType) {
		Assert.notNull(qualifierType, "'qualifierType' must not be null");
		this.qualifierTypes.add(qualifierType);
	}

	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for the given qualifier annotation types.
	 * @param qualifierTypes the qualifier annotations to look for
	 */
	public QualifierAnnotationAutowireCandidateResolver(Set<Class<? extends Annotation>> qualifierTypes) {
		Assert.notNull(qualifierTypes, "'qualifierTypes' must not be null");
		this.qualifierTypes.addAll(qualifierTypes);
	}


	/**
	 * Register the given type to be used as a qualifier when autowiring.
	 * <p>This identifies qualifier annotations for direct use (on fields,
	 * method parameters and constructor parameters) as well as meta
	 * annotations that in turn identify actual qualifier annotations.
	 * <p>This implementation only supports annotations as qualifier types.
	 * The default is Spring's {@link Qualifier} annotation which serves
	 * as a qualifier for direct use and also as a meta annotation.
	 * @param qualifierType the annotation type to register
	 */
	public void addQualifierType(Class<? extends Annotation> qualifierType) {
		this.qualifierTypes.add(qualifierType);
	}

	/**
	 * Set the 'value' annotation type, to be used on fields, method parameters
	 * and constructor parameters.
	 * <p>The default value annotation type is the Spring-provided
	 * {@link Value} annotation.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate a default value
	 * expression for a specific argument.
	 */
	public void setValueAnnotationType(Class<? extends Annotation> valueAnnotationType) {
		this.valueAnnotationType = valueAnnotationType;
	}


	/**
	 * Determine whether the provided bean definition is an autowire candidate.
	 * <p>To be considered a candidate the bean's <em>autowire-candidate</em>
	 * attribute must not have been set to 'false'. Also, if an annotation on
	 * the field or parameter to be autowired is recognized by this bean factory
	 * as a <em>qualifier</em>, the bean must 'match' against the annotation as
	 * well as any attributes it may contain. The bean definition must contain
	 * the same qualifier or match by meta attributes. A "value" attribute will
	 * fallback to match against the bean name or an alias if a qualifier or
	 * attribute does not match.
	 *
	 * 判断提供的bean definition是否是一个 自动注入的候选。
	 * 1、首先autowiredCandidate属性不能为false
	 * 2、其次，如果要注入的字段或者参数上存在一个被bean factory识别为 qualifier的注解，那么这个bean必须匹配这个注解，
	 * 也必须匹配这个注解包含的属性。这个bean definition必须包含同样的qualifier 或者 被元属性匹配。
	 * 3、qualifier注解的value属性的值被作为一个兜底去匹配beanName或者别名，如果没有匹配上bean definition里面的qualifier或属性的话。
	 * @see Qualifier
	 */
	@Override
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		// 调用父类的isAutowireCandidate方法
		boolean match = super.isAutowireCandidate(bdHolder, descriptor);
		// 如果父类方法返回true
		if (match) {
			// 如果依赖描述中的字段和方法参数上标注了@Qualifier注解，那么进行检查
			match = checkQualifiers(bdHolder, descriptor.getAnnotations());
			// 如果匹配
			if (match) {
				// 获取依赖描述中的方法参数
				MethodParameter methodParam = descriptor.getMethodParameter();
				// 如果存在方法参数
				if (methodParam != null) {
					// 获取对应的方法，如果方法参数中的executable是构造器类型的话，这里会返回null
					Method method = methodParam.getMethod();
					// 如果方法不存在 或者 返回返回值是void，那么说明该注入点是构造器的方法参数，
					if (method == null || void.class == method.getReturnType()) {
						// 那么检查构造器上标注的注解中的@Qualifier注解
						match = checkQualifiers(bdHolder, methodParam.getMethodAnnotations());
					}
				}
			}
		}
		return match;
	}

	/**
	 * Match the given qualifier annotations against the candidate bean definition.
	 */
	protected boolean checkQualifiers(BeanDefinitionHolder bdHolder, Annotation[] annotationsToSearch) {
		// 如果注解数组为空的话，直接返回true
		if (ObjectUtils.isEmpty(annotationsToSearch)) {
			return true;
		}
		// 创建一个SimpleTypeConverter
		SimpleTypeConverter typeConverter = new SimpleTypeConverter();
		// 遍历注解数组
		for (Annotation annotation : annotationsToSearch) {
			Class<? extends Annotation> type = annotation.annotationType();
			boolean checkMeta = true;
			boolean fallbackToMeta = false;
			// 如果注解类型是@Qualifier类型的或者@javax.inject.Qualifier类型的，或者注解类型上标注了对应类型的元注解，返回true
			// 比如自定义了一个注解@CustomQualifier，其元注解上标注了@Qualifier注解，也是可以的
			if (isQualifier(type)) {
				// 调用checkQualifier方法，如果检查不通过，将fallbackToMeta参数置为true，表示需要去元注解上判断
				if (!checkQualifier(bdHolder, annotation, typeConverter)) {
					fallbackToMeta = true;
				}
				// 如果检查通过，将checkMeta置为false
				else {
					checkMeta = false;
				}
			}
			// 如果checkMeta标志为true
			if (checkMeta) {
				boolean foundMeta = false;
				// 获取注解类型上标注的元注解
				for (Annotation metaAnn : type.getAnnotations()) {
					Class<? extends Annotation> metaType = metaAnn.annotationType();
					// 判断元注解是否是@Qualifier
					if (isQualifier(metaType)) {
						// 如果是，将foundMeta标志置为true
						foundMeta = true;
						// Only accept fallback match if @Qualifier annotation has a value...
						// Otherwise it is just a marker for a custom qualifier annotation.
						// 如果fallbackToMeta标志位true并且注解的value属性里没有值，即元注解上只是单纯的标注了@Qualifier注解，并没有设置其value值，
						// 说明这里的@Qualifier只是用于标记自定义的注解是一个限定符注解，并不提供属性值来检验是否满足限定符。这种情况直接返回false
						// 或者 检查不通过，返回false
						if ((fallbackToMeta && StringUtils.isEmpty(AnnotationUtils.getValue(metaAnn))) ||
								!checkQualifier(bdHolder, metaAnn, typeConverter)) {
							return false;
						}
					}
				}
				// 如果fallbackToMeta为true foundMeta为false，说明最外层的注解检验失败，并且元注解不存在限定符注解，那么直接返回false
				if (fallbackToMeta && !foundMeta) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Checks whether the given annotation type is a recognized qualifier type.
	 */
	protected boolean isQualifier(Class<? extends Annotation> annotationType) {
		// 遍历qualifier注解的类型集合，其中有Spring提供的@Qualifier注解，也有javax.inject.Qualifier注解
		for (Class<? extends Annotation> qualifierType : this.qualifierTypes) {
			// 判断注解类型是否等于qualifierType 或者 注解类型上标注了qualifierType注解
			if (annotationType.equals(qualifierType) || annotationType.isAnnotationPresent(qualifierType)) {
				// 如果是，返回true
				return true;
			}
		}
		// 否则返回false
		return false;
	}

	/**
	 * Match the given qualifier annotation against the candidate bean definition.
	 */
	protected boolean checkQualifier(
			BeanDefinitionHolder bdHolder, Annotation annotation, TypeConverter typeConverter) {

		Class<? extends Annotation> type = annotation.annotationType();
		RootBeanDefinition bd = (RootBeanDefinition) bdHolder.getBeanDefinition();

		// 尝试从bd根据注解类型名称获取AutowireCandidateQualifier
		AutowireCandidateQualifier qualifier = bd.getQualifier(type.getName());
		// 如果为null的话，尝试根据类型的短名称获取
		if (qualifier == null) {
			qualifier = bd.getQualifier(ClassUtils.getShortName(type));
		}
		// 如果仍然为null
		if (qualifier == null) {
			// First, check annotation on qualified element, if any
			// 尝试从qualifiedElement上获取对应类型的注解
			Annotation targetAnnotation = getQualifiedElementAnnotation(bd, type);
			// Then, check annotation on factory method, if applicable
			// 如果不存在，尝试从factoryMethod上获取对应类型的注解
			if (targetAnnotation == null) {
				targetAnnotation = getFactoryMethodAnnotation(bd, type);
			}
			// 如果仍不存在，尝试从被装饰的bd的factoryMethod上获取对应类型的注解
			if (targetAnnotation == null) {
				RootBeanDefinition dbd = getResolvedDecoratedDefinition(bd);
				if (dbd != null) {
					targetAnnotation = getFactoryMethodAnnotation(dbd, type);
				}
			}
			// 如果仍然为null
			if (targetAnnotation == null) {
				// Look for matching annotation on the target class
				if (getBeanFactory() != null) {
					try {
						// 从beanFactory中获取到beanName对应的beanType
						Class<?> beanType = getBeanFactory().getType(bdHolder.getBeanName());
						if (beanType != null) {
							// 然后尝试从beanType上获取对应类型的注解
							targetAnnotation = AnnotationUtils.getAnnotation(ClassUtils.getUserClass(beanType), type);
						}
					}
					catch (NoSuchBeanDefinitionException ex) {
						// Not the usual case - simply forget about the type check...
					}
				}
				// 如果仍为null 并且 bd存在beanClass
				if (targetAnnotation == null && bd.hasBeanClass()) {
					// 尝试从beanClass上获取对应类型的注解
					targetAnnotation = AnnotationUtils.getAnnotation(ClassUtils.getUserClass(bd.getBeanClass()), type);
				}
			}
			// 如果目标注解不为null 并且 等于传入的参数注解，返回true
			if (targetAnnotation != null && targetAnnotation.equals(annotation)) {
				return true;
			}
		}

		// 获取注解对应的属性
		Map<String, Object> attributes = AnnotationUtils.getAnnotationAttributes(annotation);
		// 如果属性map为空 并且 qualifier为null，直接返回false
		if (attributes.isEmpty() && qualifier == null) {
			// If no attributes, the qualifier must be present
			return false;
		}
		// 遍历注解对应的属性map
		for (Map.Entry<String, Object> entry : attributes.entrySet()) {
			String attributeName = entry.getKey();
			Object expectedValue = entry.getValue();
			Object actualValue = null;
			// Check qualifier first
			// 如果qualifier不为null的话，尝试根据属性名获取实际的value
			if (qualifier != null) {
				actualValue = qualifier.getAttribute(attributeName);
			}
			// 如果实际的value为null，尝试从bd中直接根据属性名获取实际value
			if (actualValue == null) {
				// Fall back on bean definition attribute
				actualValue = bd.getAttribute(attributeName);
			}
			// 如果实际的value仍为null 并且 属性名等于value字符串 并且 期待的value是String类型的 并且 期待的value和beanName匹配
			if (actualValue == null && attributeName.equals(AutowireCandidateQualifier.VALUE_KEY) &&
					expectedValue instanceof String && bdHolder.matchesName((String) expectedValue)) {
				// Fall back on bean name (or alias) match
				// 继续遍历循环
				continue;
			}
			// 如果实际的value为null 并且 qualifier不为null的话
			if (actualValue == null && qualifier != null) {
				// Fall back on default, but only if the qualifier is present
				// 获取默认值作为实际的value
				actualValue = AnnotationUtils.getDefaultValue(annotation, attributeName);
			}
			// 如果实际的value不为null，将其进行类型转换
			if (actualValue != null) {
				actualValue = typeConverter.convertIfNecessary(actualValue, expectedValue.getClass());
			}
			// 如果期待的value和实际的value不相等，返回false
			if (!expectedValue.equals(actualValue)) {
				return false;
			}
		}
		// 否则返回true
		return true;
	}

	@Nullable
	protected Annotation getQualifiedElementAnnotation(RootBeanDefinition bd, Class<? extends Annotation> type) {
		AnnotatedElement qualifiedElement = bd.getQualifiedElement();
		return (qualifiedElement != null ? AnnotationUtils.getAnnotation(qualifiedElement, type) : null);
	}

	@Nullable
	protected Annotation getFactoryMethodAnnotation(RootBeanDefinition bd, Class<? extends Annotation> type) {
		Method resolvedFactoryMethod = bd.getResolvedFactoryMethod();
		return (resolvedFactoryMethod != null ? AnnotationUtils.getAnnotation(resolvedFactoryMethod, type) : null);
	}


	/**
	 * Determine whether the given dependency declares an autowired annotation,
	 * checking its required flag.
	 * @see Autowired#required()
	 */
	@Override
	public boolean isRequired(DependencyDescriptor descriptor) {
		// 如果父类方法返回的是false，直接返回false
		if (!super.isRequired(descriptor)) {
			return false;
		}
		// 否则获取其依赖描述上的@Autowired注解
		Autowired autowired = descriptor.getAnnotation(Autowired.class);
		// 如果注解不存在返回true，否则返回注解的required属性
		return (autowired == null || autowired.required());
	}

	/**
	 * Determine whether the given dependency declares a qualifier annotation.
	 * @see #isQualifier(Class)
	 * @see Qualifier
	 */
	@Override
	public boolean hasQualifier(DependencyDescriptor descriptor) {
		// 获取DependencyDescriptor所持有的字段或者方法参数上的注解
		for (Annotation ann : descriptor.getAnnotations()) {
			// 判断这些注解中是否包含有Qualifier(1.Spring声明的@Qualifier 2.javax.inject.Qualifier注解)相关的注解
			if (isQualifier(ann.annotationType())) {
				// 如果存在，返回true
				return true;
			}
		}
		// 否则返回false
		return false;
	}

	/**
	 * Determine whether the given dependency declares a value annotation.
	 * @see Value
	 */
	@Override
	@Nullable
	// 判断给定的DependencyDescriptor上是否声明了一个@Value注解
	public Object getSuggestedValue(DependencyDescriptor descriptor) {
		// 根据descriptor上的标注的注解数组找到@Value注解的value属性值
		Object value = findValue(descriptor.getAnnotations());
		// 如果value属性值为null的话
		if (value == null) {
			// 获取到descriptor持有的methodParameter
			MethodParameter methodParam = descriptor.getMethodParameter();
			// 如果methodParameter不为null，说明是方法参数类型的依赖注入
			if (methodParam != null) {
				// 尝试从标注在方法上的注解中查找@Value注解的value属性值
				value = findValue(methodParam.getMethodAnnotations());
			}
		}
		// 最后返回value的属性值
		return value;
	}

	/**
	 * Determine a suggested value from any of the given candidate annotations.
	 */
	@Nullable
	protected Object findValue(Annotation[] annotationsToSearch) {
		// 如果要搜索的注解数组长度大于0
		if (annotationsToSearch.length > 0) {   // qualifier annotations have to be local
			// 查找标注在这个AnnotatedElement对象上的注解中的@Value注解的属性map。
			// 该方法获取的注解属性，classValuesAsString和nestedAnnotationsAsMap属性都为false，
			AnnotationAttributes attr = AnnotatedElementUtils.getMergedAnnotationAttributes(
					// 根据注解数组创建一个AnnotatedElement的子类
					AnnotatedElementUtils.forAnnotations(annotationsToSearch), this.valueAnnotationType);
			// 如果对应的属性map不为null的话
			if (attr != null) {
				// 调用方法提取value属性值
				return extractValue(attr);
			}
		}
		return null;
	}

	/**
	 * Extract the value attribute from the given annotation.
	 * @since 4.3
	 */
	protected Object extractValue(AnnotationAttributes attr) {
		Object value = attr.get(AnnotationUtils.VALUE);
		if (value == null) {
			throw new IllegalStateException("Value annotation must have a value attribute");
		}
		return value;
	}

}
