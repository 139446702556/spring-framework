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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.MethodIntrospector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Abstract base class for {@link HandlerMapping} implementations that define
 * a mapping between a request and a {@link HandlerMethod}.
 *
 * <p>For each registered handler method, a unique mapping is maintained with
 * subclasses defining the details of the mapping type {@code <T>}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 * @param <T> the mapping for a {@link HandlerMethod} containing the conditions
 * needed to match the handler method to incoming request.
 */
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {

	/**
	 * Bean name prefix for target beans behind scoped proxies. Used to exclude those
	 * targets from handler method detection, in favor of the corresponding proxies.
	 * <p>We're not checking the autowire-candidate status here, which is how the
	 * proxy target filtering problem is being handled at the autowiring level,
	 * since autowire-candidate may have been turned to {@code false} for other
	 * reasons, while still expecting the bean to be eligible for handler methods.
	 * <p>Originally defined in {@link org.springframework.aop.scope.ScopedProxyUtils}
	 * but duplicated here to avoid a hard dependency on the spring-aop module.
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

	private static final HandlerMethod PREFLIGHT_AMBIGUOUS_MATCH =
			new HandlerMethod(new EmptyHandler(), ClassUtils.getMethod(EmptyHandler.class, "handle"));

	private static final CorsConfiguration ALLOW_CORS_CONFIG = new CorsConfiguration();

	static {
		ALLOW_CORS_CONFIG.addAllowedOrigin("*");
		ALLOW_CORS_CONFIG.addAllowedMethod("*");
		ALLOW_CORS_CONFIG.addAllowedHeader("*");
		ALLOW_CORS_CONFIG.setAllowCredentials(true);
	}

	/**是否可以只扫描可访问的HandlerMethod们*/
	private boolean detectHandlerMethodsInAncestorContexts = false;
	/**Mapping的命名策略*/
	@Nullable
	private HandlerMethodMappingNamingStrategy<T> namingStrategy;
	/**Mapping注册表*/
	private final MappingRegistry mappingRegistry = new MappingRegistry();


	/**
	 * Whether to detect handler methods in beans in ancestor ApplicationContexts.
	 * <p>Default is "false": Only beans in the current ApplicationContext are
	 * considered, i.e. only in the context that this HandlerMapping itself
	 * is defined in (typically the current DispatcherServlet's context).
	 * <p>Switch this flag on to detect handler beans in ancestor contexts
	 * (typically the Spring root WebApplicationContext) as well.
	 * @see #getCandidateBeanNames()
	 */
	public void setDetectHandlerMethodsInAncestorContexts(boolean detectHandlerMethodsInAncestorContexts) {
		this.detectHandlerMethodsInAncestorContexts = detectHandlerMethodsInAncestorContexts;
	}

	/**
	 * Configure the naming strategy to use for assigning a default name to every
	 * mapped handler method.
	 * <p>The default naming strategy is based on the capital letters of the
	 * class name followed by "#" and then the method name, e.g. "TC#getFoo"
	 * for a class named TestController with method getFoo.
	 */
	public void setHandlerMethodMappingNamingStrategy(HandlerMethodMappingNamingStrategy<T> namingStrategy) {
		this.namingStrategy = namingStrategy;
	}

	/**
	 * Return the configured naming strategy or {@code null}.
	 */
	@Nullable
	public HandlerMethodMappingNamingStrategy<T> getNamingStrategy() {
		return this.namingStrategy;
	}

	/**
	 * Return a (read-only) map with all mappings and HandlerMethod's.
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
		this.mappingRegistry.acquireReadLock();
		try {
			return Collections.unmodifiableMap(this.mappingRegistry.getMappings());
		}
		finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Return the handler methods for the given mapping name.
	 * @param mappingName the mapping name
	 * @return a list of matching HandlerMethod's or {@code null}; the returned
	 * list will never be modified and is safe to iterate.
	 * @see #setHandlerMethodMappingNamingStrategy
	 */
	@Nullable
	public List<HandlerMethod> getHandlerMethodsForMappingName(String mappingName) {
		return this.mappingRegistry.getHandlerMethodsByMappingName(mappingName);
	}

	/**
	 * Return the internal mapping registry. Provided for testing purposes.
	 */
	MappingRegistry getMappingRegistry() {
		return this.mappingRegistry;
	}

	/**
	 * Register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping for the handler method
	 * @param handler the handler
	 * @param method the method
	 */
	public void registerMapping(T mapping, Object handler, Method method) {
		if (logger.isTraceEnabled()) {
			logger.trace("Register \"" + mapping + "\" to " + method.toGenericString());
		}
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Un-register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping to unregister
	 */
	public void unregisterMapping(T mapping) {
		if (logger.isTraceEnabled()) {
			logger.trace("Unregister mapping \"" + mapping + "\"");
		}
		this.mappingRegistry.unregister(mapping);
	}


	// Handler method detection

	/**
	 * Detects handler methods at initialization.
	 * 在初始化时检查处理程序方法
	 * @see #initHandlerMethods
	 */
	@Override
	public void afterPropertiesSet() {
		//初始化处理器的方法们
		initHandlerMethods();
	}

	/**
	 * Scan beans in the ApplicationContext, detect and register handler methods.
	 * @see #getCandidateBeanNames()
	 * @see #processCandidateBean
	 * @see #handlerMethodsInitialized
	 */
	protected void initHandlerMethods() {
		//遍历所有的候选beanName，逐个处理
		for (String beanName : getCandidateBeanNames()) {
			//如果当前beanName值不是以SCOPED_TARGET_NAME_PREFIX开头
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
				//处理bean
				processCandidateBean(beanName);
			}
		}
		//初始化处理器的方法们；目前此方法是空的模板方法，只做了日志记录，暂无具体实现
		handlerMethodsInitialized(getHandlerMethods());
	}

	/**
	 * Determine the names of candidate beans in the application context.
	 * @since 5.1
	 * @see #setDetectHandlerMethodsInAncestorContexts
	 * @see BeanFactoryUtils#beanNamesForTypeIncludingAncestors
	 */
	protected String[] getCandidateBeanNames() {
		//如果此标识为true，则获取当前上下文容器及其父容器中的所有bean对象名称
		//否则，则只获取当前上下文容器中的全部bean对象名称
		//即是否检查所有可访问的HandlerMethod们（所有的bean对象，上下文及其父上下文中）
		return (this.detectHandlerMethodsInAncestorContexts ?
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(obtainApplicationContext(), Object.class) :
				obtainApplicationContext().getBeanNamesForType(Object.class));
	}

	/**
	 * Determine the type of the specified candidate bean and call
	 * {@link #detectHandlerMethods} if identified as a handler type.
	 * <p>This implementation avoids bean creation through checking
	 * {@link org.springframework.beans.factory.BeanFactory#getType}
	 * and calling {@link #detectHandlerMethods} with the bean name.
	 * @param beanName the name of the candidate bean
	 * @since 5.1
	 * @see #isHandler
	 * @see #detectHandlerMethods
	 * 判断给定beanName对应的bean对象是否为处理器，如果是，则扫描处理器方法
	 */
	protected void processCandidateBean(String beanName) {
		//从当前上下文容器中获取给定beanName对应的bean对象对应的类型
		Class<?> beanType = null;
		try {
			beanType = obtainApplicationContext().getType(beanName);
		}
		catch (Throwable ex) {
			// An unresolvable bean type, probably from a lazy bean - let's ignore it.
			if (logger.isTraceEnabled()) {
				logger.trace("Could not resolve type for bean '" + beanName + "'", ex);
			}
		}
		//判断bean是否为处理器，如果是，则扫描处理器的方法们
		if (beanType != null && isHandler(beanType)) {
			detectHandlerMethods(beanName);
		}
	}

	/**
	 * Look for handler methods in the specified handler bean.
	 * 在指定的handler bean对象中查找handler方法
	 * @param handler either a bean name or an actual handler instance
	 * @see #getMappingForMethod
	 */
	protected void detectHandlerMethods(Object handler) {
		//获取处理器类型
		Class<?> handlerType = (handler instanceof String ?
				obtainApplicationContext().getType((String) handler) : handler.getClass());

		if (handlerType != null) {
			//获取真实的类型；因为handlerType可能是代理类
			Class<?> userType = ClassUtils.getUserClass(handlerType);
			//获得匹配的方法集合
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
					(MethodIntrospector.MetadataLookup<T>) method -> {
						try {
							return getMappingForMethod(method, userType);
						}
						catch (Throwable ex) {
							throw new IllegalStateException("Invalid mapping on handler class [" +
									userType.getName() + "]: " + method, ex);
						}
					});
			//记录日志
			if (logger.isTraceEnabled()) {
				logger.trace(formatMappings(userType, methods));
			}
			//遍历方法，逐个注册HandlerMethod
			methods.forEach((method, mapping) -> {
				//从给定的处理器类型中查找与method对象匹配的实际调用方法
				Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
				//注册HandlerMethod
				registerHandlerMethod(handler, invocableMethod, mapping);
			});
		}
	}

	private String formatMappings(Class<?> userType, Map<Method, T> methods) {
		String formattedType = Arrays.stream(ClassUtils.getPackageName(userType).split("\\."))
				.map(p -> p.substring(0, 1))
				.collect(Collectors.joining(".", "", ".")) + userType.getSimpleName();
		Function<Method, String> methodFormatter = method -> Arrays.stream(method.getParameterTypes())
				.map(Class::getSimpleName)
				.collect(Collectors.joining(",", "(", ")"));
		return methods.entrySet().stream()
				.map(e -> {
					Method method = e.getKey();
					return e.getValue() + ": " + method.getName() + methodFormatter.apply(method);
				})
				.collect(Collectors.joining("\n\t", "\n\t" + formattedType + ":" + "\n\t", ""));
	}

	/**
	 * Register a handler method and its unique mapping. Invoked at startup for
	 * each detected handler method.
	 * @param handler the bean name of the handler or the handler instance
	 * @param method the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 * @throws IllegalStateException if another method was already registered
	 * under the same mapping
	 */
	protected void registerHandlerMethod(Object handler, Method method, T mapping) {
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Create the HandlerMethod instance.
	 * 创建一个HandlerMethod实例对象
	 * @param handler either a bean name or an actual handler instance
	 * @param method the target method
	 * @return the created HandlerMethod
	 */
	protected HandlerMethod createHandlerMethod(Object handler, Method method) {
		HandlerMethod handlerMethod;
		//如果给定的handler是一个beanName
		if (handler instanceof String) {
			String beanName = (String) handler;
			//通过beanName、beanFactory创建HandlerMethod对象
			handlerMethod = new HandlerMethod(beanName,
					obtainApplicationContext().getAutowireCapableBeanFactory(), method);
		}
		else {
			//如果handler是一个处理器实例对象，则用其创建HandlerMethod对象
			handlerMethod = new HandlerMethod(handler, method);
		}
		return handlerMethod;
	}

	/**
	 * Extract and return the CORS configuration for the mapping.
	 */
	@Nullable
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, T mapping) {
		return null;
	}

	/**
	 * Invoked after all handler methods have been detected.
	 * 在检测到所有处理程序方法后调用（即handlerMethod们初始化）
	 * @param handlerMethods a read-only map with handler methods and mappings.
	 */
	protected void handlerMethodsInitialized(Map<T, HandlerMethod> handlerMethods) {
		// Total includes detected mappings + explicit registrations via registerMapping
		int total = handlerMethods.size();
		if ((logger.isTraceEnabled() && total == 0) || (logger.isDebugEnabled() && total > 0) ) {
			logger.debug(total + " mappings in " + formatMappingName());
		}
	}


	// Handler method lookup

	/**
	 * Look up a handler method for the given request.
	 * 查找给定请求对应的HandlerMethod对象
	 */
	@Override
	protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
		//获取请求的路径
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		//获得写锁
		this.mappingRegistry.acquireReadLock();
		try {
			//获取对应的HandlerMethod对象
			HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
			//进一步，获取HandlerMethod对象
			return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null);
		}
		finally {
			//释放读锁
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Look up the best-matching handler method for the current request.
	 * If multiple matches are found, the best match is selected.
	 * 查找当前给定请求的最佳匹配处理程序方法（handlerMethod），如果匹配到的有多个，
	 * 则选择最佳的匹配项
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @return the best-matching handler method, or {@code null} if no match
	 * @see #handleMatch(Object, String, HttpServletRequest)
	 * @see #handleNoMatch(Set, String, HttpServletRequest)
	 */
	@Nullable
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {
		//Match数组，用于存储匹配上当前请求的结果
		List<Match> matches = new ArrayList<>();
		//优先，基于直接URL的mapping们，进行匹配
		//通过当前请求的url路径，在urlLookup缓存中获取对应的mapping集合
		List<T> directPathMatches = this.mappingRegistry.getMappingsByUrl(lookupPath);
		//将匹配请求直接路径对应的mapping集合中与request匹配的mapping对象添加到matches中
		if (directPathMatches != null) {
			addMatchingMappings(directPathMatches, matches, request);
		}
		//其次，如果直接路径对应的mappings没有与当前请求匹配的
		//则扫描注册表中所有Mapping们，进行匹配
		if (matches.isEmpty()) {
			// No choice but to go through all mappings...
			addMatchingMappings(this.mappingRegistry.getMappings().keySet(), matches, request);
		}
		//如果匹配到了，则获取最佳匹配的Match对象的handlerMethod属性
		if (!matches.isEmpty()) {
			//创建一个MatchComparator对象
			Comparator<Match> comparator = new MatchComparator(getMappingComparator(request));
			//对matches进行排序
			matches.sort(comparator);
			//获取排序后的集合中的第一个Match对象
			Match bestMatch = matches.get(0);
			//处理存在多个Match对象的情况
			if (matches.size() > 1) {
				//记录matches的信息日志
				if (logger.isTraceEnabled()) {
					logger.trace(matches.size() + " matching mappings: " + matches);
				}
				//处理cors请求，并返回指定的HandlerMethod对象
				if (CorsUtils.isPreFlightRequest(request)) {
					return PREFLIGHT_AMBIGUOUS_MATCH;
				}
				//获取matches集合中的第二个Match对象
				Match secondBestMatch = matches.get(1);
				//使用自定义比较器来比较两个Match对象，如果相等，则抛出异常
				//因为两个优先级一样高，说明无法判断谁更优先
				if (comparator.compare(bestMatch, secondBestMatch) == 0) {
					Method m1 = bestMatch.handlerMethod.getMethod();
					Method m2 = secondBestMatch.handlerMethod.getMethod();
					String uri = request.getRequestURI();
					throw new IllegalStateException(
							"Ambiguous handler methods mapped for '" + uri + "': {" + m1 + ", " + m2 + "}");
				}
			}
			//将首个匹配的Match对象的handlerMethod对象添加到当前请求属性中
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, bestMatch.handlerMethod);
			//处理首个Match对象，设置首个Match对象的lookupPath属性到请求属性中
			handleMatch(bestMatch.mapping, lookupPath, request);
			//返回首个匹配的Match对象的handlerMethod属性值
			return bestMatch.handlerMethod;
		}
		//如果匹配不到，则处理不匹配的情况
		else {
			return handleNoMatch(this.mappingRegistry.getMappings().keySet(), lookupPath, request);
		}
	}

	private void addMatchingMappings(Collection<T> mappings, List<Match> matches, HttpServletRequest request) {
		//遍历mapping数组
		for (T mapping : mappings) {
			//执行mapping与request的匹配
			T match = getMatchingMapping(mapping, request);
			//如果匹配，则创建Match对象，并将其添加到matches中
			if (match != null) {
				matches.add(new Match(match, this.mappingRegistry.getMappings().get(mapping)));
			}
		}
	}

	/**
	 * Invoked when a matching mapping is found.
	 * 找到匹配映射时调用
	 * @param mapping the matching mapping
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 */
	protected void handleMatch(T mapping, String lookupPath, HttpServletRequest request) {
		request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
	}

	/**
	 * Invoked when no matching mapping is not found.
	 * 未找到匹配映射时调用
	 * 此处为空方法，返回null，具体交由子类实现
	 * @param mappings all registered mappings
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @throws ServletException in case of errors
	 */
	@Nullable
	protected HandlerMethod handleNoMatch(Set<T> mappings, String lookupPath, HttpServletRequest request)
			throws Exception {

		return null;
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, HttpServletRequest request) {
		CorsConfiguration corsConfig = super.getCorsConfiguration(handler, request);
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			if (handlerMethod.equals(PREFLIGHT_AMBIGUOUS_MATCH)) {
				return AbstractHandlerMethodMapping.ALLOW_CORS_CONFIG;
			}
			else {
				CorsConfiguration corsConfigFromMethod = this.mappingRegistry.getCorsConfiguration(handlerMethod);
				corsConfig = (corsConfig != null ? corsConfig.combine(corsConfigFromMethod) : corsConfigFromMethod);
			}
		}
		return corsConfig;
	}


	// Abstract template methods

	/**
	 * Whether the given type is a handler with handler methods.
	 * @param beanType the type of the bean being checked
	 * @return "true" if this a handler type, "false" otherwise.
	 */
	protected abstract boolean isHandler(Class<?> beanType);

	/**
	 * Provide the mapping for a handler method. A method for which no
	 * mapping can be provided is not a handler method.
	 * @param method the method to provide a mapping for
	 * @param handlerType the handler type, possibly a sub-type of the method's
	 * declaring class
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * Extract and return the URL paths contained in a mapping.
	 */
	protected abstract Set<String> getMappingPathPatterns(T mapping);

	/**
	 * Check if a mapping matches the current request and return a (potentially
	 * new) mapping with conditions relevant to the current request.
	 * @param mapping the mapping to get a match for
	 * @param request the current HTTP servlet request
	 * @return the match, or {@code null} if the mapping doesn't match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, HttpServletRequest request);

	/**
	 * Return a comparator for sorting matching mappings.
	 * The returned comparator should sort 'better' matches higher.
	 * @param request the current request
	 * @return the comparator (never {@code null})
	 */
	protected abstract Comparator<T> getMappingComparator(HttpServletRequest request);


	/**
	 * A registry that maintains all mappings to handler methods, exposing methods
	 * to perform lookups and providing concurrent access.
	 * <p>Package-private for testing purposes.
	 */
	class MappingRegistry {
		/**
		 * 注册表
		 * key：Mapping
		 */
		private final Map<T, MappingRegistration<T>> registry = new HashMap<>();
		/**注册表2  key：Mapping*/
		private final Map<T, HandlerMethod> mappingLookup = new LinkedHashMap<>();
		/**直接URL的映射  key：直接URL  value：Mapping数组*/
		private final MultiValueMap<String, T> urlLookup = new LinkedMultiValueMap<>();
		/**Mapping的名字与HandlerMethod的映射  key：Mapping的名字  value：HandlerMethod数组*/
		private final Map<String, List<HandlerMethod>> nameLookup = new ConcurrentHashMap<>();

		private final Map<HandlerMethod, CorsConfiguration> corsLookup = new ConcurrentHashMap<>();
		/**读写锁*/
		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

		/**
		 * Return all mappings and handler methods. Not thread-safe.
		 * 返回所有的mappings和handlerMethod；不是线程安全的
		 * @see #acquireReadLock()
		 */
		public Map<T, HandlerMethod> getMappings() {
			return this.mappingLookup;
		}

		/**
		 * Return matches for the given URL path. Not thread-safe.
		 * 返回给定URL路径的匹配项，不是线程安全的
		 * @see #acquireReadLock()
		 */
		@Nullable
		public List<T> getMappingsByUrl(String urlPath) {
			return this.urlLookup.get(urlPath);
		}

		/**
		 * Return handler methods by mapping name. Thread-safe for concurrent use.
		 */
		public List<HandlerMethod> getHandlerMethodsByMappingName(String mappingName) {
			return this.nameLookup.get(mappingName);
		}

		/**
		 * Return CORS configuration. Thread-safe for concurrent use.
		 */
		public CorsConfiguration getCorsConfiguration(HandlerMethod handlerMethod) {
			HandlerMethod original = handlerMethod.getResolvedFromHandlerMethod();
			return this.corsLookup.get(original != null ? original : handlerMethod);
		}

		/**
		 * Acquire the read lock when using getMappings and getMappingsByUrl.
		 * 获得读锁
		 */
		public void acquireReadLock() {
			this.readWriteLock.readLock().lock();
		}

		/**
		 * Release the read lock after using getMappings and getMappingsByUrl.
		 * 释放读锁
		 */
		public void releaseReadLock() {
			this.readWriteLock.readLock().unlock();
		}
		/**注册*/
		public void register(T mapping, Object handler, Method method) {
			//获得写锁
			this.readWriteLock.writeLock().lock();
			try {
				//创建HandlerMethod对象
				HandlerMethod handlerMethod = createHandlerMethod(handler, method);
				//检验当前mapping在注册表中是否存在，如果存在，则抛出异常
				assertUniqueMethodMapping(handlerMethod, mapping);
				//添加mapping和handlerMethod的对应到mappingLookup中
				this.mappingLookup.put(mapping, handlerMethod);
				//获得mapping对应的普通Url数组
				List<String> directUrls = getDirectUrls(mapping);
				//添加url+mapping到urlLookup集合中
				for (String url : directUrls) {
					this.urlLookup.add(url, mapping);
				}
				//初始化nameLookup
				String name = null;
				//如果设置了mapping的命名策略器
				if (getNamingStrategy() != null) {
					//获得mapping的名字
					name = getNamingStrategy().getName(handlerMethod, mapping);
					//添加mapping的名字+HandlerMethod到nameLookup中
					addMappingName(name, handlerMethod);
				}
				//初始化cors跨域配置，并将handlerMethod+corsConfig添加到corsLookup中
				CorsConfiguration corsConfig = initCorsConfiguration(handler, method, mapping);
				if (corsConfig != null) {
					this.corsLookup.put(handlerMethod, corsConfig);
				}
				//创建MappingRegistration对象，并将其与mapping的映射添加到registry中
				this.registry.put(mapping, new MappingRegistration<>(mapping, handlerMethod, directUrls, name));
			}
			finally {
				//释放写锁
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void assertUniqueMethodMapping(HandlerMethod newHandlerMethod, T mapping) {
			//从注册表中获取mapping对应的HandlerMethod对象
			HandlerMethod handlerMethod = this.mappingLookup.get(mapping);
			//如果注册表中已有映射对应的handlerMethod，并且不与newHandlerMethod方法相同，则抛出异常（说明不唯一）
			if (handlerMethod != null && !handlerMethod.equals(newHandlerMethod)) {
				throw new IllegalStateException(
						"Ambiguous mapping. Cannot map '" +	newHandlerMethod.getBean() + "' method \n" +
						newHandlerMethod + "\nto " + mapping + ": There is already '" +
						handlerMethod.getBean() + "' bean method\n" + handlerMethod + " mapped.");
			}
		}

		private List<String> getDirectUrls(T mapping) {
			List<String> urls = new ArrayList<>(1);
			//遍历mapping对应的路径
			for (String path : getMappingPathPatterns(mapping)) {
				//非**模式**路径，则将其添加到urls中（即直接路径，没有匹配占位符的路径）
				if (!getPathMatcher().isPattern(path)) {
					urls.add(path);
				}
			}
			return urls;
		}

		private void addMappingName(String name, HandlerMethod handlerMethod) {
			//从注册表中获得Mapping的名字对应的HandlerMethod数组
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			//如果oldList为空，则初始化为空数组
			if (oldList == null) {
				oldList = Collections.emptyList();
			}
			//如果当前要注册的HandlerMethod已经存在，则不用添加
			for (HandlerMethod current : oldList) {
				if (handlerMethod.equals(current)) {
					return;
				}
			}
			//添加到nameLookup中
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() + 1);
			newList.addAll(oldList);
			newList.add(handlerMethod);
			this.nameLookup.put(name, newList);
		}
		/**取消注册*/
		public void unregister(T mapping) {
			//获得写锁
			this.readWriteLock.writeLock().lock();
			try {
				//从registry中移除
				MappingRegistration<T> definition = this.registry.remove(mapping);
				if (definition == null) {
					return;
				}
				//从mappingLookup移除
				this.mappingLookup.remove(definition.getMapping());
				//从urlLookup中移除
				for (String url : definition.getDirectUrls()) {
					List<T> list = this.urlLookup.get(url);
					if (list != null) {
						list.remove(definition.getMapping());
						if (list.isEmpty()) {
							this.urlLookup.remove(url);
						}
					}
				}
				//从nameLookup中移除
				removeMappingName(definition);
				//从corsLookup中移除
				this.corsLookup.remove(definition.getHandlerMethod());
			}
			finally {
				//释放写锁
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void removeMappingName(MappingRegistration<T> definition) {
			String name = definition.getMappingName();
			if (name == null) {
				return;
			}
			HandlerMethod handlerMethod = definition.getHandlerMethod();
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				return;
			}
			if (oldList.size() <= 1) {
				this.nameLookup.remove(name);
				return;
			}
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() - 1);
			for (HandlerMethod current : oldList) {
				if (!current.equals(handlerMethod)) {
					newList.add(current);
				}
			}
			this.nameLookup.put(name, newList);
		}
	}

	/**Mapping注册登记*/
	private static class MappingRegistration<T> {
		/**Mapping对象*/
		private final T mapping;
		/**HandlerMethod对象*/
		private final HandlerMethod handlerMethod;
		/**直接url数组*/
		private final List<String> directUrls;
		/**mapping的名字*/
		@Nullable
		private final String mappingName;

		public MappingRegistration(T mapping, HandlerMethod handlerMethod,
				@Nullable List<String> directUrls, @Nullable String mappingName) {

			Assert.notNull(mapping, "Mapping must not be null");
			Assert.notNull(handlerMethod, "HandlerMethod must not be null");
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
			this.directUrls = (directUrls != null ? directUrls : Collections.emptyList());
			this.mappingName = mappingName;
		}

		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public List<String> getDirectUrls() {
			return this.directUrls;
		}

		@Nullable
		public String getMappingName() {
			return this.mappingName;
		}
	}


	/**
	 * A thin wrapper around a matched HandlerMethod and its mapping, for the purpose of
	 * comparing the best match with a comparator in the context of the current request.
	 * 将mapping与handlerMethod封装在一起的对象
	 */
	private class Match {
		/**mapping对象*/
		private final T mapping;
		/**HandlerMethod对象*/
		private final HandlerMethod handlerMethod;

		public Match(T mapping, HandlerMethod handlerMethod) {
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}


	private class MatchComparator implements Comparator<Match> {

		private final Comparator<T> comparator;

		public MatchComparator(Comparator<T> comparator) {
			this.comparator = comparator;
		}

		@Override
		public int compare(Match match1, Match match2) {
			return this.comparator.compare(match1.mapping, match2.mapping);
		}
	}


	private static class EmptyHandler {

		@SuppressWarnings("unused")
		public void handle() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

}
