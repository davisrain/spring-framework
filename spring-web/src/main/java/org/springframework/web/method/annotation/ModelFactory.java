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

package org.springframework.web.method.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.core.Conventions;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Assist with initialization of the {@link Model} before controller method
 * invocation and with updates to it after the invocation.
 *
 * <p>On initialization the model is populated with attributes temporarily stored
 * in the session and through the invocation of {@code @ModelAttribute} methods.
 *
 * <p>On update model attributes are synchronized with the session and also
 * {@link BindingResult} attributes are added if missing.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public final class ModelFactory {

	private final List<ModelMethod> modelMethods = new ArrayList<>();

	private final WebDataBinderFactory dataBinderFactory;

	private final SessionAttributesHandler sessionAttributesHandler;


	/**
	 * Create a new instance with the given {@code @ModelAttribute} methods.
	 * @param handlerMethods the {@code @ModelAttribute} methods to invoke
	 * @param binderFactory for preparation of {@link BindingResult} attributes
	 * @param attributeHandler for access to session attributes
	 */
	public ModelFactory(@Nullable List<InvocableHandlerMethod> handlerMethods,
			WebDataBinderFactory binderFactory, SessionAttributesHandler attributeHandler) {

		// 如果传入的handlerMethods不为null
		if (handlerMethods != null) {
			// 遍历这些handlerMethods
			for (InvocableHandlerMethod handlerMethod : handlerMethods) {
				// 将其包装成ModelMethod存入modelMethods这个list类型的属性中
				this.modelMethods.add(new ModelMethod(handlerMethod));
			}
		}
		// 设置dataBinderFactory
		this.dataBinderFactory = binderFactory;
		// 设置sessionAttributeHandler
		this.sessionAttributesHandler = attributeHandler;
	}


	/**
	 * Populate the model in the following order:
	 * <ol>
	 * <li>Retrieve "known" session attributes listed as {@code @SessionAttributes}.
	 * <li>Invoke {@code @ModelAttribute} methods
	 * <li>Find {@code @ModelAttribute} method arguments also listed as
	 * {@code @SessionAttributes} and ensure they're present in the model raising
	 * an exception if necessary.
	 * </ol>
	 * @param request the current request
	 * @param container a container with the model to be initialized
	 * @param handlerMethod the method for which the model is initialized
	 * @throws Exception may arise from {@code @ModelAttribute} methods
	 */
	public void initModel(NativeWebRequest request, ModelAndViewContainer container, HandlerMethod handlerMethod)
			throws Exception {

		// 使用sessionAttributeHandler遍历request中session的attributes
		Map<String, ?> sessionAttributes = this.sessionAttributesHandler.retrieveAttributes(request);
		// 将获取到的sessionAttributes合并到container的modelMap中
		container.mergeAttributes(sessionAttributes);
		// 调用标注了@ModelAttribute注解但没有标注@RequestMapping注解的那些方法来对container进行配置
		invokeModelAttributeMethods(request, container);

		// 查找出handlerMethod的参数中标注了@ModelAttribute注解的 并且参数在@SessionAttributes注解中也存在的 参数的name集合
		for (String name : findSessionAttributeArguments(handlerMethod)) {
			// 如果container中的modelMap中不包含该name的话
			if (!container.containsAttribute(name)) {
				// 从session中根据name取获取
				Object value = this.sessionAttributesHandler.retrieveAttribute(request, name);
				if (value == null) {
					throw new HttpSessionRequiredException("Expected session attribute '" + name + "'", name);
				}
				// 获取到了之后存入container的modelMap中
				container.addAttribute(name, value);
			}
		}
	}

	/**
	 * Invoke model attribute methods to populate the model.
	 * Attributes are added only if not already present in the model.
	 */
	private void invokeModelAttributeMethods(NativeWebRequest request, ModelAndViewContainer container)
			throws Exception {

		// 当modelMethods不为空时
		while (!this.modelMethods.isEmpty()) {
			// 通过container获取到对饮国得handlerMethod
			InvocableHandlerMethod modelMethod = getNextModelMethod(container).getHandlerMethod();
			// 查找到标注在方法上的@ModelAttribute注解
			ModelAttribute ann = modelMethod.getMethodAnnotation(ModelAttribute.class);
			Assert.state(ann != null, "No ModelAttribute annotation");
			// 判断container中是否存在注解中的name
			if (container.containsAttribute(ann.name())) {
				// 如果存在，但是注解的binding属性是false
				if (!ann.binding()) {
					// 将name添加进container中的bindingDisabled列表中
					container.setBindingDisabled(ann.name());
				}
				continue;
			}

			// 如果container中不存在注解的name的话，调用handlerMethod的invokeForRequest方法
			Object returnValue = modelMethod.invokeForRequest(request, container);
			// 如果方法的返回类型不是void的话
			if (!modelMethod.isVoid()){
				// 根据返回值获取返回值的name
				String returnValueName = getNameForReturnValue(returnValue, modelMethod.getReturnType());
				// 如果注解的binding属性是false的话
				if (!ann.binding()) {
					// 将returnValueName添加进bindingDisabled列表中
					container.setBindingDisabled(returnValueName);
				}
				// 如果container不包含returnValueName的话，将returnValueName和returnValue添加进去
				if (!container.containsAttribute(returnValueName)) {
					container.addAttribute(returnValueName, returnValue);
				}
			}
		}
	}

	private ModelMethod getNextModelMethod(ModelAndViewContainer container) {
		// 遍历modelMethods
		for (ModelMethod modelMethod : this.modelMethods) {
			// 检查modelMethod的dependencies，看container中是否包含modelMethod所有的dependencies
			if (modelMethod.checkDependencies(container)) {
				// 如果检查通过，将modelMethod从modelMethods中删除并返回
				this.modelMethods.remove(modelMethod);
				return modelMethod;
			}
		}
		// 如果检查不通过，获取modelMethods中第一个元素，删除并返回
		ModelMethod modelMethod = this.modelMethods.get(0);
		this.modelMethods.remove(modelMethod);
		return modelMethod;
	}

	/**
	 * Find {@code @ModelAttribute} arguments also listed as {@code @SessionAttributes}.
	 */
	private List<String> findSessionAttributeArguments(HandlerMethod handlerMethod) {
		List<String> result = new ArrayList<>();
		// 循环handlerMethod的方法参数
		for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
			// 如果参数上标注了@ModelAttribute注解
			if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
				// 根据参数获取attribute的name
				String name = getNameForParameter(parameter);
				// 获取参数的类型
				Class<?> paramType = parameter.getParameterType();
				// 调用sessionAttributesHandler的isHandlerSessionAttribute方法进行判断
				if (this.sessionAttributesHandler.isHandlerSessionAttribute(name, paramType)) {
					// 如果返回true，将name加入结果
					result.add(name);
				}
			}
		}
		return result;
	}

	/**
	 * Promote model attributes listed as {@code @SessionAttributes} to the session.
	 * Add {@link BindingResult} attributes where necessary.
	 * @param request the current request
	 * @param container contains the model to update
	 * @throws Exception if creating BindingResult attributes fails
	 */
	public void updateModel(NativeWebRequest request, ModelAndViewContainer container) throws Exception {
		ModelMap defaultModel = container.getDefaultModel();
		if (container.getSessionStatus().isComplete()){
			this.sessionAttributesHandler.cleanupAttributes(request);
		}
		else {
			this.sessionAttributesHandler.storeAttributes(request, defaultModel);
		}
		if (!container.isRequestHandled() && container.getModel() == defaultModel) {
			updateBindingResult(request, defaultModel);
		}
	}

	/**
	 * Add {@link BindingResult} attributes to the model for attributes that require it.
	 */
	private void updateBindingResult(NativeWebRequest request, ModelMap model) throws Exception {
		List<String> keyNames = new ArrayList<>(model.keySet());
		for (String name : keyNames) {
			Object value = model.get(name);
			if (value != null && isBindingCandidate(name, value)) {
				String bindingResultKey = BindingResult.MODEL_KEY_PREFIX + name;
				if (!model.containsAttribute(bindingResultKey)) {
					WebDataBinder dataBinder = this.dataBinderFactory.createBinder(request, value, name);
					model.put(bindingResultKey, dataBinder.getBindingResult());
				}
			}
		}
	}

	/**
	 * Whether the given attribute requires a {@link BindingResult} in the model.
	 */
	private boolean isBindingCandidate(String attributeName, Object value) {
		if (attributeName.startsWith(BindingResult.MODEL_KEY_PREFIX)) {
			return false;
		}

		if (this.sessionAttributesHandler.isHandlerSessionAttribute(attributeName, value.getClass())) {
			return true;
		}

		return (!value.getClass().isArray() && !(value instanceof Collection) &&
				!(value instanceof Map) && !BeanUtils.isSimpleValueType(value.getClass()));
	}


	/**
	 * Derive the model attribute name for the given method parameter based on
	 * a {@code @ModelAttribute} parameter annotation (if present) or falling
	 * back on parameter type based conventions.
	 * @param parameter a descriptor for the method parameter
	 * @return the derived name
	 * @see Conventions#getVariableNameForParameter(MethodParameter)
	 */
	public static String getNameForParameter(MethodParameter parameter) {
		// 获取方法参数上的@ModelAttribute注解
		ModelAttribute ann = parameter.getParameterAnnotation(ModelAttribute.class);
		// 如果注解不为null的话，取注解的value属性
		String name = (ann != null ? ann.value() : null);
		// 如果name不为空，直接返回name，否则根据参数类型获取name
		return (StringUtils.hasText(name) ? name : Conventions.getVariableNameForParameter(parameter));
	}

	/**
	 * Derive the model attribute name for the given return value. Results will be
	 * based on:
	 * <ol>
	 * <li>the method {@code ModelAttribute} annotation value
	 * <li>the declared return type if it is more specific than {@code Object}
	 * <li>the actual return value type
	 * </ol>
	 * @param returnValue the value returned from a method invocation
	 * @param returnType a descriptor for the return type of the method
	 * @return the derived name (never {@code null} or empty String)
	 */
	public static String getNameForReturnValue(@Nullable Object returnValue, MethodParameter returnType) {
		// 根据returnType获取方法上的@ModelAttribute注解
		ModelAttribute ann = returnType.getMethodAnnotation(ModelAttribute.class);
		// 如果注解不为null且注解的value属性不为空，返回注解的value值
		if (ann != null && StringUtils.hasText(ann.value())) {
			return ann.value();
		}
		// 否则
		else {
			// 获取到returnType对应的方法
			Method method = returnType.getMethod();
			Assert.state(method != null, "No handler method");
			// 获取持有方法的类对象
			Class<?> containingClass = returnType.getContainingClass();
			// 获取到方法的返回值的类对象
			Class<?> resolvedType = GenericTypeResolver.resolveReturnType(method, containingClass);
			// 根据方法返回值的声明类型（如果返回值的声明类型不是Object.class的话）或者 方法返回值的实际类型 获取name
			return Conventions.getVariableNameForReturnType(method, resolvedType, returnValue);
		}
	}


	private static class ModelMethod {

		private final InvocableHandlerMethod handlerMethod;

		private final Set<String> dependencies = new HashSet<>();

		public ModelMethod(InvocableHandlerMethod handlerMethod) {
			// 将handlerMethod赋值给自身属性
			this.handlerMethod = handlerMethod;
			// 解析handlerMethod中的methodParameter
			for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
				// 判断方法参数上有没有标注@ModelAttribute注解
				if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
					// 根据MethodParameter获取到name添加进dependencies的list中
					this.dependencies.add(getNameForParameter(parameter));
				}
			}
		}

		public InvocableHandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public boolean checkDependencies(ModelAndViewContainer mavContainer) {
			// 遍历dependencies
			for (String name : this.dependencies) {
				// 一旦发现mavContainer中不包含name，返回false
				if (!mavContainer.containsAttribute(name)) {
					return false;
				}
			}
			// 否则 返回true
			return true;
		}

		@Override
		public String toString() {
			return this.handlerMethod.getMethod().toGenericString();
		}
	}

}
