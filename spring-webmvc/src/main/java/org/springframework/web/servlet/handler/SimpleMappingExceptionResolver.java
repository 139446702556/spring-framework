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

package org.springframework.web.servlet.handler;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.WebUtils;

/**
 * {@link org.springframework.web.servlet.HandlerExceptionResolver} implementation
 * that allows for mapping exception class names to view names, either for a set of
 * given handlers or for all handlers in the DispatcherServlet.
 *
 * <p>Error views are analogous to error page JSPs, but can be used with any kind of
 * exception including any checked one, with fine-granular mappings for specific handlers.
 *
 * @author Juergen Hoeller
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @since 22.11.2003
 * @see org.springframework.web.servlet.DispatcherServlet
 * 此类是spring mvc提供的一个简易匹配的异常处理方式，可以通过xml中进行配置
 */
public class SimpleMappingExceptionResolver extends AbstractHandlerExceptionResolver {

	/** The default name of the exception attribute: "exception". 默认的异常属性名称 */
	public static final String DEFAULT_EXCEPTION_ATTRIBUTE = "exception";

	/**异常的视图映射  key：异常的全类名  value：视图名*/
	@Nullable
	private Properties exceptionMappings;
	/**排除的异常数组*/
	@Nullable
	private Class<?>[] excludedExceptions;
	/**默认的错误视图*/
	@Nullable
	private String defaultErrorView;
	/**默认状态码*/
	@Nullable
	private Integer defaultStatusCode;
	/**状态码的映射  key：视图名 value：状态码*/
	private Map<String, Integer> statusCodes = new HashMap<>();
	/**异常设置到ModelAndView中的属性名*/
	@Nullable
	private String exceptionAttribute = DEFAULT_EXCEPTION_ATTRIBUTE;


	/**
	 * Set the mappings between exception class names and error view names.
	 * The exception class name can be a substring, with no wildcard support at present.
	 * A value of "ServletException" would match {@code javax.servlet.ServletException}
	 * and subclasses, for example.
	 * <p><b>NB:</b> Consider carefully how
	 * specific the pattern is, and whether to include package information (which isn't mandatory).
	 * For example, "Exception" will match nearly anything, and will probably hide other rules.
	 * "java.lang.Exception" would be correct if "Exception" was meant to define a rule for all
	 * checked exceptions. With more unusual exception names such as "BaseBusinessException"
	 * there's no need to use a FQN.
	 * @param mappings exception patterns (can also be fully qualified class names) as keys,
	 * and error view names as values
	 */
	public void setExceptionMappings(Properties mappings) {
		this.exceptionMappings = mappings;
	}

	/**
	 * Set one or more exceptions to be excluded from the exception mappings.
	 * Excluded exceptions are checked first and if one of them equals the actual
	 * exception, the exception will remain unresolved.
	 * @param excludedExceptions one or more excluded exception types
	 */
	public void setExcludedExceptions(Class<?>... excludedExceptions) {
		this.excludedExceptions = excludedExceptions;
	}

	/**
	 * Set the name of the default error view.
	 * This view will be returned if no specific mapping was found.
	 * <p>Default is none.
	 */
	public void setDefaultErrorView(String defaultErrorView) {
		this.defaultErrorView = defaultErrorView;
	}

	/**
	 * Set the HTTP status code that this exception resolver will apply for a given
	 * resolved error view. Keys are view names; values are status codes.
	 * <p>Note that this error code will only get applied in case of a top-level request.
	 * It will not be set for an include request, since the HTTP status cannot be modified
	 * from within an include.
	 * <p>If not specified, the default status code will be applied.
	 * @see #setDefaultStatusCode(int)
	 */
	public void setStatusCodes(Properties statusCodes) {
		for (Enumeration<?> enumeration = statusCodes.propertyNames(); enumeration.hasMoreElements();) {
			String viewName = (String) enumeration.nextElement();
			Integer statusCode = Integer.valueOf(statusCodes.getProperty(viewName));
			this.statusCodes.put(viewName, statusCode);
		}
	}

	/**
	 * An alternative to {@link #setStatusCodes(Properties)} for use with
	 * Java-based configuration.
	 */
	public void addStatusCode(String viewName, int statusCode) {
		this.statusCodes.put(viewName, statusCode);
	}

	/**
	 * Returns the HTTP status codes provided via {@link #setStatusCodes(Properties)}.
	 * Keys are view names; values are status codes.
	 */
	public Map<String, Integer> getStatusCodesAsMap() {
		return Collections.unmodifiableMap(this.statusCodes);
	}

	/**
	 * Set the default HTTP status code that this exception resolver will apply
	 * if it resolves an error view and if there is no status code mapping defined.
	 * <p>Note that this error code will only get applied in case of a top-level request.
	 * It will not be set for an include request, since the HTTP status cannot be modified
	 * from within an include.
	 * <p>If not specified, no status code will be applied, either leaving this to the
	 * controller or view, or keeping the servlet engine's default of 200 (OK).
	 * @param defaultStatusCode the HTTP status code value, for example 500
	 * ({@link HttpServletResponse#SC_INTERNAL_SERVER_ERROR}) or 404 ({@link HttpServletResponse#SC_NOT_FOUND})
	 * @see #setStatusCodes(Properties)
	 */
	public void setDefaultStatusCode(int defaultStatusCode) {
		this.defaultStatusCode = defaultStatusCode;
	}

	/**
	 * Set the name of the model attribute as which the exception should be exposed.
	 * Default is "exception".
	 * <p>This can be either set to a different attribute name or to {@code null}
	 * for not exposing an exception attribute at all.
	 * @see #DEFAULT_EXCEPTION_ATTRIBUTE
	 */
	public void setExceptionAttribute(@Nullable String exceptionAttribute) {
		this.exceptionAttribute = exceptionAttribute;
	}


	/**
	 * Actually resolve the given exception that got thrown during on handler execution,
	 * returning a ModelAndView that represents a specific error page if appropriate.
	 * <p>May be overridden in subclasses, in order to apply specific exception checks.
	 * Note that this template method will be invoked <i>after</i> checking whether this
	 * resolved applies ("mappedHandlers" etc), so an implementation may simply proceed
	 * with its actual exception handling.
	 * @param request current HTTP request
	 * @param response current HTTP response
	 * @param handler the executed handler, or {@code null} if none chosen at the time
	 * of the exception (for example, if multipart resolution failed)
	 * @param ex the exception that got thrown during handler execution
	 * @return a corresponding {@code ModelAndView} to forward to,
	 * or {@code null} for default processing in the resolution chain
	 */
	@Override
	@Nullable
	protected ModelAndView doResolveException(
			HttpServletRequest request, HttpServletResponse response, @Nullable Object handler, Exception ex) {

		// Expose ModelAndView for chosen error view.
		//获取异常对应的视图名称
		String viewName = determineViewName(ex, request);
		//如果viewName不为空
		if (viewName != null) {
			// Apply HTTP status code for error views, if specified.
			// Only apply it if we're processing a top-level request.
			//获取视图对应的状态码
			Integer statusCode = determineStatusCode(request, viewName);
			//如果状态码不为空，则设置状态码到响应中
			if (statusCode != null) {
				applyStatusCodeIfPossible(request, response, statusCode);
			}
			//通过当前请求、异常和视图名称创建ModelAndView对象，并返回
			return getModelAndView(viewName, ex, request);
		}
		//如果异常对应的视图名称为空，则直接返回null
		else {
			return null;
		}
	}

	/**
	 * Determine the view name for the given exception, first checking against the
	 * {@link #setExcludedExceptions(Class[]) "excludedExecptions"}, then searching the
	 * {@link #setExceptionMappings "exceptionMappings"}, and finally using the
	 * {@link #setDefaultErrorView "defaultErrorView"} as a fallback.
	 * @param ex the exception that got thrown during handler execution
	 * @param request current HTTP request (useful for obtaining metadata)
	 * @return the resolved view name, or {@code null} if excluded or none found
	 * 解析异常获取异常对应的视图名称
	 */
	@Nullable
	protected String determineViewName(Exception ex, HttpServletRequest request) {
		//异常对应的视图名称
		String viewName = null;
		//如果当前上下文中设置了要排除的异常
		if (this.excludedExceptions != null) {
			//迭代excludedExceptions数组，判断如果当前的异常是要排除的异常，则直接返回null
			for (Class<?> excludedEx : this.excludedExceptions) {
				if (excludedEx.equals(ex.getClass())) {
					return null;
				}
			}
		}
		// Check for specific exception mappings.
		//如果exceptionMappings不为空，则从中获取到与当前异常匹配的视图
		if (this.exceptionMappings != null) {
			viewName = findMatchingViewName(this.exceptionMappings, ex);
		}
		// Return default error view else, if defined.
		//如果没有获取到异常对应的视图，并且设置了默认的错误视图，则使用默认的错误视图
		if (viewName == null && this.defaultErrorView != null) {
			if (logger.isDebugEnabled()) {
				logger.debug("Resolving to default view '" + this.defaultErrorView + "'");
			}
			viewName = this.defaultErrorView;
		}
		return viewName;
	}

	/**
	 * Find a matching view name in the given exception mappings.
	 * 从给定的异常映射表中获取到与给定异常匹配的视图名称
	 * @param exceptionMappings mappings between exception class names and error view names
	 * @param ex the exception that got thrown during handler execution
	 * @return the view name, or {@code null} if none found
	 * @see #setExceptionMappings
	 */
	@Nullable
	protected String findMatchingViewName(Properties exceptionMappings, Exception ex) {
		//视图名称
		String viewName = null;
		//当前使用的异常全类名
		String dominantMapping = null;
		//当前所使用的异常全类名的层级
		int deepest = Integer.MAX_VALUE;
		//遍历exceptionMappings数组，寻找最匹配的视图名称
		for (Enumeration<?> names = exceptionMappings.propertyNames(); names.hasMoreElements();) {
			//获取异常全类名
			String exceptionMapping = (String) names.nextElement();
			//获取层级
			int depth = getDepth(exceptionMapping, ex);
			//如果当前异常全类名对应给定的异常层级更低，则使用它
			if (depth >= 0 && (depth < deepest || (depth == deepest &&
					dominantMapping != null && exceptionMapping.length() > dominantMapping.length()))) {
				deepest = depth;
				dominantMapping = exceptionMapping;
				viewName = exceptionMappings.getProperty(exceptionMapping);
			}
		}
		//如果viewName不为空，则记录日志，并返回viewName对象
		if (viewName != null && logger.isDebugEnabled()) {
			logger.debug("Resolving to view '" + viewName + "' based on mapping [" + dominantMapping + "]");
		}
		return viewName;
	}

	/**
	 * Return the depth to the superclass matching.
	 * <p>0 means ex matches exactly. Returns -1 if there's no match.
	 * Otherwise, returns depth. Lowest depth wins.
	 */
	protected int getDepth(String exceptionMapping, Exception ex) {
		return getDepth(exceptionMapping, ex.getClass(), 0);
	}

	private int getDepth(String exceptionMapping, Class<?> exceptionClass, int depth) {
		//匹配上了，则返回当前depth层级值
		if (exceptionClass.getName().contains(exceptionMapping)) {
			// Found it!
			return depth;
		}
		// If we've gone as far as we can go and haven't found it...
		//如果未匹配上，则返回-1
		if (exceptionClass == Throwable.class) {
			return -1;
		}
		//递归调用，继续匹配给定exceptionMapping全类名和给定异常的类型的父类（层级递增）
		return getDepth(exceptionMapping, exceptionClass.getSuperclass(), depth + 1);
	}

	/**
	 * Determine the HTTP status code to apply for the given error view.
	 * <p>The default implementation returns the status code for the given view name (specified through the
	 * {@link #setStatusCodes(Properties) statusCodes} property), or falls back to the
	 * {@link #setDefaultStatusCode defaultStatusCode} if there is no match.
	 * <p>Override this in a custom subclass to customize this behavior.
	 * @param request current HTTP request
	 * @param viewName the name of the error view
	 * @return the HTTP status code to use, or {@code null} for the servlet container's default
	 * (200 in case of a standard error view)
	 * @see #setDefaultStatusCode
	 * @see #applyStatusCodeIfPossible
	 * 获得视图对应的状态码
	 */
	@Nullable
	protected Integer determineStatusCode(HttpServletRequest request, String viewName) {
		//从statusCodes缓存中获取viewName对应的状态码，并返回
		if (this.statusCodes.containsKey(viewName)) {
			return this.statusCodes.get(viewName);
		}
		//如果获取不到，则使用默认的状态码
		return this.defaultStatusCode;
	}

	/**
	 * Apply the specified HTTP status code to the given response, if possible (that is,
	 * if not executing within an include request).
	 * @param request current HTTP request
	 * @param response current HTTP response
	 * @param statusCode the status code to apply
	 * @see #determineStatusCode
	 * @see #setDefaultStatusCode
	 * @see HttpServletResponse#setStatus
	 * 设置状态码到响应中
	 */
	protected void applyStatusCodeIfPossible(HttpServletRequest request, HttpServletResponse response, int statusCode) {
		//如果给定的请求对应不包含请求，即是顶部传递过来的请求对象
		if (!WebUtils.isIncludeRequest(request)) {
			//记录日志
			if (logger.isDebugEnabled()) {
				logger.debug("Applying HTTP status " + statusCode);
			}
			//设置状态码到响应中
			response.setStatus(statusCode);
			//设置错误状态码到请求的ERROR_STATUS_CODE_ATTRIBUTE属性中
			request.setAttribute(WebUtils.ERROR_STATUS_CODE_ATTRIBUTE, statusCode);
		}
	}

	/**
	 * Return a ModelAndView for the given request, view name and exception.
	 * <p>The default implementation delegates to {@link #getModelAndView(String, Exception)}.
	 * @param viewName the name of the error view
	 * @param ex the exception that got thrown during handler execution
	 * @param request current HTTP request (useful for obtaining metadata)
	 * @return the ModelAndView instance
	 */
	protected ModelAndView getModelAndView(String viewName, Exception ex, HttpServletRequest request) {
		return getModelAndView(viewName, ex);
	}

	/**
	 * Return a ModelAndView for the given view name and exception.
	 * 使用给定的视图名称和异常创建ModelAndView对象
	 * <p>The default implementation adds the specified exception attribute.
	 * Can be overridden in subclasses.
	 * @param viewName the name of the error view
	 * @param ex the exception that got thrown during handler execution
	 * @return the ModelAndView instance
	 * @see #setExceptionAttribute
	 */
	protected ModelAndView getModelAndView(String viewName, Exception ex) {
		//使用给定的视图名称创建ModelAndView对象
		ModelAndView mv = new ModelAndView(viewName);
		//如果exceptionAttribute不为空，则将其和给定异常设置到mv中
		if (this.exceptionAttribute != null) {
			mv.addObject(this.exceptionAttribute, ex);
		}
		//返回mv
		return mv;
	}

}
