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

package org.springframework.beans.factory.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringValueResolver;

/**
 * Visitor class for traversing {@link BeanDefinition} objects, in particular
 * the property values and constructor argument values contained in them,
 * resolving bean metadata values.
 *
 * <p>Used by {@link PropertyPlaceholderConfigurer} to parse all String values
 * contained in a BeanDefinition, resolving any placeholders found.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 1.2
 * @see BeanDefinition
 * @see BeanDefinition#getPropertyValues
 * @see BeanDefinition#getConstructorArgumentValues
 * @see PropertyPlaceholderConfigurer
 */
public class BeanDefinitionVisitor {

	@Nullable
	private StringValueResolver valueResolver;


	/**
	 * Create a new BeanDefinitionVisitor, applying the specified
	 * value resolver to all bean metadata values.
	 * @param valueResolver the StringValueResolver to apply
	 */
	public BeanDefinitionVisitor(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		this.valueResolver = valueResolver;
	}

	/**
	 * Create a new BeanDefinitionVisitor for subclassing.
	 * Subclasses need to override the {@link #resolveStringValue} method.
	 */
	protected BeanDefinitionVisitor() {
	}


	/**
	 * Traverse the given BeanDefinition object and the MutablePropertyValues
	 * and ConstructorArgumentValues contained in them.
	 * 用来解析beanDefinition相关属性值中的占位符
	 * @param beanDefinition the BeanDefinition object to traverse
	 * @see #resolveStringValue(String)
	 */
	public void visitBeanDefinition(BeanDefinition beanDefinition) {
		//解析ParentName中的占位符
		visitParentName(beanDefinition);
		//解析BeanClassName中的占位符
		visitBeanClassName(beanDefinition);
		//解析FactoryBeanName中的占位符
		visitFactoryBeanName(beanDefinition);
		//解析FactoryMethodName中的占位符
		visitFactoryMethodName(beanDefinition);
		//解析Scope中的占位符
		visitScope(beanDefinition);
		//检测当前bean是否设置了属性值对象，即属性标签，如果设置了则解析属性值中的占位符
		if (beanDefinition.hasPropertyValues()) {
			visitPropertyValues(beanDefinition.getPropertyValues());
		}
		//当前bean定义中是否设置了构造参数值，如果设置了则解析索引参数集合和通用参数集合中参数值中的占位符
		if (beanDefinition.hasConstructorArgumentValues()) {
			ConstructorArgumentValues cas = beanDefinition.getConstructorArgumentValues();
			visitIndexedArgumentValues(cas.getIndexedArgumentValues());
			visitGenericArgumentValues(cas.getGenericArgumentValues());
		}
	}

	protected void visitParentName(BeanDefinition beanDefinition) {
		String parentName = beanDefinition.getParentName();
		if (parentName != null) {
			String resolvedName = resolveStringValue(parentName);
			if (!parentName.equals(resolvedName)) {
				beanDefinition.setParentName(resolvedName);
			}
		}
	}

	protected void visitBeanClassName(BeanDefinition beanDefinition) {
		//获取beanClassName
		String beanClassName = beanDefinition.getBeanClassName();
		//如果不为空
		if (beanClassName != null) {
			//解析beanClassName中的占位符，将其替换为属性文件中设置的真实值
			String resolvedName = resolveStringValue(beanClassName);
			//如果其中存在占位符，并得到解析，则将解析后得到的值设置会beanDefinition中
			if (!beanClassName.equals(resolvedName)) {
				beanDefinition.setBeanClassName(resolvedName);
			}
		}
	}

	protected void visitFactoryBeanName(BeanDefinition beanDefinition) {
		String factoryBeanName = beanDefinition.getFactoryBeanName();
		if (factoryBeanName != null) {
			String resolvedName = resolveStringValue(factoryBeanName);
			if (!factoryBeanName.equals(resolvedName)) {
				beanDefinition.setFactoryBeanName(resolvedName);
			}
		}
	}

	protected void visitFactoryMethodName(BeanDefinition beanDefinition) {
		String factoryMethodName = beanDefinition.getFactoryMethodName();
		if (factoryMethodName != null) {
			String resolvedName = resolveStringValue(factoryMethodName);
			if (!factoryMethodName.equals(resolvedName)) {
				beanDefinition.setFactoryMethodName(resolvedName);
			}
		}
	}

	protected void visitScope(BeanDefinition beanDefinition) {
		String scope = beanDefinition.getScope();
		if (scope != null) {
			String resolvedScope = resolveStringValue(scope);
			if (!scope.equals(resolvedScope)) {
				beanDefinition.setScope(resolvedScope);
			}
		}
	}

	protected void visitPropertyValues(MutablePropertyValues pvs) {
		//获取当前属性值对象的属性值数组
		PropertyValue[] pvArray = pvs.getPropertyValues();
		//遍历属性值数组
		for (PropertyValue pv : pvArray) {
			//解析当前属性值中的占位符，使用真值替换
			Object newVal = resolveValue(pv.getValue());
			//如果此属性值中存在占位符，并被替换，则将其重新设置到属性对象中
			if (!ObjectUtils.nullSafeEquals(newVal, pv.getValue())) {
				pvs.add(pv.getName(), newVal);
			}
		}
	}

	protected void visitIndexedArgumentValues(Map<Integer, ConstructorArgumentValues.ValueHolder> ias) {
		for (ConstructorArgumentValues.ValueHolder valueHolder : ias.values()) {
			Object newVal = resolveValue(valueHolder.getValue());
			if (!ObjectUtils.nullSafeEquals(newVal, valueHolder.getValue())) {
				valueHolder.setValue(newVal);
			}
		}
	}

	protected void visitGenericArgumentValues(List<ConstructorArgumentValues.ValueHolder> gas) {
		for (ConstructorArgumentValues.ValueHolder valueHolder : gas) {
			Object newVal = resolveValue(valueHolder.getValue());
			if (!ObjectUtils.nullSafeEquals(newVal, valueHolder.getValue())) {
				valueHolder.setValue(newVal);
			}
		}
	}

	@SuppressWarnings("rawtypes")
	@Nullable
	protected Object resolveValue(@Nullable Object value) {
		//检测当前给定的值是否为BeanDefinition，如果是，递归调用visitBeanDefinition方法进行访问，转换占位符
		if (value instanceof BeanDefinition) {
			visitBeanDefinition((BeanDefinition) value);
		}
		//给定值为BeanDefinitionHolder类型，递归调用
		else if (value instanceof BeanDefinitionHolder) {
			visitBeanDefinition(((BeanDefinitionHolder) value).getBeanDefinition());
		}
		//如果给定的value对象为RuntimeBeanReference类型
		else if (value instanceof RuntimeBeanReference) {
			RuntimeBeanReference ref = (RuntimeBeanReference) value;
			//转换当前运行时bean引用的bean名称为真值（解析其中的占位符，将其替换为属性中对应的值）
			String newBeanName = resolveStringValue(ref.getBeanName());
			//如果解析后得到的新beanName为空，则返回null
			if (newBeanName == null) {
				return null;
			}
			//如果解析后得到的新值和原始的beanName不相同，则使用解析后的新beanName创建一个RuntimeBeanReference对象，并返回
			if (!newBeanName.equals(ref.getBeanName())) {
				return new RuntimeBeanReference(newBeanName);
			}
		}
		//如果给定值为RuntimeBeanNameReference类型
		else if (value instanceof RuntimeBeanNameReference) {
			RuntimeBeanNameReference ref = (RuntimeBeanNameReference) value;
			//解析其中的beanName的占位符，返回真值
			String newBeanName = resolveStringValue(ref.getBeanName());
			//返回null
			if (newBeanName == null) {
				return null;
			}
			//使用newBeanName创建一个RuntimeBeanNameReference对象并返回
			if (!newBeanName.equals(ref.getBeanName())) {
				return new RuntimeBeanNameReference(newBeanName);
			}
		}
		//对象数组
		else if (value instanceof Object[]) {
			visitArray((Object[]) value);
		}
		//集合类
		else if (value instanceof List) {
			visitList((List) value);
		}
		//Set类型
		else if (value instanceof Set) {
			visitSet((Set) value);
		}
		//Map类型
		else if (value instanceof Map) {
			visitMap((Map) value);
		}
		//TypedStringValue类型，获取其中的值，如果不为空，则进行解析，并将解析后得到的真值重新设置会对象中
		else if (value instanceof TypedStringValue) {
			TypedStringValue typedStringValue = (TypedStringValue) value;
			String stringValue = typedStringValue.getValue();
			if (stringValue != null) {
				String visitedString = resolveStringValue(stringValue);
				typedStringValue.setValue(visitedString);
			}
		}
		//如果给定值为字符串类型，则直接使用访问字符串值的方式解析
		else if (value instanceof String) {
			return resolveStringValue((String) value);
		}
		return value;
	}

	protected void visitArray(Object[] arrayVal) {
		//遍历对象数组，使用递归调用resolveValue方法来对其进行解析，并且使用解析后的真值覆盖掉原先的原始值
		for (int i = 0; i < arrayVal.length; i++) {
			Object elem = arrayVal[i];
			Object newVal = resolveValue(elem);
			if (!ObjectUtils.nullSafeEquals(newVal, elem)) {
				arrayVal[i] = newVal;
			}
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	protected void visitList(List listVal) {
		//遍历集合，递归调用resolveValue方法解析每个元素，并使用解析后的真值替换掉原始值
		for (int i = 0; i < listVal.size(); i++) {
			Object elem = listVal.get(i);
			Object newVal = resolveValue(elem);
			if (!ObjectUtils.nullSafeEquals(newVal, elem)) {
				listVal.set(i, newVal);
			}
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	protected void visitSet(Set setVal) {
		//存储新值的对象
		Set newContent = new LinkedHashSet();
		//此标识表示该Set集合中是否有元素包含了占位符，并被解析
		boolean entriesModified = false;
		//遍历给定Set对象
		for (Object elem : setVal) {
			//获取当前元素的hash值
			int elemHash = (elem != null ? elem.hashCode() : 0);
			//解析当前元素中的占位符，返回新的对象
			Object newVal = resolveValue(elem);
			//获取解析后新值的hash值
			int newValHash = (newVal != null ? newVal.hashCode() : 0);
			//将新值添加到newContent集合中
			newContent.add(newVal);
			//如果解析后的新对象和原始对象不同，或者hash值不同，则将此标识置为true
			entriesModified = entriesModified || (newVal != elem || newValHash != elemHash);
		}
		//如果Set内部值变化了
		if (entriesModified) {
			//清除给定Set中的全部元素
			setVal.clear();
			//将解析后的全部新值添加到其中
			setVal.addAll(newContent);
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	protected void visitMap(Map<?, ?> mapVal) {
		//保存解析后值的对象
		Map newContent = new LinkedHashMap();
		//是否有新值产生
		boolean entriesModified = false;
		//遍历Map中的元素
		for (Map.Entry entry : mapVal.entrySet()) {
			//获取key，并获取key的hash值
			Object key = entry.getKey();
			int keyHash = (key != null ? key.hashCode() : 0);
			//解析key，获得真值
			Object newKey = resolveValue(key);
			//获取新值的hash值
			int newKeyHash = (newKey != null ? newKey.hashCode() : 0);
			//获取值
			Object val = entry.getValue();
			//解析值
			Object newVal = resolveValue(val);
			//保存解析得到的新值
			newContent.put(newKey, newVal);
			//判断值是否有改变
			entriesModified = entriesModified || (newVal != val || newKey != key || newKeyHash != keyHash);
		}
		//如果值有改变，清除给定值中的对象，并将新对象添加到其中
		if (entriesModified) {
			mapVal.clear();
			mapVal.putAll(newContent);
		}
	}

	/**
	 * Resolve the given String value, for example parsing placeholders.
	 * 解析给定的字符串，将其中的占位符替换为实际值
	 * @param strVal the original String value
	 * @return the resolved String value
	 */
	@Nullable
	protected String resolveStringValue(String strVal) {
		if (this.valueResolver == null) {
			throw new IllegalStateException("No StringValueResolver specified - pass a resolver " +
					"object into the constructor or override the 'resolveStringValue' method");
		}
		//解析字符串中的占位符，将占位符替换为实际值
		String resolvedValue = this.valueResolver.resolveStringValue(strVal);
		// Return original String if not modified.
		//如果给定值中有占位符，则返回解析后的值，否则返回给定值
		return (strVal.equals(resolvedValue) ? strVal : resolvedValue);
	}

}
