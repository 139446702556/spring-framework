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

package org.springframework.web.servlet.handler;

import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * A {@link HandlerExceptionResolver} that delegates to a list of other
 * {@link HandlerExceptionResolver HandlerExceptionResolvers}.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 * 复合的HandlerExceptionResolver实现类
 */
public class HandlerExceptionResolverComposite implements HandlerExceptionResolver, Ordered {
	/**处理器异常解析器集合*/
	@Nullable
	private List<HandlerExceptionResolver> resolvers;
	/**排序的优先级  最低*/
	private int order = Ordered.LOWEST_PRECEDENCE;


	/**
	 * Set the list of exception resolvers to delegate to.
	 */
	public void setExceptionResolvers(List<HandlerExceptionResolver> exceptionResolvers) {
		this.resolvers = exceptionResolvers;
	}

	/**
	 * Return the list of exception resolvers to delegate to.
	 */
	public List<HandlerExceptionResolver> getExceptionResolvers() {
		return (this.resolvers != null ? Collections.unmodifiableList(this.resolvers) : Collections.emptyList());
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}


	/**
	 * Resolve the exception by iterating over the list of configured exception resolvers.
	 * 通过迭代配置的HandlerExceptionResolver集合，来对给定的异常进行解析
	 * <p>The first one to return a {@link ModelAndView} wins. Otherwise {@code null} is returned.
	 */
	@Override
	@Nullable
	public ModelAndView resolveException(
			HttpServletRequest request, HttpServletResponse response, @Nullable Object handler, Exception ex) {
		//如果配置的HandlerExceptionResolver集合不为空
		if (this.resolvers != null) {
			//迭代注册的HandlerExceptionResolver集合，逐个去处理给定的异常ex，如果处理成功，则直接返回处理得到的ModelAndView结果
			//此处为按照注册的解析器的顺序，来找到第一个可以解析给定异常的HandlerExceptionResolver解析器，并返回解析结果
			for (HandlerExceptionResolver handlerExceptionResolver : this.resolvers) {
				//解析异常
				ModelAndView mav = handlerExceptionResolver.resolveException(request, response, handler, ex);
				//如果解析成功，则返回解析得到的ModelAndView对象
				if (mav != null) {
					return mav;
				}
			}
		}
		//如果未注册任何解析器，或者给定异常不能解析，则返回null
		return null;
	}

}
