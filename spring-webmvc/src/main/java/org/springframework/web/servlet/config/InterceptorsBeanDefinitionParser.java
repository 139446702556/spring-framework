/*
 * Copyright 2002-2016 the original author or authors.
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

package org.springframework.web.servlet.config;

import java.util.List;

import org.w3c.dom.Element;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.springframework.util.xml.DomUtils;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * {@link org.springframework.beans.factory.xml.BeanDefinitionParser} that parses a
 * {@code interceptors} element to register a set of {@link MappedInterceptor} definitions.
 *
 * @author Keith Donald
 * @since 3.0
 */
class InterceptorsBeanDefinitionParser implements BeanDefinitionParser {

	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext context) {
		context.pushContainingComponent(
				new CompositeComponentDefinition(element.getTagName(), context.extractSource(element)));
		//路径解析器引用
		RuntimeBeanReference pathMatcherRef = null;
		//判断当前元素节点是否设置了path-matcher属性，如果设置了，则使用设置的beanName来创建一个路径匹配器引用对象
		if (element.hasAttribute("path-matcher")) {
			pathMatcherRef = new RuntimeBeanReference(element.getAttribute("path-matcher"));
		}
		//从当前给定的element标签信息来获取其bean、ref、interceptor三个子标签元素对象
		List<Element> interceptors = DomUtils.getChildElementsByTagName(element, "bean", "ref", "interceptor");
		//遍历拦截器标签元素对象数组
		for (Element interceptor : interceptors) {
			//创建MappedInterceptor类型的RootBeanDefinition对象
			RootBeanDefinition mappedInterceptorDef = new RootBeanDefinition(MappedInterceptor.class);
			//设置源对象
			mappedInterceptorDef.setSource(context.extractSource(interceptor));
			//设置role属性
			mappedInterceptorDef.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
			//匹配的路径
			ManagedList<String> includePatterns = null;
			//不匹配的路径
			ManagedList<String> excludePatterns = null;
			//拦截器bean对象
			Object interceptorBean;
			//如果当前标签的标签名为interceptor字符串
			if ("interceptor".equals(interceptor.getLocalName())) {
				//获取当前拦截器标签下的mapping子标签，将其解析为匹配的拦截器的集合
				includePatterns = getIncludePatterns(interceptor, "mapping");
				//获取当前拦截器标签下的exclude-mapping子标签，将其解析为不匹配的拦截器的集合
				excludePatterns = getIncludePatterns(interceptor, "exclude-mapping");
				//获取当前拦截器标签下的bean、ref两个标签中定义的第一个标签元素对象
				Element beanElem = DomUtils.getChildElementsByTagName(interceptor, "bean", "ref").get(0);
				//解析beanElem元素对象，获得对应的bean相关信息
				interceptorBean = context.getDelegate().parsePropertySubElement(beanElem, null);
			}
			//其它标签
			else {
				//证明标签为一个bean标签或者ref标签，则直接解析获取对应的bean信息对象
				interceptorBean = context.getDelegate().parsePropertySubElement(interceptor, null);
			}
			//设置MappedInterceptor构造函数的三个参数
			mappedInterceptorDef.getConstructorArgumentValues().addIndexedArgumentValue(0, includePatterns);
			mappedInterceptorDef.getConstructorArgumentValues().addIndexedArgumentValue(1, excludePatterns);
			mappedInterceptorDef.getConstructorArgumentValues().addIndexedArgumentValue(2, interceptorBean);
			//如果拦截器标签中设置了路径匹配器的引用，则将其添加到MappedInterceptor对象中
			if (pathMatcherRef != null) {
				mappedInterceptorDef.getPropertyValues().add("pathMatcher", pathMatcherRef);
			}
			//返回当前BeanDefinition注册到容器中的beanName
			String beanName = context.getReaderContext().registerWithGeneratedName(mappedInterceptorDef);
			//注册bean组件定义到上下文容器中
			context.registerComponent(new BeanComponentDefinition(mappedInterceptorDef, beanName));
		}

		context.popAndRegisterContainingComponent();
		return null;
	}

	private ManagedList<String> getIncludePatterns(Element interceptor, String elementName) {
		//获取给定的拦截器标签下的给定elementName的对应子标签集合
		List<Element> paths = DomUtils.getChildElementsByTagName(interceptor, elementName);
		//创建匹配集合
		ManagedList<String> patterns = new ManagedList<>(paths.size());
		//遍历paths集合，获取每个标签节点的path属性值，并将其添加到patterns中
		for (Element path : paths) {
			patterns.add(path.getAttribute("path"));
		}
		//返回匹配
		return patterns;
	}

}
