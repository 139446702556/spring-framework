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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.mail.internet.MimeUtility;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Spring MultipartHttpServletRequest adapter, wrapping a Servlet 3.0 HttpServletRequest
 * and its Part objects. Parameters get exposed through the native request's getParameter
 * methods - without any custom processing on our side.
 *
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @since 3.1
 * @see StandardServletMultipartResolver
 * 此类为基于servlet 3.0的Multipart HttpServletRequest的实现类
 */
public class StandardMultipartHttpServletRequest extends AbstractMultipartHttpServletRequest {
	/**普通参数名称的集合 指的是非上传文件的参数名*/
	@Nullable
	private Set<String> multipartParameterNames;


	/**
	 * Create a new StandardMultipartHttpServletRequest wrapper for the given request,
	 * immediately parsing the multipart content.
	 * @param request the servlet request to wrap
	 * @throws MultipartException if parsing failed
	 */
	public StandardMultipartHttpServletRequest(HttpServletRequest request) throws MultipartException {
		this(request, false);
	}

	/**
	 * Create a new StandardMultipartHttpServletRequest wrapper for the given request.
	 * @param request the servlet request to wrap
	 * @param lazyParsing whether multipart parsing should be triggered lazily on
	 * first access of multipart files or parameters
	 * @throws MultipartException if an immediate parsing attempt failed
	 * @since 3.2.9
	 */
	public StandardMultipartHttpServletRequest(HttpServletRequest request, boolean lazyParsing)
			throws MultipartException {
		//调用父类的构造函数
		super(request);
		//如果不是延迟加载，则解析当前给定的请求
		if (!lazyParsing) {
			parseRequest(request);
		}
	}

	/**解析给定的请求对象*/
	private void parseRequest(HttpServletRequest request) {
		try {
			//获取当前请求中的Part数组对象
			Collection<Part> parts = request.getParts();
			//初始化multipartParameterNames字段
			this.multipartParameterNames = new LinkedHashSet<>(parts.size());
			//用于存储解析当前请求得到的MultipartFile对象
			MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>(parts.size());
			//遍历请求中的Part数组
			for (Part part : parts) {
				//获取CONTENT_DISPOSITION头的值
				String headerValue = part.getHeader(HttpHeaders.CONTENT_DISPOSITION);
				//解析CONTENT_DISPOSITION头的值，获取到其对应的ContentDisposition对象
				ContentDisposition disposition = ContentDisposition.parse(headerValue);
				//获取文件名称
				String filename = disposition.getFilename();
				//情况一，文件名非空，说明当前是文件参数，则创建StandardMultipartFile对象，并将其添加到files中
				if (filename != null) {
					//如果文件名是以=？开头的并且以？=结尾的
					if (filename.startsWith("=?") && filename.endsWith("?=")) {
						//则对当前文件名进行解码
						filename = MimeDelegate.decode(filename);
					}
					//将part.name与当前part和filename创建的StandardMultipartFile对象的关系添加到files中
					files.add(part.getName(), new StandardMultipartFile(part, filename));
				}
				//情况二，文件名为空，说明当前是普通参数，则将part.name添加到multipartParameterNames中
				else {
					this.multipartParameterNames.add(part.getName());
				}
			}
			//将files设置到multipartFiles的属性中
			setMultipartFiles(files);
		}
		catch (Throwable ex) {
			//处理解析失败产生的异常
			handleParseFailure(ex);
		}
	}
	/**根据给定异常的信息，抛出相应的异常*/
	protected void handleParseFailure(Throwable ex) {
		//获取异常信息
		String msg = ex.getMessage();
		//如果异常信息不为空，并且信息内容包含size和exced，则抛出MaxUploadSizeExceededException异常
		if (msg != null && msg.contains("size") && msg.contains("exceed")) {
			throw new MaxUploadSizeExceededException(-1, ex);
		}
		//其它情况，则抛出MultipartException异常
		throw new MultipartException("Failed to parse multipart servlet request", ex);
	}

	@Override
	protected void initializeMultipart() {
		//解析当前请求
		parseRequest(getRequest());
	}

	@Override
	public Enumeration<String> getParameterNames() {
		//通过判断multipartParameterNames属性是否为空，来判断是否已经初始化
		if (this.multipartParameterNames == null) {
			//初始化Multipart属性，实则为解析当前请求
			initializeMultipart();
		}
		if (this.multipartParameterNames.isEmpty()) {
			return super.getParameterNames();
		}

		// Servlet 3.0 getParameterNames() not guaranteed to include multipart form items
		// (e.g. on WebLogic 12) -> need to merge them here to be on the safe side
		Set<String> paramNames = new LinkedHashSet<>();
		Enumeration<String> paramEnum = super.getParameterNames();
		while (paramEnum.hasMoreElements()) {
			paramNames.add(paramEnum.nextElement());
		}
		paramNames.addAll(this.multipartParameterNames);
		return Collections.enumeration(paramNames);
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		if (this.multipartParameterNames == null) {
			initializeMultipart();
		}
		if (this.multipartParameterNames.isEmpty()) {
			return super.getParameterMap();
		}

		// Servlet 3.0 getParameterMap() not guaranteed to include multipart form items
		// (e.g. on WebLogic 12) -> need to merge them here to be on the safe side
		Map<String, String[]> paramMap = new LinkedHashMap<>(super.getParameterMap());
		for (String paramName : this.multipartParameterNames) {
			if (!paramMap.containsKey(paramName)) {
				paramMap.put(paramName, getParameterValues(paramName));
			}
		}
		return paramMap;
	}

	@Override
	public String getMultipartContentType(String paramOrFileName) {
		try {
			Part part = getPart(paramOrFileName);
			return (part != null ? part.getContentType() : null);
		}
		catch (Throwable ex) {
			throw new MultipartException("Could not access multipart servlet request", ex);
		}
	}

	@Override
	public HttpHeaders getMultipartHeaders(String paramOrFileName) {
		try {
			Part part = getPart(paramOrFileName);
			if (part != null) {
				HttpHeaders headers = new HttpHeaders();
				for (String headerName : part.getHeaderNames()) {
					headers.put(headerName, new ArrayList<>(part.getHeaders(headerName)));
				}
				return headers;
			}
			else {
				return null;
			}
		}
		catch (Throwable ex) {
			throw new MultipartException("Could not access multipart servlet request", ex);
		}
	}


	/**
	 * Spring MultipartFile adapter, wrapping a Servlet 3.0 Part object.
	 */
	@SuppressWarnings("serial")
	private static class StandardMultipartFile implements MultipartFile, Serializable {

		private final Part part;

		private final String filename;

		public StandardMultipartFile(Part part, String filename) {
			this.part = part;
			this.filename = filename;
		}

		@Override
		public String getName() {
			return this.part.getName();
		}

		@Override
		public String getOriginalFilename() {
			return this.filename;
		}

		@Override
		public String getContentType() {
			return this.part.getContentType();
		}

		@Override
		public boolean isEmpty() {
			return (this.part.getSize() == 0);
		}

		@Override
		public long getSize() {
			return this.part.getSize();
		}

		@Override
		public byte[] getBytes() throws IOException {
			return FileCopyUtils.copyToByteArray(this.part.getInputStream());
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return this.part.getInputStream();
		}

		@Override
		public void transferTo(File dest) throws IOException, IllegalStateException {
			this.part.write(dest.getPath());
			if (dest.isAbsolute() && !dest.exists()) {
				// Servlet 3.0 Part.write is not guaranteed to support absolute file paths:
				// may translate the given path to a relative location within a temp dir
				// (e.g. on Jetty whereas Tomcat and Undertow detect absolute paths).
				// At least we offloaded the file from memory storage; it'll get deleted
				// from the temp dir eventually in any case. And for our user's purposes,
				// we can manually copy it to the requested location as a fallback.
				FileCopyUtils.copy(this.part.getInputStream(), Files.newOutputStream(dest.toPath()));
			}
		}

		@Override
		public void transferTo(Path dest) throws IOException, IllegalStateException {
			FileCopyUtils.copy(this.part.getInputStream(), Files.newOutputStream(dest));
		}
	}


	/**
	 * Inner class to avoid a hard dependency on the JavaMail API.
	 */
	private static class MimeDelegate {

		public static String decode(String value) {
			try {
				return MimeUtility.decodeText(value);
			}
			catch (UnsupportedEncodingException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

}
