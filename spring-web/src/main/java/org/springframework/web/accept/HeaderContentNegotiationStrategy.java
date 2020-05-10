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

package org.springframework.web.accept;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * A {@code ContentNegotiationStrategy} that checks the 'Accept' request header.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.2
 */
public class HeaderContentNegotiationStrategy implements ContentNegotiationStrategy {

	/**
	 * {@inheritDoc}
	 * @throws HttpMediaTypeNotAcceptableException if the 'Accept' header cannot be parsed
	 * 从给定请求的请求头中解析得到支持的MediaType集合
	 */
	@Override
	public List<MediaType> resolveMediaTypes(NativeWebRequest request)
			throws HttpMediaTypeNotAcceptableException {
		//获取当前请求头中的Accept属性值
		String[] headerValueArray = request.getHeaderValues(HttpHeaders.ACCEPT);
		//如果未获取到，则返回MEDIA_TYPE_ALL_LIST，标识支持所有的类型
		if (headerValueArray == null) {
			return MEDIA_TYPE_ALL_LIST;
		}
		//将数组转换为集合
		List<String> headerValues = Arrays.asList(headerValueArray);
		try {
			//将解析得到的支持类型的字符串描述结合转化为对应的MediaType集合
			List<MediaType> mediaTypes = MediaType.parseMediaTypes(headerValues);
			//根据MediaType的Specificity和Quality两个属性，对其进行排序
			MediaType.sortBySpecificityAndQuality(mediaTypes);
			//返回；如果不是空则返回查找到的集合，否则返回MEDIA_TYPE_ALL_LIST
			return !CollectionUtils.isEmpty(mediaTypes) ? mediaTypes : MEDIA_TYPE_ALL_LIST;
		}
		catch (InvalidMediaTypeException ex) {
			throw new HttpMediaTypeNotAcceptableException(
					"Could not parse 'Accept' header " + headerValues + ": " + ex.getMessage());
		}
	}

}
