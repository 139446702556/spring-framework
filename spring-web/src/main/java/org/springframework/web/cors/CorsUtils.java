/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.web.cors;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * Utility class for CORS request handling based on the
 * <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>.
 *
 * @author Sebastien Deleuze
 * @since 4.2
 */
public abstract class CorsUtils {

	/**
	 * Returns {@code true} if the request is a valid CORS one.
	 * 如果请求是有效的CORS请求（跨域请求），则返回true
	 * 通过检测当前请求头中是否有名为Origin的header对象
	 */
	public static boolean isCorsRequest(HttpServletRequest request) {
		return (request.getHeader(HttpHeaders.ORIGIN) != null);
	}

	/**
	 * Returns {@code true} if the request is a valid CORS pre-flight one.
	 * 如果给定的请求是一个有效的CORS请求，则返回true
	 */
	public static boolean isPreFlightRequest(HttpServletRequest request) {
		return (isCorsRequest(request) && HttpMethod.OPTIONS.matches(request.getMethod()) &&
				request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null);
	}

}
