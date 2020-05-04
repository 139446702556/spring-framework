/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.web.servlet.mvc;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.lang.Nullable;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.ModelAndView;

/**
 * Adapter to use the plain {@link org.springframework.web.HttpRequestHandler}
 * interface with the generic {@link org.springframework.web.servlet.DispatcherServlet}.
 * Supports handlers that implement the {@link LastModified} interface.
 *
 * <p>This is an SPI class, not used directly by application code.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see org.springframework.web.servlet.DispatcherServlet
 * @see org.springframework.web.HttpRequestHandler
 * @see LastModified
 * @see SimpleControllerHandlerAdapter
 * 此类为基于HttpRequestHandler接口的HandlerAdapter接口的实现类
 */
public class HttpRequestHandlerAdapter implements HandlerAdapter {

	@Override
	public boolean supports(Object handler) {
		//判断给定的handler处理器是否为HttpRequestHandler类型
		return (handler instanceof HttpRequestHandler);
	}

	@Override
	@Nullable
	public ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		//执行handler中实现的HttpRequestHandler接口中的handleRequest方法
		((HttpRequestHandler) handler).handleRequest(request, response);
		return null;
	}

	@Override
	public long getLastModified(HttpServletRequest request, Object handler) {
		//给定的处理器对象类型实现了LastModified接口，则使用对应方法进行获取并返回
		if (handler instanceof LastModified) {
			return ((LastModified) handler).getLastModified(request);
		}
		//否则，默认返回-1
		return -1L;
	}

}
