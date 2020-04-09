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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving @AspectJ beans from a BeanFactory and building
 * Spring Advisors based on them, for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AnnotationAwareAspectJAutoProxyCreator
 */
public class BeanFactoryAspectJAdvisorsBuilder {

	private final ListableBeanFactory beanFactory;

	private final AspectJAdvisorFactory advisorFactory;

	@Nullable
	private volatile List<String> aspectBeanNames;

	private final Map<String, List<Advisor>> advisorsCache = new ConcurrentHashMap<>();

	private final Map<String, MetadataAwareAspectInstanceFactory> aspectFactoryCache = new ConcurrentHashMap<>();


	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory) {
		this(beanFactory, new ReflectiveAspectJAdvisorFactory(beanFactory));
	}

	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 * @param advisorFactory the AspectJAdvisorFactory to build each Advisor with
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		Assert.notNull(advisorFactory, "AspectJAdvisorFactory must not be null");
		this.beanFactory = beanFactory;
		this.advisorFactory = advisorFactory;
	}


	/**
	 * Look for AspectJ-annotated aspect beans in the current bean factory,
	 * and return to a list of Spring AOP Advisors representing them.
	 * 从当前上下文beanFactory ioc容器中查找标注了@Aspect注解的bean对象，并返回全部查找到的spring aop 监听器集合
	 * <p>Creates a Spring Advisor for each AspectJ advice method.
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	public List<Advisor> buildAspectJAdvisors() {
		//使用双重检查锁，来从aspectBeanNames缓存中获取所有标注@Aspect注解的bean名称
		List<String> aspectNames = this.aspectBeanNames;

		if (aspectNames == null) {
			synchronized (this) {
				aspectNames = this.aspectBeanNames;
				if (aspectNames == null) {
					//用于存储通知器的集合
					List<Advisor> advisors = new ArrayList<>();
					//初始化
					aspectNames = new ArrayList<>();
					//从容器中获取所有的bean名称
					String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
							this.beanFactory, Object.class, true, false);
					//遍历beanNames
					for (String beanName : beanNames) {
						//判断当前beanName对应的bean是否合格（默认返回true，交由子类实现），如果不合格，则跳过此beanName
						if (!isEligibleBean(beanName)) {
							continue;
						}
						// We must be careful not to instantiate beans eagerly as in this case they
						// would be cached by the Spring container but would not have been weaved.
						//根据beanName获取bean的类型，如果类型为空，则跳过此bean
						Class<?> beanType = this.beanFactory.getType(beanName);
						if (beanType == null) {
							continue;
						}
						//检测beanType是否包含Aspect注解
						if (this.advisorFactory.isAspect(beanType)) {
							//将当前beanName添加到aspectNames中
							aspectNames.add(beanName);
							//创建一个AspectMetadata对象
							AspectMetadata amd = new AspectMetadata(beanType, beanName);
							//当前切面实例化对象设置的是单例模式
							if (amd.getAjType().getPerClause().getKind() == PerClauseKind.SINGLETON) {
								//创建一个BeanFactoryAspectInstanceFactory对象
								MetadataAwareAspectInstanceFactory factory =
										new BeanFactoryAspectInstanceFactory(this.beanFactory, beanName);
								//获取通知器
								List<Advisor> classAdvisors = this.advisorFactory.getAdvisors(factory);
								//如果当前beanName对应的bean设置为单例模式的
								if (this.beanFactory.isSingleton(beanName)) {
									//则将其添加到advisorsCache（通知器缓存）中
									this.advisorsCache.put(beanName, classAdvisors);
								}
								else {
									//如果为原型模式，则将当前beanName和其切面工厂保存到aspectFactoryCache缓存中
									this.aspectFactoryCache.put(beanName, factory);
								}
								advisors.addAll(classAdvisors);
							}
							else {
								// Per target or per this.
								//如果当前beanName对应的bean设置为单例模式，则抛出异常
								if (this.beanFactory.isSingleton(beanName)) {
									throw new IllegalArgumentException("Bean with name '" + beanName +
											"' is a singleton, but aspect instantiation model is not singleton");
								}
								//创建一个PrototypeAspectInstanceFactory对象
								MetadataAwareAspectInstanceFactory factory =
										new PrototypeAspectInstanceFactory(this.beanFactory, beanName);
								//因为是原型模式的bean和切面，所以只对其切面工厂进行缓存
								this.aspectFactoryCache.put(beanName, factory);
								//获取通知器，并添加到advisors中
								advisors.addAll(this.advisorFactory.getAdvisors(factory));
							}
						}
					}
					//将当前获得的有@Aspect注解的beanName集合添加到缓存中
					this.aspectBeanNames = aspectNames;
					//返回获取的全部通知器
					return advisors;
				}
			}
		}
		//如果缓存中或者获取到的带有@Aspect注解的beanName集合为空的话，则标识没有此类bean，则返回空集合
		if (aspectNames.isEmpty()) {
			return Collections.emptyList();
		}
		//用于存储通知器对象的集合
		List<Advisor> advisors = new ArrayList<>();
		//迭代aspectNames
		for (String aspectName : aspectNames) {
			//从缓存中获取aspectName对应的通知器集
			List<Advisor> cachedAdvisors = this.advisorsCache.get(aspectName);
			//如果获取到了，则直接将其添加到advisors中
			if (cachedAdvisors != null) {
				advisors.addAll(cachedAdvisors);
			}
			else {
				//如果没从缓存中获取到此名称对应的通知器集
				//则从缓存中获取当前name获取通知器使用的工厂对象，并进行通知器的获取
				MetadataAwareAspectInstanceFactory factory = this.aspectFactoryCache.get(aspectName);
				advisors.addAll(this.advisorFactory.getAdvisors(factory));
			}
		}
		return advisors;
	}

	/**
	 * Return whether the aspect bean with the given name is eligible.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
