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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.multipart.support.MultipartResolutionDelegate;

/**
 * Resolves {@link Map} method arguments annotated with an @{@link RequestParam}
 * where the annotation does not specify a request parameter name.
 *
 * <p>The created {@link Map} contains all request parameter name/value pairs,
 * or all multipart files for a given parameter name if specifically declared
 * with {@link MultipartFile} as the value type. If the method parameter type is
 * {@link MultiValueMap} instead, the created map contains all request parameters
 * and all their values for cases where request parameters have multiple values
 * (or multiple multipart files of the same name).
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 * @see RequestParamMethodArgumentResolver
 * @see HttpServletRequest#getParameterMap()
 * @see MultipartRequest#getMultiFileMap()
 * @see MultipartRequest#getFileMap()
 * 用来处理@RequestParam注解，但是注解上无name属性的Map类型的参数的RequestParamMethodArgumentResolver的实现类
 * 此类解析参数时是将全部的请求参数添加到Map中，而RequestParamMethodArgumentResolver类它是将注解中name属性指定的参数添加到Map中
 */
public class RequestParamMapMethodArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		//获取当前方法参数上设置的RequestParam注解对象
		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		//如果当前参数类型为Map类型，并且参数上设置了@RequestParam注解，此注解没有设置name值；则返回true（支持解析当前参数）
		return (requestParam != null && Map.class.isAssignableFrom(parameter.getParameterType()) &&
				!StringUtils.hasText(requestParam.name()));
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {
		//解析当前给定参数的类型
		ResolvableType resolvableType = ResolvableType.forMethodParameter(parameter);
		//处理MultiValueMap类型的参数
		if (MultiValueMap.class.isAssignableFrom(parameter.getParameterType())) {
			// MultiValueMap
			//解析得到当前参数对应的值的实际类型
			Class<?> valueType = resolvableType.as(MultiValueMap.class).getGeneric(1).resolve();
			//如果是MultipartFile类型，解析MultipartRequest，并获得其传递参数，如果请求解析失败，则返回空集合
			if (valueType == MultipartFile.class) {
				MultipartRequest multipartRequest = MultipartResolutionDelegate.resolveMultipartRequest(webRequest);
				return (multipartRequest != null ? multipartRequest.getMultiFileMap() : new LinkedMultiValueMap<>(0));
			}
			//Part类型
			else if (valueType == Part.class) {
				//将当前请求转化为HttpServletRequest类型
				HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
				//如果当前请求为MultipartRequest类型，则解析其中的全部Part参数，构成LinkedMultiValueMap容器并返回
				if (servletRequest != null && MultipartResolutionDelegate.isMultipartRequest(servletRequest)) {
					Collection<Part> parts = servletRequest.getParts();
					LinkedMultiValueMap<String, Part> result = new LinkedMultiValueMap<>(parts.size());
					for (Part part : parts) {
						result.add(part.getName(), part);
					}
					return result;
				}
				return new LinkedMultiValueMap<>(0);
			}
			//普通类型，直接获取请求传递的全部参数，并将其添加到map容器中返回
			else {
				Map<String, String[]> parameterMap = webRequest.getParameterMap();
				MultiValueMap<String, String> result = new LinkedMultiValueMap<>(parameterMap.size());
				parameterMap.forEach((key, values) -> {
					for (String value : values) {
						result.add(key, value);
					}
				});
				return result;
			}
		}
		//普通map类型的处理
		else {
			// Regular Map
			//下面用于处理普通的map类型的请求参数，分为MultipartFile、Part和普通类型三种，逻辑与上述一致
			Class<?> valueType = resolvableType.asMap().getGeneric(1).resolve();
			if (valueType == MultipartFile.class) {
				MultipartRequest multipartRequest = MultipartResolutionDelegate.resolveMultipartRequest(webRequest);
				return (multipartRequest != null ? multipartRequest.getFileMap() : new LinkedHashMap<>(0));
			}
			else if (valueType == Part.class) {
				HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
				if (servletRequest != null && MultipartResolutionDelegate.isMultipartRequest(servletRequest)) {
					Collection<Part> parts = servletRequest.getParts();
					LinkedHashMap<String, Part> result = new LinkedHashMap<>(parts.size());
					for (Part part : parts) {
						if (!result.containsKey(part.getName())) {
							result.put(part.getName(), part);
						}
					}
					return result;
				}
				return new LinkedHashMap<>(0);
			}
			else {
				Map<String, String[]> parameterMap = webRequest.getParameterMap();
				Map<String, String> result = new LinkedHashMap<>(parameterMap.size());
				parameterMap.forEach((key, values) -> {
					if (values.length > 0) {
						result.put(key, values[0]);
					}
				});
				return result;
			}
		}
	}

}
