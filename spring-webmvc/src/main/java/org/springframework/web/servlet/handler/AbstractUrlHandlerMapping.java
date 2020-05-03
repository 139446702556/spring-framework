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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.HandlerExecutionChain;

/**
 * Abstract base class for URL-mapped {@link org.springframework.web.servlet.HandlerMapping}
 * implementations. Provides infrastructure for mapping handlers to URLs and configurable
 * URL lookup. For information on the latter, see "alwaysUseFullPath" property.
 *
 * <p>Supports direct matches, e.g. a registered "/test" matches "/test", and
 * various Ant-style pattern matches, e.g. a registered "/t*" pattern matches
 * both "/test" and "/team", "/test/*" matches all paths in the "/test" directory,
 * "/test/**" matches all paths below "/test". For details, see the
 * {@link org.springframework.util.AntPathMatcher AntPathMatcher} javadoc.
 *
 * <p>Will search all path patterns to find the most exact match for the
 * current request path. The most exact match is defined as the longest
 * path pattern that matches the current request path.
 *
 * 以URL作为handler的HandlerMapping抽象类，提供handler的获取、注册等等通用的骨架方法
 *
 * @author Juergen Hoeller
 * @author Arjen Poutsma
 * @since 16.04.2003
 */
public abstract class AbstractUrlHandlerMapping extends AbstractHandlerMapping implements MatchableHandlerMapping {
	/**根路径的处理器*/
	@Nullable
	private Object rootHandler;
	/**使用后置的 / 匹配*/
	private boolean useTrailingSlashMatch = false;
	/**是否延迟加载处理器   默认，关闭*/
	private boolean lazyInitHandlers = false;
	/**路径和处理器的映射   key：路径  value：处理器对象*/
	private final Map<String, Object> handlerMap = new LinkedHashMap<>();


	/**
	 * Set the root handler for this handler mapping, that is,
	 * the handler to be registered for the root path ("/").
	 * <p>Default is {@code null}, indicating no root handler.
	 */
	public void setRootHandler(@Nullable Object rootHandler) {
		this.rootHandler = rootHandler;
	}

	/**
	 * Return the root handler for this handler mapping (registered for "/"),
	 * or {@code null} if none.
	 */
	@Nullable
	public Object getRootHandler() {
		return this.rootHandler;
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 * If enabled a URL pattern such as "/users" also matches to "/users/".
	 * <p>The default value is {@code false}.
	 */
	public void setUseTrailingSlashMatch(boolean useTrailingSlashMatch) {
		this.useTrailingSlashMatch = useTrailingSlashMatch;
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 * 是否与url匹配，而不考虑尾部是否有斜杠
	 */
	public boolean useTrailingSlashMatch() {
		return this.useTrailingSlashMatch;
	}

	/**
	 * Set whether to lazily initialize handlers. Only applicable to
	 * singleton handlers, as prototypes are always lazily initialized.
	 * Default is "false", as eager initialization allows for more efficiency
	 * through referencing the controller objects directly.
	 * <p>If you want to allow your controllers to be lazily initialized,
	 * make them "lazy-init" and set this flag to true. Just making them
	 * "lazy-init" will not work, as they are initialized through the
	 * references from the handler mapping in this case.
	 */
	public void setLazyInitHandlers(boolean lazyInitHandlers) {
		this.lazyInitHandlers = lazyInitHandlers;
	}

	/**
	 * Look up a handler for the URL path of the given request.
	 * 查找给定请求的url路径对应的handler对象
	 * @param request current HTTP request 当前http请求
	 * @return the handler instance, or {@code null} if none found
	 * 返回的是handler实例对象，如果没有找到，则返回null
	 */
	@Override
	@Nullable
	protected Object getHandlerInternal(HttpServletRequest request) throws Exception {
		//获取当前给定请求对应的url路径
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		//获取对应的处理器
		Object handler = lookupHandler(lookupPath, request);
		//如果没有找到处理器
		if (handler == null) {
			// We need to care for the default handler directly, since we need to
			// expose the PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE for it as well.
			//存储当前要使用的处理器对象
			Object rawHandler = null;
			//如果当前请求的url路径为/，则使用rootHandler对象
			if ("/".equals(lookupPath)) {
				rawHandler = getRootHandler();
			}
			//如果当前无使用的处理器对象，则使用默认的处理器对象，即defaultHandler对象
			if (rawHandler == null) {
				rawHandler = getDefaultHandler();
			}
			//如果从rootHandler或者defaultHandler对象中获取到了处理器信心
			if (rawHandler != null) {
				// Bean name or resolved handler?
				//如果rawHandler是字符串类型，即是beanName，则从当前的应用上下文容器中获取此名称对应的bean对象
				if (rawHandler instanceof String) {
					String handlerName = (String) rawHandler;
					rawHandler = obtainApplicationContext().getBean(handlerName);
				}
				//校验处理器，空方法，目前暂无子类实现此接口
				validateHandler(rawHandler, request);
				//创建处理器（此处创建的为HandlerExecutionChain对象）
				handler = buildPathExposingHandler(rawHandler, lookupPath, lookupPath, null);
			}
		}
		return handler;
	}

	/**
	 * Look up a handler instance for the given URL path.
	 * <p>Supports direct matches, e.g. a registered "/test" matches "/test",
	 * and various Ant-style pattern matches, e.g. a registered "/t*" matches
	 * both "/test" and "/team". For details, see the AntPathMatcher class.
	 * <p>Looks for the most exact pattern, where most exact is defined as
	 * the longest path pattern.
	 * 根据给定的请求路径和请求对象，获得对应的处理器
	 * @param urlPath the URL the bean is mapped to
	 * @param request current HTTP request (to expose the path within the mapping to)
	 * @return the associated handler instance, or {@code null} if not found
	 * @see #exposePathWithinMapping
	 * @see org.springframework.util.AntPathMatcher
	 */
	@Nullable
	protected Object lookupHandler(String urlPath, HttpServletRequest request) throws Exception {
		// Direct match?
		//情况一，使用给定的urlPath从handlerMap中直接匹配处理器
		Object handler = this.handlerMap.get(urlPath);
		//如果获取到了url路径对应的处理器对象
		if (handler != null) {
			// Bean name or resolved handler?
			//判断handler是否为String类型，即是beanName，则从上下文容器中找到其对应的bean对象作为处理器
			if (handler instanceof String) {
				String handlerName = (String) handler;
				handler = obtainApplicationContext().getBean(handlerName);
			}
			//空方法，校验处理器，此方法暂时无子类实现
			validateHandler(handler, request);
			//创建处理器
			return buildPathExposingHandler(handler, urlPath, urlPath, null);
		}

		// Pattern match?
		//情况二，pattern模式匹配合适的，并添加到matchingPatterns集合中
		List<String> matchingPatterns = new ArrayList<>();
		//遍历注册的所有路径
		for (String registeredPattern : this.handlerMap.keySet()) {
			//判断当前的模式路径是否与请求的路径相匹配，如果匹配将其添加到matchingPatterns中
			if (getPathMatcher().match(registeredPattern, urlPath)) {
				matchingPatterns.add(registeredPattern);
			}
			//如果设置为不考虑尾部是否有斜杠来匹配
			else if (useTrailingSlashMatch()) {
				//如果当前路径模式尾部没有斜杠，并且添加上斜杠之后与当前给定请求的路径匹配，则将其加上斜杠添加到matchingPatterns中
				if (!registeredPattern.endsWith("/") && getPathMatcher().match(registeredPattern + "/", urlPath)) {
					matchingPatterns.add(registeredPattern + "/");
				}
			}
		}
		//存储首个匹配的结果
		String bestMatch = null;
		//获取当前请求路径中对应的路径模式比较器
		Comparator<String> patternComparator = getPathMatcher().getPatternComparator(urlPath);
		//有与给定的请求路径匹配的注册的请求模式
		if (!matchingPatterns.isEmpty()) {
			//排序
			matchingPatterns.sort(patternComparator);
			//如果有多个匹配当前请求的模式路径，则记录日志
			if (logger.isTraceEnabled() && matchingPatterns.size() > 1) {
				logger.trace("Matching patterns " + matchingPatterns);
			}
			//获取排序后的第一个匹配路径
			bestMatch = matchingPatterns.get(0);
		}
		//存在首个匹配的结果
		if (bestMatch != null) {
			//通过首个匹配的模式路径在handlerMap注册表中获取对应的handler对象
			handler = this.handlerMap.get(bestMatch);
			//获取失败
			if (handler == null) {
				//如果当前模式路径末尾有斜杠，则去掉斜杠，重新在去handlerMap中获取对应的处理器对象
				if (bestMatch.endsWith("/")) {
					handler = this.handlerMap.get(bestMatch.substring(0, bestMatch.length() - 1));
				}
				//如果还未获取到，则抛出异常
				if (handler == null) {
					throw new IllegalStateException(
							"Could not find handler for best pattern match [" + bestMatch + "]");
				}
			}
			// Bean name or resolved handler?
			//如果获取到的handler对象为字符串类型，即是beanName，则从当前容器的上下文中获取其对应的bean处理器对象
			if (handler instanceof String) {
				String handlerName = (String) handler;
				handler = obtainApplicationContext().getBean(handlerName);
			}
			//验证处理器，空方法；目前没有具体子类实现
			validateHandler(handler, request);
			//获取匹配的路径
			String pathWithinMapping = getPathMatcher().extractPathWithinPattern(bestMatch, urlPath);

			// There might be multiple 'best patterns', let's make sure we have the correct URI template variables
			// for all of them
			//获取路径参数集合
			Map<String, String> uriTemplateVariables = new LinkedHashMap<>();
			//遍历所有与当前请求路径匹配的模式路径
			for (String matchingPattern : matchingPatterns) {
				//如果当前模式路径与获取的首个匹配模式路径相等
				if (patternComparator.compare(bestMatch, matchingPattern) == 0) {
					//使用当前模式与给定的请求路径进行匹配，获取参数集合
					Map<String, String> vars = getPathMatcher().extractUriTemplateVariables(matchingPattern, urlPath);
					//针对当前请求路径的参数进行相应的url解码
					Map<String, String> decodedVars = getUrlPathHelper().decodePathVariables(request, vars);
					//将其添加到uriTemplateVariables中
					uriTemplateVariables.putAll(decodedVars);
				}
			}
			//如果当前请求路径存在参数，则记录日志
			if (logger.isTraceEnabled() && uriTemplateVariables.size() > 0) {
				logger.trace("URI variables " + uriTemplateVariables);
			}
			//创建处理器，并返回
			return buildPathExposingHandler(handler, bestMatch, pathWithinMapping, uriTemplateVariables);
		}

		// No handler found...
		//没有找到对应处理器，则返回null
		return null;
	}

	/**
	 * Validate the given handler against the current request.
	 * 根据当前给定的请求验证给定的处理器对象，当前实现为空的，是模板方法，交由子类实现
	 * <p>The default implementation is empty. Can be overridden in subclasses,
	 * for example to enforce specific preconditions expressed in URL mappings.
	 * @param handler the handler object to validate
	 * @param request current HTTP request
	 * @throws Exception if validation failed
	 */
	protected void validateHandler(Object handler, HttpServletRequest request) throws Exception {
	}

	/**
	 * Build a handler object for the given raw handler, exposing the actual
	 * handler, the {@link #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE}, as well as
	 * the {@link #URI_TEMPLATE_VARIABLES_ATTRIBUTE} before executing the handler.
	 * <p>The default implementation builds a {@link HandlerExecutionChain}
	 * with a special interceptor that exposes the path attribute and uri template variables
	 * @param rawHandler the raw handler to expose
	 * @param pathWithinMapping the path to expose before executing the handler
	 * @param uriTemplateVariables the URI template variables, can be {@code null} if no variables found
	 * @return the final handler object
	 * 构建暴露路径的handler对象
	 */
	protected Object buildPathExposingHandler(Object rawHandler, String bestMatchingPattern,
			String pathWithinMapping, @Nullable Map<String, String> uriTemplateVariables) {
		//根据给定的handler对象创建一个HandlerExecutionChain对象
		HandlerExecutionChain chain = new HandlerExecutionChain(rawHandler);
		//添加PathExposingHandlerInterceptor拦截器到chain中
		chain.addInterceptor(new PathExposingHandlerInterceptor(bestMatchingPattern, pathWithinMapping));
		//如果给定的当前请求路径传递了参数，则将UriTemplateVariablesHandlerInterceptor拦截器添加到chain中
		if (!CollectionUtils.isEmpty(uriTemplateVariables)) {
			chain.addInterceptor(new UriTemplateVariablesHandlerInterceptor(uriTemplateVariables));
		}
		//返回处理器执行器链
		return chain;
	}

	/**
	 * Expose the path within the current mapping as request attribute.
	 * @param pathWithinMapping the path within the current mapping
	 * @param request the request to expose the path to
	 * @see #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 */
	protected void exposePathWithinMapping(String bestMatchingPattern, String pathWithinMapping,
			HttpServletRequest request) {

		request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, bestMatchingPattern);
		request.setAttribute(PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, pathWithinMapping);
	}

	/**
	 * Expose the URI templates variables as request attribute.
	 * @param uriTemplateVariables the URI template variables
	 * @param request the request to expose the path to
	 * @see #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 */
	protected void exposeUriTemplateVariables(Map<String, String> uriTemplateVariables, HttpServletRequest request) {
		request.setAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriTemplateVariables);
	}

	@Override
	@Nullable
	public RequestMatchResult match(HttpServletRequest request, String pattern) {
		//获取给定的请求的路径
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		//匹配给定的模式与当前请求的路径，若匹配，则创建RequestMatchResult对象，并返回
		if (getPathMatcher().match(pattern, lookupPath)) {
			return new RequestMatchResult(pattern, lookupPath, getPathMatcher());
		}
		//如果不匹配，并且设置匹配url时不需要考虑尾部是否有斜杠
		else if (useTrailingSlashMatch()) {
			//给定模式路径不以/结尾，并且模式路径尾部添加/后与当前请求路径匹配，则创建对应的RequestMatchResult对象并返回
			if (!pattern.endsWith("/") && getPathMatcher().match(pattern + "/", lookupPath)) {
				return new RequestMatchResult(pattern + "/", lookupPath, getPathMatcher());
			}
		}
		//如果不匹配，则返回null
		return null;
	}

	/**
	 * Register the specified handler for the given URL paths.
	 * 注册指定URL数组和对应的处理器到handlerMap注册表中
	 * 注册多个URL的处理器
	 * @param urlPaths the URLs that the bean should be mapped to
	 * @param beanName the name of the handler bean
	 * @throws BeansException if the handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	protected void registerHandler(String[] urlPaths, String beanName) throws BeansException, IllegalStateException {
		//断言urlPaths数组不为空
		Assert.notNull(urlPaths, "URL path array must not be null");
		//遍历urlPaths数组
		for (String urlPath : urlPaths) {
			//注册处理器
			registerHandler(urlPath, beanName);
		}
	}

	/**
	 * Register the specified handler for the given URL path.
	 * 注册单个URL的处理器
	 * @param urlPath the URL the bean should be mapped to
	 * @param handler the handler instance or handler bean name String
	 * (a bean name will automatically be resolved into the corresponding handler bean)
	 * @throws BeansException if the handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	protected void registerHandler(String urlPath, Object handler) throws BeansException, IllegalStateException {
		//断言给定参数不为空
		Assert.notNull(urlPath, "URL path must not be null");
		Assert.notNull(handler, "Handler object must not be null");
		//用于存储最终解析得到的处理器
		Object resolvedHandler = handler;

		// Eagerly resolve handler if referencing singleton via name.
		//如果不延迟加载处理器，并且handler为beanName，即字符串类型
		if (!this.lazyInitHandlers && handler instanceof String) {
			String handlerName = (String) handler;
			ApplicationContext applicationContext = obtainApplicationContext();
			//如果当前处理器名称对应的bean对象是单例的，则提前加载获取handler对象
			if (applicationContext.isSingleton(handlerName)) {
				resolvedHandler = applicationContext.getBean(handlerName);
			}
		}
		//从路径和处理器映射表中获取给定url路径对应的handler对象
		Object mappedHandler = this.handlerMap.get(urlPath);
		//如果获取到了，并且不与给定的handler相同，即出现了争议，则抛出异常
		if (mappedHandler != null) {
			if (mappedHandler != resolvedHandler) {
				throw new IllegalStateException(
						"Cannot map " + getHandlerDescription(handler) + " to URL path [" + urlPath +
						"]: There is already " + getHandlerDescription(mappedHandler) + " mapped.");
			}
		}
		//如果当前处理器注册表缓存中不存在给定路径对应的处理器对象
		else {
			//如果给定url路径为/，即根路径
			if (urlPath.equals("/")) {
				//记录日志
				if (logger.isTraceEnabled()) {
					logger.trace("Root mapping to " + getHandlerDescription(handler));
				}
				//设置当前处理器为根处理器
				setRootHandler(resolvedHandler);
			}
			//如果给定url路径为/*
			else if (urlPath.equals("/*")) {
				//记录日志
				if (logger.isTraceEnabled()) {
					logger.trace("Default mapping to " + getHandlerDescription(handler));
				}
				//设置当前解析得到的处理器为默认处理器
				setDefaultHandler(resolvedHandler);
			}
			//其它路径情况
			else {
				//将当前给定的url路径与解析得到的handler对象添加到handlerMap注册表中
				this.handlerMap.put(urlPath, resolvedHandler);
				//记录日志
				if (logger.isTraceEnabled()) {
					logger.trace("Mapped [" + urlPath + "] onto " + getHandlerDescription(handler));
				}
			}
		}
	}

	private String getHandlerDescription(Object handler) {
		return (handler instanceof String ? "'" + handler + "'" : handler.toString());
	}


	/**
	 * Return the registered handlers as an unmodifiable Map, with the registered path
	 * as key and the handler object (or handler bean name in case of a lazy-init handler)
	 * as value.
	 * @see #getDefaultHandler()
	 */
	public final Map<String, Object> getHandlerMap() {
		return Collections.unmodifiableMap(this.handlerMap);
	}

	/**
	 * Indicates whether this handler mapping support type-level mappings. Default to {@code false}.
	 */
	protected boolean supportsTypeLevelMappings() {
		return false;
	}


	/**
	 * Special interceptor for exposing the
	 * {@link AbstractUrlHandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE} attribute.
	 * @see AbstractUrlHandlerMapping#exposePathWithinMapping
	 */
	private class PathExposingHandlerInterceptor extends HandlerInterceptorAdapter {
		/**最佳匹配的路径*/
		private final String bestMatchingPattern;
		/**被匹配的路径*/
		private final String pathWithinMapping;

		public PathExposingHandlerInterceptor(String bestMatchingPattern, String pathWithinMapping) {
			this.bestMatchingPattern = bestMatchingPattern;
			this.pathWithinMapping = pathWithinMapping;
		}

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			//暴露BEST_MATCHING_PATTERN_ATTRIBUTE、PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE属性
			exposePathWithinMapping(this.bestMatchingPattern, this.pathWithinMapping, request);
			//暴露BEST_MATCHING_HANDLER_ATTRIBUTE属性
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
			//暴露INTROSPECT_TYPE_LEVEL_MAPPING属性
			request.setAttribute(INTROSPECT_TYPE_LEVEL_MAPPING, supportsTypeLevelMappings());
			return true;
		}

	}

	/**
	 * Special interceptor for exposing the
	 * {@link AbstractUrlHandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE} attribute.
	 * @see AbstractUrlHandlerMapping#exposePathWithinMapping
	 */
	private class UriTemplateVariablesHandlerInterceptor extends HandlerInterceptorAdapter {
		/**路径参数变量集合*/
		private final Map<String, String> uriTemplateVariables;

		public UriTemplateVariablesHandlerInterceptor(Map<String, String> uriTemplateVariables) {
			this.uriTemplateVariables = uriTemplateVariables;
		}

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			//通过当前请求的属性来暴露URI_TEMPLATE_VARIABLES_ATTRIBUTE属性值
			exposeUriTemplateVariables(this.uriTemplateVariables, request);
			return true;
		}
	}

}
