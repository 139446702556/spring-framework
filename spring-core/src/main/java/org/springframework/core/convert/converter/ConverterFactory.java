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

package org.springframework.core.convert.converter;

/**
 * A factory for "ranged" converters that can convert objects from S to subtypes of R.
 * 一个用于远程转换的转换工厂，可以将对象从S类型转换R的子类型
 * 用于1：N的source->target的类型转换
 * <p>Implementations may additionally implement {@link ConditionalConverter}.
 *
 * @author Keith Donald
 * @since 3.0
 * @param <S> the source type converters created by this factory can convert from
 * @param <R> the target range (or base) type converters created by this factory can convert to;
 * for example {@link Number} for a set of number subtypes.
 * @see ConditionalConverter
 */
public interface ConverterFactory<S, R> {

	/**
	 * Get the converter to convert from S to target type T, where T is also an instance of R.
	 * 获取指定目标类型对应的转换器，其可以将对象从S类型转换为目标类型T，其中T是R的子类
	 * @param <T> the target type
	 * @param targetType the target type to convert to
	 * @return a converter from S to T
	 */
	<T extends R> Converter<S, T> getConverter(Class<T> targetType);

}
