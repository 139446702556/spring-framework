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

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aopalliance.intercept.Interceptor;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.IntroductionAwareMethodMatcher;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.lang.Nullable;

/**
 * A simple but definitive way of working out an advice chain for a Method,
 * given an {@link Advised} object. Always rebuilds each advice chain;
 * caching can be provided by subclasses.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Adrian Colyer
 * @since 2.0.3
 */
@SuppressWarnings("serial")
public class DefaultAdvisorChainFactory implements AdvisorChainFactory, Serializable {

	@Override
	public List<Object> getInterceptorsAndDynamicInterceptionAdvice(
			Advised config, Method method, @Nullable Class<?> targetClass) {

		// This is somewhat tricky... We have to process introductions first,
		// but we need to preserve order in the ultimate list.
		//获取注册器registry为DefaultAdvisorAdapterRegistry类型的
		AdvisorAdapterRegistry registry = GlobalAdvisorAdapterRegistry.getInstance();
		//获取符合当前bean对象的全部通知器集合
		Advisor[] advisors = config.getAdvisors();
		//用于存储适合当前方法的拦截器集合
		List<Object> interceptorList = new ArrayList<>(advisors.length);
		//获取实际的目标类型
		Class<?> actualClass = (targetClass != null ? targetClass : method.getDeclaringClass());
		Boolean hasIntroductions = null;
		//遍历通知器列表
		for (Advisor advisor : advisors) {
			//如果advisor为PointCutAdvisor类型的实例（需要对类型和方法都匹配）
			if (advisor instanceof PointcutAdvisor) {
				// Add it conditionally.
				PointcutAdvisor pointcutAdvisor = (PointcutAdvisor) advisor;
				//如果已经为特定的目标类过滤了通知器或者使用ClassFilter对bean类型进行匹配（如果无法匹配，则说明当前通知器不适合使用在当前bean上）
				if (config.isPreFiltered() || pointcutAdvisor.getPointcut().getClassFilter().matches(actualClass)) {
					//获取当前通知器中的方法匹配器
					MethodMatcher mm = pointcutAdvisor.getPointcut().getMethodMatcher();
					boolean match;
					//如果获取到的方法匹配器是IntroductionAwareMethodMatcher类型实例
					if (mm instanceof IntroductionAwareMethodMatcher) {
						if (hasIntroductions == null) {
							//判断给定通知器集合中是否有与目标类型相匹配的
							hasIntroductions = hasMatchingIntroductions(advisors, actualClass);
						}
						//通过使用MethodMatcher（方法匹配器）对目标方法进行匹配
						match = ((IntroductionAwareMethodMatcher) mm).matches(method, actualClass, hasIntroductions);
					}
					else {
						//通过方法匹配器对目标方法进行匹配
						match = mm.matches(method, actualClass);
					}
					//如果当前通知器与给定方法匹配
					if (match) {
						//将advisor中的advice转化为相应的拦截器
						MethodInterceptor[] interceptors = registry.getInterceptors(advisor);
						//isRuntime如果返回true，则表示MethodMatcher要在运行时做一些检测
						if (mm.isRuntime()) {
							// Creating a new object instance in the getInterceptors() method
							// isn't a problem as we normally cache created chains.
							//将所有的拦截器与对应的方法匹配器包装为InterceptorAndDynamicMethodMatcher类型对象，添加到拦截器集合中返回
							for (MethodInterceptor interceptor : interceptors) {
								interceptorList.add(new InterceptorAndDynamicMethodMatcher(interceptor, mm));
							}
						}
						//如果不需要在运行时检测，则直接将通知转换得到的拦截器添加到interceptorList中
						else {
							interceptorList.addAll(Arrays.asList(interceptors));
						}
					}
				}
			}
			//如果advisor为IntroductionAdvisor类型实例（只对类型进行匹配）
			else if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				//如果已经为目标类过滤了通知器或者使用给定的通知器的类型过滤器对bean类型进行匹配
				if (config.isPreFiltered() || ia.getClassFilter().matches(actualClass)) {
					//将advisor中的advice转换为相应的拦截器数组
					Interceptor[] interceptors = registry.getInterceptors(advisor);
					//将转换得到的拦截器加入到interceptorList中
					interceptorList.addAll(Arrays.asList(interceptors));
				}
			}
			//如果当前advisor是其它类型的实例，则无需进行类型和方法的匹配，直接将advisor的advice转换为对应的拦截器
			//然后添加到interceptorList中
			else {
				Interceptor[] interceptors = registry.getInterceptors(advisor);
				interceptorList.addAll(Arrays.asList(interceptors));
			}
		}

		return interceptorList;
	}

	/**
	 * Determine whether the Advisors contain matching introductions.
	 * 确定给定的通知器数组中是否有适合给定类的通知器
	 */
	private static boolean hasMatchingIntroductions(Advisor[] advisors, Class<?> actualClass) {
		//迭代通知器集合
		for (Advisor advisor : advisors) {
			//如果advisor是IntroductionAdvisor类型实例
			if (advisor instanceof IntroductionAdvisor) {
				//使用该通知器的类型过滤器与给定类型进行匹配，如果匹配返回true
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				if (ia.getClassFilter().matches(actualClass)) {
					return true;
				}
			}
		}
		//如果所有的通知器均与给定类型不匹配，则返回false
		return false;
	}

}
