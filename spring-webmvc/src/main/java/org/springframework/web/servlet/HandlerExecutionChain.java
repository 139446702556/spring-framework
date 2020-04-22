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

package org.springframework.web.servlet;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

/**
 * Handler execution chain, consisting of handler object and any handler interceptors.
 * Returned by HandlerMapping's {@link HandlerMapping#getHandler} method.
 * 处理程序执行链（用于包含处理当前请求的处理器和拦截器们）
 * @author Juergen Hoeller
 * @since 20.06.2003
 * @see HandlerInterceptor
 */
public class HandlerExecutionChain {

	private static final Log logger = LogFactory.getLog(HandlerExecutionChain.class);
	/**处理器*/
	private final Object handler;
	/**拦截器数组*/
	@Nullable
	private HandlerInterceptor[] interceptors;
	/**拦截器数组，在实际使用时，会调用getInterceptors方法，初始化到interceptors数组中*/
	@Nullable
	private List<HandlerInterceptor> interceptorList;
	/**已经执行了的拦截器的preHandle方法的位置；主要用于实现appluPostHandle方法的逻辑*/
	private int interceptorIndex = -1;


	/**
	 * Create a new HandlerExecutionChain.
	 * 创建一个新的HandlerExecutionChain对象
	 * @param handler the handler object to execute
	 */
	public HandlerExecutionChain(Object handler) {
		this(handler, (HandlerInterceptor[]) null);
	}

	/**
	 * Create a new HandlerExecutionChain.
	 * 创建一个新的HandlerExecutionChain对象
	 * @param handler the handler object to execute
	 * @param interceptors the array of interceptors to apply
	 * (in the given order) before the handler itself executes
	 */
	public HandlerExecutionChain(Object handler, @Nullable HandlerInterceptor... interceptors) {
		//如果给定的handler是HandlerExecutionChain类型的
		if (handler instanceof HandlerExecutionChain) {
			//强转
			HandlerExecutionChain originalChain = (HandlerExecutionChain) handler;
			//获取其包装的处理器，并赋值给当前对象
			this.handler = originalChain.getHandler();
			//设置HandlerInterceptor们到当前的处理器执行链中
			this.interceptorList = new ArrayList<>();
			CollectionUtils.mergeArrayIntoCollection(originalChain.getInterceptors(), this.interceptorList);
			CollectionUtils.mergeArrayIntoCollection(interceptors, this.interceptorList);
		}
		else {
			//直接赋值
			this.handler = handler;
			this.interceptors = interceptors;
		}
	}


	/**
	 * Return the handler object to execute.
	 */
	public Object getHandler() {
		return this.handler;
	}
	/**添加拦截器到interceptorList集合中*/
	public void addInterceptor(HandlerInterceptor interceptor) {
		initInterceptorList().add(interceptor);
	}
	/**添加拦截器们到interceptorList中*/
	public void addInterceptors(HandlerInterceptor... interceptors) {
		if (!ObjectUtils.isEmpty(interceptors)) {
			CollectionUtils.mergeArrayIntoCollection(interceptors, initInterceptorList());
		}
	}

	private List<HandlerInterceptor> initInterceptorList() {
		//如果interceptorList集合为null
		if (this.interceptorList == null) {
			//则初始化
			this.interceptorList = new ArrayList<>();
			//如果interceptors数组不为null
			if (this.interceptors != null) {
				// An interceptor array specified through the constructor
				//则将interceptors数组中的拦截器们拷贝到interceptorsList集合中
				CollectionUtils.mergeArrayIntoCollection(this.interceptors, this.interceptorList);
			}
		}
		//清空interceptors数组
		this.interceptors = null;
		//返回interceptorList集合
		return this.interceptorList;
	}

	/**
	 * Return the array of interceptors to apply (in the given order).
	 * @return the array of HandlerInterceptors instances (may be {@code null})
	 */
	@Nullable
	public HandlerInterceptor[] getInterceptors() {
		//如果interceptors数组为空，并且interceptorList集合不为空
		//则将interceptorList集合初始化到interceptions数组中
		if (this.interceptors == null && this.interceptorList != null) {
			this.interceptors = this.interceptorList.toArray(new HandlerInterceptor[0]);
		}
		//返回interceptions数组
		return this.interceptors;
	}


	/**
	 * Apply preHandle methods of registered interceptors.
	 * 应用已注册的拦截器的前置处理方法
	 * @return {@code true} if the execution chain should proceed with the
	 * next interceptor or the handler itself. Else, DispatcherServlet assumes
	 * that this interceptor has already dealt with the response itself.
	 */
	boolean applyPreHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
		//获取应用在当前请求上的拦截器数组
		HandlerInterceptor[] interceptors = getInterceptors();
		//如果存在匹配的拦截器
		if (!ObjectUtils.isEmpty(interceptors)) {
			//遍历所有匹配的拦截器
			for (int i = 0; i < interceptors.length; i++) {
				HandlerInterceptor interceptor = interceptors[i];
				//执行拦截器的前置方法preHandle方法，如果有一个此方法返回false
				if (!interceptor.preHandle(request, response, this.handler)) {
					//逆序遍历前面已经执行成功的拦截器的afterCompletion方法（返回true的拦截器）
					triggerAfterCompletion(request, response, null);
					//结束此请求的后续执行，表示前置处理失败
					return false;
				}
				//记录当前已经执行的拦截器链的索引位置
				this.interceptorIndex = i;
			}
		}
		//返回true，表示前置处理成功
		return true;
	}

	/**
	 * Apply postHandle methods of registered interceptors.
	 * 调用与当前请求匹配的所有注册的拦截器的postHandle方法
	 * 应用拦截器的后置处理
	 */
	void applyPostHandle(HttpServletRequest request, HttpServletResponse response, @Nullable ModelAndView mv)
			throws Exception {
		//获得拦截器数组
		HandlerInterceptor[] interceptors = getInterceptors();
		//如果不为空
		if (!ObjectUtils.isEmpty(interceptors)) {
			//倒序遍历拦截器数组
			for (int i = interceptors.length - 1; i >= 0; i--) {
				HandlerInterceptor interceptor = interceptors[i];
				//执行后置处理
				interceptor.postHandle(request, response, this.handler, mv);
			}
		}
	}

	/**
	 * Trigger afterCompletion callbacks on the mapped HandlerInterceptors.
	 * Will just invoke afterCompletion for all interceptors whose preHandle invocation
	 * has successfully completed and returned true.
	 * 触发拦截器的已完成处理
	 */
	void triggerAfterCompletion(HttpServletRequest request, HttpServletResponse response, @Nullable Exception ex)
			throws Exception {
		//获取与当前请求匹配的拦截器数组
		HandlerInterceptor[] interceptors = getInterceptors();
		//有匹配的拦截器链
		if (!ObjectUtils.isEmpty(interceptors)) {
			//反向遍历已经执行过preHandle前置处理方法成功的拦截器（即和之前的执行逆序执行）
			for (int i = this.interceptorIndex; i >= 0; i--) {
				HandlerInterceptor interceptor = interceptors[i];
				try {
					//执行其中的afterCompletion方法
					interceptor.afterCompletion(request, response, this.handler, ex);
				}
				catch (Throwable ex2) {
					//注意，如果执行失败，仅仅会打印错误日志，不会结束循环
					logger.error("HandlerInterceptor.afterCompletion threw exception", ex2);
				}
			}
		}
	}

	/**
	 * Apply afterConcurrentHandlerStarted callback on mapped AsyncHandlerInterceptors.
	 * 调用注册到容器中和给定请求匹配的AsyncHandlerInterceptors拦截器们的afterConcurrentHandlingStarted方法
	 */
	void applyAfterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response) {
		//获取拦截器数组
		HandlerInterceptor[] interceptors = getInterceptors();
		//如果不为空
		if (!ObjectUtils.isEmpty(interceptors)) {
			//倒序遍历拦截器数组
			for (int i = interceptors.length - 1; i >= 0; i--) {
				//如果当前拦截器对象实现了AsyncHandlerInterceptor接口
				if (interceptors[i] instanceof AsyncHandlerInterceptor) {
					try {
						//强转类型
						AsyncHandlerInterceptor asyncInterceptor = (AsyncHandlerInterceptor) interceptors[i];
						//调用此拦截器实现的AsyncHandlerInterceptor接口的afterConcurrentHandlingStarted方法
						asyncInterceptor.afterConcurrentHandlingStarted(request, response, this.handler);
					}
					catch (Throwable ex) {
						//注意此处执行也只是在发生异常时记录了日志，没有中断循环
						logger.error("Interceptor [" + interceptors[i] + "] failed in afterConcurrentHandlingStarted", ex);
					}
				}
			}
		}
	}


	/**
	 * Delegates to the handler and interceptors' {@code toString()}.
	 */
	@Override
	public String toString() {
		Object handler = getHandler();
		StringBuilder sb = new StringBuilder();
		sb.append("HandlerExecutionChain with [").append(handler).append("] and ");
		if (this.interceptorList != null) {
			sb.append(this.interceptorList.size());
		}
		else if (this.interceptors != null) {
			sb.append(this.interceptors.length);
		}
		else {
			sb.append(0);
		}
		return sb.append(" interceptors").toString();
	}

}
