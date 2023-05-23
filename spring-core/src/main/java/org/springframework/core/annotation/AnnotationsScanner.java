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

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Scanner to search for relevant annotations in the annotation hierarchy of an
 * {@link AnnotatedElement}.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 5.2
 * @see AnnotationsProcessor
 */
abstract class AnnotationsScanner {

	private static final Annotation[] NO_ANNOTATIONS = {};

	private static final Method[] NO_METHODS = {};


	private static final Map<AnnotatedElement, Annotation[]> declaredAnnotationCache =
			new ConcurrentReferenceHashMap<>(256);

	private static final Map<Class<?>, Method[]> baseTypeMethodsCache =
			new ConcurrentReferenceHashMap<>(256);


	private AnnotationsScanner() {
	}


	/**
	 * Scan the hierarchy of the specified element for relevant annotations and
	 * call the processor as required.
	 * @param context an optional context object that will be passed back to the
	 * processor
	 * @param source the source element to scan
	 * @param searchStrategy the search strategy to use
	 * @param processor the processor that receives the annotations
	 * @return the result of {@link AnnotationsProcessor#finish(Object)}
	 */
	@Nullable
	static <C, R> R scan(C context, AnnotatedElement source, SearchStrategy searchStrategy,
			AnnotationsProcessor<C, R> processor) {

		R result = process(context, source, searchStrategy, processor);
		return processor.finish(result);
	}

	@Nullable
	private static <C, R> R process(C context, AnnotatedElement source,
			SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		// 如果source是Class类型的，调用processClass方法
		if (source instanceof Class) {
			return processClass(context, (Class<?>) source, searchStrategy, processor);
		}
		// 如果source是Method类型的，调用processMethod方法
		if (source instanceof Method) {
			return processMethod(context, (Method) source, searchStrategy, processor);
		}
		// 否则调用processElement方法
		return processElement(context, source, processor);
	}

	@Nullable
	private static <C, R> R processClass(C context, Class<?> source,
			SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		// 根据搜索策略，选择不同方法进行处理
		switch (searchStrategy) {
			case DIRECT:
				return processElement(context, source, processor);
			case INHERITED_ANNOTATIONS:
				return processClassInheritedAnnotations(context, source, searchStrategy, processor);
			case SUPERCLASS:
				return processClassHierarchy(context, source, processor, false, false);
			case TYPE_HIERARCHY:
				return processClassHierarchy(context, source, processor, true, false);
			case TYPE_HIERARCHY_AND_ENCLOSING_CLASSES:
				return processClassHierarchy(context, source, processor, true, true);
		}
		throw new IllegalStateException("Unsupported search strategy " + searchStrategy);
	}

	@Nullable
	private static <C, R> R processClassInheritedAnnotations(C context, Class<?> source,
			SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		try {
			// 判断source是否有层级关系
			if (isWithoutHierarchy(source, searchStrategy)) {
				// 如果没有层级关系的话，直接调用processElement只针对source进行处理
				return processElement(context, source, processor);
			}
			Annotation[] relevant = null;
			int remaining = Integer.MAX_VALUE;
			int aggregateIndex = 0;
			Class<?> root = source;
			// 如果source不为null且不为Object.class且不是Ordered.class且类名不是java.开头的，并且remaining大于0的情况下，进入循环
			while (source != null && source != Object.class && remaining > 0 &&
					!hasPlainJavaAnnotationsOnly(source)) {
				// 调用processor的doWithAggregate方法获取结果
				R result = processor.doWithAggregate(context, aggregateIndex);
				// 如果结果不为null，直接返回
				if (result != null) {
					return result;
				}
				// 否则，调用getDeclaredAnnotations获取source上声明的注解
				Annotation[] declaredAnnotations = getDeclaredAnnotations(source, true);
				// 如果relevant为null且声明的注解数组长度大于0
				if (relevant == null && declaredAnnotations.length > 0) {
					// 调用root的getAnnotations方法将结果赋值给relevant。
					// getAnnotations方法会获取自身声明的注解以及父类的声明的标注了@Inherit的注解
					relevant = root.getAnnotations();
					// 将remaining设置为relevant的长度
					remaining = relevant.length;
				}
				// 遍历声明的注解数组
				for (int i = 0; i < declaredAnnotations.length; i++) {
					// 如果对应的数组元素不为null的话
					if (declaredAnnotations[i] != null) {
						boolean isRelevant = false;
						// 从relevant中查找注解类型和下标i对应的declaredAnnotation的注解类型相同的元素
						for (int relevantIndex = 0; relevantIndex < relevant.length; relevantIndex++) {
							if (relevant[relevantIndex] != null &&
									declaredAnnotations[i].annotationType() == relevant[relevantIndex].annotationType()) {
								// 如果找到了，将isRelevant设为true
								isRelevant = true;
								// 将relevant数组对应的元素置为null
								relevant[relevantIndex] = null;
								// 将remaining-1
								remaining--;
								// 跳出内层循环
								break;
							}
						}
						// 如果isRelevant仍为false，说明没有相关的注解
						if (!isRelevant) {
							// 将declaredAnnotations数组对应下标的元素置为null
							declaredAnnotations[i] = null;
						}
					}
				}
				// 调用processor的doWithAnnotations获取结果，如果result不为null的话，直接返回
				result = processor.doWithAnnotations(context, aggregateIndex, source, declaredAnnotations);
				if (result != null) {
					return result;
				}
				// 获取source的父类型
				source = source.getSuperclass();
				// 将aggregateIndex+1，继续循环
				aggregateIndex++;
			}
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processClassHierarchy(C context, Class<?> source,
			AnnotationsProcessor<C, R> processor, boolean includeInterfaces, boolean includeEnclosing) {

		return processClassHierarchy(context, new int[] {0}, source, processor,
				includeInterfaces, includeEnclosing);
	}

	@Nullable
	private static <C, R> R processClassHierarchy(C context, int[] aggregateIndex, Class<?> source,
			AnnotationsProcessor<C, R> processor, boolean includeInterfaces, boolean includeEnclosing) {

		try {
			R result = processor.doWithAggregate(context, aggregateIndex[0]);
			if (result != null) {
				return result;
			}
			if (hasPlainJavaAnnotationsOnly(source)) {
				return null;
			}
			Annotation[] annotations = getDeclaredAnnotations(source, false);
			result = processor.doWithAnnotations(context, aggregateIndex[0], source, annotations);
			if (result != null) {
				return result;
			}
			aggregateIndex[0]++;
			if (includeInterfaces) {
				for (Class<?> interfaceType : source.getInterfaces()) {
					R interfacesResult = processClassHierarchy(context, aggregateIndex,
						interfaceType, processor, true, includeEnclosing);
					if (interfacesResult != null) {
						return interfacesResult;
					}
				}
			}
			Class<?> superclass = source.getSuperclass();
			if (superclass != Object.class && superclass != null) {
				R superclassResult = processClassHierarchy(context, aggregateIndex,
					superclass, processor, includeInterfaces, includeEnclosing);
				if (superclassResult != null) {
					return superclassResult;
				}
			}
			if (includeEnclosing) {
				// Since merely attempting to load the enclosing class may result in
				// automatic loading of sibling nested classes that in turn results
				// in an exception such as NoClassDefFoundError, we wrap the following
				// in its own dedicated try-catch block in order not to preemptively
				// halt the annotation scanning process.
				try {
					Class<?> enclosingClass = source.getEnclosingClass();
					if (enclosingClass != null) {
						R enclosingResult = processClassHierarchy(context, aggregateIndex,
							enclosingClass, processor, includeInterfaces, true);
						if (enclosingResult != null) {
							return enclosingResult;
						}
					}
				}
				catch (Throwable ex) {
					AnnotationUtils.handleIntrospectionFailure(source, ex);
				}
			}
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processMethod(C context, Method source,
			SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		switch (searchStrategy) {
			case DIRECT:
			case INHERITED_ANNOTATIONS:
				return processMethodInheritedAnnotations(context, source, processor);
			case SUPERCLASS:
				return processMethodHierarchy(context, new int[] {0}, source.getDeclaringClass(),
						processor, source, false);
			case TYPE_HIERARCHY:
			case TYPE_HIERARCHY_AND_ENCLOSING_CLASSES:
				return processMethodHierarchy(context, new int[] {0}, source.getDeclaringClass(),
						processor, source, true);
		}
		throw new IllegalStateException("Unsupported search strategy " + searchStrategy);
	}

	@Nullable
	private static <C, R> R processMethodInheritedAnnotations(C context, Method source,
			AnnotationsProcessor<C, R> processor) {

		try {
			R result = processor.doWithAggregate(context, 0);
			return (result != null ? result :
				processMethodAnnotations(context, 0, source, processor));
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processMethodHierarchy(C context, int[] aggregateIndex,
			Class<?> sourceClass, AnnotationsProcessor<C, R> processor, Method rootMethod,
			boolean includeInterfaces) {

		try {
			R result = processor.doWithAggregate(context, aggregateIndex[0]);
			if (result != null) {
				return result;
			}
			if (hasPlainJavaAnnotationsOnly(sourceClass)) {
				return null;
			}
			boolean calledProcessor = false;
			if (sourceClass == rootMethod.getDeclaringClass()) {
				result = processMethodAnnotations(context, aggregateIndex[0],
					rootMethod, processor);
				calledProcessor = true;
				if (result != null) {
					return result;
				}
			}
			else {
				for (Method candidateMethod : getBaseTypeMethods(context, sourceClass)) {
					if (candidateMethod != null && isOverride(rootMethod, candidateMethod)) {
						result = processMethodAnnotations(context, aggregateIndex[0],
							candidateMethod, processor);
						calledProcessor = true;
						if (result != null) {
							return result;
						}
					}
				}
			}
			if (Modifier.isPrivate(rootMethod.getModifiers())) {
				return null;
			}
			if (calledProcessor) {
				aggregateIndex[0]++;
			}
			if (includeInterfaces) {
				for (Class<?> interfaceType : sourceClass.getInterfaces()) {
					R interfacesResult = processMethodHierarchy(context, aggregateIndex,
						interfaceType, processor, rootMethod, true);
					if (interfacesResult != null) {
						return interfacesResult;
					}
				}
			}
			Class<?> superclass = sourceClass.getSuperclass();
			if (superclass != Object.class && superclass != null) {
				R superclassResult = processMethodHierarchy(context, aggregateIndex,
					superclass, processor, rootMethod, includeInterfaces);
				if (superclassResult != null) {
					return superclassResult;
				}
			}
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(rootMethod, ex);
		}
		return null;
	}

	private static <C> Method[] getBaseTypeMethods(C context, Class<?> baseType) {
		if (baseType == Object.class || hasPlainJavaAnnotationsOnly(baseType)) {
			return NO_METHODS;
		}

		Method[] methods = baseTypeMethodsCache.get(baseType);
		if (methods == null) {
			boolean isInterface = baseType.isInterface();
			methods = isInterface ? baseType.getMethods() : ReflectionUtils.getDeclaredMethods(baseType);
			int cleared = 0;
			for (int i = 0; i < methods.length; i++) {
				if ((!isInterface && Modifier.isPrivate(methods[i].getModifiers())) ||
						hasPlainJavaAnnotationsOnly(methods[i]) ||
						getDeclaredAnnotations(methods[i], false).length == 0) {
					methods[i] = null;
					cleared++;
				}
			}
			if (cleared == methods.length) {
				methods = NO_METHODS;
			}
			baseTypeMethodsCache.put(baseType, methods);
		}
		return methods;
	}

	private static boolean isOverride(Method rootMethod, Method candidateMethod) {
		return (!Modifier.isPrivate(candidateMethod.getModifiers()) &&
				candidateMethod.getName().equals(rootMethod.getName()) &&
				hasSameParameterTypes(rootMethod, candidateMethod));
	}

	private static boolean hasSameParameterTypes(Method rootMethod, Method candidateMethod) {
		if (candidateMethod.getParameterCount() != rootMethod.getParameterCount()) {
			return false;
		}
		Class<?>[] rootParameterTypes = rootMethod.getParameterTypes();
		Class<?>[] candidateParameterTypes = candidateMethod.getParameterTypes();
		if (Arrays.equals(candidateParameterTypes, rootParameterTypes)) {
			return true;
		}
		return hasSameGenericTypeParameters(rootMethod, candidateMethod,
				rootParameterTypes);
	}

	private static boolean hasSameGenericTypeParameters(
			Method rootMethod, Method candidateMethod, Class<?>[] rootParameterTypes) {

		Class<?> sourceDeclaringClass = rootMethod.getDeclaringClass();
		Class<?> candidateDeclaringClass = candidateMethod.getDeclaringClass();
		if (!candidateDeclaringClass.isAssignableFrom(sourceDeclaringClass)) {
			return false;
		}
		for (int i = 0; i < rootParameterTypes.length; i++) {
			Class<?> resolvedParameterType = ResolvableType.forMethodParameter(
					candidateMethod, i, sourceDeclaringClass).resolve();
			if (rootParameterTypes[i] != resolvedParameterType) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	private static <C, R> R processMethodAnnotations(C context, int aggregateIndex, Method source,
			AnnotationsProcessor<C, R> processor) {

		Annotation[] annotations = getDeclaredAnnotations(source, false);
		R result = processor.doWithAnnotations(context, aggregateIndex, source, annotations);
		if (result != null) {
			return result;
		}
		Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(source);
		if (bridgedMethod != source) {
			Annotation[] bridgedAnnotations = getDeclaredAnnotations(bridgedMethod, true);
			for (int i = 0; i < bridgedAnnotations.length; i++) {
				if (ObjectUtils.containsElement(annotations, bridgedAnnotations[i])) {
					bridgedAnnotations[i] = null;
				}
			}
			return processor.doWithAnnotations(context, aggregateIndex, source, bridgedAnnotations);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processElement(C context, AnnotatedElement source,
			AnnotationsProcessor<C, R> processor) {

		try {
			R result = processor.doWithAggregate(context, 0);
			return (result != null ? result : processor.doWithAnnotations(
				context, 0, source, getDeclaredAnnotations(source, false)));
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Nullable
	static <A extends Annotation> A getDeclaredAnnotation(AnnotatedElement source, Class<A> annotationType) {
		// 获取source上声明的所有符合条件的注解组成的数组，
		// 不符合条件的逻辑是：注解类以java.lang org.springframework.lang开头 或者 注解的属性方法调用会抛出TypeNotPresentException
		Annotation[] annotations = getDeclaredAnnotations(source, false);
		// 遍历注解数组
		for (Annotation annotation : annotations) {
			// 如果注解不为null且类型和传入的annotationType相等，直接返回
			if (annotation != null && annotationType == annotation.annotationType()) {
				return (A) annotation;
			}
		}
		// 否则返回null
		return null;
	}

	static Annotation[] getDeclaredAnnotations(AnnotatedElement source, boolean defensive) {
		boolean cached = false;
		// 尝试从缓存中获取
		Annotation[] annotations = declaredAnnotationCache.get(source);
		// 如果命中缓存，将cached置为true
		if (annotations != null) {
			cached = true;
		}
		else {
			// 调用source的getDeclaredAnnotations获取source上声明的注解
			annotations = source.getDeclaredAnnotations();
			// 如果获取到的注解数组长度不为0
			if (annotations.length != 0) {
				// 设置allIgnored标志为true
				boolean allIgnored = true;
				// 遍历注解数组
				for (int i = 0; i < annotations.length; i++) {
					Annotation annotation = annotations[i];
					// 判断该注解是否应该被忽略 或者 该注解是非法的(注解的属性方法会抛出TypeNotPresentException异常)
					if (isIgnorable(annotation.annotationType()) ||
							!AttributeMethods.forAnnotationType(annotation.annotationType()).isValid(annotation)) {
						// 将注解数组对应下标的元素置为null
						annotations[i] = null;
					}
					// 一旦有注解没有被忽略且不是非法的
					else {
						// 将allIgnored标志置为false，表示注解数组没有全部被忽略
						allIgnored = false;
					}
				}
				// 当allIgnored为true时，返回常量NO_ANNOTATIONS，否则返回获取到的注解数组
				annotations = (allIgnored ? NO_ANNOTATIONS : annotations);
				// 当source是Class类型或Member类型的时候，放入缓存，并将cached置为true
				if (source instanceof Class || source instanceof Member) {
					declaredAnnotationCache.put(source, annotations);
					cached = true;
				}
			}
		}
		// 如果defensive为false或者annotations没有内容 或者 cached为false，直接返回对象
		if (!defensive || annotations.length == 0 || !cached) {
			return annotations;
		}
		// 否则返回克隆对象
		return annotations.clone();
	}

	private static boolean isIgnorable(Class<?> annotationType) {
		// 判断annotationType的名称是否是以java.lang或org.springframework.lang开头的，如果是，表示可以被忽略
		return AnnotationFilter.PLAIN.matches(annotationType);
	}

	static boolean isKnownEmpty(AnnotatedElement source, SearchStrategy searchStrategy) {
		// 判断AnnotatedElement上是否只标注了plain类型的java注解
		if (hasPlainJavaAnnotationsOnly(source)) {
			return true;
		}
		// 如果搜索策略是DIRECT 或者 source没有层次结构
		if (searchStrategy == SearchStrategy.DIRECT || isWithoutHierarchy(source, searchStrategy)) {
			// 如果source是属于Method类型的并且是桥接方法的话，直接返回false
			if (source instanceof Method && ((Method) source).isBridge()) {
				return false;
			}
			// 否则调用getDeclareAnnotations方法获取到声明的注解数组，判断其长度是否为0
			return getDeclaredAnnotations(source, false).length == 0;
		}
		// 其他情况返回false
		return false;
	}

	static boolean hasPlainJavaAnnotationsOnly(@Nullable Object annotatedElement) {
		// 如果annotatedElement是class类型的，判断其类名是否是以java.开头 或者 类型是否是Ordered.class
		if (annotatedElement instanceof Class) {
			return hasPlainJavaAnnotationsOnly((Class<?>) annotatedElement);
		}
		// 如果annotatedElement是Member类型的，那么判断声明它的类型是否是以java.开头的 或者 是否是Ordered.class
		else if (annotatedElement instanceof Member) {
			return hasPlainJavaAnnotationsOnly(((Member) annotatedElement).getDeclaringClass());
		}
		// 否则返回false
		else {
			return false;
		}
	}

	static boolean hasPlainJavaAnnotationsOnly(Class<?> type) {
		// 如果类名是以java.开头的 或者 类型是Ordered.class，返回true
		return (type.getName().startsWith("java.") || type == Ordered.class);
	}

	private static boolean isWithoutHierarchy(AnnotatedElement source, SearchStrategy searchStrategy) {
		// 如果source是Object.class，因为Object是所有类的父类，自身没有父类了，因此没有层级关系，返回true
		if (source == Object.class) {
			return true;
		}
		// 如果source是Class类型的
		if (source instanceof Class) {
			Class<?> sourceClass = (Class<?>) source;
			// 如果其父类是Object.class且没有实现接口，那么noSuperTypes置为true
			boolean noSuperTypes = (sourceClass.getSuperclass() == Object.class &&
					sourceClass.getInterfaces().length == 0);
			// 然后根据搜索策略是否包含外部类的搜索来决定返回怎样的结果
			return (searchStrategy == SearchStrategy.TYPE_HIERARCHY_AND_ENCLOSING_CLASSES ? noSuperTypes &&
					sourceClass.getEnclosingClass() == null : noSuperTypes);
		}
		// 如果source是Method类型的
		if (source instanceof Method) {
			Method sourceMethod = (Method) source;
			// 如果方法是私有的，直接返回true。或者根据声明方法的类再次调用isWithoutHierarchy来判断
			return (Modifier.isPrivate(sourceMethod.getModifiers()) ||
					isWithoutHierarchy(sourceMethod.getDeclaringClass(), searchStrategy));
		}
		// 其他情况返回true
		return true;
	}

	static void clearCache() {
		declaredAnnotationCache.clear();
		baseTypeMethodsCache.clear();
	}

}
