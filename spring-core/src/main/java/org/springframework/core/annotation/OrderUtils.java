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

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * General utility for determining the order of an object based on its type declaration.
 * Handles Spring's {@link Order} annotation as well as {@link javax.annotation.Priority}.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @since 4.1
 * @see Order
 * @see javax.annotation.Priority
 */
@SuppressWarnings("unchecked")
public abstract class OrderUtils {

	/** Cache marker for a non-annotated Class. */
	private static final Object NOT_ANNOTATED = new Object();


	@Nullable
	private static Class<? extends Annotation> priorityAnnotationType;

	static {
		try {
			priorityAnnotationType = (Class<? extends Annotation>)
					ClassUtils.forName("javax.annotation.Priority", OrderUtils.class.getClassLoader());
		}
		catch (Throwable ex) {
			// javax.annotation.Priority not available
			priorityAnnotationType = null;
		}
	}


	/** Cache for @Order value (or NOT_ANNOTATED marker) per Class. */
	private static final Map<Class<?>, Object> orderCache = new ConcurrentReferenceHashMap<>(64);

	/** Cache for @Priority value (or NOT_ANNOTATED marker) per Class. */
	/**缓存类对象与其优先级值的缓存注册表*/
	private static final Map<Class<?>, Object> priorityCache = new ConcurrentReferenceHashMap<>();


	/**
	 * Return the order on the specified {@code type}, or the specified
	 * default value if none can be found.
	 * <p>Takes care of {@link Order @Order} and {@code @javax.annotation.Priority}.
	 * @param type the type to handle
	 * @return the priority value, or the specified default order if none can be found
	 * @since 5.0
	 * @see #getPriority(Class)
	 */
	public static int getOrder(Class<?> type, int defaultOrder) {
		Integer order = getOrder(type);
		return (order != null ? order : defaultOrder);
	}

	/**
	 * Return the order on the specified {@code type}, or the specified
	 * default value if none can be found.
	 * <p>Takes care of {@link Order @Order} and {@code @javax.annotation.Priority}.
	 * @param type the type to handle
	 * @return the priority value, or the specified default order if none can be found
	 * @see #getPriority(Class)
	 */
	@Nullable
	public static Integer getOrder(Class<?> type, @Nullable Integer defaultOrder) {
		Integer order = getOrder(type);
		return (order != null ? order : defaultOrder);
	}

	/**
	 * Return the order on the specified {@code type}.
	 * <p>Takes care of {@link Order @Order} and {@code @javax.annotation.Priority}.
	 * @param type the type to handle
	 * @return the order value, or {@code null} if none can be found
	 * @see #getPriority(Class)
	 */
	@Nullable
	public static Integer getOrder(Class<?> type) {
		Object cached = orderCache.get(type);
		if (cached != null) {
			return (cached instanceof Integer ? (Integer) cached : null);
		}
		Order order = AnnotationUtils.findAnnotation(type, Order.class);
		Integer result;
		if (order != null) {
			result = order.value();
		}
		else {
			result = getPriority(type);
		}
		orderCache.put(type, (result != null ? result : NOT_ANNOTATED));
		return result;
	}

	/**
	 * Return the value of the {@code javax.annotation.Priority} annotation
	 * declared on the specified type, or {@code null} if none.
	 * @param type the type to handle
	 * @return the priority value if the annotation is declared, or {@code null} if none
	 */
	@Nullable
	public static Integer getPriority(Class<?> type) {
		//如果priorityAnnotationType类型为空，则无法查找，则返回null
		if (priorityAnnotationType == null) {
			return null;
		}
		//如缓存中获取指定类型对应的优先级
		Object cached = priorityCache.get(type);
		//缓存中存在则直接返回
		if (cached != null) {
			return (cached instanceof Integer ? (Integer) cached : null);
		}
		//从指定类上查找优先级注解对象
		Annotation priority = AnnotationUtils.findAnnotation(type, priorityAnnotationType);
		//从获取到的优先级注解标签中获取优先级值，并且将其添加到缓存中，返回
		Integer result = null;
		if (priority != null) {
			result = (Integer) AnnotationUtils.getValue(priority);
		}
		priorityCache.put(type, (result != null ? result : NOT_ANNOTATED));
		return result;
	}

}
