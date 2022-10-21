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

package org.springframework.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;

/**
 * Utility class for working with Strings that have placeholder values in them.
 * A placeholder takes the form {@code ${name}}. Using {@code PropertyPlaceholderHelper}
 * these placeholders can be substituted for user-supplied values.
 *
 * <p>Values for substitution can be supplied using a {@link Properties} instance or
 * using a {@link PlaceholderResolver}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 3.0
 */
public class PropertyPlaceholderHelper {

	private static final Log logger = LogFactory.getLog(PropertyPlaceholderHelper.class);

	private static final Map<String, String> wellKnownSimplePrefixes = new HashMap<>(4);

	static {
		wellKnownSimplePrefixes.put("}", "{");
		wellKnownSimplePrefixes.put("]", "[");
		wellKnownSimplePrefixes.put(")", "(");
	}


	private final String placeholderPrefix;

	private final String placeholderSuffix;

	private final String simplePrefix;

	@Nullable
	private final String valueSeparator;

	private final boolean ignoreUnresolvablePlaceholders;


	/**
	 * Creates a new {@code PropertyPlaceholderHelper} that uses the supplied prefix and suffix.
	 * Unresolvable placeholders are ignored.
	 * @param placeholderPrefix the prefix that denotes the start of a placeholder
	 * @param placeholderSuffix the suffix that denotes the end of a placeholder
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix) {
		this(placeholderPrefix, placeholderSuffix, null, true);
	}

	/**
	 * Creates a new {@code PropertyPlaceholderHelper} that uses the supplied prefix and suffix.
	 * @param placeholderPrefix the prefix that denotes the start of a placeholder
	 * @param placeholderSuffix the suffix that denotes the end of a placeholder
	 * @param valueSeparator the separating character between the placeholder variable
	 * and the associated default value, if any
	 * @param ignoreUnresolvablePlaceholders indicates whether unresolvable placeholders should
	 * be ignored ({@code true}) or cause an exception ({@code false})
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix,
			@Nullable String valueSeparator, boolean ignoreUnresolvablePlaceholders) {

		Assert.notNull(placeholderPrefix, "'placeholderPrefix' must not be null");
		Assert.notNull(placeholderSuffix, "'placeholderSuffix' must not be null");
		this.placeholderPrefix = placeholderPrefix;
		this.placeholderSuffix = placeholderSuffix;
		// 根据后缀去map中拿到简单前缀
		String simplePrefixForSuffix = wellKnownSimplePrefixes.get(this.placeholderSuffix);
		// 如果简单前缀不为null，并且占位符前缀是以简单前缀结尾的
		if (simplePrefixForSuffix != null && this.placeholderPrefix.endsWith(simplePrefixForSuffix)) {
			// 那么就将该值赋给simplePrefix字段
			this.simplePrefix = simplePrefixForSuffix;
		}
		// 否则简单前缀字段就等于占位符前缀
		else {
			this.simplePrefix = this.placeholderPrefix;
		}
		this.valueSeparator = valueSeparator;
		this.ignoreUnresolvablePlaceholders = ignoreUnresolvablePlaceholders;
	}


	/**
	 * Replaces all placeholders of format {@code ${name}} with the corresponding
	 * property from the supplied {@link Properties}.
	 * @param value the value containing the placeholders to be replaced
	 * @param properties the {@code Properties} to use for replacement
	 * @return the supplied value with placeholders replaced inline
	 */
	public String replacePlaceholders(String value, final Properties properties) {
		Assert.notNull(properties, "'properties' must not be null");
		return replacePlaceholders(value, properties::getProperty);
	}

	/**
	 * Replaces all placeholders of format {@code ${name}} with the value returned
	 * from the supplied {@link PlaceholderResolver}.
	 * @param value the value containing the placeholders to be replaced
	 * @param placeholderResolver the {@code PlaceholderResolver} to use for replacement
	 * @return the supplied value with placeholders replaced inline
	 */
	public String replacePlaceholders(String value, PlaceholderResolver placeholderResolver) {
		Assert.notNull(value, "'value' must not be null");
		return parseStringValue(value, placeholderResolver, null);
	}

	protected String parseStringValue(
			String value, PlaceholderResolver placeholderResolver, @Nullable Set<String> visitedPlaceholders) {

		int startIndex = value.indexOf(this.placeholderPrefix);
		// 如果不存在占位符前缀，直接返回
		if (startIndex == -1) {
			return value;
		}

		StringBuilder result = new StringBuilder(value);
		while (startIndex != -1) {
			int endIndex = findPlaceholderEndIndex(result, startIndex);
			// 当endIndex不为-1时，说明查找到了占位符后缀
			if (endIndex != -1) {
				// 截取出占位符前缀后缀所包裹的内容 例如${name} 截取出的就是name
				String placeholder = result.substring(startIndex + this.placeholderPrefix.length(), endIndex);
				// 将其赋值给originPlaceholder
				String originalPlaceholder = placeholder;
				// 如果visitedPlaceholders为null，初始化一个初始容量为4的set
				if (visitedPlaceholders == null) {
					visitedPlaceholders = new HashSet<>(4);
				}
				// 将originalPlaceholder放入set中，如果已经访问过的placeholder再次访问的话，会循环引用的错误
				if (!visitedPlaceholders.add(originalPlaceholder)) {
					throw new IllegalArgumentException(
							"Circular placeholder reference '" + originalPlaceholder + "' in property definitions");
				}
				// Recursive invocation, parsing placeholders contained in the placeholder key.
				// 递归调用，解析占位符中的占位符
				placeholder = parseStringValue(placeholder, placeholderResolver, visitedPlaceholders);
				// Now obtain the value for the fully resolved key...
				// 通过方法传入的占位符解析器解析占位符，比如传入的AbstractPropertyResolver类中的模板方法getPropertyAsRawString的具体实现，
				// 拿到占位符对应的具体的值
				String propVal = placeholderResolver.resolvePlaceholder(placeholder);
				// 如果对应的值为null并且valueSeparator不为null
				if (propVal == null && this.valueSeparator != null) {
					// 判断占位符中是否存在valueSeparator 比如这种形式 ${name:tom}
					int separatorIndex = placeholder.indexOf(this.valueSeparator);
					if (separatorIndex != -1) {
						// 如果存在，根据valueSeparator分割，取到实际的占位符 name
						String actualPlaceholder = placeholder.substring(0, separatorIndex);
						// valueSeparator后面的是默认值，也分割出来 tom
						String defaultValue = placeholder.substring(separatorIndex + this.valueSeparator.length());
						// 再根据实际的占位符去解析
						propVal = placeholderResolver.resolvePlaceholder(actualPlaceholder);
						// 如果得到的值还是为null的话，将默认值赋给propVal
						if (propVal == null) {
							propVal = defaultValue;
						}
					}
				}
				if (propVal != null) {
					// Recursive invocation, parsing placeholders contained in the
					// previously resolved placeholder value.
					// 递归解析propVal，如果propVal还有占位符的话，继续解析
					propVal = parseStringValue(propVal, placeholderResolver, visitedPlaceholders);
					// 将最后得到的propVal用于替换包含前缀和后缀的占位符 例如 ${name:tom}会整个被替换成tom
					result.replace(startIndex, endIndex + this.placeholderSuffix.length(), propVal);
					if (logger.isTraceEnabled()) {
						logger.trace("Resolved placeholder '" + placeholder + "'");
					}
					// 继续往后解析字符串，看是否还存在占位符前缀
					startIndex = result.indexOf(this.placeholderPrefix, startIndex + propVal.length());
				}
				// 如果propVal为null且ignoreUnresolvablePlaceholders参数为true，
				// 忽略无法解析的占位符，将startIndex指向下一个占位符前缀的位置
				else if (this.ignoreUnresolvablePlaceholders) {
					// Proceed with unprocessed value.
					startIndex = result.indexOf(this.placeholderPrefix, endIndex + this.placeholderSuffix.length());
				}
				// 如果参数为false，直接报错
				else {
					throw new IllegalArgumentException("Could not resolve placeholder '" +
							placeholder + "'" + " in value \"" + value + "\"");
				}
				// 将原始占位符从set中移除
				visitedPlaceholders.remove(originalPlaceholder);
			}
			// 当endIndex为-1时，说明没有完整的占位符，将startIndex置为-1，结束循环，直接返回原值
			else {
				startIndex = -1;
			}
		}
		return result.toString();
	}

	private int findPlaceholderEndIndex(CharSequence buf, int startIndex) {
		// 将index指向前缀后面的第一个字符
		int index = startIndex + this.placeholderPrefix.length();
		int withinNestedPlaceholder = 0;
		// 当index等于buf的长度时，循环结束，没有找到占位符结束的位置，返回-1
		while (index < buf.length()) {
			// 判断buf从index位置开始是不是和placeholderSuffix相匹配
			if (StringUtils.substringMatch(buf, index, this.placeholderSuffix)) {
				// 当和placeholderSuffix匹配时，判断是否有嵌套的占位符
				if (withinNestedPlaceholder > 0) {
					// 如果有，将withinNestedPlaceholder-1，并且将index指向placeholderSuffix后面一个字符，继续循环，查找外层的占位符后缀
					withinNestedPlaceholder--;
					index = index + this.placeholderSuffix.length();
				}
				// 当withinNestedPlaceholder = 0时，返回index的值，此时index指向最外层的占位符后缀
				else {
					return index;
				}
			}
			// 判断buf从index位置开始是不是和simplePrefix相匹配
			else if (StringUtils.substringMatch(buf, index, this.simplePrefix)) {
				// 当和simplePrefix匹配时，说明有嵌套的占位符，将withinNestedPlaceholder+1，并且将index指向simplePrefix后面一个字符
				withinNestedPlaceholder++;
				index = index + this.simplePrefix.length();
			}
			// 如果都不匹配，将index+1，继续比较
			else {
				index++;
			}
		}
		return -1;
	}


	/**
	 * Strategy interface used to resolve replacement values for placeholders contained in Strings.
	 */
	@FunctionalInterface
	public interface PlaceholderResolver {

		/**
		 * Resolve the supplied placeholder name to the replacement value.
		 * @param placeholderName the name of the placeholder to resolve
		 * @return the replacement value, or {@code null} if no replacement is to be made
		 */
		@Nullable
		String resolvePlaceholder(String placeholderName);
	}

}
