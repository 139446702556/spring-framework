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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		//定义一个set来保存所有的BeanFactoryPostProcessors
		Set<String> processedBeans = new HashSet<>();
		//判断当前beanFactory是否为BeanDefinitionRegistry类型
		if (beanFactory instanceof BeanDefinitionRegistry) {
			//强转类型
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			//BeanFactoryPostProcessor集合
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			//BeanDefinitionRegistryPostProcessor集合
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();
			//迭代注册的beanFactoryPostProcessors
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				//判断当前postProcesspr是否为BeanDefinitionRegistryPostProcessor类型
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					//如果是，则调用其postProcessBeanDefinitionRegistry方法进行注册，同时将其加入到registryProcessors集合中
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					registryProcessors.add(registryProcessor);
				}
				else {
					//否则为普通的bean工厂后置处理器，则加入到regularPostProcessors中，后续处理
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			//用于保存当前处理的BeanDefinitionRegistryPostProcessor类型的bean实例对象
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			//从当前上下文的beanFactory容器中获取BeanDefinitionRegistryPostProcessor类型的bean名称
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			//首先，处理实现了PriorityOrdered接口的BeanDefinitionRegistryPostProcessor类型的bean对象
			//迭代当前上下文中全部BeanDefinitionRegistryPostProcessor类型的bean名称
			for (String ppName : postProcessorNames) {
				//如果当前beanName对应的bean对象实现了PriorityOrdered接口
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					//从当前容器中获取指定名称和类型的bean实例，并将其添加到currentRegistryProcessors中
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					//将处理过的beanName添加到processedBeans中
					processedBeans.add(ppName);
				}
			}
			//根据不同的优先级进行排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			//加入到registryProcessors中
			registryProcessors.addAll(currentRegistryProcessors);
			//调用所有实现了PriorityOrdered接口的BeanDefinitionRegistryPostProcessors的bean的postProcessBeanDefinitionRegistry方法，来进行注册
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			//清空集合，以为后续使用
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			//接下来，调用实现了Ordered接口的全部BeanDefinitionRegistryPostProcessors
			//其逻辑和上面类似
			//获取上下文ioc容器中BeanDefinitionRegistryPostProcessor类型的全部beanName
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			//迭代postProcessorNames
			for (String ppName : postProcessorNames) {
				//如果当前beanName对应的bean对象没有被处理过（执行过相应方法），并且其实现了Ordered接口
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					//获取当前beanName和类型对应的bean对象，并添加到currentRegistryProcessors中
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					//将处理过的beanName添加到processedBeans中
					processedBeans.add(ppName);
				}
			}
			//排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			//添加到registryProcessors中
			registryProcessors.addAll(currentRegistryProcessors);
			//调用实现了Ordered接口的BeanDefinitionRegistryPostProcessors类型对象的postProcessBeanDefinitionRegistry方法进行注册操作
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			//清空集合，准备后续使用
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			//最后，调用全部其它的BeanDefinitionRegistryPostProcessors
			//表示循环是否反复的做
			boolean reiterate = true;
			while (reiterate) {
				//无需继续循环
				reiterate = false;
				//从上下文容器中获取BeanDefinitionRegistryPostProcessor
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				//迭代
				for (String ppName : postProcessorNames) {
					//如果当前BeanDefinitionRegistryPostProcessor没有被处理过
					if (!processedBeans.contains(ppName)) {
						//获取其对应的bean对象，并添加到currentRegistryProcessors中
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						//将处理过的bean添加到其中
						processedBeans.add(ppName);
						//如果当前有需要处理的bean，则将其置为true，表示需要在检查一次
						reiterate = true;
					}
				}
				//排序
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				//添加到registryProcessors中
				registryProcessors.addAll(currentRegistryProcessors);
				//执行其的postProcessBeanDefinitionRegistry方法
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
				//清除
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			//现在，调用所有的registryProcessors（手动注册和配置文件自动注册）和regularPostProcessors（手动注册）中的回调函数（postProcessBeanFactory方法）
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			//如果给定的beanFactory不是BeanDefinitionRegistry的实例，则无需做任何检查
			//直接调用全部手动注册的BeanFactoryPostProcessor的postProcessBeanFactory方法即可
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		//从当前上下文容器中获取全部BeanFactoryPostProcessor类型的bean对象名称集合
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		//用于存储实现了PriorityOrdered接口的BeanFactoryPostProcessor实例
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		//存储实现了Ordered接口的BeanFactoryPostProcessor的beanName
		List<String> orderedPostProcessorNames = new ArrayList<>();
		//存储没有实现任何排序接口的BeanFactoryPostProcessor的beanName
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		//迭代
		for (String ppName : postProcessorNames) {
			//如果当前BeanFactoryPostProcessor已经处理过了，则直接跳过
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			//如果当前BeanFactoryPostProcessor实现了PriorityOrdered接口，则获取其实例对象，并添加到priorityOrderedPostProcessors中
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			//如果当前BeanFactoryPostProcessor实现了Ordered接口，则将其beanName添加到orderedPostProcessorNames中
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			//如果当前对象没有实现上述两种排序接口，则将beanName添加到nonOrderedPostProcessorNames中
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		//首先，处理实现了PriorityOrdered接口的BeanFactoryPostProcessors
		//排序
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		//调用其postProcessBeanFactory方法
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		//然后，处理实现了Ordered接口的BeanFactoryPostProcessor对象
		//用于存储实现了Ordered接口的BeanFactoryPostProcessor实例
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>();
		//从上下文容器中获取指定beanName对应的BeanFactoryPostProcessor对象并添加到orderedPostProcessors中
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		//排序
		sortPostProcessors(orderedPostProcessors, beanFactory);
		//调用其postProcessBeanFactory方法
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		//最后调用全部其它的BeanFactoryPostProcessors的postProcessBeanFactory方法
		//存储未实现排序接口的bean工厂后置处理器对象
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>();
		//获取其对象加入到nonOrderedPostProcessors中
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		//调用其postProcessBeanFactory方法
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		//清除上下文中全部当前操作用到的缓存
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {
		//获取所有的BeanPostProcessor的beanName
		//这些bean名称都已经全部加载到容器中去了，但是没有实例化
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		//计算当前beanFactory中注册的全部BeanPostProcessor的个数（手动注册加上自动注册加1）（多加的一个1是因为注册了一个BeanPostProcessorChecker）
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		//注册BeanPostProcessorChecker，它的作用是用于在BeanPostProcessor实例化期间记录日志
		//当一个bean不能被所有的BeanPostProcessor处理时
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		//存储的为实现了PriorityOrdered接口的类的处理器对象，可以保证其顺序
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		//MergedBeanDefinitionPostProcessor
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		//使用Ordered保证顺序（存储beanName）
		List<String> orderedPostProcessorNames = new ArrayList<>();
		//没有顺序（存储beanName）
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		//迭代全部以bean的形式注册的BeanPostProcessor对应的bean名称
		for (String ppName : postProcessorNames) {
			//检查当前ppName对应的bean对象所属的类是否实现了PriorityOrdered接口
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				//获取指定名称的bean实例化对象
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				//将此bean后置处理器添加到priorityOrderedPostProcessors中
				priorityOrderedPostProcessors.add(pp);
				//如果当前BeanPostProcessor对象是MergedBeanDefinitionPostProcessor接口的实现类的对象
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					//将其添加到internalPostProcessors中
					internalPostProcessors.add(pp);
				}
			}
			//如果当前bean对象的所属类实现了Ordered接口，则将其添加到orderedPostProcessorNames集合中
			//否则将其添加到nonOrderedPostProcessorNames集合中
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		//首先，先注册所有实现了PriorityOrdered接口的BeanPostProcessor
		//先排序
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		//然后注册bean的后置处理器
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		//然后，注册所有实现了Ordered接口的BeanPostProcessor
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>();
		//遍历实现了Ordered接口的BeanPostProcessor的bean名称集合
		for (String ppName : orderedPostProcessorNames) {
			//获取指定名称对应的bean对象
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			//添加到orderedPostProcessors中
			orderedPostProcessors.add(pp);
			//如果当前Bean的后置处理器对象实现了MergedBeanDefinitionPostProcessor接口，则将其添加到internalPostProcessors中
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		//对orderedPostProcessors中元素进行排序
		sortPostProcessors(orderedPostProcessors, beanFactory);
		//注册orderedPostProcessors中的bean后置处理器
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		//现在，注册所有无序的BeanPostProcessor
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>();
		//将所有的无序的bean名称，转化为对应的beanPostProcessor对象
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		//注册所有的无序BeanPostProcessor
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		//最后，注册所有的内部的BeanPostProcessor（即实现了MergedBeanDefinitionPostProcessor接口的bean后置处理器）
		//排序
		sortPostProcessors(internalPostProcessors, beanFactory);
		//将其注册到beanFactory中
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		//将检测内部bean的后置处理器重新注册为ApplicationListener，将其移动到处理器链表的末端（用于获取代理等）
		//将ApplicationListenerDetector（探测器）加入到beanFactory的处理器集合末尾
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		//获取beanFactory所依赖的Comparator（比较器）对象
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		//使用的比较器对象
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		//根据不同的比较器，使用集合对象中getOrder方法返回的优先排序值，使用不同的逻辑对其进行排序
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 * 遍历给定的Bean定义注册器的后置处理器集合，调用其postProcessBeanDefinitionRegistry方法进行注册
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry);
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 * 调用给定的BeanFactoryPostProcessor集合中所有的postProcessBeanFactory回调方法
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {
		//遍历当前给定的bean后置处理器集合，将其一一注册到beanFactory中
		for (BeanPostProcessor postProcessor : postProcessors) {
			beanFactory.addBeanPostProcessor(postProcessor);
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
