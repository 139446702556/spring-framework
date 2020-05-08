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

package org.springframework.web.method.annotation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;

import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestScope;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Abstract base class for resolving method arguments from a named value.
 * Request parameters, request headers, and path variables are examples of named
 * values. Each may have a name, a required flag, and a default value.
 *
 * <p>Subclasses define how to do the following:
 * <ul>
 * <li>Obtain named value information for a method parameter
 * <li>Resolve names into argument values
 * <li>Handle missing argument values when argument values are required
 * <li>Optionally handle a resolved value
 * </ul>
 *
 * <p>A default value string can contain ${...} placeholders and Spring Expression
 * Language #{...} expressions. For this to work a
 * {@link ConfigurableBeanFactory} must be supplied to the class constructor.
 *
 * <p>A {@link WebDataBinder} is created to apply type conversion to the resolved
 * argument value if it doesn't match the method parameter type.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 * 此类为基于名字来获取其对应的值的HandlerMethodArgumnetResolver的抽象基类
 * 例如@RequestParam(value="aaa")，则表示为从请求中获取名字为aaa对应的参数值
 */
public abstract class AbstractNamedValueMethodArgumentResolver implements HandlerMethodArgumentResolver {

	@Nullable
	private final ConfigurableBeanFactory configurableBeanFactory;

	@Nullable
	private final BeanExpressionContext expressionContext;
	/**此对象为MethodParameter与NamedValueInfo的映射关系  缓存对象*/
	private final Map<MethodParameter, NamedValueInfo> namedValueInfoCache = new ConcurrentHashMap<>(256);


	public AbstractNamedValueMethodArgumentResolver() {
		this.configurableBeanFactory = null;
		this.expressionContext = null;
	}

	/**
	 * Create a new {@link AbstractNamedValueMethodArgumentResolver} instance.
	 * @param beanFactory a bean factory to use for resolving ${...} placeholder
	 * and #{...} SpEL expressions in default values, or {@code null} if default
	 * values are not expected to contain expressions
	 */
	public AbstractNamedValueMethodArgumentResolver(@Nullable ConfigurableBeanFactory beanFactory) {
		this.configurableBeanFactory = beanFactory;
		this.expressionContext =
				(beanFactory != null ? new BeanExpressionContext(beanFactory, new RequestScope()) : null);
	}

	/**解析指定参数的值*/
	@Override
	@Nullable
	public final Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {
		//获取给定的方法参数对应的NamedValueInfo对象
		NamedValueInfo namedValueInfo = getNamedValueInfo(parameter);
		//如果当前给定的方法参数是内嵌类型的，则获取其内嵌的参数，否则还是使用parameter自身
		MethodParameter nestedParameter = parameter.nestedIfOptional();
		//如果name是占位符，则将其进行解析为对应的值
		Object resolvedName = resolveStringValue(namedValueInfo.name);
		//如果解析不到，则抛出异常
		if (resolvedName == null) {
			throw new IllegalArgumentException(
					"Specified name must not resolve to null: [" + namedValueInfo.name + "]");
		}
		//解析name对应的值
		Object arg = resolveName(resolvedName.toString(), nestedParameter, webRequest);
		//如果解析不到，即arg为空，则使用默认的值
		if (arg == null) {
			//如果设置了默认值，则使用默认值作为此参数的值（默认值有占位符处理的过程）
			if (namedValueInfo.defaultValue != null) {
				arg = resolveStringValue(namedValueInfo.defaultValue);
			}
			//如果未设置默认值，而且此参数为必填的，则处理参数缺失的情况
			else if (namedValueInfo.required && !nestedParameter.isOptional()) {
				//本方法默认实现为直接抛出对应异常，其子类RequestParamMethodArgumentResolver会重写此方法
				handleMissingValue(namedValueInfo.name, nestedParameter, webRequest);
			}
			//处理参数为空值的情况
			arg = handleNullValue(namedValueInfo.name, arg, nestedParameter.getNestedParameterType());
		}
		//如果arg为空字符串，且设置了默认值，则使用默认值
		else if ("".equals(arg) && namedValueInfo.defaultValue != null) {
			arg = resolveStringValue(namedValueInfo.defaultValue);
		}
		//如果设置了binderFactory组件
		if (binderFactory != null) {
			//为当前请求的参数创建对应的WebDataBinder对象
			WebDataBinder binder = binderFactory.createBinder(webRequest, null, namedValueInfo.name);
			try {
				//执行参数值的类型转换
				arg = binder.convertIfNecessary(arg, parameter.getParameterType(), parameter);
			}
			catch (ConversionNotSupportedException ex) {
				throw new MethodArgumentConversionNotSupportedException(arg, ex.getRequiredType(),
						namedValueInfo.name, parameter, ex.getCause());
			}
			catch (TypeMismatchException ex) {
				throw new MethodArgumentTypeMismatchException(arg, ex.getRequiredType(),
						namedValueInfo.name, parameter, ex.getCause());

			}
		}
		//处理解析后得到的值，此方法为模板方法，默认为空方法，交由子类实现，其PathVariableMethodArgumentResolver子类会重写此方法
		handleResolvedValue(arg, namedValueInfo.name, parameter, mavContainer, webRequest);

		return arg;
	}

	/**
	 * Obtain the named value for the given method parameter.
	 * 获取给定的MehodPatameter对应的NamedValueInfo对象
	 */
	private NamedValueInfo getNamedValueInfo(MethodParameter parameter) {
		//从缓存中获取给定的方法参数对应的NamedValueInfo对象
		NamedValueInfo namedValueInfo = this.namedValueInfoCache.get(parameter);
		//如果缓存中不存在
		if (namedValueInfo == null) {
			//则来通过给定的方法参数对象创建一个新的NamedValueInfo对象，此方法为抽象类，具体交由子类实现
			namedValueInfo = createNamedValueInfo(parameter);
			//更新NamedValueInfo对象
			namedValueInfo = updateNamedValueInfo(parameter, namedValueInfo);
			//将MethodParameter与NamedValueInfo的映射关系加入缓存中
			this.namedValueInfoCache.put(parameter, namedValueInfo);
		}
		//返回找到的与给定方法参数匹配的NamedValueInfo对象
		return namedValueInfo;
	}

	/**
	 * Create the {@link NamedValueInfo} object for the given method parameter. Implementations typically
	 * retrieve the method annotation by means of {@link MethodParameter#getParameterAnnotation(Class)}.
	 * @param parameter the method parameter
	 * @return the named value information
	 */
	protected abstract NamedValueInfo createNamedValueInfo(MethodParameter parameter);

	/**
	 * Create a new NamedValueInfo based on the given NamedValueInfo with sanitized values.
	 * 更新给定的namedValueInfo对象
	 */
	private NamedValueInfo updateNamedValueInfo(MethodParameter parameter, NamedValueInfo info) {
		//获取给定的NamedValueInfo对象的名字
		String name = info.name;
		//如果名字为空
		if (info.name.isEmpty()) {
			//获取给定与其对应的方法参数名称
			name = parameter.getParameterName();
			//如果参数名称也为空，则抛出IllegalArgumentException异常
			if (name == null) {
				throw new IllegalArgumentException(
						"Name for argument type [" + parameter.getNestedParameterType().getName() +
						"] not available, and parameter name information not found in class file either.");
			}
		}
		//获取默认值
		String defaultValue = (ValueConstants.DEFAULT_NONE.equals(info.defaultValue) ? null : info.defaultValue);
		//使用给定的信息创建一个新的NamedValueInfo对象，并返回
		return new NamedValueInfo(name, info.required, defaultValue);
	}

	/**
	 * Resolve the given annotation-specified value,
	 * potentially containing placeholders and expressions.
	 * 将给定的占位符解析为对应的值
	 */
	@Nullable
	private Object resolveStringValue(String value) {
		//如果configurableBeanFactory为空，则不进行解析
		if (this.configurableBeanFactory == null) {
			return value;
		}
		//获得占位符对应的值
		String placeholdersResolved = this.configurableBeanFactory.resolveEmbeddedValue(value);
		//获取bean的表达式解析器对象
		BeanExpressionResolver exprResolver = this.configurableBeanFactory.getBeanExpressionResolver();
		//如果exprResolver或者expressionContext为空，则不进行解析，直接返回原值
		if (exprResolver == null || this.expressionContext == null) {
			return value;
		}
		//计算表达式
		return exprResolver.evaluate(placeholdersResolved, this.expressionContext);
	}

	/**
	 * Resolve the given parameter type and value name into an argument value.
	 * @param name the name of the value being resolved
	 * @param parameter the method parameter to resolve to an argument value
	 * (pre-nested in case of a {@link java.util.Optional} declaration)
	 * @param request the current request
	 * @return the resolved argument (may be {@code null})
	 * @throws Exception in case of errors
	 */
	@Nullable
	protected abstract Object resolveName(String name, MethodParameter parameter, NativeWebRequest request)
			throws Exception;

	/**
	 * Invoked when a named value is required, but {@link #resolveName(String, MethodParameter, NativeWebRequest)}
	 * returned {@code null} and there is no default value. Subclasses typically throw an exception in this case.
	 * @param name the name for the value
	 * @param parameter the method parameter
	 * @param request the current request
	 * @since 4.3
	 */
	protected void handleMissingValue(String name, MethodParameter parameter, NativeWebRequest request)
			throws Exception {

		handleMissingValue(name, parameter);
	}

	/**
	 * Invoked when a named value is required, but {@link #resolveName(String, MethodParameter, NativeWebRequest)}
	 * returned {@code null} and there is no default value. Subclasses typically throw an exception in this case.
	 * @param name the name for the value
	 * @param parameter the method parameter
	 */
	protected void handleMissingValue(String name, MethodParameter parameter) throws ServletException {
		throw new ServletRequestBindingException("Missing argument '" + name +
				"' for method parameter of type " + parameter.getNestedParameterType().getSimpleName());
	}

	/**
	 * A {@code null} results in a {@code false} value for {@code boolean}s or an exception for other primitives.
	 */
	@Nullable
	private Object handleNullValue(String name, @Nullable Object value, Class<?> paramType) {
		//如果给定的value为空
		if (value == null) {
			//如果参数类型为Boolean类型，则返回false
			if (Boolean.TYPE.equals(paramType)) {
				return Boolean.FALSE;
			}
			//如果当前参数为基本类型，因null无法转化为基本类型，则抛出对应异常信息
			else if (paramType.isPrimitive()) {
				throw new IllegalStateException("Optional " + paramType.getSimpleName() + " parameter '" + name +
						"' is present but cannot be translated into a null value due to being declared as a " +
						"primitive type. Consider declaring it as object wrapper for the corresponding primitive type.");
			}
		}
		//返回默认值
		return value;
	}

	/**
	 * Invoked after a value is resolved.
	 * @param arg the resolved argument value
	 * @param name the argument name
	 * @param parameter the argument parameter type
	 * @param mavContainer the {@link ModelAndViewContainer} (may be {@code null})
	 * @param webRequest the current request
	 */
	protected void handleResolvedValue(@Nullable Object arg, String name, MethodParameter parameter,
			@Nullable ModelAndViewContainer mavContainer, NativeWebRequest webRequest) {
	}


	/**
	 * Represents the information about a named value, including name, whether it's required and a default value.
	 */
	protected static class NamedValueInfo {
		/**名字*/
		private final String name;
		/**是否必填*/
		private final boolean required;
		/**默认值*/
		@Nullable
		private final String defaultValue;

		public NamedValueInfo(String name, boolean required, @Nullable String defaultValue) {
			this.name = name;
			this.required = required;
			this.defaultValue = defaultValue;
		}
	}

}
