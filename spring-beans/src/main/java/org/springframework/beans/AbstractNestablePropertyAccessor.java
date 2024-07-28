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

package org.springframework.beans;

import java.beans.PropertyChangeEvent;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedActionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.CollectionFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * A basic {@link ConfigurablePropertyAccessor} that provides the necessary
 * infrastructure for all typical use cases.
 *
 * <p>This accessor will convert collection and array values to the corresponding
 * target collections or arrays, if necessary. Custom property editors that deal
 * with collections or arrays can either be written via PropertyEditor's
 * {@code setValue}, or against a comma-delimited String via {@code setAsText},
 * as String arrays are converted in such a format if the array itself is not
 * assignable.
 *
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @author Rod Johnson
 * @author Rob Harrop
 * @since 4.2
 * @see #registerCustomEditor
 * @see #setPropertyValues
 * @see #setPropertyValue
 * @see #getPropertyValue
 * @see #getPropertyType
 * @see BeanWrapper
 * @see PropertyEditorRegistrySupport
 */
public abstract class AbstractNestablePropertyAccessor extends AbstractPropertyAccessor {

	/**
	 * We'll create a lot of these objects, so we don't want a new logger every time.
	 */
	private static final Log logger = LogFactory.getLog(AbstractNestablePropertyAccessor.class);

	private int autoGrowCollectionLimit = Integer.MAX_VALUE;

	@Nullable
	Object wrappedObject;

	private String nestedPath = "";

	@Nullable
	Object rootObject;

	/** Map with cached nested Accessors: nested path -> Accessor instance. */
	@Nullable
	private Map<String, AbstractNestablePropertyAccessor> nestedPropertyAccessors;


	/**
	 * Create a new empty accessor. Wrapped instance needs to be set afterwards.
	 * Registers default editors.
	 * @see #setWrappedInstance
	 */
	protected AbstractNestablePropertyAccessor() {
		this(true);
	}

	/**
	 * Create a new empty accessor. Wrapped instance needs to be set afterwards.
	 * @param registerDefaultEditors whether to register default editors
	 * (can be suppressed if the accessor won't need any type conversion)
	 * @see #setWrappedInstance
	 */
	protected AbstractNestablePropertyAccessor(boolean registerDefaultEditors) {
		if (registerDefaultEditors) {
			registerDefaultEditors();
		}
		this.typeConverterDelegate = new TypeConverterDelegate(this);
	}

	/**
	 * Create a new accessor for the given object.
	 * @param object the object wrapped by this accessor
	 */
	protected AbstractNestablePropertyAccessor(Object object) {
		registerDefaultEditors();
		setWrappedInstance(object);
	}

	/**
	 * Create a new accessor, wrapping a new instance of the specified class.
	 * @param clazz class to instantiate and wrap
	 */
	protected AbstractNestablePropertyAccessor(Class<?> clazz) {
		registerDefaultEditors();
		setWrappedInstance(BeanUtils.instantiateClass(clazz));
	}

	/**
	 * Create a new accessor for the given object,
	 * registering a nested path that the object is in.
	 * @param object the object wrapped by this accessor
	 * @param nestedPath the nested path of the object
	 * @param rootObject the root object at the top of the path
	 */
	protected AbstractNestablePropertyAccessor(Object object, String nestedPath, Object rootObject) {
		registerDefaultEditors();
		setWrappedInstance(object, nestedPath, rootObject);
	}

	/**
	 * Create a new accessor for the given object,
	 * registering a nested path that the object is in.
	 * @param object the object wrapped by this accessor
	 * @param nestedPath the nested path of the object
	 * @param parent the containing accessor (must not be {@code null})
	 */
	protected AbstractNestablePropertyAccessor(Object object, String nestedPath, AbstractNestablePropertyAccessor parent) {
		// 设置自身的wrappedObject nestedPath rootObject TypeConverterDelegate等属性
		setWrappedInstance(object, nestedPath, parent.getWrappedInstance());
		// 继承parent的各种属性
		setExtractOldValueForEditor(parent.isExtractOldValueForEditor());
		setAutoGrowNestedPaths(parent.isAutoGrowNestedPaths());
		setAutoGrowCollectionLimit(parent.getAutoGrowCollectionLimit());
		setConversionService(parent.getConversionService());
	}


	/**
	 * Specify a limit for array and collection auto-growing.
	 * <p>Default is unlimited on a plain accessor.
	 */
	public void setAutoGrowCollectionLimit(int autoGrowCollectionLimit) {
		this.autoGrowCollectionLimit = autoGrowCollectionLimit;
	}

	/**
	 * Return the limit for array and collection auto-growing.
	 */
	public int getAutoGrowCollectionLimit() {
		return this.autoGrowCollectionLimit;
	}

	/**
	 * Switch the target object, replacing the cached introspection results only
	 * if the class of the new object is different to that of the replaced object.
	 * @param object the new target object
	 */
	public void setWrappedInstance(Object object) {
		setWrappedInstance(object, "", null);
	}

	/**
	 * Switch the target object, replacing the cached introspection results only
	 * if the class of the new object is different to that of the replaced object.
	 *
	 * 切换目标对象，当这个新object的类型和被替换的object的类型不一致时，替换缓存的内省结果
	 *
	 * @param object the new target object
	 * @param nestedPath the nested path of the object
	 * @param rootObject the root object at the top of the path
	 */
	public void setWrappedInstance(Object object, @Nullable String nestedPath, @Nullable Object rootObject) {
		// 将wrappedObject设置为object，如果object是optional类型的，调用其get方法获取被包装的对象
		this.wrappedObject = ObjectUtils.unwrapOptional(object);
		Assert.notNull(this.wrappedObject, "Target object must not be null");
		// 如果nestedPath存在的话，设置给自身属性
		this.nestedPath = (nestedPath != null ? nestedPath : "");
		// 如果自身的nestedPath存在，说明是某个pa的嵌套属性，因此使用rootObject赋值，否则用自身的wrappedObject赋值
		this.rootObject = (!this.nestedPath.isEmpty() ? rootObject : this.wrappedObject);
		this.nestedPropertyAccessors = null;
		// 根据wrappedObject创建一个类型转换的委托对象
		this.typeConverterDelegate = new TypeConverterDelegate(this, this.wrappedObject);
	}

	public final Object getWrappedInstance() {
		Assert.state(this.wrappedObject != null, "No wrapped object");
		return this.wrappedObject;
	}

	public final Class<?> getWrappedClass() {
		return getWrappedInstance().getClass();
	}

	/**
	 * Return the nested path of the object wrapped by this accessor.
	 */
	public final String getNestedPath() {
		return this.nestedPath;
	}

	/**
	 * Return the root object at the top of the path of this accessor.
	 * @see #getNestedPath
	 */
	public final Object getRootInstance() {
		Assert.state(this.rootObject != null, "No root object");
		return this.rootObject;
	}

	/**
	 * Return the class of the root object at the top of the path of this accessor.
	 * @see #getNestedPath
	 */
	public final Class<?> getRootClass() {
		return getRootInstance().getClass();
	}

	@Override
	public void setPropertyValue(String propertyName, @Nullable Object value) throws BeansException {
		AbstractNestablePropertyAccessor nestedPa;
		try {
			// 根据属性名获取到对应的嵌套的属性访问器(nestedPa)
			nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		}
		catch (NotReadablePropertyException ex) {
			throw new NotWritablePropertyException(getRootClass(), this.nestedPath + propertyName,
					"Nested property in path '" + propertyName + "' does not exist", ex);
		}
		// 获取到propertyName最后一个分隔符后面的内容，并将其转换为PropertyTokenHolder
		PropertyTokenHolder tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
		nestedPa.setPropertyValue(tokens, new PropertyValue(propertyName, value));
	}

	@Override
	public void setPropertyValue(PropertyValue pv) throws BeansException {
		// 获取pv的resolvedTokens
		PropertyTokenHolder tokens = (PropertyTokenHolder) pv.resolvedTokens;
		// 如果tokens不存在，进行解析，然后调用
		if (tokens == null) {
			// 获取propertyName
			String propertyName = pv.getName();
			AbstractNestablePropertyAccessor nestedPa;
			try {
				// 获取到对应的嵌套propertyAccessor，如果propertyName中不含分隔符，那么获取到的就是自身
				nestedPa = getPropertyAccessorForPropertyPath(propertyName);
			}
			catch (NotReadablePropertyException ex) {
				throw new NotWritablePropertyException(getRootClass(), this.nestedPath + propertyName,
						"Nested property in path '" + propertyName + "' does not exist", ex);
			}
			// 获取到propertyName最后一个分隔符之后的内容，转换为tokens
			tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
			// 如果嵌套的pa等于自身，即propertyName中不含分隔符
			if (nestedPa == this) {
				// 那么将tokens缓存到pv对应的原始pv的resolvedTokens字段中
				pv.getOriginalPropertyValue().resolvedTokens = tokens;
			}
			// 调用嵌套pa的setPropertyValue方法
			nestedPa.setPropertyValue(tokens, pv);
		}
		// 如果存在，直接调用重载方法
		else {
			setPropertyValue(tokens, pv);
		}
	}

	protected void setPropertyValue(PropertyTokenHolder tokens, PropertyValue pv) throws BeansException {
		// 如果tokens的keys不为null的话
		if (tokens.keys != null) {
			processKeyedProperty(tokens, pv);
		}
		// 如果tokens不存在keys的话
		else {
			processLocalProperty(tokens, pv);
		}
	}

	@SuppressWarnings("unchecked")
	private void processKeyedProperty(PropertyTokenHolder tokens, PropertyValue pv) {
		// 获取持有这个属性的对象的值，比如propertyName为props[1][2]，获取的就是props[1]所指向的对象
		Object propValue = getPropertyHoldingValue(tokens);
		// 根据actualName获取对应的BeanPropertyHandler
		PropertyHandler ph = getLocalPropertyHandler(tokens.actualName);
		// 如果ph不存在，报错
		if (ph == null) {
			throw new InvalidPropertyException(
					getRootClass(), this.nestedPath + tokens.actualName, "No property handler found");
		}
		Assert.state(tokens.keys != null, "No token keys");
		// 获取到最后一个key
		String lastKey = tokens.keys[tokens.keys.length - 1];

		// 如果propValue是数组
		if (propValue.getClass().isArray()) {
			// 获取到数组的元素类型
			Class<?> requiredType = propValue.getClass().getComponentType();
			// 将lastKey解析为int类型，表示该属性对应在propValue这个数组中的下标
			int arrayIndex = Integer.parseInt(lastKey);
			Object oldValue = null;
			try {
				// 如果isExtractOldValueForEditor为true 并且 元素的下标小于数组的长度
				if (isExtractOldValueForEditor() && arrayIndex < Array.getLength(propValue)) {
					// 获取数组对应位置的元素作为oldValue
					oldValue = Array.get(propValue, arrayIndex);
				}
				// 将pv中持有的value类型转换为要求的类型
				Object convertedValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
						requiredType, ph.nested(tokens.keys.length));
				// 获取数组的长度
				int length = Array.getLength(propValue);
				// 如果元素的下标大于等于数组长度，并且元素的下标小于自动增长的长度限制
				if (arrayIndex >= length && arrayIndex < this.autoGrowCollectionLimit) {
					// 获取数组的元素类型
					Class<?> componentType = propValue.getClass().getComponentType();
					// 根据元素类型创建一个长度为arrayIndex + 1的数组
					Object newArray = Array.newInstance(componentType, arrayIndex + 1);
					// 并且将原数组的内容复制过去
					System.arraycopy(propValue, 0, newArray, 0, length);
					// 获取最后一个[字符的位置
					int lastKeyIndex = tokens.canonicalName.lastIndexOf('[');
					// 截取最后一个[字符前面的内容作为属性名
					String propName = tokens.canonicalName.substring(0, lastKeyIndex);
					// 将新数组设置进propName属性名指向的属性中
					setPropertyValue(propName, newArray);
					// 根据属性名获取到新的propValue
					propValue = getPropertyValue(propName);
				}
				// 然后向指定下标设置转换过的pv中的value
				Array.set(propValue, arrayIndex, convertedValue);
			}
			catch (IndexOutOfBoundsException ex) {
				throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
						"Invalid array index in property path '" + tokens.canonicalName + "'", ex);
			}
		}

		// 如果propValue是List类型的
		else if (propValue instanceof List) {
			// 获取最后一个泛型所表示的类型，比如List<List<String>> 那么获取到的是String.class
			Class<?> requiredType = ph.getCollectionType(tokens.keys.length);
			List<Object> list = (List<Object>) propValue;
			// 将key转换为集合的下标
			int index = Integer.parseInt(lastKey);
			Object oldValue = null;
			// 如果isExtractOldValueForEditor为true 并且 下标小于集合的长度
			if (isExtractOldValueForEditor() && index < list.size()) {
				// 获取oldValue
				oldValue = list.get(index);
			}
			// 将pv中的value转换为要求的类型
			Object convertedValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
					requiredType, ph.nested(tokens.keys.length));
			int size = list.size();
			// 如果下标大于等于集合的大小 并且 下标小于允许自动增长的限制
			if (index >= size && index < this.autoGrowCollectionLimit) {
				// 将size到index之间的元素都设置为null
				for (int i = size; i < index; i++) {
					try {
						list.add(null);
					}
					catch (NullPointerException ex) {
						throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
								"Cannot set element with index " + index + " in List of size " +
								size + ", accessed using property path '" + tokens.canonicalName +
								"': List does not support filling up gaps with null elements");
					}
				}
				// 然后将转换后的值添加到list中
				list.add(convertedValue);
			}
			// 否则，调用list的set方法向指定下标设置值
			else {
				try {
					list.set(index, convertedValue);
				}
				catch (IndexOutOfBoundsException ex) {
					throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
							"Invalid list index in property path '" + tokens.canonicalName + "'", ex);
				}
			}
		}

		// 如果propValue是Map类型的
		else if (propValue instanceof Map) {
			// 获取map的key的类型
			Class<?> mapKeyType = ph.getMapKeyType(tokens.keys.length);
			// 获取map的value的类型
			Class<?> mapValueType = ph.getMapValueType(tokens.keys.length);
			Map<Object, Object> map = (Map<Object, Object>) propValue;
			// IMPORTANT: Do not pass full property name in here - property editors
			// must not kick in for map keys but rather only for map values.
			TypeDescriptor typeDescriptor = TypeDescriptor.valueOf(mapKeyType);
			// 将lastKey转换为map所需的key的类型，这里的propertyName传null是为了不使用propertyEditor去转换map的key
			Object convertedMapKey = convertIfNecessary(null, null, lastKey, mapKeyType, typeDescriptor);
			Object oldValue = null;
			// isExtractOldValueForEditor为true的话，获取oldValue
			if (isExtractOldValueForEditor()) {
				oldValue = map.get(convertedMapKey);
			}
			// Pass full property name and old value in here, since we want full
			// conversion ability for map values.
			// 将pv的value转换为map所需的value的类型
			Object convertedMapValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
					mapValueType, ph.nested(tokens.keys.length));
			// 然后将转换后的key和value放入map
			map.put(convertedMapKey, convertedMapValue);
		}

		// 其他情况报错
		else {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Property referenced in indexed property path '" + tokens.canonicalName +
					"' is neither an array nor a List nor a Map; returned value was [" + propValue + "]");
		}
	}

	private Object getPropertyHoldingValue(PropertyTokenHolder tokens) {
		// Apply indexes and map keys: fetch value for all keys but the last one.
		// 获取tokens中除了最后一个key的值
		Assert.state(tokens.keys != null, "No token keys");
		// 根据tokens生成一个getterTokens，其中keys不包含tokens最后的key
		PropertyTokenHolder getterTokens = new PropertyTokenHolder(tokens.actualName);
		getterTokens.canonicalName = tokens.canonicalName;
		getterTokens.keys = new String[tokens.keys.length - 1];
		System.arraycopy(tokens.keys, 0, getterTokens.keys, 0, tokens.keys.length - 1);

		Object propValue;
		try {
			// 根据getterTokens获取到了对应的value对象
			propValue = getPropertyValue(getterTokens);
		}
		catch (NotReadablePropertyException ex) {
			throw new NotWritablePropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Cannot access indexed value in property referenced " +
					"in indexed property path '" + tokens.canonicalName + "'", ex);
		}

		// 如果propValue为null
		if (propValue == null) {
			// null map value case
			// 当前pa的autoGrowNestedPaths标志为true
			if (isAutoGrowNestedPaths()) {
				// 获取到最后一个[字符的位置
				int lastKeyIndex = tokens.canonicalName.lastIndexOf('[');
				// 截取最后一个[前面的内容赋值给getterTokens的canonicalName
				getterTokens.canonicalName = tokens.canonicalName.substring(0, lastKeyIndex);
				// 然后调用setDefaultValue向对应属性设置默认值
				propValue = setDefaultValue(getterTokens);
			}
			// 否则，报错
			else {
				throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + tokens.canonicalName,
						"Cannot access indexed value in property referenced " +
						"in indexed property path '" + tokens.canonicalName + "': returned null");
			}
		}
		// 返回propValue
		return propValue;
	}

	private void processLocalProperty(PropertyTokenHolder tokens, PropertyValue pv) {
		// 根据tokens中的actualName获取对应的BeanPropertyHandler
		PropertyHandler ph = getLocalPropertyHandler(tokens.actualName);
		// 如果ph为null 或者 ph不是可读的
		if (ph == null || !ph.isWritable()) {
			// 如果pv是optional的，直接返回
			if (pv.isOptional()) {
				if (logger.isDebugEnabled()) {
					logger.debug("Ignoring optional value for property '" + tokens.actualName +
							"' - property not found on bean class [" + getRootClass().getName() + "]");
				}
				return;
			}
			// 如果propertyAccessor的suppressNotWritablePropertyException标志为true，直接返回。
			// 当setPropertyValues方法的ignoreUnknown参数为true的时候，自身pa的该参数会被置为true。但是嵌套的pa不会置为true
			if (this.suppressNotWritablePropertyException) {
				// Optimization for common ignoreUnknown=true scenario since the
				// exception would be caught and swallowed higher up anyway...
				return;
			}
			// 否则抛出异常
			throw createNotWritablePropertyException(tokens.canonicalName);
		}

		Object oldValue = null;
		try {
			// 获取pa的value值
			Object originalValue = pv.getValue();
			// 将原始值赋值给要应用的值
			Object valueToApply = originalValue;
			// 如果pv的conversionNecessary不是false的话
			if (!Boolean.FALSE.equals(pv.conversionNecessary)) {
				// 判断pv是否已经转换过了
				if (pv.isConverted()) {
					// 如果是，使用转换过的值作为要应用的值
					valueToApply = pv.getConvertedValue();
				}
				// 如果没有转换过
				else {
					// 如果isExtractOldValueForEditor标志为true 并且 ph是可读的
					if (isExtractOldValueForEditor() && ph.isReadable()) {
						try {
							// 调用ph的getValue获取oldValue
							oldValue = ph.getValue();
						}
						catch (Exception ex) {
							if (ex instanceof PrivilegedActionException) {
								ex = ((PrivilegedActionException) ex).getException();
							}
							if (logger.isDebugEnabled()) {
								logger.debug("Could not read previous value of property '" +
										this.nestedPath + tokens.canonicalName + "'", ex);
							}
						}
					}
					// 将originalValue转换为对应的类型，赋值给要使用的值
					valueToApply = convertForProperty(
							tokens.canonicalName, oldValue, originalValue, ph.toTypeDescriptor());
				}
				// 将pv的原始pv的conversionNecessary根据 原始值和要使用的值的相同与否 来赋值
				pv.getOriginalPropertyValue().conversionNecessary = (valueToApply != originalValue);
			}
			// 调用BeanPropertyHandler的setValue方法，具体逻辑就是调用属性的写方法，将值设置进去
			ph.setValue(valueToApply);
		}
		catch (TypeMismatchException ex) {
			throw ex;
		}
		catch (InvocationTargetException ex) {
			PropertyChangeEvent propertyChangeEvent = new PropertyChangeEvent(
					getRootInstance(), this.nestedPath + tokens.canonicalName, oldValue, pv.getValue());
			if (ex.getTargetException() instanceof ClassCastException) {
				throw new TypeMismatchException(propertyChangeEvent, ph.getPropertyType(), ex.getTargetException());
			}
			else {
				Throwable cause = ex.getTargetException();
				if (cause instanceof UndeclaredThrowableException) {
					// May happen e.g. with Groovy-generated methods
					cause = cause.getCause();
				}
				throw new MethodInvocationException(propertyChangeEvent, cause);
			}
		}
		catch (Exception ex) {
			PropertyChangeEvent pce = new PropertyChangeEvent(
					getRootInstance(), this.nestedPath + tokens.canonicalName, oldValue, pv.getValue());
			throw new MethodInvocationException(pce, ex);
		}
	}

	@Override
	@Nullable
	public Class<?> getPropertyType(String propertyName) throws BeansException {
		try {
			PropertyHandler ph = getPropertyHandler(propertyName);
			if (ph != null) {
				return ph.getPropertyType();
			}
			else {
				// Maybe an indexed/mapped property...
				Object value = getPropertyValue(propertyName);
				if (value != null) {
					return value.getClass();
				}
				// Check to see if there is a custom editor,
				// which might give an indication on the desired target type.
				Class<?> editorType = guessPropertyTypeFromEditors(propertyName);
				if (editorType != null) {
					return editorType;
				}
			}
		}
		catch (InvalidPropertyException ex) {
			// Consider as not determinable.
		}
		return null;
	}

	@Override
	@Nullable
	public TypeDescriptor getPropertyTypeDescriptor(String propertyName) throws BeansException {
		try {
			// 获取属性名嵌套的propertyAccessor
			AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
			// 获取属性名最后一个分隔符之后的内容
			String finalPath = getFinalPath(nestedPa, propertyName);
			// 将finalPath解析为PropertyTokenHolder
			PropertyTokenHolder tokens = getPropertyNameTokens(finalPath);
			// 使用tokens中的actualName从nestedPa的CachedIntrospectionResults中获取对应的PropertyDescriptor，
			// 再用pd实例化一个PropertyHandler返回
			PropertyHandler ph = nestedPa.getLocalPropertyHandler(tokens.actualName);
			// 如果ph不为null
			if (ph != null) {
				// 如果tokens中存在keys
				if (tokens.keys != null) {
					// 如果ph是可读的或者可写的
					if (ph.isReadable() || ph.isWritable()) {
						// 根据keys的长度调用ph的nested方法返回
						return ph.nested(tokens.keys.length);
					}
				}
				// 如果tokens中不存在keys
				else {
					// 如果ph是可读的或者可写的
					if (ph.isReadable() || ph.isWritable()) {
						// 调用ph的toTypeDescriptor方法
						return ph.toTypeDescriptor();
					}
				}
			}
		}
		catch (InvalidPropertyException ex) {
			// Consider as not determinable.
		}
		return null;
	}

	@Override
	public boolean isReadableProperty(String propertyName) {
		try {
			PropertyHandler ph = getPropertyHandler(propertyName);
			if (ph != null) {
				return ph.isReadable();
			}
			else {
				// Maybe an indexed/mapped property...
				getPropertyValue(propertyName);
				return true;
			}
		}
		catch (InvalidPropertyException ex) {
			// Cannot be evaluated, so can't be readable.
		}
		return false;
	}

	@Override
	public boolean isWritableProperty(String propertyName) {
		try {
			PropertyHandler ph = getPropertyHandler(propertyName);
			if (ph != null) {
				return ph.isWritable();
			}
			else {
				// Maybe an indexed/mapped property...
				getPropertyValue(propertyName);
				return true;
			}
		}
		catch (InvalidPropertyException ex) {
			// Cannot be evaluated, so can't be writable.
		}
		return false;
	}

	@Nullable
	private Object convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue,
			@Nullable Object newValue, @Nullable Class<?> requiredType, @Nullable TypeDescriptor td)
			throws TypeMismatchException {

		Assert.state(this.typeConverterDelegate != null, "No TypeConverterDelegate");
		try {
			// 调用委托类型转换对象进行转换
			return this.typeConverterDelegate.convertIfNecessary(propertyName, oldValue, newValue, requiredType, td);
		}
		catch (ConverterNotFoundException | IllegalStateException ex) {
			PropertyChangeEvent pce =
					new PropertyChangeEvent(getRootInstance(), this.nestedPath + propertyName, oldValue, newValue);
			throw new ConversionNotSupportedException(pce, requiredType, ex);
		}
		catch (ConversionException | IllegalArgumentException ex) {
			PropertyChangeEvent pce =
					new PropertyChangeEvent(getRootInstance(), this.nestedPath + propertyName, oldValue, newValue);
			throw new TypeMismatchException(pce, requiredType, ex);
		}
	}

	@Nullable
	protected Object convertForProperty(
			String propertyName, @Nullable Object oldValue, @Nullable Object newValue, TypeDescriptor td)
			throws TypeMismatchException {

		return convertIfNecessary(propertyName, oldValue, newValue, td.getType(), td);
	}

	@Override
	@Nullable
	public Object getPropertyValue(String propertyName) throws BeansException {
		AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		PropertyTokenHolder tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
		return nestedPa.getPropertyValue(tokens);
	}

	@SuppressWarnings("unchecked")
	@Nullable
	protected Object getPropertyValue(PropertyTokenHolder tokens) throws BeansException {
		// 获取tokens中的canonicalName作为属性名
		String propertyName = tokens.canonicalName;
		// 获取tokens中的actualName作为实际名
		String actualName = tokens.actualName;
		// 通过实际名字获取属性处理器PropertyHandler，获取到的是一个持有actualName属性名对应的pd的BeanPropertyHandler对象
		PropertyHandler ph = getLocalPropertyHandler(actualName);
		// 如果属性处理器为null 或者 不可读，报错
		if (ph == null || !ph.isReadable()) {
			throw new NotReadablePropertyException(getRootClass(), this.nestedPath + propertyName);
		}
		try {
			// 调用ph持有的pd的读方法，获取到对应属性的值
			Object value = ph.getValue();
			// 如果tokens中含有keys
			if (tokens.keys != null) {
				// 如果获取到的对应属性值为null
				if (value == null) {
					// 如果isAutoGrowNestedPaths为true
					if (isAutoGrowNestedPaths()) {
						// 根据actualName初始化一个tokens
						// 根据tokens获取对应属性的TypeDescription
						// 根据TypeDescription创建默认对象，并和tokens中的canonicalName组成一个PropertyValue，
						// 将PropertyValue设置进tokens所对应的属性中
						// 然后获取对应属性的值
						value = setDefaultValue(new PropertyTokenHolder(tokens.actualName));
					}
					// 否则，报错
					else {
						throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + propertyName,
								"Cannot access indexed value of property referenced in indexed " +
										"property path '" + propertyName + "': returned null");
					}
				}
				// 构建一个StringBuilder作为indexedPropertyName
				StringBuilder indexedPropertyName = new StringBuilder(tokens.actualName);
				// apply indexes and map keys
				// 然后遍历keys，应用索引或者map类型的key
				for (int i = 0; i < tokens.keys.length; i++) {
					String key = tokens.keys[i];
					// 如果value为null的话，报错
					if (value == null) {
						throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + propertyName,
								"Cannot access indexed value of property referenced in indexed " +
										"property path '" + propertyName + "': returned null");
					}
					// 如果value是数组类型的
					else if (value.getClass().isArray()) {
						// 将key解析为int类型
						int index = Integer.parseInt(key);
						// 如果index大于等于了数组的长度，并且isAutoGrowNestedPaths为true，扩展数组，并向扩展后的位置填入默认值
						value = growArrayIfNecessary(value, index, indexedPropertyName.toString());
						// 获取对应下标的元素
						value = Array.get(value, index);
					}
					// 如果value是List类型的
					else if (value instanceof List) {
						int index = Integer.parseInt(key);
						List<Object> list = (List<Object>) value;
						// 如果index超出了list的size，且isAutoGrowNestedPaths为true，扩展list
						growCollectionIfNecessary(list, index, indexedPropertyName.toString(), ph, i + 1);
						// 获取对应下标的元素
						value = list.get(index);
					}
					// 如果value是set类型的
					else if (value instanceof Set) {
						// Apply index to Iterator in case of a Set.
						Set<Object> set = (Set<Object>) value;
						int index = Integer.parseInt(key);
						// 如果index小于0或者index大于等于了set的size，报错
						if (index < 0 || index >= set.size()) {
							throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
									"Cannot get element with index " + index + " from Set of size " +
											set.size() + ", accessed using property path '" + propertyName + "'");
						}
						// 遍历set
						Iterator<Object> it = set.iterator();
						for (int j = 0; it.hasNext(); j++) {
							Object elem = it.next();
							// 当遍历到第index元素的时候，赋值给value，并跳出循环
							if (j == index) {
								value = elem;
								break;
							}
						}
					}
					// 如果value是map类型的
					else if (value instanceof Map) {
						Map<Object, Object> map = (Map<Object, Object>) value;
						// 根据map声明的泛型获取map的key的类型
						Class<?> mapKeyType = ph.getResolvableType().getNested(i + 1).asMap().resolveGeneric(0);
						// IMPORTANT: Do not pass full property name in here - property editors
						// must not kick in for map keys but rather only for map values.
						TypeDescriptor typeDescriptor = TypeDescriptor.valueOf(mapKeyType);
						// 将String类型的key转换成实际map的key的类型
						Object convertedMapKey = convertIfNecessary(null, null, key, mapKeyType, typeDescriptor);
						// 根据转换后的key获取map中对应的value
						value = map.get(convertedMapKey);
					}
					// 如果value属于其他属性，报错
					else {
						throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
								"Property referenced in indexed property path '" + propertyName +
										"' is neither an array nor a List nor a Set nor a Map; returned value was [" + value + "]");
					}
					// 向indexedPropertyName中拼接 前缀 + key + 后缀
					indexedPropertyName.append(PROPERTY_KEY_PREFIX).append(key).append(PROPERTY_KEY_SUFFIX);
				}
			}
			return value;
		}
		catch (IndexOutOfBoundsException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Index of out of bounds in property path '" + propertyName + "'", ex);
		}
		catch (NumberFormatException | TypeMismatchException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Invalid index in property path '" + propertyName + "'", ex);
		}
		catch (InvocationTargetException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Getter for property '" + actualName + "' threw exception", ex);
		}
		catch (Exception ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Illegal attempt to get property '" + actualName + "' threw exception", ex);
		}
	}


	/**
	 * Return the {@link PropertyHandler} for the specified {@code propertyName}, navigating
	 * if necessary. Return {@code null} if not found rather than throwing an exception.
	 * @param propertyName the property to obtain the descriptor for
	 * @return the property descriptor for the specified property,
	 * or {@code null} if not found
	 * @throws BeansException in case of introspection failure
	 */
	@Nullable
	protected PropertyHandler getPropertyHandler(String propertyName) throws BeansException {
		Assert.notNull(propertyName, "Property name must not be null");
		AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		return nestedPa.getLocalPropertyHandler(getFinalPath(nestedPa, propertyName));
	}

	/**
	 * Return a {@link PropertyHandler} for the specified local {@code propertyName}.
	 * Only used to reach a property available in the current context.
	 * @param propertyName the name of a local property
	 * @return the handler for that property, or {@code null} if it has not been found
	 */
	@Nullable
	protected abstract PropertyHandler getLocalPropertyHandler(String propertyName);

	/**
	 * Create a new nested property accessor instance.
	 * Can be overridden in subclasses to create a PropertyAccessor subclass.
	 * @param object the object wrapped by this PropertyAccessor
	 * @param nestedPath the nested path of the object
	 * @return the nested PropertyAccessor instance
	 */
	protected abstract AbstractNestablePropertyAccessor newNestedPropertyAccessor(Object object, String nestedPath);

	/**
	 * Create a {@link NotWritablePropertyException} for the specified property.
	 */
	protected abstract NotWritablePropertyException createNotWritablePropertyException(String propertyName);


	private Object growArrayIfNecessary(Object array, int index, String name) {
		// 如果autoGrowNestedPaths为false的话，直接返回array
		if (!isAutoGrowNestedPaths()) {
			return array;
		}
		// 否则获取数组的长度
		int length = Array.getLength(array);
		// 判断如果要设置的元素下标大于等于数组长度 并且 小于自动增长的限制
		if (index >= length && index < this.autoGrowCollectionLimit) {
			// 那么根据index + 1为数组长度生成一个新的数组
			Class<?> componentType = array.getClass().getComponentType();
			Object newArray = Array.newInstance(componentType, index + 1);
			// 将原数组的元素复制到新数组
			System.arraycopy(array, 0, newArray, 0, length);
			for (int i = length; i < Array.getLength(newArray); i++) {
				// 将老数组的长度 到 新数组长度之间的元素设置为 componentType的默认值
				Array.set(newArray, i, newValue(componentType, null, name));
			}
			// 将新数组设置进对应的属性中
			setPropertyValue(name, newArray);
			// 根据属性名获取新数组并返回
			Object defaultValue = getPropertyValue(name);
			Assert.state(defaultValue != null, "Default value must not be null");
			return defaultValue;
		}
		// 如果不需要扩展数组长度，直接返回
		else {
			return array;
		}
	}

	private void growCollectionIfNecessary(Collection<Object> collection, int index, String name,
			PropertyHandler ph, int nestingLevel) {
		// 如果autoGrowNestedPaths为false的话，直接返回
		if (!isAutoGrowNestedPaths()) {
			return;
		}
		int size = collection.size();
		// 如果要设置的下标的位置 大于集合的size 并且 小于自动增长的限制
		if (index >= size && index < this.autoGrowCollectionLimit) {
			// 获取对应的泛型所表示的类型
			Class<?> elementType = ph.getResolvableType().getNested(nestingLevel).asCollection().resolveGeneric();
			// 如果元素类型不为null
			if (elementType != null) {
				// 将从原本集合的size位置要index位置之间的元素设置为elementType类型的默认值
				for (int i = collection.size(); i < index + 1; i++) {
					collection.add(newValue(elementType, null, name));
				}
			}
		}
	}

	/**
	 * Get the last component of the path. Also works if not nested.
	 * @param pa property accessor to work on
	 * @param nestedPath property path we know is nested
	 * @return last component of the path (the property on the target bean)
	 */
	protected String getFinalPath(AbstractNestablePropertyAccessor pa, String nestedPath) {
		// 如果传入的pa参数就等于自身，直接返回nestedPath
		if (pa == this) {
			return nestedPath;
		}
		// 否则返回最后一个分隔符分隔出的路径
		return nestedPath.substring(PropertyAccessorUtils.getLastNestedPropertySeparatorIndex(nestedPath) + 1);
	}

	/**
	 * Recursively navigate to return a property accessor for the nested property path.
	 * @param propertyPath property path, which may be nested
	 * @return a property accessor for the target bean
	 */
	protected AbstractNestablePropertyAccessor getPropertyAccessorForPropertyPath(String propertyPath) {
		// 找到属性路径中第一个分隔符.的位置
		int pos = PropertyAccessorUtils.getFirstNestedPropertySeparatorIndex(propertyPath);
		// Handle nested properties recursively.
		// 如果分隔符存在
		if (pos > -1) {
			// 获取分隔符前面的内容作为嵌套的属性
			String nestedProperty = propertyPath.substring(0, pos);
			// 获取分隔符后面的内容作为嵌套的路径
			String nestedPath = propertyPath.substring(pos + 1);
			// 根据嵌套的属性获取对应的propertyAccessor
			AbstractNestablePropertyAccessor nestedPa = getNestedPropertyAccessor(nestedProperty);
			// 然后递归调用nestPa去解析嵌套的路径
			return nestedPa.getPropertyAccessorForPropertyPath(nestedPath);
		}
		// 如果分隔符不存在，直接返回自身
		else {
			return this;
		}
	}

	/**
	 * Retrieve a Property accessor for the given nested property.
	 * Create a new one if not found in the cache.
	 * <p>Note: Caching nested PropertyAccessors is necessary now,
	 * to keep registered custom editors for nested properties.
	 * @param nestedProperty property to create the PropertyAccessor for
	 * @return the PropertyAccessor instance, either cached or newly created
	 */
	private AbstractNestablePropertyAccessor getNestedPropertyAccessor(String nestedProperty) {
		// 如果自身nestedPropertyAccessors为null的话，初始化为一个HashMap
		if (this.nestedPropertyAccessors == null) {
			this.nestedPropertyAccessors = new HashMap<>();
		}
		// Get value of bean property.
		// 将嵌套的属性解析为PropertyTokenHolder对象
		PropertyTokenHolder tokens = getPropertyNameTokens(nestedProperty);
		// 获取tokens中的canonicalName
		String canonicalName = tokens.canonicalName;
		// 根据tokens获取属性名对应的属性值
		Object value = getPropertyValue(tokens);
		// 如果value为null 或者 value是Optional类型的，但是isPresent返回false
		if (value == null || (value instanceof Optional && !((Optional<?>) value).isPresent())) {
			// 如果isAutoGrowNestedPaths为true，设置默认值给value
			if (isAutoGrowNestedPaths()) {
				value = setDefaultValue(tokens);
			}
			// 否则报错
			else {
				throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + canonicalName);
			}
		}

		// Lookup cached sub-PropertyAccessor, create new one if not found.
		// 查询其子propertyAccessors中是否有canonicalName对应的propertyAccessor
		AbstractNestablePropertyAccessor nestedPa = this.nestedPropertyAccessors.get(canonicalName);
		// 如果不存在对应的子pa 或者 子pa的包装实例和value不相等，那么创建一个新的pa放入到nestedPropertyAccessors中
		if (nestedPa == null || nestedPa.getWrappedInstance() != ObjectUtils.unwrapOptional(value)) {
			if (logger.isTraceEnabled()) {
				logger.trace("Creating new nested " + getClass().getSimpleName() + " for property '" + canonicalName + "'");
			}
			// 调用newNestedPropertyAccessor方法，这是一个模板方法，beanWrapperImpl的实现就是new一个beanWrapperImpl，设置其nestedPath和parent
			nestedPa = newNestedPropertyAccessor(value, this.nestedPath + canonicalName + NESTED_PROPERTY_SEPARATOR);
			// Inherit all type-specific PropertyEditors.
			// 将自身持有的defaultPropertyEditors都复制给 嵌套的pa
			copyDefaultEditorsTo(nestedPa);
			// 复制customPropertyEditor给 嵌套的pa
			copyCustomEditorsTo(nestedPa, canonicalName);
			// 将嵌套的pa根据canonicalName放入到nestedPropertyAccessors这个map中
			this.nestedPropertyAccessors.put(canonicalName, nestedPa);
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Using cached nested property accessor for property '" + canonicalName + "'");
			}
		}
		return nestedPa;
	}

	private Object setDefaultValue(PropertyTokenHolder tokens) {
		// 根据tokens创建一个PropertyValue对象
		PropertyValue pv = createDefaultPropertyValue(tokens);
		// 将pv设置进tokens对应的属性中
		setPropertyValue(tokens, pv);
		// 根据tokens获取对应的属性值
		Object defaultValue = getPropertyValue(tokens);
		Assert.state(defaultValue != null, "Default value must not be null");
		// 返回属性值
		return defaultValue;
	}

	private PropertyValue createDefaultPropertyValue(PropertyTokenHolder tokens) {
		// 根据tokens中的canonicalName获取获取对应属性的TypeDescriptor
		TypeDescriptor desc = getPropertyTypeDescriptor(tokens.canonicalName);
		if (desc == null) {
			throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Could not determine property type for auto-growing a default value");
		}
		// 根据TypeDescriptor创建一个默认值
		Object defaultValue = newValue(desc.getType(), desc, tokens.canonicalName);
		// 然后将canonicalName和默认值构建成一个PropertyValue返回
		return new PropertyValue(tokens.canonicalName, defaultValue);
	}

	private Object newValue(Class<?> type, @Nullable TypeDescriptor desc, String name) {
		try {
			// 如果type是数组类型的
			if (type.isArray()) {
				// 获取数组的成员类型
				Class<?> componentType = type.getComponentType();
				// TODO - only handles 2-dimensional arrays
				// 如果成员类型也是数组类型的，现在只能解析二维数组
				if (componentType.isArray()) {
					// 创建一个二维数组
					Object array = Array.newInstance(componentType, 1);
					// 将第一个元素设置为创建的一维数组
					Array.set(array, 0, Array.newInstance(componentType.getComponentType(), 0));
					return array;
				}
				// 如果成员类型不是数组，创建一个对应类型的看那个数组返回
				else {
					return Array.newInstance(componentType, 0);
				}
			}
			// 如果是Collection类型的，创建一个空的Collection类型返回
			else if (Collection.class.isAssignableFrom(type)) {
				TypeDescriptor elementDesc = (desc != null ? desc.getElementTypeDescriptor() : null);
				return CollectionFactory.createCollection(type, (elementDesc != null ? elementDesc.getType() : null), 16);
			}
			// 如果是Map类型的，创建一个空的Map类型返回
			else if (Map.class.isAssignableFrom(type)) {
				TypeDescriptor keyDesc = (desc != null ? desc.getMapKeyTypeDescriptor() : null);
				return CollectionFactory.createMap(type, (keyDesc != null ? keyDesc.getType() : null), 16);
			}
			// 如果是其他类型
			else {
				// 获取类型申明的构造器
				Constructor<?> ctor = type.getDeclaredConstructor();
				// 如果构造器是私有的，报错
				if (Modifier.isPrivate(ctor.getModifiers())) {
					throw new IllegalAccessException("Auto-growing not allowed with private constructor: " + ctor);
				}
				// 否则，进行实例化
				return BeanUtils.instantiateClass(ctor);
			}
		}
		catch (Throwable ex) {
			throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + name,
					"Could not instantiate property type [" + type.getName() + "] to auto-grow nested property path", ex);
		}
	}

	/**
	 * Parse the given property name into the corresponding property name tokens.
	 * @param propertyName the property name to parse
	 * @return representation of the parsed property tokens
	 */
	private PropertyTokenHolder getPropertyNameTokens(String propertyName) {
		String actualName = null;
		List<String> keys = new ArrayList<>(2);
		int searchIndex = 0;
		while (searchIndex != -1) {
			// 获取属性名从searchIndex开始的 属性key前缀字符('[') 的位置
			int keyStart = propertyName.indexOf(PROPERTY_KEY_PREFIX, searchIndex);
			// 然后将searchIndex置为-1
			searchIndex = -1;
			// 如果找到了属性key前缀字符('[')
			if (keyStart != -1) {
				// 尝试从 属性key前缀字符('[') 之后开始查找 属性key后缀字符(']')
				int keyEnd = getPropertyNameKeyEnd(propertyName, keyStart + PROPERTY_KEY_PREFIX.length());
				// 如果找到了 属性key后缀字符(']')
				if (keyEnd != -1) {
					// 如果此时actualName为null
					if (actualName == null) {
						// 那么截取属性名开始到 属性key前缀字符('[')的字符串，作为实际的名字
						actualName = propertyName.substring(0, keyStart);
					}
					// 然后截取被属性key前后缀包裹的字符串作为key
					String key = propertyName.substring(keyStart + PROPERTY_KEY_PREFIX.length(), keyEnd);
					// 如果key的长度大于1，并且被单引号包裹 或者 被双引号包裹
					if (key.length() > 1 && (key.startsWith("'") && key.endsWith("'")) ||
							(key.startsWith("\"") && key.endsWith("\""))) {
						// 将单引号 或者 双引号去除掉
						key = key.substring(1, key.length() - 1);
					}
					// 将key加入到keys集合中
					keys.add(key);
					// 将搜索的下标设置为 属性key后缀字符(']') 之后的位置，继续进行遍历
					searchIndex = keyEnd + PROPERTY_KEY_SUFFIX.length();
				}
			}
		}
		// 如果actualName不为null的话，使用actualName，否则直接使用propertyName作为参数创建一个PropertyTokenHolder对象
		PropertyTokenHolder tokens = new PropertyTokenHolder(actualName != null ? actualName : propertyName);
		// 如果keys集合不为空的话
		if (!keys.isEmpty()) {
			// 将tokens中的canonicalName后拼接上 [key1][key2]... 这样的字符串
			tokens.canonicalName += PROPERTY_KEY_PREFIX +
					StringUtils.collectionToDelimitedString(keys, PROPERTY_KEY_SUFFIX + PROPERTY_KEY_PREFIX) +
					PROPERTY_KEY_SUFFIX;
			// 将tokens的keys属性赋值为解析出来的keys
			tokens.keys = StringUtils.toStringArray(keys);
		}
		return tokens;
	}

	private int getPropertyNameKeyEnd(String propertyName, int startIndex) {
		int unclosedPrefixes = 0;
		int length = propertyName.length();
		// 从startIndex开始遍历propertyName对应的字符
		for (int i = startIndex; i < length; i++) {
			// 判断下标对应的字符
			switch (propertyName.charAt(i)) {
				// 如果仍然是 属性key前缀字符('[')
				case PropertyAccessor.PROPERTY_KEY_PREFIX_CHAR:
					// The property name contains opening prefix(es)...
					// 将unclosedPrefixes计数器+1，统计没有关闭的前缀的个数
					unclosedPrefixes++;
					break;
					// 如果字符是 属性key后缀字符(']')
				case PropertyAccessor.PROPERTY_KEY_SUFFIX_CHAR:
					// 此时没有关闭的前缀字符个数为0，直接返回下标i
					if (unclosedPrefixes == 0) {
						// No unclosed prefix(es) in the property name (left) ->
						// this is the suffix we are looking for.
						return i;
					}
					// 否则将unclosedPrefixes计数器-1
					else {
						// This suffix does not close the initial prefix but rather
						// just one that occurred within the property name.
						unclosedPrefixes--;
					}
					break;
			}
		}
		// 如果没有找到对应的 属性key后缀字符(']')，返回-1
		return -1;
	}


	@Override
	public String toString() {
		String className = getClass().getName();
		if (this.wrappedObject == null) {
			return className + ": no wrapped object set";
		}
		return className + ": wrapping object [" + ObjectUtils.identityToString(this.wrappedObject) + ']';
	}


	/**
	 * A handler for a specific property.
	 */
	protected abstract static class PropertyHandler {

		private final Class<?> propertyType;

		private final boolean readable;

		private final boolean writable;

		public PropertyHandler(Class<?> propertyType, boolean readable, boolean writable) {
			this.propertyType = propertyType;
			this.readable = readable;
			this.writable = writable;
		}

		public Class<?> getPropertyType() {
			return this.propertyType;
		}

		public boolean isReadable() {
			return this.readable;
		}

		public boolean isWritable() {
			return this.writable;
		}

		public abstract TypeDescriptor toTypeDescriptor();

		public abstract ResolvableType getResolvableType();

		@Nullable
		public Class<?> getMapKeyType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asMap().resolveGeneric(0);
		}

		@Nullable
		public Class<?> getMapValueType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asMap().resolveGeneric(1);
		}

		@Nullable
		public Class<?> getCollectionType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asCollection().resolveGeneric();
		}

		@Nullable
		public abstract TypeDescriptor nested(int level);

		@Nullable
		public abstract Object getValue() throws Exception;

		public abstract void setValue(@Nullable Object value) throws Exception;
	}


	/**
	 * Holder class used to store property tokens.
	 */
	protected static class PropertyTokenHolder {

		public PropertyTokenHolder(String name) {
			this.actualName = name;
			this.canonicalName = name;
		}

		public String actualName;

		public String canonicalName;

		@Nullable
		public String[] keys;
	}

}
