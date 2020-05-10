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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.Conventions;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

/**
 * Resolves method arguments annotated with {@code @RequestBody} and handles return
 * values from methods annotated with {@code @ResponseBody} by reading and writing
 * to the body of the request or response with an {@link HttpMessageConverter}.
 *
 * <p>An {@code @RequestBody} method argument is also validated if it is annotated
 * with {@code @javax.validation.Valid}. In case of validation failure,
 * {@link MethodArgumentNotValidException} is raised and results in an HTTP 400
 * response status code if {@link DefaultHandlerExceptionResolver} is configured.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 * 此类可以处理返回值和参数两种类型的数据，实现了HandlerMethodReturnValueHandler接口和HandlerMethodArgumentResolver接口
 * 用于处理参数被@RequestBody注解标注的或者返回值被@ResponseBody注解标注的请求处理
 * 主要用于前后端分离的场景，来实现restful api接口给前端返回数据
 */
public class RequestResponseBodyMethodProcessor extends AbstractMessageConverterMethodProcessor {

	/**
	 * Basic constructor with converters only. Suitable for resolving
	 * {@code @RequestBody}. For handling {@code @ResponseBody} consider also
	 * providing a {@code ContentNegotiationManager}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters) {
		super(converters);
	}

	/**
	 * Basic constructor with converters and {@code ContentNegotiationManager}.
	 * Suitable for resolving {@code @RequestBody} and handling
	 * {@code @ResponseBody} without {@code Request~} or
	 * {@code ResponseBodyAdvice}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager) {

		super(converters, manager);
	}

	/**
	 * Complete constructor for resolving {@code @RequestBody} method arguments.
	 * For handling {@code @ResponseBody} consider also providing a
	 * {@code ContentNegotiationManager}.
	 * @since 4.2
	 * converters参数：它是HttpMessageConverter转换器的集合，用于把请求方法处理得到的数据转化为
	 * 其它需要的某种格式的数据
	 * requestResponseBodyAdvice参数：其中注册的通知器用于拦截请求的返回值，实现对返回结果的修改
	 * 它一般为ResponseBodyAdvice类型
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, null, requestResponseBodyAdvice);
	}

	/**
	 * Complete constructor for resolving {@code @RequestBody} and handling
	 * {@code @ResponseBody}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager, @Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, manager, requestResponseBodyAdvice);
	}


	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(RequestBody.class);
	}
	/**判断当前给定的MethodParameter对象所对应的方法或者所属类上是否标有@ResponseBody注解，有则支持*/
	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		return (AnnotatedElementUtils.hasAnnotation(returnType.getContainingClass(), ResponseBody.class) ||
				returnType.hasMethodAnnotation(ResponseBody.class));
	}

	/**
	 * Throws MethodArgumentNotValidException if validation fails.
	 * @throws HttpMessageNotReadableException if {@link RequestBody#required()}
	 * is {@code true} and there is no body content or if there is no suitable
	 * converter to read the content with.
	 */
	@Override
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {

		parameter = parameter.nestedIfOptional();
		Object arg = readWithMessageConverters(webRequest, parameter, parameter.getNestedGenericParameterType());
		String name = Conventions.getVariableNameForParameter(parameter);

		if (binderFactory != null) {
			WebDataBinder binder = binderFactory.createBinder(webRequest, arg, name);
			if (arg != null) {
				validateIfApplicable(binder, parameter);
				if (binder.getBindingResult().hasErrors() && isBindExceptionRequired(binder, parameter)) {
					throw new MethodArgumentNotValidException(parameter, binder.getBindingResult());
				}
			}
			if (mavContainer != null) {
				mavContainer.addAttribute(BindingResult.MODEL_KEY_PREFIX + name, binder.getBindingResult());
			}
		}

		return adaptArgumentIfNecessary(arg, parameter);
	}

	@Override
	protected <T> Object readWithMessageConverters(NativeWebRequest webRequest, MethodParameter parameter,
			Type paramType) throws IOException, HttpMediaTypeNotSupportedException, HttpMessageNotReadableException {

		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		Assert.state(servletRequest != null, "No HttpServletRequest");
		ServletServerHttpRequest inputMessage = new ServletServerHttpRequest(servletRequest);

		Object arg = readWithMessageConverters(inputMessage, parameter, paramType);
		if (arg == null && checkRequired(parameter)) {
			throw new HttpMessageNotReadableException("Required request body is missing: " +
					parameter.getExecutable().toGenericString(), inputMessage);
		}
		return arg;
	}

	protected boolean checkRequired(MethodParameter parameter) {
		RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
		return (requestBody != null && requestBody.required() && !parameter.isOptional());
	}

	@Override
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
			ModelAndViewContainer mavContainer, NativeWebRequest webRequest)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {
		//设置标识状态为已处理（true）
		mavContainer.setRequestHandled(true);
		//创建请求和响应对象
		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);
		ServletServerHttpResponse outputMessage = createOutputMessage(webRequest);

		// Try even with null return value. ResponseBodyAdvice could get involved.
		//使用HttpMessageConverter对对象进行转换，并将其写入响应中（此过程中可以使用注册的ResponseBodyAdvice对象对返回值进行修改）
		writeWithMessageConverters(returnValue, returnType, inputMessage, outputMessage);
	}

}
