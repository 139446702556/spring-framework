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

package org.springframework.beans.factory.xml;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;

/**
 * {@code EntityResolver} implementation that tries to resolve entity references
 * through a {@link org.springframework.core.io.ResourceLoader} (usually,
 * relative to the resource base of an {@code ApplicationContext}), if applicable.
 * Extends {@link DelegatingEntityResolver} to also provide DTD and XSD lookup.
 *
 * <p>Allows to use standard XML entities to include XML snippets into an
 * application context definition, for example to split a large XML file
 * into various modules. The include paths can be relative to the
 * application context's resource base as usual, instead of relative
 * to the JVM working directory (the XML parser's default).
 *
 * <p>Note: In addition to relative paths, every URL that specifies a
 * file in the current system root, i.e. the JVM working directory,
 * will be interpreted relative to the application context too.
 *
 * @author Juergen Hoeller
 * @since 31.07.2003
 * @see org.springframework.core.io.ResourceLoader
 * @see org.springframework.context.ApplicationContext
 */
public class ResourceEntityResolver extends DelegatingEntityResolver {

	private static final Log logger = LogFactory.getLog(ResourceEntityResolver.class);

	private final ResourceLoader resourceLoader;


	/**
	 * Create a ResourceEntityResolver for the specified ResourceLoader
	 * (usually, an ApplicationContext).
	 * @param resourceLoader the ResourceLoader (or ApplicationContext)
	 * to load XML entity includes with
	 * 为指定的ResourceLoader创建一个ResourceEntityResolver对象，此类继承了DelegatingEntityResolver类
	 */
	public ResourceEntityResolver(ResourceLoader resourceLoader) {
		super(resourceLoader.getClassLoader());
		this.resourceLoader = resourceLoader;
	}


	@Override
	@Nullable
	public InputSource resolveEntity(@Nullable String publicId, @Nullable String systemId)
			throws SAXException, IOException {
		//调用其父类DelegatingEntityResolver类的方法，进行解析实体
		InputSource source = super.resolveEntity(publicId, systemId);
		//解析失败，并且systemId不为空，使用resourceLoader进行解析
		if (source == null && systemId != null) {
			String resourcePath = null;
			try {
				//使用utf-8进行解码systemId
				String decodedSystemId = URLDecoder.decode(systemId, "UTF-8");
				//转化为URL字符串
				String givenUrl = new URL(decodedSystemId).toString();
				//解析文件资源的相对路径（相对于系统的根路径）
				String systemRootUrl = new File("").toURI().toURL().toString();
				// Try relative to resource base if currently in system root.
				//如果当前资源在系统根目录中，则获取相对于系统根目录的相对路径
				if (givenUrl.startsWith(systemRootUrl)) {
					resourcePath = givenUrl.substring(systemRootUrl.length());
				}
			}
			catch (Exception ex) {
				// Typically a MalformedURLException or AccessControlException.
				if (logger.isDebugEnabled()) {
					logger.debug("Could not resolve XML entity [" + systemId + "] against system root URL", ex);
				}
				// No URL (or no resolvable URL) -> try relative to resource base.
				//如果没有URL或者解析URL失败，则使用systemId
				resourcePath = systemId;
			}
			//如果有资源路径
			if (resourcePath != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Trying to locate XML entity [" + systemId + "] as resource [" + resourcePath + "]");
				}
				//获取Resource资源
				Resource resource = this.resourceLoader.getResource(resourcePath);
				//创建inputSource对象，并设置publicId和systemId属性
				source = new InputSource(resource.getInputStream());
				source.setPublicId(publicId);
				source.setSystemId(systemId);
				if (logger.isDebugEnabled()) {
					logger.debug("Found XML entity [" + systemId + "]: " + resource);
				}
			}
			//如果systemId是DTD或者XSD资源文件
			else if (systemId.endsWith(DTD_SUFFIX) || systemId.endsWith(XSD_SUFFIX)) {
				// External dtd/xsd lookup via https even for canonical http declaration
				String url = systemId;
				//使用https进行对外部的dtd/xsd进行查找
				if (url.startsWith("http:")) {
					url = "https:" + url.substring(5);
				}
				try {
					//通过https的资源路径来创建一个inputSource对象，并设置systemId和publicId属性
					source = new InputSource(new URL(url).openStream());
					source.setPublicId(publicId);
					source.setSystemId(systemId);
				}
				catch (IOException ex) {
					if (logger.isDebugEnabled()) {
						logger.debug("Could not resolve XML entity [" + systemId + "] through URL [" + url + "]", ex);
					}
					// Fall back to the parser's default behavior.
					//如果发生异常，则使用默认行为，从网络上来下载
					source = null;
				}
			}
		}

		return source;
	}

}
