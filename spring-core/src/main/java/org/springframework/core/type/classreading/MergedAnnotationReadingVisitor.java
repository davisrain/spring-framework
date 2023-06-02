/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.core.type.classreading;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;
import org.springframework.core.annotation.AnnotationFilter;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * {@link AnnotationVisitor} that can be used to construct a
 * {@link MergedAnnotation}.
 *
 * @author Phillip Webb
 * @since 5.2
 * @param <A> the annotation type
 */
class MergedAnnotationReadingVisitor<A extends Annotation> extends AnnotationVisitor {

	@Nullable
	private final ClassLoader classLoader;

	@Nullable
	private final Object source;

	private final Class<A> annotationType;

	private final Consumer<MergedAnnotation<A>> consumer;

	private final Map<String, Object> attributes = new LinkedHashMap<>(4);


	public MergedAnnotationReadingVisitor(@Nullable ClassLoader classLoader, @Nullable Object source,
			Class<A> annotationType, Consumer<MergedAnnotation<A>> consumer) {

		super(SpringAsmInfo.ASM_VERSION);
		this.classLoader = classLoader;
		this.source = source;
		this.annotationType = annotationType;
		this.consumer = consumer;
	}


	@Override
	public void visit(String name, Object value) {
		// 如果值是Type类型的，获取其类型名称
		if (value instanceof Type) {
			value = ((Type) value).getClassName();
		}
		// 否则将name和value作为键值对存入attributes中
		this.attributes.put(name, value);
	}

	@Override
	public void visitEnum(String name, String descriptor, String value) {
		// 根据描述符加载对应枚举类，再根据value获取对应枚举值，将枚举值作为value，属性名作为name，放入attributes中
		visitEnum(descriptor, value, enumValue -> this.attributes.put(name, enumValue));
	}

	@Override
	@Nullable
	public AnnotationVisitor visitAnnotation(String name, String descriptor) {
		return visitAnnotation(descriptor, annotation -> this.attributes.put(name, annotation));
	}

	@Override
	public AnnotationVisitor visitArray(String name) {
		return new ArrayVisitor(value -> this.attributes.put(name, value));
	}

	@Override
	public void visitEnd() {
		// 根据持有的元素生成一个MergeAnnotation
		MergedAnnotation<A> annotation = MergedAnnotation.of(
				this.classLoader, this.source, this.annotationType, this.attributes);
		// 然后调用consumer的accept方法将生成的MergeAnnotation进行处理，比如加入到SimpleAnnotationMetadataReadingVisitor持有的annotations集合中
		this.consumer.accept(annotation);
	}

	@SuppressWarnings("unchecked")
	public <E extends Enum<E>> void visitEnum(String descriptor, String value, Consumer<E> consumer) {
		// 将注解的字段描述符转换为类名形式
		String className = Type.getType(descriptor).getClassName();
		// 加载出对应的枚举类
		Class<E> type = (Class<E>) ClassUtils.resolveClassName(className, this.classLoader);
		// 获取枚举类中对应的枚举，调用consumer的accept方法，也就是将属性名和枚举值作为键值对放入attributes中
		consumer.accept(Enum.valueOf(type, value));
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private <T extends Annotation> AnnotationVisitor visitAnnotation(
			String descriptor, Consumer<MergedAnnotation<T>> consumer) {

		// 将描述符转换为类型名称
		String className = Type.getType(descriptor).getClassName();
		// 根据注解类型名进行过滤
		if (AnnotationFilter.PLAIN.matches(className)) {
			return null;
		}
		// 加载对应注解类
		Class<T> type = (Class<T>) ClassUtils.resolveClassName(className, this.classLoader);
		// 根据自身的source生成一个新的AnnotationVisitor返回
		return new MergedAnnotationReadingVisitor<>(this.classLoader, this.source, type, consumer);
	}

	@SuppressWarnings("unchecked")
	@Nullable
	static <A extends Annotation> AnnotationVisitor get(@Nullable ClassLoader classLoader,
			@Nullable Supplier<Object> sourceSupplier, String descriptor, boolean visible,
			Consumer<MergedAnnotation<A>> consumer) {

		// 如果是运行时不可见的，直接返回null
		if (!visible) {
			return null;
		}

		// 根据注解的描述符获取注解的类型名称
		String typeName = Type.getType(descriptor).getClassName();
		// 判断注解类名是否是java.lang org.springframework.lang开头的，如果是的话，直接返回null，忽略掉
		if (AnnotationFilter.PLAIN.matches(typeName)) {
			return null;
		}

		// 如果sourceSupplier不为空的话，调用get方法获取source，
		// 这里的source就是持有了标注该注解的类的名称的一个Source类对象，Source是SimpleAnnotationMetadataReadingVisitor的一个内部类
		Object source = (sourceSupplier != null ? sourceSupplier.get() : null);
		try {
			// 根据注解类名称加载对应的注解类
			Class<A> annotationType = (Class<A>) ClassUtils.forName(typeName, classLoader);
			// 根据创建的注解类型初始化一个MergedAnnotationReadingVisitor返回
			return new MergedAnnotationReadingVisitor<>(classLoader, source, annotationType, consumer);
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return null;
		}
	}


	/**
	 * {@link AnnotationVisitor} to deal with array attributes.
	 */
	private class ArrayVisitor extends AnnotationVisitor {

		private final List<Object> elements = new ArrayList<>();

		private final Consumer<Object[]> consumer;

		ArrayVisitor(Consumer<Object[]> consumer) {
			super(SpringAsmInfo.ASM_VERSION);
			this.consumer = consumer;
		}

		@Override
		public void visit(String name, Object value) {
			if (value instanceof Type) {
				value = ((Type) value).getClassName();
			}
			this.elements.add(value);
		}

		@Override
		public void visitEnum(String name, String descriptor, String value) {
			MergedAnnotationReadingVisitor.this.visitEnum(descriptor, value, this.elements::add);
		}

		@Override
		@Nullable
		public AnnotationVisitor visitAnnotation(String name, String descriptor) {
			return MergedAnnotationReadingVisitor.this.visitAnnotation(descriptor, this.elements::add);
		}

		@Override
		public void visitEnd() {
			// 获取集合的元素类型
			Class<?> componentType = getComponentType();
			// 根据元素类型创建一个相同长度的数组
			Object[] array = (Object[]) Array.newInstance(componentType, this.elements.size());
			// 调用consumer的accept方法，将数组元素添加到attributes中与对应name形成键值对
			this.consumer.accept(this.elements.toArray(array));
		}

		private Class<?> getComponentType() {
			// 如果数组为空，直接返回Object.class
			if (this.elements.isEmpty()) {
				return Object.class;
			}
			Object firstElement = this.elements.get(0);
			if (firstElement instanceof Enum) {
				return ((Enum<?>) firstElement).getDeclaringClass();
			}
			return firstElement.getClass();
		}
	}

}
