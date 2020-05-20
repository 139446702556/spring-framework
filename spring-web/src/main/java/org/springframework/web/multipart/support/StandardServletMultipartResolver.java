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

package org.springframework.web.multipart.support;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.apache.commons.logging.LogFactory;

import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;

/**
 * Standard implementation of the {@link MultipartResolver} interface,
 * based on the Servlet 3.0 {@link javax.servlet.http.Part} API.
 * To be added as "multipartResolver" bean to a Spring DispatcherServlet context,
 * without any extra configuration at the bean level (see below).
 *
 * <p><b>Note:</b> In order to use Servlet 3.0 based multipart parsing,
 * you need to mark the affected servlet with a "multipart-config" section in
 * {@code web.xml}, or with a {@link javax.servlet.MultipartConfigElement}
 * in programmatic servlet registration, or (in case of a custom servlet class)
 * possibly with a {@link javax.servlet.annotation.MultipartConfig} annotation
 * on your servlet class. Configuration settings such as maximum sizes or
 * storage locations need to be applied at that servlet registration level;
 * Servlet 3.0 does not allow for them to be set at the MultipartResolver level.
 *
 * <pre class="code">
 * public class AppInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {
 *	 // ...
 *	 &#064;Override
 *	 protected void customizeRegistration(ServletRegistration.Dynamic registration) {
 *     // Optionally also set maxFileSize, maxRequestSize, fileSizeThreshold
 *     registration.setMultipartConfig(new MultipartConfigElement("/tmp"));
 *   }
 * }
 * </pre>
 *
 * @author Juergen Hoeller
 * @since 3.1
 * @see #setResolveLazily
 * @see HttpServletRequest#getParts()
 * @see org.springframework.web.multipart.commons.CommonsMultipartResolver
 * 此类为使用了Servlet 3.0的标准上传API的MultipartResolver的实现类
 */
public class StandardServletMultipartResolver implements MultipartResolver {
	/**是否延迟解析   是否在需要获得对应文件时，再进行文件的解析*/
	private boolean resolveLazily = false;


	/**
	 * Set whether to resolve the multipart request lazily at the time of
	 * file or parameter access.
	 * <p>Default is "false", resolving the multipart elements immediately, throwing
	 * corresponding exceptions at the time of the {@link #resolveMultipart} call.
	 * Switch this to "true" for lazy multipart parsing, throwing parse exceptions
	 * once the application attempts to obtain multipart files or parameters.
	 * @since 3.2.9
	 */
	public void setResolveLazily(boolean resolveLazily) {
		this.resolveLazily = resolveLazily;
	}

	/**判断当前请求是否为Multipart类型请求*/
	@Override
	public boolean isMultipart(HttpServletRequest request) {
		//判断当前请求的Content-Type属性的值是否是以multipart/开头的
		return StringUtils.startsWithIgnoreCase(request.getContentType(), "multipart/");
	}

	@Override
	public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) throws MultipartException {
		//通过给定的请求创建一个StandardMultipartHttpServletRequest对象，并返回（其中resolveLazily字段控制了是否直接解析）
		return new StandardMultipartHttpServletRequest(request, this.resolveLazily);
	}
	/**删除在处理Multipart请求时产生的临时Part们*/
	@Override
	public void cleanupMultipart(MultipartHttpServletRequest request) {
		//如果当前给定的request请求不是AbstractMultipartHttpServletRequest类型的
		//或者它是此类型，并且是已经被解析了的
		if (!(request instanceof AbstractMultipartHttpServletRequest) ||
				((AbstractMultipartHttpServletRequest) request).isResolved()) {
			// To be on the safe side: explicitly delete the parts,
			// but only actual file parts (for Resin compatibility)
			try {
				//遍历当前request请求中的Part数组
				for (Part part : request.getParts()) {
					//如果当前part部分的名称在给定request请求中有对应的MultipartFile对象，则删除当前临时的part对象
					if (request.getFile(part.getName()) != null) {
						part.delete();
					}
				}
			}
			catch (Throwable ex) {
				//记录错误日志
				LogFactory.getLog(getClass()).warn("Failed to perform cleanup of multipart items", ex);
			}
		}
	}

}
