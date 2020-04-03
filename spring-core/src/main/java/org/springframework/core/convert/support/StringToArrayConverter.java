/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.core.convert.support;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.Set;

import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Converts a comma-delimited String to an Array.
 * Only matches if String.class can be converted to the target array element type.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @since 3.0
 */
final class StringToArrayConverter implements ConditionalGenericConverter {

	private final ConversionService conversionService;


	public StringToArrayConverter(ConversionService conversionService) {
		this.conversionService = conversionService;
	}


	@Override
	public Set<ConvertiblePair> getConvertibleTypes() {
		return Collections.singleton(new ConvertiblePair(String.class, Object[].class));
	}

	@Override
	public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
		return ConversionUtils.canConvertElements(sourceType, targetType.getElementTypeDescriptor(),
				this.conversionService);
	}

	@Override
	@Nullable
	public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
		//源对象为null，则直接返回
		if (source == null) {
			return null;
		}
		//将源对象转换为字符串
		String string = (String) source;
		//按照逗号来切割给定的字符串，得到字符串数组
		String[] fields = StringUtils.commaDelimitedListToStringArray(string);
		//获取TypeDescriptor对象
		TypeDescriptor targetElementType = targetType.getElementTypeDescriptor();
		//检查目标元素类型不可为空
		Assert.state(targetElementType != null, "No target element type");
		//创建目标数组（给定目标类型的，指定长度的数组）
		Object target = Array.newInstance(targetElementType.getType(), fields.length);
		//遍历字符串切割得到的数组
		for (int i = 0; i < fields.length; i++) {
			//获取原数据
			String sourceElement = fields[i];
			//递归调用，转换原数据为目标类型，返回目标数据
			Object targetElement = this.conversionService.convert(sourceElement.trim(), sourceType, targetElementType);
			//将转换得到的目标元素插入到目标数组target中
			Array.set(target, i, targetElement);
		}
		//返回转换得到的目标对象
		return target;
	}

}
