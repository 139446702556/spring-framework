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

package org.springframework.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Simple implementation of the {@link AliasRegistry} interface.
 * Serves as base class for
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
 * implementations.
 *
 * @author Juergen Hoeller
 * @since 2.5.2
 */
public class SimpleAliasRegistry implements AliasRegistry {

	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * Map from alias to canonical name.
	 * 用于存储别名和bean名称的映射
	 */
	private final Map<String, String> aliasMap = new ConcurrentHashMap<>(16);


	@Override
	public void registerAlias(String name, String alias) {
		//校验name、alias不能为空
		Assert.hasText(name, "'name' must not be empty");
		Assert.hasText(alias, "'alias' must not be empty");
		//锁定全局变量
		synchronized (this.aliasMap) {
			//如果当前的别名和bean名称相等的话，则移除alias，并且记录debug日志
			if (alias.equals(name)) {
				this.aliasMap.remove(alias);
				if (logger.isDebugEnabled()) {
					logger.debug("Alias definition '" + alias + "' ignored since it points to same name");
				}
			}
			else {
				//获取alias已经注册的beanName
				String registeredName = this.aliasMap.get(alias);
				//已经存在
				if (registeredName != null) {
					//和当前beanName相同，则无需注册
					if (registeredName.equals(name)) {
						// An existing alias - no need to re-register
						return;
					}
					//不允许别名覆盖，则抛出IllegalStateException异常
					if (!allowAliasOverriding()) {
						throw new IllegalStateException("Cannot define alias '" + alias + "' for name '" +
								name + "': It is already registered for name '" + registeredName + "'.");
					}
					if (logger.isDebugEnabled()) {
						logger.debug("Overriding alias '" + alias + "' definition for registered name '" +
								registeredName + "' with new target name '" + name + "'");
					}
				}
				//检查是否存在循环指向
				checkForAliasCircle(name, alias);
				//注册alias和beanName的映射
				this.aliasMap.put(alias, name);
				if (logger.isTraceEnabled()) {
					logger.trace("Alias definition '" + alias + "' registered for name '" + name + "'");
				}
			}
		}
	}

	/**
	 * Return whether alias overriding is allowed.
	 * Default is {@code true}.
	 */
	protected boolean allowAliasOverriding() {
		return true;
	}

	/**
	 * Determine whether the given name has the given alias registered.
	 * 确定给定的名称是否注册了给定的别名
	 * @param name the name to check
	 * @param alias the alias to look for
	 * @since 4.2.1
	 */
	public boolean hasAlias(String name, String alias) {
		//循环遍历aliasMap映射表，看当前alias和name的关系是否存在循环引用，即（A，1）（1，B）A和B为bean名称和别名
		//或者看当前name和alias是否已经注册了
		for (Map.Entry<String, String> entry : this.aliasMap.entrySet()) {
			String registeredName = entry.getValue();
			if (registeredName.equals(name)) {
				String registeredAlias = entry.getKey();
				if (registeredAlias.equals(alias) || hasAlias(registeredAlias, alias)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void removeAlias(String alias) {
		synchronized (this.aliasMap) {
			String name = this.aliasMap.remove(alias);
			if (name == null) {
				throw new IllegalStateException("No alias '" + alias + "' registered");
			}
		}
	}

	@Override
	public boolean isAlias(String name) {
		return this.aliasMap.containsKey(name);
	}

	@Override
	public String[] getAliases(String name) {
		List<String> result = new ArrayList<>();
		synchronized (this.aliasMap) {
			retrieveAliases(name, result);
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Transitively retrieve all aliases for the given name.
	 * 可传递的检索给定名称的所有别名（传递是因为每个别名可能还有对应的别名）
	 * @param name the target name to find aliases for
	 * @param result the resulting aliases list
	 */
	private void retrieveAliases(String name, List<String> result) {
		this.aliasMap.forEach((alias, registeredName) -> {
			if (registeredName.equals(name)) {
				result.add(alias);
				retrieveAliases(alias, result);
			}
		});
	}

	/**
	 * Resolve all alias target names and aliases registered in this
	 * factory, applying the given StringValueResolver to them.
	 * <p>The value resolver may for example resolve placeholders
	 * in target bean names and even in alias names.
	 * @param valueResolver the StringValueResolver to apply
	 */
	public void resolveAliases(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		synchronized (this.aliasMap) {
			Map<String, String> aliasCopy = new HashMap<>(this.aliasMap);
			aliasCopy.forEach((alias, registeredName) -> {
				String resolvedAlias = valueResolver.resolveStringValue(alias);
				String resolvedName = valueResolver.resolveStringValue(registeredName);
				if (resolvedAlias == null || resolvedName == null || resolvedAlias.equals(resolvedName)) {
					this.aliasMap.remove(alias);
				}
				else if (!resolvedAlias.equals(alias)) {
					String existingName = this.aliasMap.get(resolvedAlias);
					if (existingName != null) {
						if (existingName.equals(resolvedName)) {
							// Pointing to existing alias - just remove placeholder
							this.aliasMap.remove(alias);
							return;
						}
						throw new IllegalStateException(
								"Cannot register resolved alias '" + resolvedAlias + "' (original: '" + alias +
								"') for name '" + resolvedName + "': It is already registered for name '" +
								registeredName + "'.");
					}
					checkForAliasCircle(resolvedName, resolvedAlias);
					this.aliasMap.remove(alias);
					this.aliasMap.put(resolvedAlias, resolvedName);
				}
				else if (!registeredName.equals(resolvedName)) {
					this.aliasMap.put(alias, resolvedName);
				}
			});
		}
	}

	/**
	 * Check whether the given name points back to the given alias as an alias
	 * in the other direction already, catching a circular reference upfront
	 * and throwing a corresponding IllegalStateException.
	 * @param name the candidate name
	 * @param alias the candidate alias
	 * @see #registerAlias
	 * @see #hasAlias
	 */
	protected void checkForAliasCircle(String name, String alias) {
		if (hasAlias(alias, name)) {
			throw new IllegalStateException("Cannot register alias '" + alias +
					"' for name '" + name + "': Circular reference - '" +
					name + "' is a direct or indirect alias for '" + alias + "' already");
		}
	}

	/**
	 * Determine the raw name, resolving aliases to canonical names.
	 * 获取指定的原始名称，即通过alias在映射表中获取对应的beanName（规范名称）
	 * @param name the user-specified name
	 * @return the transformed name
	 */
	public String canonicalName(String name) {
		String canonicalName = name;
		// Handle aliasing...
		String resolvedName;
		//通过循环在别名和bean名称的映射表中获取到alias对应的bean名称
		//如果没有获取到，则直接返回当前名称
		//使用循环获取的原因是为了防止，比如别名A指向别名B，而别名B对应值S，则这里返回的就是S
		do {
			resolvedName = this.aliasMap.get(canonicalName);
			if (resolvedName != null) {
				canonicalName = resolvedName;
			}
		}
		while (resolvedName != null);
		return canonicalName;
	}

}
