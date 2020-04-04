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

package org.springframework.beans;

import java.lang.reflect.Field;

import org.springframework.core.MethodParameter;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;

/**
 * Interface that defines type conversion methods. Typically (but not necessarily)
 * implemented in conjunction with the {@link PropertyEditorRegistry} interface.
 * 定义类型转换的接口，将一个对象转化为指定类型
 * spring 3之前TypeConverter是基于PropertyEditor（线程不安全）实现的，所以是线程不安全的
 * 而在spring 3 之后基于ConversionService（线程安全的，它的方法实现都是用了同步锁）实现的TypeConverter则是线程安全的
 * <p><b>Note:</b> Since TypeConverter implementations are typically based on
 * {@link java.beans.PropertyEditor PropertyEditors} which aren't thread-safe,
 * TypeConverters themselves are <em>not</em> to be considered as thread-safe either.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see SimpleTypeConverter
 * @see BeanWrapperImpl
 */
public interface TypeConverter {

	/**
	 * Convert the value to the required type (if necessary from a String).
	 * <p>Conversions from String to any type will typically use the {@code setAsText}
	 * method of the PropertyEditor class, or a Spring Converter in a ConversionService.
	 * @param value the value to convert
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @return the new value, possibly the result of type conversion
	 * @throws TypeMismatchException if type conversion failed
	 * @see java.beans.PropertyEditor#setAsText(String)
	 * @see java.beans.PropertyEditor#getValue()
	 * @see org.springframework.core.convert.ConversionService
	 * @see org.springframework.core.convert.converter.Converter
	 */
	@Nullable
	<T> T convertIfNecessary(@Nullable Object value, @Nullable Class<T> requiredType) throws TypeMismatchException;

	/**
	 * Convert the value to the required type (if necessary from a String).
	 * <p>Conversions from String to any type will typically use the {@code setAsText}
	 * method of the PropertyEditor class, or a Spring Converter in a ConversionService.
	 * @param value the value to convert
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @param methodParam the method parameter that is the target of the conversion
	 * (for analysis of generic types; may be {@code null})
	 * @return the new value, possibly the result of type conversion
	 * @throws TypeMismatchException if type conversion failed
	 * @see java.beans.PropertyEditor#setAsText(String)
	 * @see java.beans.PropertyEditor#getValue()
	 * @see org.springframework.core.convert.ConversionService
	 * @see org.springframework.core.convert.converter.Converter
	 */
	@Nullable
	<T> T convertIfNecessary(@Nullable Object value, @Nullable Class<T> requiredType,
			@Nullable MethodParameter methodParam) throws TypeMismatchException;

	/**
	 * Convert the value to the required type (if necessary from a String).
	 * <p>Conversions from String to any type will typically use the {@code setAsText}
	 * method of the PropertyEditor class, or a Spring Converter in a ConversionService.
	 * @param value the value to convert
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @param field the reflective field that is the target of the conversion
	 * (for analysis of generic types; may be {@code null})
	 * @return the new value, possibly the result of type conversion
	 * @throws TypeMismatchException if type conversion failed
	 * @see java.beans.PropertyEditor#setAsText(String)
	 * @see java.beans.PropertyEditor#getValue()
	 * @see org.springframework.core.convert.ConversionService
	 * @see org.springframework.core.convert.converter.Converter
	 */
	@Nullable
	<T> T convertIfNecessary(@Nullable Object value, @Nullable Class<T> requiredType, @Nullable Field field)
			throws TypeMismatchException;

	/**
	 * Convert the value to the required type (if necessary from a String).
	 * <p>Conversions from String to any type will typically use the {@code setAsText}
	 * method of the PropertyEditor class, or a Spring Converter in a ConversionService.
	 * @param value the value to convert
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @param typeDescriptor the type descriptor to use (may be {@code null}))
	 * @return the new value, possibly the result of type conversion
	 * @throws TypeMismatchException if type conversion failed
	 * @since 5.1.4
	 * @see java.beans.PropertyEditor#setAsText(String)
	 * @see java.beans.PropertyEditor#getValue()
	 * @see org.springframework.core.convert.ConversionService
	 * @see org.springframework.core.convert.converter.Converter
	 */
	@Nullable
	default <T> T convertIfNecessary(@Nullable Object value, @Nullable Class<T> requiredType,
			@Nullable TypeDescriptor typeDescriptor) throws TypeMismatchException {

		throw new UnsupportedOperationException("TypeDescriptor resolution not supported");
	}

}
