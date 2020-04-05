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

package org.springframework.beans.factory.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Simple implementation of the {@link BeanDefinitionRegistry} interface.
 * Provides registry capabilities only, with no factory capabilities built in.
 * Can for example be used for testing bean definition readers.
 * 当前类是BeanDefinitionRegistry接口的一个简单实现，它还继承了SimpleAliasRegistry类（AliasRegistry接口的简单实现）
 * 它只提供注册表的功能，无工厂的功能
 * @author Juergen Hoeller
 * @since 2.5.2
 */
public class SimpleBeanDefinitionRegistry extends SimpleAliasRegistry implements BeanDefinitionRegistry {

	/**
	 * Map of bean definition objects, keyed by bean name.
	 * bean definition对象的映射容器，key为bean的名字，值为bean definition，默认容量为64（注册表）
	 * 它是线程安全的，当前类中的全部针对注册表的操作均是针对当前给定beanDefinitionMap注册表 map结构的操作
	  */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(64);


	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {
		//判断给定参数不为空，添加到注册表中
		Assert.hasText(beanName, "'beanName' must not be empty");
		Assert.notNull(beanDefinition, "BeanDefinition must not be null");
		this.beanDefinitionMap.put(beanName, beanDefinition);
	}

	@Override
	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		//移除注册表中指定beanName对应的数据，删除失败抛出异常
		if (this.beanDefinitionMap.remove(beanName) == null) {
			throw new NoSuchBeanDefinitionException(beanName);
		}
	}

	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		//从注册表中获取给定beanName对应的BeanDefinition信息，如果注册表中未存在，则抛出NoSuchBeanDefinitionException异常
		//否则直接返回
		BeanDefinition bd = this.beanDefinitionMap.get(beanName);
		if (bd == null) {
			throw new NoSuchBeanDefinitionException(beanName);
		}
		return bd;
	}

	@Override
	public boolean containsBeanDefinition(String beanName) {
		return this.beanDefinitionMap.containsKey(beanName);
	}

	@Override
	public String[] getBeanDefinitionNames() {
		return StringUtils.toStringArray(this.beanDefinitionMap.keySet());
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	@Override
	public boolean isBeanNameInUse(String beanName) {
		return isAlias(beanName) || containsBeanDefinition(beanName);
	}

}
