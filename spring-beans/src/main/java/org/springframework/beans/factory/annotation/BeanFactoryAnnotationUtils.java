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

package org.springframework.beans.factory.annotation;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Convenience methods performing bean lookups related to Spring-specific annotations,
 * for example Spring's {@link Qualifier @Qualifier} annotation.
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @since 3.1.2
 * @see BeanFactoryUtils
 */
public abstract class BeanFactoryAnnotationUtils {

	/**
	 * Retrieve all bean of type {@code T} from the given {@code BeanFactory} declaring a
	 * qualifier (e.g. via {@code <qualifier>} or {@code @Qualifier}) matching the given
	 * qualifier, or having a bean name matching the given qualifier.
	 * @param beanFactory the factory to get the target beans from (also searching ancestors)
	 * @param beanType the type of beans to retrieve
	 * @param qualifier the qualifier for selecting among all type matches
	 * @return the matching beans of type {@code T}
	 * @throws BeansException if any of the matching beans could not be created
	 * @since 5.1.1
	 * @see BeanFactoryUtils#beansOfTypeIncludingAncestors(ListableBeanFactory, Class)
	 */
	public static <T> Map<String, T> qualifiedBeansOfType(
			ListableBeanFactory beanFactory, Class<T> beanType, String qualifier) throws BeansException {

		String[] candidateBeans = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, beanType);
		Map<String, T> result = new LinkedHashMap<>(4);
		for (String beanName : candidateBeans) {
			if (isQualifierMatch(qualifier::equals, beanName, beanFactory)) {
				result.put(beanName, beanFactory.getBean(beanName, beanType));
			}
		}
		return result;
	}

	/**
	 * Obtain a bean of type {@code T} from the given {@code BeanFactory} declaring a
	 * qualifier (e.g. via {@code <qualifier>} or {@code @Qualifier}) matching the given
	 * qualifier, or having a bean name matching the given qualifier.
	 * @param beanFactory the factory to get the target bean from (also searching ancestors)
	 * @param beanType the type of bean to retrieve
	 * @param qualifier the qualifier for selecting between multiple bean matches
	 * @return the matching bean of type {@code T} (never {@code null})
	 * @throws NoUniqueBeanDefinitionException if multiple matching beans of type {@code T} found
	 * @throws NoSuchBeanDefinitionException if no matching bean of type {@code T} found
	 * @throws BeansException if the bean could not be created
	 * @see BeanFactoryUtils#beanOfTypeIncludingAncestors(ListableBeanFactory, Class)
	 */
	public static <T> T qualifiedBeanOfType(BeanFactory beanFactory, Class<T> beanType, String qualifier)
			throws BeansException {

		Assert.notNull(beanFactory, "BeanFactory must not be null");
		//如果给定beanFactory是ListableBeanFactory类型实例对象
		if (beanFactory instanceof ListableBeanFactory) {
			// Full qualifier matching supported.
			//则检测所有的支持限定符的可能的匹配（bean的xml中定义的限定符、工厂方法上Qualifier注解定义的限定符、bean的类型的类上Qualifier注解定义的限定符）
			return qualifiedBeanOfType((ListableBeanFactory) beanFactory, beanType, qualifier);
		}
		//检测给定限定符在beanFactory容器中是否存在定义了与其匹配的bean
		else if (beanFactory.containsBean(qualifier)) {
			// Fallback: target bean at least found by bean name.
			//如果此beanFactory容器中存在，则通过beanName在上下文容器中获取对应的bean实例对象
			return beanFactory.getBean(qualifier, beanType);
		}
		else {
			throw new NoSuchBeanDefinitionException(qualifier, "No matching " + beanType.getSimpleName() +
					" bean found for bean name '" + qualifier +
					"'! (Note: Qualifier matching not supported because given " +
					"BeanFactory does not implement ConfigurableListableBeanFactory.)");
		}
	}

	/**
	 * Obtain a bean of type {@code T} from the given {@code BeanFactory} declaring a qualifier
	 * (e.g. {@code <qualifier>} or {@code @Qualifier}) matching the given qualifier).
	 * @param bf the factory to get the target bean from
	 * @param beanType the type of bean to retrieve
	 * @param qualifier the qualifier for selecting between multiple bean matches
	 * @return the matching bean of type {@code T} (never {@code null})
	 */
	private static <T> T qualifiedBeanOfType(ListableBeanFactory bf, Class<T> beanType, String qualifier) {
		//从给定beanFactory容器中以及其父类链路中的全部beanFactory中获取给定beanType类型的全部beanName集合
		String[] candidateBeans = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(bf, beanType);
		//存储匹配的beanName
		String matchingBean = null;
		//遍历全部的候选bean集合
		for (String beanName : candidateBeans) {
			//如果当前bean与给定的限定符匹配
			if (isQualifierMatch(qualifier::equals, beanName, bf)) {
				//如果已经存在了匹配的bean，则抛出异常（给定的限定符和beanType只能确定唯一一个bean，多个则报错）
				if (matchingBean != null) {
					throw new NoUniqueBeanDefinitionException(beanType, matchingBean, beanName);
				}
				//存储与给定bean类型和限定符匹配的beanName
				matchingBean = beanName;
			}
		}
		//如果匹配到了对应的beanName，则去给定的beanFactory容器中获取对应的bean实例对象，并返回
		if (matchingBean != null) {
			return bf.getBean(matchingBean, beanType);
		}
		//在当前上下文容器中获取到的所有给定类型的bean中如果没有与给定限定符匹配的
		//则检测当前beanFactory容器中是否有与给定限定符匹配的bean（限定符则为别名、factoryBean或者beanName）
		else if (bf.containsBean(qualifier)) {
			// Fallback: target bean at least found by bean name - probably a manually registered singleton.
			//至少通过beanName找到目标bean，此bean可能是手动注册的单例对象
			return bf.getBean(qualifier, beanType);
		}
		//如果在给定beanFactory上下文容器中无法找到与给定限定符相匹配的bean，则抛出NoSuchBeanDefinitionException异常
		else {
			throw new NoSuchBeanDefinitionException(qualifier, "No matching " + beanType.getSimpleName() +
					" bean found for qualifier '" + qualifier + "' - neither qualifier match nor bean name match!");
		}
	}

	/**
	 * Check whether the named bean declares a qualifier of the given name.
	 * @param qualifier the qualifier to match 与给定限定符匹配的方法
	 * @param beanName the name of the candidate bean
	 * @param beanFactory the factory from which to retrieve the named bean
	 * @return {@code true} if either the bean definition (in the XML case)
	 * or the bean's factory method (in the {@code @Bean} case) defines a matching
	 * qualifier value (through {@code <qualifier>} or {@code @Qualifier})
	 * @since 5.0
	 */
	public static boolean isQualifierMatch(
			Predicate<String> qualifier, String beanName, @Nullable BeanFactory beanFactory) {

		// Try quick bean name or alias match first...
		//如果给定限定符和给定的beanName匹配，则返回true
		if (qualifier.test(beanName)) {
			return true;
		}
		//给定beanFactory不为null
		if (beanFactory != null) {
			//遍历给定beanName对应的别名集合，判断给定限定符如果有与别名匹配的，则返回true
			for (String alias : beanFactory.getAliases(beanName)) {
				if (qualifier.test(alias)) {
					return true;
				}
			}
			try {
				//从beanFactory容器中获取beanName对应bean的类型
				Class<?> beanType = beanFactory.getType(beanName);
				//如果beanFactory为ConfigurableBeanFactory类型实例
				if (beanFactory instanceof ConfigurableBeanFactory) {
					//获取给定beanFactory容器以及其相关父类容器中的beanName对应的所有beanDefinition对象的属性合并后的beanDefinition对象
					BeanDefinition bd = ((ConfigurableBeanFactory) beanFactory).getMergedBeanDefinition(beanName);
					// Explicit qualifier metadata on bean definition? (typically in XML definition)
					//bean定义上的显示限定符元数据（通过在xml中定义的）（bd为AbstractBeanDefinition类型实例）
					if (bd instanceof AbstractBeanDefinition) {
						AbstractBeanDefinition abd = (AbstractBeanDefinition) bd;
						//从缓存中获取Qualifier类型名注解对应的AutowireCandidateQualifier实例对象
						AutowireCandidateQualifier candidate = abd.getQualifier(Qualifier.class.getName());
						//如果存在
						if (candidate != null) {
							//获取Qualifier类型注解对象的value属性值
							Object value = candidate.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
							//如果value不为null，并且给定的限定符与当前bean设置的Qualifier注解的value值匹配，则返回true
							if (value != null && qualifier.test(value.toString())) {
								return true;
							}
						}
					}
					// Corresponding qualifier on factory method? (typically in configuration class)
					//工厂方法上相应的限定词（通常在配置类中）（RootBeanDefinition类型实例）
					if (bd instanceof RootBeanDefinition) {
						//获取创建该bean时使用的工厂方法对象
						Method factoryMethod = ((RootBeanDefinition) bd).getResolvedFactoryMethod();
						//获取成功
						if (factoryMethod != null) {
							//从factoryMethod方法对象上获取其标注的Qualifier注解对象
							Qualifier targetAnnotation = AnnotationUtils.getAnnotation(factoryMethod, Qualifier.class);
							//检测Qualifier注解中的value值是否与给定限定符匹配
							if (targetAnnotation != null) {
								return qualifier.test(targetAnnotation.value());
							}
						}
					}
				}
				// Corresponding qualifier on bean implementation class? (for custom user types)
				//bean实现类上相应的限定词?(适用于自定义用户类型)
				if (beanType != null) {
					//从当前bean的类型定义上获取Qualifier注解属性对象，并判断其value属性值是否与给定限定符值匹配
					Qualifier targetAnnotation = AnnotationUtils.getAnnotation(beanType, Qualifier.class);
					if (targetAnnotation != null) {
						return qualifier.test(targetAnnotation.value());
					}
				}
			}
			catch (NoSuchBeanDefinitionException ex) {
				// Ignore - can't compare qualifiers for a manually registered singleton object
				//忽略--不能比较手动注册的单例对象的限定符
			}
		}
		return false;
	}

}
