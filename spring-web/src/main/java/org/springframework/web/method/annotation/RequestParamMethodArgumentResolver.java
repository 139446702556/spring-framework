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

import java.beans.PropertyEditor;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.UriComponentsContributor;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.support.MultipartResolutionDelegate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resolves method arguments annotated with @{@link RequestParam}, arguments of
 * type {@link MultipartFile} in conjunction with Spring's {@link MultipartResolver}
 * abstraction, and arguments of type {@code javax.servlet.http.Part} in conjunction
 * with Servlet 3.0 multipart requests. This resolver can also be created in default
 * resolution mode in which simple types (int, long, etc.) not annotated with
 * {@link RequestParam @RequestParam} are also treated as request parameters with
 * the parameter name derived from the argument name.
 *
 * <p>If the method parameter type is {@link Map}, the name specified in the
 * annotation is used to resolve the request parameter String value. The value is
 * then converted to a {@link Map} via type conversion assuming a suitable
 * {@link Converter} or {@link PropertyEditor} has been registered.
 * Or if a request parameter name is not specified the
 * {@link RequestParamMapMethodArgumentResolver} is used instead to provide
 * access to all request parameters in the form of a map.
 *
 * <p>A {@link WebDataBinder} is invoked to apply type conversion to resolved request
 * header values that don't yet match the method parameter type.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @since 3.1
 * @see RequestParamMapMethodArgumentResolver
 * 此类为请求参数的HandlerMethodArgumentResolver的实现类，处理普通的请求参数（处理@RequestParam注解设置）
 */
public class RequestParamMethodArgumentResolver extends AbstractNamedValueMethodArgumentResolver
		implements UriComponentsContributor {

	private static final TypeDescriptor STRING_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(String.class);
	/**是否使用默认解决*/
	private final boolean useDefaultResolution;


	/**
	 * Create a new {@link RequestParamMethodArgumentResolver} instance.
	 * @param useDefaultResolution in default resolution mode a method argument
	 * that is a simple type, as defined in {@link BeanUtils#isSimpleProperty},
	 * is treated as a request parameter even if it isn't annotated, the
	 * request parameter name is derived from the method parameter name.
	 */
	public RequestParamMethodArgumentResolver(boolean useDefaultResolution) {
		this.useDefaultResolution = useDefaultResolution;
	}

	/**
	 * Create a new {@link RequestParamMethodArgumentResolver} instance.
	 * @param beanFactory a bean factory used for resolving  ${...} placeholder
	 * and #{...} SpEL expressions in default values, or {@code null} if default
	 * values are not expected to contain expressions
	 * @param useDefaultResolution in default resolution mode a method argument
	 * that is a simple type, as defined in {@link BeanUtils#isSimpleProperty},
	 * is treated as a request parameter even if it isn't annotated, the
	 * request parameter name is derived from the method parameter name.
	 */
	public RequestParamMethodArgumentResolver(@Nullable ConfigurableBeanFactory beanFactory,
			boolean useDefaultResolution) {

		super(beanFactory);
		this.useDefaultResolution = useDefaultResolution;
	}


	/**
	 * Supports the following:
	 * <ul>
	 * <li>@RequestParam-annotated method arguments.
	 * This excludes {@link Map} params where the annotation does not specify a name.
	 * See {@link RequestParamMapMethodArgumentResolver} instead for such params.
	 * <li>Arguments of type {@link MultipartFile} unless annotated with @{@link RequestPart}.
	 * <li>Arguments of type {@code Part} unless annotated with @{@link RequestPart}.
	 * <li>In default resolution mode, simple type arguments even if not with @{@link RequestParam}.
	 * </ul>
	 */
	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		//当前参数有@RequestParam注解的情况
		if (parameter.hasParameterAnnotation(RequestParam.class)) {
			//当前的请求参数为Map类型，则@RequestParam注解必须设置name属性
			if (Map.class.isAssignableFrom(parameter.nestedIfOptional().getNestedParameterType())) {
				//获取参数上设置的@RequestParam注解对象
				RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
				//如果参数上存在@RequestParam注解对象，并设置了name属性，则返回true（表示支持此方法解析）
				return (requestParam != null && StringUtils.hasText(requestParam.name()));
			}
			//其它类型，默认支持
			else {
				return true;
			}
		}
		//无@RequestParam注解的情况
		else {
			//如果当前参数上有@RequestPart注解，则直接返回false；即@RequestPart注解的优先级大于@RequestParam注解
			if (parameter.hasParameterAnnotation(RequestPart.class)) {
				return false;
			}
			//如果参数为内嵌参数，则获得其内嵌的值；否则返回当前给定的参数自身
			parameter = parameter.nestedIfOptional();
			//如果当前参数为Multipart参数，则返回true，表示支持
			if (MultipartResolutionDelegate.isMultipartArgument(parameter)) {
				return true;
			}
			//如果开启了useDefaultResolution功能，则判断是否为普通类型
			else if (this.useDefaultResolution) {
				return BeanUtils.isSimpleProperty(parameter.getNestedParameterType());
			}
			//其它类型，不支持
			else {
				return false;
			}
		}
	}

	@Override
	protected NamedValueInfo createNamedValueInfo(MethodParameter parameter) {
		//获取当前参数上的@RequestParam注解对象
		RequestParam ann = parameter.getParameterAnnotation(RequestParam.class);
		//创建并返回NamedValueInfo对象（如果有此注解则使用注解初始化，没有则创建一个默认的RequestParamNamedValueInfo对象）
		//默认的NamedValueInfo对象的name值为空字符串，在使用时会默认使用其参数的名称
		return (ann != null ? new RequestParamNamedValueInfo(ann) : new RequestParamNamedValueInfo());
	}
	/**获得参数名对应的参数值*/
	@Override
	@Nullable
	protected Object resolveName(String name, MethodParameter parameter, NativeWebRequest request) throws Exception {
		//情况一，处理HttpServletRequest情况下的MultipartFile和Part的情况
		//将当前给定请求转换为HttpServletRequest类型的请求对象
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		//如果转换成功
		if (servletRequest != null) {
			//从当前请求中解析给定name对应的参数的值（此值的类型为MultipartFile类型）
			Object mpArg = MultipartResolutionDelegate.resolveMultipartArgument(name, parameter, servletRequest);
			//如果当前给定的参数名称是可以解析的，则直接返回解析得到的参数值对象（mpArg）
			if (mpArg != MultipartResolutionDelegate.UNRESOLVABLE) {
				return mpArg;
			}
		}
		//情况二，处理MultipartRequest情况下的MultipartFile的情况
		//用于存储解析后得到的参数值对象
		Object arg = null;
		//将当前请求转化为MultipartRequest类型请求对象
		MultipartRequest multipartRequest = request.getNativeRequest(MultipartRequest.class);
		//如果转化成功
		if (multipartRequest != null) {
			//从当前请求中获取给定name参数对应的MultipartFile集合对象
			List<MultipartFile> files = multipartRequest.getFiles(name);
			//如果获取到了，则将其添加到arg中
			if (!files.isEmpty()) {
				arg = (files.size() == 1 ? files.get(0) : files);
			}
		}
		//情况三，普通参数的获取
		//如果上述两种情况未获取到参数值
		if (arg == null) {
			//直接从当前请求的普通参数中获取name对应的值
			String[] paramValues = request.getParameterValues(name);
			//如果获取到了参数对应的值，则将其设置到arg中
			if (paramValues != null) {
				arg = (paramValues.length == 1 ? paramValues[0] : paramValues);
			}
		}
		//返回从当前请求中获取到的name参数对应的值
		return arg;
	}
	/**处理当前参数未在给定请求中找到对应匹配值的情况，根据参数类型的不同抛出不同类型的异常*/
	@Override
	protected void handleMissingValue(String name, MethodParameter parameter, NativeWebRequest request)
			throws Exception {
		//将当前给定请求转化为HttpServletRequest类型对象
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		//判断当前给定参数对象是否为MultipartFile类型或者Part类型
		if (MultipartResolutionDelegate.isMultipartArgument(parameter)) {
			//如果当前请求为空，或则当前请求不是发送文件资源的请求，则抛出MultipartException异常，否则其它文件异常抛出MissingServletRequestPartException异常
			if (servletRequest == null || !MultipartResolutionDelegate.isMultipartRequest(servletRequest)) {
				throw new MultipartException("Current request is not a multipart request");
			}
			else {
				throw new MissingServletRequestPartException(name);
			}
		}
		//如果参数是基本类型，则抛出其对应的异常
		else {
			throw new MissingServletRequestParameterException(name,
					parameter.getNestedParameterType().getSimpleName());
		}
	}

	@Override
	public void contributeMethodArgument(MethodParameter parameter, @Nullable Object value,
			UriComponentsBuilder builder, Map<String, Object> uriVariables, ConversionService conversionService) {

		Class<?> paramType = parameter.getNestedParameterType();
		if (Map.class.isAssignableFrom(paramType) || MultipartFile.class == paramType || Part.class == paramType) {
			return;
		}

		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		String name = (requestParam != null && StringUtils.hasLength(requestParam.name()) ?
				requestParam.name() : parameter.getParameterName());
		Assert.state(name != null, "Unresolvable parameter name");

		if (value == null) {
			if (requestParam != null &&
					(!requestParam.required() || !requestParam.defaultValue().equals(ValueConstants.DEFAULT_NONE))) {
				return;
			}
			builder.queryParam(name);
		}
		else if (value instanceof Collection) {
			for (Object element : (Collection<?>) value) {
				element = formatUriValue(conversionService, TypeDescriptor.nested(parameter, 1), element);
				builder.queryParam(name, element);
			}
		}
		else {
			builder.queryParam(name, formatUriValue(conversionService, new TypeDescriptor(parameter), value));
		}
	}

	@Nullable
	protected String formatUriValue(
			@Nullable ConversionService cs, @Nullable TypeDescriptor sourceType, @Nullable Object value) {

		if (value == null) {
			return null;
		}
		else if (value instanceof String) {
			return (String) value;
		}
		else if (cs != null) {
			return (String) cs.convert(value, sourceType, STRING_TYPE_DESCRIPTOR);
		}
		else {
			return value.toString();
		}
	}


	private static class RequestParamNamedValueInfo extends NamedValueInfo {

		public RequestParamNamedValueInfo() {
			super("", false, ValueConstants.DEFAULT_NONE);
		}

		public RequestParamNamedValueInfo(RequestParam annotation) {
			super(annotation.name(), annotation.required(), annotation.defaultValue());
		}
	}

}
