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

package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.FatalBeanException;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

/**
 * Default implementation of the {@link NamespaceHandlerResolver} interface.
 * Resolves namespace URIs to implementation classes based on the mappings
 * contained in mapping file.
 *
 * <p>By default, this implementation looks for the mapping file at
 * {@code META-INF/spring.handlers}, but this can be changed using the
 * {@link #DefaultNamespaceHandlerResolver(ClassLoader, String)} constructor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 2.0
 * @see NamespaceHandler
 * @see DefaultBeanDefinitionDocumentReader
 */
public class DefaultNamespaceHandlerResolver implements NamespaceHandlerResolver {

	/**
	 * The location to look for the mapping files. Can be present in multiple JAR files.
	 */
	public static final String DEFAULT_HANDLER_MAPPINGS_LOCATION = "META-INF/spring.handlers";


	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** ClassLoader to use for NamespaceHandler classes. */
	@Nullable
	private final ClassLoader classLoader;

	/**
	 * Resource location to search for.
	 * NamespaceHandler的映射关系配置文件地址（默认为META-INF/spring.handlers）
	 */
	private final String handlerMappingsLocation;

	/**
	 * Stores the mappings from namespace URI to NamespaceHandler class name / instance.
	 * namespaceUri与NamespaceHandler的映射关系表
	 * key：命名空间uri
	 * value：分为两种情况：（1）未初始化，存储的为NamespaceHandler的类路径；（2）已初始化，存储的为NamespaceHandler对象
	 */
	@Nullable
	private volatile Map<String, Object> handlerMappings;


	/**
	 * Create a new {@code DefaultNamespaceHandlerResolver} using the
	 * default mapping file location.
	 * <p>This constructor will result in the thread context ClassLoader being used
	 * to load resources.
	 * @see #DEFAULT_HANDLER_MAPPINGS_LOCATION
	 */
	public DefaultNamespaceHandlerResolver() {
		this(null, DEFAULT_HANDLER_MAPPINGS_LOCATION);
	}

	/**
	 * Create a new {@code DefaultNamespaceHandlerResolver} using the
	 * default mapping file location.
	 * 创建一个新的DefaultNamespaceHandlerResolver对象，其解析注册表时使用的是META-INF/spring.handlers下的文件内容
	 * 来生成一个namespaceUri和自定义handler实现类对应的关系
	 * classLoader如果当前参数的不为空，则使用；否则使用当前线程的类加载器（一般为appclassLoader）
	 * @param classLoader the {@link ClassLoader} instance used to load mapping resources
	 * (may be {@code null}, in which case the thread context ClassLoader will be used)
	 * @see #DEFAULT_HANDLER_MAPPINGS_LOCATION
	 */
	public DefaultNamespaceHandlerResolver(@Nullable ClassLoader classLoader) {
		this(classLoader, DEFAULT_HANDLER_MAPPINGS_LOCATION);
	}

	/**
	 * Create a new {@code DefaultNamespaceHandlerResolver} using the
	 * supplied mapping file location.
	 * @param classLoader the {@link ClassLoader} instance used to load mapping resources
	 * may be {@code null}, in which case the thread context ClassLoader will be used)
	 * @param handlerMappingsLocation the mapping file location
	 */
	public DefaultNamespaceHandlerResolver(@Nullable ClassLoader classLoader, String handlerMappingsLocation) {
		Assert.notNull(handlerMappingsLocation, "Handler mappings location must not be null");
		this.classLoader = (classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader());
		this.handlerMappingsLocation = handlerMappingsLocation;
	}


	/**
	 * Locate the {@link NamespaceHandler} for the supplied namespace URI
	 * from the configured mappings.
	 * 从指定的配置映射表中通过给定的namespaceUri来获取到对应的自定义NamespaceHandler实现类对象
	 * @param namespaceUri the relevant namespace URI
	 * @return the located {@link NamespaceHandler}, or {@code null} if none found
	 */
	@Override
	@Nullable
	public NamespaceHandler resolve(String namespaceUri) {
		//获取所有已经配置的namespaceUri与handler映射表
		Map<String, Object> handlerMappings = getHandlerMappings();
		//从映射表中获取到namespaceUri对应的handler的信息
		Object handlerOrClassName = handlerMappings.get(namespaceUri);
		//不存在，则返回null
		if (handlerOrClassName == null) {
			return null;
		}
		//已经初始化（首次加载时，映射表中存储的应该是处理器类的全路径限定名，为string）
		else if (handlerOrClassName instanceof NamespaceHandler) {
			return (NamespaceHandler) handlerOrClassName;
		}
		//未初始化，需要进行初始化
		else {
			//取出指定处理器的类路径全限定名
			String className = (String) handlerOrClassName;
			try {
				//通过指定的handler类名称来加载此类信息
				Class<?> handlerClass = ClassUtils.forName(className, this.classLoader);
				//如果当前创建的handler对象对应的类没有实现NamespaceHandler接口，则报错
				if (!NamespaceHandler.class.isAssignableFrom(handlerClass)) {
					throw new FatalBeanException("Class [" + className + "] for namespace [" + namespaceUri +
							"] does not implement the [" + NamespaceHandler.class.getName() + "] interface");
				}
				//通过handlerClass类对象来创建对应的实例化对象
				NamespaceHandler namespaceHandler = (NamespaceHandler) BeanUtils.instantiateClass(handlerClass);
				//初始化namespaceHandler对象（主要是将自定义标签解析器进行注册，其为我们自定义实现NamespaceHandler接口的init方法实现）
				namespaceHandler.init();
				//将此对象与namespaceUri的对应关系添加到缓存中，覆盖掉原先命名空间uri与处理器类名对应关系的记录
				handlerMappings.put(namespaceUri, namespaceHandler);
				return namespaceHandler;
			}
			catch (ClassNotFoundException ex) {
				throw new FatalBeanException("Could not find NamespaceHandler class [" + className +
						"] for namespace [" + namespaceUri + "]", ex);
			}
			catch (LinkageError err) {
				throw new FatalBeanException("Unresolvable class definition for NamespaceHandler class [" +
						className + "] for namespace [" + namespaceUri + "]", err);
			}
		}
	}

	/**
	 * Load the specified NamespaceHandler mappings lazily.
	 * 懒加载NamespaceHandler与namespaceUri的映射关系表（在第一次调用此方法使用时，才进行加载信息填充映射表）
	 */
	private Map<String, Object> getHandlerMappings() {
		//双重检查锁，进行延迟加载
		Map<String, Object> handlerMappings = this.handlerMappings;
		if (handlerMappings == null) {
			synchronized (this) {
				handlerMappings = this.handlerMappings;
				if (handlerMappings == null) {
					if (logger.isTraceEnabled()) {
						logger.trace("Loading NamespaceHandler mappings from [" + this.handlerMappingsLocation + "]");
					}
					try {
						//使用指定classLoader加载器，来加载handlerMappingsLocation路径下的属性文件
						Properties mappings =
								PropertiesLoaderUtils.loadAllProperties(this.handlerMappingsLocation, this.classLoader);
						if (logger.isTraceEnabled()) {
							logger.trace("Loaded NamespaceHandler mappings: " + mappings);
						}
						//初始化handlerMappings容器
						handlerMappings = new ConcurrentHashMap<>(mappings.size());
						//将加载的属性内容存入到handlerMappings容器中
						CollectionUtils.mergePropertiesIntoMap(mappings, handlerMappings);
						//重新赋值给映射表对象
						this.handlerMappings = handlerMappings;
					}
					catch (IOException ex) {
						throw new IllegalStateException(
								"Unable to load NamespaceHandler mappings from location [" + this.handlerMappingsLocation + "]", ex);
					}
				}
			}
		}
		return handlerMappings;
	}


	@Override
	public String toString() {
		return "NamespaceHandlerResolver using mappings " + getHandlerMappings();
	}

}
