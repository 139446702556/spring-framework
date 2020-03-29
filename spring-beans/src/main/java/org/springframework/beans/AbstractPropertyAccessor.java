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

package org.springframework.beans;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;

/**
 * Abstract implementation of the {@link PropertyAccessor} interface.
 * Provides base implementations of all convenience methods, with the
 * implementation of actual property access left to subclasses.
 *
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 2.0
 * @see #getPropertyValue
 * @see #setPropertyValue
 */
public abstract class AbstractPropertyAccessor extends TypeConverterSupport implements ConfigurablePropertyAccessor {

	private boolean extractOldValueForEditor = false;

	private boolean autoGrowNestedPaths = false;


	@Override
	public void setExtractOldValueForEditor(boolean extractOldValueForEditor) {
		this.extractOldValueForEditor = extractOldValueForEditor;
	}

	@Override
	public boolean isExtractOldValueForEditor() {
		return this.extractOldValueForEditor;
	}

	@Override
	public void setAutoGrowNestedPaths(boolean autoGrowNestedPaths) {
		this.autoGrowNestedPaths = autoGrowNestedPaths;
	}

	@Override
	public boolean isAutoGrowNestedPaths() {
		return this.autoGrowNestedPaths;
	}


	@Override
	public void setPropertyValue(PropertyValue pv) throws BeansException {
		setPropertyValue(pv.getName(), pv.getValue());
	}

	@Override
	public void setPropertyValues(Map<?, ?> map) throws BeansException {
		setPropertyValues(new MutablePropertyValues(map));
	}

	@Override
	public void setPropertyValues(PropertyValues pvs) throws BeansException {
		setPropertyValues(pvs, false, false);
	}

	@Override
	public void setPropertyValues(PropertyValues pvs, boolean ignoreUnknown) throws BeansException {
		setPropertyValues(pvs, ignoreUnknown, false);
	}

	@Override
	public void setPropertyValues(PropertyValues pvs, boolean ignoreUnknown, boolean ignoreInvalid)
			throws BeansException {
		//存储属性值注入时产生的异常
		List<PropertyAccessException> propertyAccessExceptions = null;
		//获取属性集合
		List<PropertyValue> propertyValues = (pvs instanceof MutablePropertyValues ?
				((MutablePropertyValues) pvs).getPropertyValueList() : Arrays.asList(pvs.getPropertyValues()));

		//遍历属性集合
		for (PropertyValue pv : propertyValues) {
			try {
				// This method may throw any BeansException, which won't be caught
				// here, if there is a critical failure such as no matching field.
				// We can attempt to deal only with less serious exceptions.
				//给bean对象注入相应的属性值，通过获取属性的引用来设置（通过反射设置字段为可访问），或者使用属性对应的setter方法来设置
				//具体通过那种方式来注入，取决于使用的实现的属性访问器的setValue方法
				setPropertyValue(pv);
			}
			catch (NotWritablePropertyException ex) {
				if (!ignoreUnknown) {
					throw ex;
				}
				// Otherwise, just ignore it and continue...
			}
			catch (NullValueInNestedPathException ex) {
				if (!ignoreInvalid) {
					throw ex;
				}
				// Otherwise, just ignore it and continue...
			}
			catch (PropertyAccessException ex) {
				//记录属性访问异常，先吞掉异常后续处理
				if (propertyAccessExceptions == null) {
					propertyAccessExceptions = new ArrayList<>();
				}
				propertyAccessExceptions.add(ex);
			}
		}

		// If we encountered individual exceptions, throw the composite exception.
		//如果在依赖注入时，发生了PropertyAccessException异常，则抛出异常对象
		if (propertyAccessExceptions != null) {
			PropertyAccessException[] paeArray = propertyAccessExceptions.toArray(new PropertyAccessException[0]);
			throw new PropertyBatchUpdateException(paeArray);
		}
	}


	// Redefined with public visibility.
	@Override
	@Nullable
	public Class<?> getPropertyType(String propertyPath) {
		return null;
	}

	/**
	 * Actually get the value of a property.
	 * @param propertyName name of the property to get the value of
	 * @return the value of the property
	 * @throws InvalidPropertyException if there is no such property or
	 * if the property isn't readable
	 * @throws PropertyAccessException if the property was valid but the
	 * accessor method failed
	 */
	@Override
	@Nullable
	public abstract Object getPropertyValue(String propertyName) throws BeansException;

	/**
	 * Actually set a property value.
	 * @param propertyName name of the property to set value of
	 * @param value the new value
	 * @throws InvalidPropertyException if there is no such property or
	 * if the property isn't writable
	 * @throws PropertyAccessException if the property was valid but the
	 * accessor method failed or a type mismatch occurred
	 */
	@Override
	public abstract void setPropertyValue(String propertyName, @Nullable Object value) throws BeansException;

}
