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
 * Utility class for working with Strings that have placeholder values in them. A placeholder takes the form
 * {@code ${name}}. Using {@code PropertyPlaceholderHelper} these placeholders can be substituted for
 * user-supplied values. <p> Values for substitution can be supplied using a {@link Properties} instance or
 * using a {@link PlaceholderResolver}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 3.0
 */
public class PropertyPlaceholderHelper {

	private static final Log logger = LogFactory.getLog(PropertyPlaceholderHelper.class);
	/**众所周知的简单简单前缀（占位符后缀与简单前缀的对应关系）*/
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
	/**表示是否可以忽略掉未解析的占位符（没有找到占位符key对应的属性值，并且没有设置默认值）*/
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
		//通过占位符后缀回去简单前缀
		String simplePrefixForSuffix = wellKnownSimplePrefixes.get(this.placeholderSuffix);
		//获取到了指定的简单前缀，并且其为设置前缀的后半部分，则使用其为简单前缀，否则使用设置前缀为简单前缀
		if (simplePrefixForSuffix != null && this.placeholderPrefix.endsWith(simplePrefixForSuffix)) {
			this.simplePrefix = simplePrefixForSuffix;
		}
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
		//找到占位符前缀（'${'符号）在给定值中的位置
		int startIndex = value.indexOf(this.placeholderPrefix);
		//若不存在，则直接返回value
		if (startIndex == -1) {
			return value;
		}
		//创建result对象
		StringBuilder result = new StringBuilder(value);
		//存在头占位符则遍历
		while (startIndex != -1) {
			//找到尾占位符}在给定值中的位置
			int endIndex = findPlaceholderEndIndex(result, startIndex);
			//如果存在尾占位符
			if (endIndex != -1) {
				//取出占位符的标识字符串（${和}中间的内容）
				String placeholder = result.substring(startIndex + this.placeholderPrefix.length(), endIndex);
				//记录原始的占位符字符串
				String originalPlaceholder = placeholder;
				//用于保存访问过的占位符集合，如果为空则初始化
				if (visitedPlaceholders == null) {
					visitedPlaceholders = new HashSet<>(4);
				}
				//将当前即将解析的原始占位符字符串添加到visitedPlaceholders中，如果此集合中已经存在指定的占位符
				//则表明给定的值中的占位符存在循环使用（即占位符中包含和当前占位符相同名称的占位符）
				if (!visitedPlaceholders.add(originalPlaceholder)) {
					throw new IllegalArgumentException(
							"Circular placeholder reference '" + originalPlaceholder + "' in property definitions");
				}
				// Recursive invocation, parsing placeholders contained in the placeholder key.
				//递归调用，解析占位符键中包含的占位符
				placeholder = parseStringValue(placeholder, placeholderResolver, visitedPlaceholders);
				// Now obtain the value for the fully resolved key...
				//从系统属性或者自定义属性中，解析给定属性名称对应的属性值
				String propVal = placeholderResolver.resolvePlaceholder(placeholder);
				//如果解析失败（不存在），并且值的分隔符不为空
				if (propVal == null && this.valueSeparator != null) {
					//找到占位符中指定值分隔符（：）的位置
					int separatorIndex = placeholder.indexOf(this.valueSeparator);
					//如果存在：
					if (separatorIndex != -1) {
						//获取真实的占位符
						String actualPlaceholder = placeholder.substring(0, separatorIndex);
						//获取当前占位符的默认值（即占位符为${aa:bb}，则aa为真实占位符，而bb这是默认值）
						String defaultValue = placeholder.substring(separatorIndex + this.valueSeparator.length());
						//解析真实占位符作为属性名对应的属性值
						propVal = placeholderResolver.resolvePlaceholder(actualPlaceholder);
						//如果未找到，则使用默认的属性值
						if (propVal == null) {
							propVal = defaultValue;
						}
					}
				}
				//如果获取到了属性值propVal
				if (propVal != null) {
					// Recursive invocation, parsing placeholders contained in the
					// previously resolved placeholder value.
					//继续解析当前占位符获取到的对应属性值（此值可能也包含占位符）
					propVal = parseStringValue(propVal, placeholderResolver, visitedPlaceholders);
					//将当前字符串中的占位符替换为其具体表示的值
					result.replace(startIndex, endIndex + this.placeholderSuffix.length(), propVal);
					if (logger.isTraceEnabled()) {
						logger.trace("Resolved placeholder '" + placeholder + "'");
					}
					//从被替换掉的占位符的末尾处继续查找下一个占位符的其实位置
					startIndex = result.indexOf(this.placeholderPrefix, startIndex + propVal.length());
				}
				//如果当前要解析的占位符未解析到对应属性值，并且此占位符未设置默认值，并且当前设置可以忽略掉未解析的占位符
				else if (this.ignoreUnresolvablePlaceholders) {
					// Proceed with unprocessed value.
					//直接跳过当前占位符，直接开始寻找下一个占位符
					startIndex = result.indexOf(this.placeholderPrefix, endIndex + this.placeholderSuffix.length());
				}
				//如果未找到占位符对应的属性值，并且无默认值，还不可忽略，则抛出参数异常
				else {
					throw new IllegalArgumentException("Could not resolve placeholder '" +
							placeholder + "'" + " in value \"" + value + "\"");
				}
				//记录解析过的占位符
				visitedPlaceholders.remove(originalPlaceholder);
			}
			//未找到与占位符开始符号匹配的结束符，则结束循环解析占位符
			else {
				startIndex = -1;
			}
		}
		//返回解析之后的值（即占位符被替换为对应的属性值）
		return result.toString();
	}

	private int findPlaceholderEndIndex(CharSequence buf, int startIndex) {
		int index = startIndex + this.placeholderPrefix.length();
		int withinNestedPlaceholder = 0;
		while (index < buf.length()) {
			if (StringUtils.substringMatch(buf, index, this.placeholderSuffix)) {
				if (withinNestedPlaceholder > 0) {
					withinNestedPlaceholder--;
					index = index + this.placeholderSuffix.length();
				}
				else {
					return index;
				}
			}
			else if (StringUtils.substringMatch(buf, index, this.simplePrefix)) {
				withinNestedPlaceholder++;
				index = index + this.simplePrefix.length();
			}
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
