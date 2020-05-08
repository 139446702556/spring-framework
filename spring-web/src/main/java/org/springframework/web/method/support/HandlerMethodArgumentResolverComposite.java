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

package org.springframework.web.method.support;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Resolves method parameters by delegating to a list of registered
 * {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}.
 * Previously resolved method parameters are cached for faster lookups.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public class HandlerMethodArgumentResolverComposite implements HandlerMethodArgumentResolver {

	@Deprecated
	protected final Log logger = LogFactory.getLog(getClass());
	/**HandlerMethodArgumentResolver集合*/
	private final List<HandlerMethodArgumentResolver> argumentResolvers = new LinkedList<>();
	/**MethodParameter与HandlerMethodArgumentResolver的映射关系的缓存，用于后续解析某个方法参数时使用，避免了重复的循环查找*/
	private final Map<MethodParameter, HandlerMethodArgumentResolver> argumentResolverCache =
			new ConcurrentHashMap<>(256);


	/**
	 * Add the given {@link HandlerMethodArgumentResolver}.
	 */
	public HandlerMethodArgumentResolverComposite addResolver(HandlerMethodArgumentResolver resolver) {
		this.argumentResolvers.add(resolver);
		return this;
	}

	/**
	 * Add the given {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}.
	 * @since 4.3
	 */
	public HandlerMethodArgumentResolverComposite addResolvers(
			@Nullable HandlerMethodArgumentResolver... resolvers) {

		if (resolvers != null) {
			Collections.addAll(this.argumentResolvers, resolvers);
		}
		return this;
	}

	/**
	 * Add the given {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}.
	 * 添加给定的处理器方法参数解析器到argumentResolvers中
	 */
	public HandlerMethodArgumentResolverComposite addResolvers(
			@Nullable List<? extends HandlerMethodArgumentResolver> resolvers) {

		if (resolvers != null) {
			this.argumentResolvers.addAll(resolvers);
		}
		return this;
	}

	/**
	 * Return a read-only list with the contained resolvers, or an empty list.
	 */
	public List<HandlerMethodArgumentResolver> getResolvers() {
		return Collections.unmodifiableList(this.argumentResolvers);
	}

	/**
	 * Clear the list of configured resolvers.
	 * @since 4.3
	 */
	public void clear() {
		this.argumentResolvers.clear();
	}


	/**
	 * Whether the given {@linkplain MethodParameter method parameter} is
	 * supported by any registered {@link HandlerMethodArgumentResolver}.
	 * 使用给定的MethodParameter对象在当前上下文中如果可以获取到对应的HandlerMethodArgumentResolver处理器，则说明支持
	 */
	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return getArgumentResolver(parameter) != null;
	}

	/**
	 * Iterate over registered
	 * {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}
	 * and invoke the one that supports it.
	 * 解析给定方法参数的值
	 * @throws IllegalArgumentException if no suitable argument resolver is found
	 */
	@Override
	@Nullable
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {
		//获取给定的方法参数对应的解析器对象
		HandlerMethodArgumentResolver resolver = getArgumentResolver(parameter);
		//如果当前上下文中没有可以解析当前参数的解析器，则抛出异常
		if (resolver == null) {
			throw new IllegalArgumentException("Unsupported parameter type [" +
					parameter.getParameterType().getName() + "]. supportsParameter should be called first.");
		}
		//如果存在可使用的解析器对象，则使用其对给定MethodParameter进行开始解析
		return resolver.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
	}

	/**
	 * Find a registered {@link HandlerMethodArgumentResolver} that supports
	 * the given method parameter.
	 * 从当前上下文已经注册的缓存中查找给定方法参数对应的HandlerMethodArgumentResolver对象
	 */
	@Nullable
	private HandlerMethodArgumentResolver getArgumentResolver(MethodParameter parameter) {
		//优先从缓存中查找，使用给定的方法参数对象从缓存中查找其对应的HandlerMethodArgumentResolver对象
		HandlerMethodArgumentResolver result = this.argumentResolverCache.get(parameter);
		//如果缓存中不存在，则遍历所有已经注册的方法参数解析器对象，找到合适的并存入缓存
		if (result == null) {
			//遍历所有注册的argumentResolver对象
			for (HandlerMethodArgumentResolver resolver : this.argumentResolvers) {
				//判断当前参数解析器是否支持解析当前给定的方法参数，支持则进入
				if (resolver.supportsParameter(parameter)) {
					//获取当前解析器对象
					result = resolver;
					//将当前给定的MethodParameter与匹配的HandlerMethodArgumentResolver对象添加到上下文的缓存中
					this.argumentResolverCache.put(parameter, result);
					//查找成功，离开循环
					break;
				}
			}
		}
		//返回找到的HandlerMethodArgumentResolver对象
		return result;
	}

}
