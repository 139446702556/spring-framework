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

package org.springframework.web.method.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Handles method return values by delegating to a list of registered {@link HandlerMethodReturnValueHandler HandlerMethodReturnValueHandlers}.
 * Previously resolved return types are cached for faster lookups.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public class HandlerMethodReturnValueHandlerComposite implements HandlerMethodReturnValueHandler {

	protected final Log logger = LogFactory.getLog(getClass());
	/**用于存储所有注册了的HandlerMethodReturnValueHandler对象*/
	private final List<HandlerMethodReturnValueHandler> returnValueHandlers = new ArrayList<>();


	/**
	 * Return a read-only list with the registered handlers, or an empty list.
	 */
	public List<HandlerMethodReturnValueHandler> getHandlers() {
		return Collections.unmodifiableList(this.returnValueHandlers);
	}

	/**
	 * Whether the given {@linkplain MethodParameter method return type} is supported by any registered
	 * {@link HandlerMethodReturnValueHandler}.
	 * 此处调用获取ReturnValueHandler处理器的方法，如果可以获取的到，则表示当前注册的处理器有支持处理当前返回值的
	 */
	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		return getReturnValueHandler(returnType) != null;
	}
	/**
	 * 获取方法返回值对应的HandlerMethodReturnValueHandler对象
	 * 思考：此处可以添加缓存来减少每次使用时查询所需要的消耗，为什么此处没有添加
	 */
	@Nullable
	private HandlerMethodReturnValueHandler getReturnValueHandler(MethodParameter returnType) {
		//遍历所有注册了的returnValueHandlers集合，判断是否支持当前返回值的处理
		for (HandlerMethodReturnValueHandler handler : this.returnValueHandlers) {
			//如果支持，则返回当前处理器
			if (handler.supportsReturnType(returnType)) {
				return handler;
			}
		}
		//如果注册的处理器中未有支持处理当前方法返回值的处理器，则返回null
		return null;
	}

	/**
	 * Iterate over registered {@link HandlerMethodReturnValueHandler HandlerMethodReturnValueHandlers} and invoke the one that supports it.
	 * @throws IllegalStateException if no suitable {@link HandlerMethodReturnValueHandler} is found.
	 * 处理返回值
	 */
	@Override
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
			ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {
		//查找支持处理当前返回值的HandlerMethodReturnValueHandler处理器对象
		HandlerMethodReturnValueHandler handler = selectHandler(returnValue, returnType);
		//如果未找到合适的处理器，则抛出异常，表示当前返回值无法处理
		if (handler == null) {
			throw new IllegalArgumentException("Unknown return value type: " + returnType.getParameterType().getName());
		}
		//找到了，则使用匹配的处理器来处理给定的返回值对象
		handler.handleReturnValue(returnValue, returnType, mavContainer, webRequest);
	}
	/**在当前上下文中注册的HandlerMethodReturnValueHandler对象集合中，查找支持解析当前给定返回值的处理器对象*/
	@Nullable
	private HandlerMethodReturnValueHandler selectHandler(@Nullable Object value, MethodParameter returnType) {
		//判断是否为异步返回值
		boolean isAsyncValue = isAsyncReturnValue(value, returnType);
		//遍历returnValueHandlers集合，逐个判断处理器是否支持处理给定返回值
		for (HandlerMethodReturnValueHandler handler : this.returnValueHandlers) {
			//如果是异步返回值类型，则必须要满足是AsyncHandlerMethodReturnValueHandler类型的处理器
			if (isAsyncValue && !(handler instanceof AsyncHandlerMethodReturnValueHandler)) {
				continue;
			}
			//如果当前处理器支持处理给定的返回值，则返回
			if (handler.supportsReturnType(returnType)) {
				return handler;
			}
		}
		//如果没有找到，则返回null
		return null;
	}
	/**判断给定返回值是否为异步返回值*/
	private boolean isAsyncReturnValue(@Nullable Object value, MethodParameter returnType) {
		//遍历迭代所有注册的returnValueHandlers集合
		for (HandlerMethodReturnValueHandler handler : this.returnValueHandlers) {
			//如果存在处理器为AsyncHandlerMethodReturnValueHandler类型的处理器，并且返回值为异步类型，则返回true（是）
			if (handler instanceof AsyncHandlerMethodReturnValueHandler &&
					((AsyncHandlerMethodReturnValueHandler) handler).isAsyncReturnValue(value, returnType)) {
				return true;
			}
		}
		//如果没有匹配的，则返回false
		return false;
	}

	/**
	 * Add the given {@link HandlerMethodReturnValueHandler}.
	 */
	public HandlerMethodReturnValueHandlerComposite addHandler(HandlerMethodReturnValueHandler handler) {
		this.returnValueHandlers.add(handler);
		return this;
	}

	/**
	 * Add the given {@link HandlerMethodReturnValueHandler HandlerMethodReturnValueHandlers}.
	 */
	public HandlerMethodReturnValueHandlerComposite addHandlers(
			@Nullable List<? extends HandlerMethodReturnValueHandler> handlers) {

		if (handlers != null) {
			this.returnValueHandlers.addAll(handlers);
		}
		return this;
	}

}
