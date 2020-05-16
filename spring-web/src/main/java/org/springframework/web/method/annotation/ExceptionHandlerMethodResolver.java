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

package org.springframework.web.method.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ExceptionDepthComparator;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Discovers {@linkplain ExceptionHandler @ExceptionHandler} methods in a given class,
 * including all of its superclasses, and helps to resolve a given {@link Exception}
 * to the exception types supported by a given {@link Method}.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public class ExceptionHandlerMethodResolver {

	/**
	 * A filter for selecting {@code @ExceptionHandler} methods.
	 * MethodFilter对象，用于过滤有@ExceptionHandler注解的方法
	 */
	public static final MethodFilter EXCEPTION_HANDLER_METHODS = method ->
			AnnotatedElementUtils.hasAnnotation(method, ExceptionHandler.class);

	/**已经映射的方法   此容器在ExceptionHandlerMethodResolver构造函数中初始化*/
	private final Map<Class<? extends Throwable>, Method> mappedMethods = new HashMap<>(16);
	/**已经匹配的方法  resolveMethod方法中初始化*/
	private final Map<Class<? extends Throwable>, Method> exceptionLookupCache = new ConcurrentReferenceHashMap<>(16);


	/**
	 * A constructor that finds {@link ExceptionHandler} methods in the given type.
	 * @param handlerType the type to introspect
	 */
	public ExceptionHandlerMethodResolver(Class<?> handlerType) {
		//遍历给定handlerType类型中标注了@ExceptionHandler注解的方法数组
		for (Method method : MethodIntrospector.selectMethods(handlerType, EXCEPTION_HANDLER_METHODS)) {
			//遍历当前异常处理方法支持的全部异常类型集合
			for (Class<? extends Throwable> exceptionType : detectExceptionMappings(method)) {
				//添加异常类型和处理当前异常类型的方法对象的映射关系到缓存mappedMethods中
				addExceptionMapping(exceptionType, method);
			}
		}
	}


	/**
	 * Extract exception mappings from the {@code @ExceptionHandler} annotation first,
	 * and then as a fallback from the method signature itself.
	 */
	@SuppressWarnings("unchecked")
	private List<Class<? extends Throwable>> detectExceptionMappings(Method method) {
		//用于存储当前异常处理方法，支持处理的异常类型集合
		List<Class<? extends Throwable>> result = new ArrayList<>();
		//解析方法的@ExceptionHandler注解标注的设置，将其设置的支持的异常方法添加到result中
		detectAnnotationExceptionMappings(method, result);
		//如果方法的@ExceptionHandler注解未设置异常类型
		if (result.isEmpty()) {
			//遍历当前方法的所有参数类型
			for (Class<?> paramType : method.getParameterTypes()) {
				//如果是Throwable类型的，则为异常类型，则将其添加到result中
				if (Throwable.class.isAssignableFrom(paramType)) {
					result.add((Class<? extends Throwable>) paramType);
				}
			}
		}
		//如果当前给定的方法未解析出支持的异常类型，则抛出异常
		if (result.isEmpty()) {
			throw new IllegalStateException("No exception types mapped to " + method);
		}
		return result;
	}

	private void detectAnnotationExceptionMappings(Method method, List<Class<? extends Throwable>> result) {
		//获取给定的method方法上的@ExceptionHandler注解对象
		ExceptionHandler ann = AnnotatedElementUtils.findMergedAnnotation(method, ExceptionHandler.class);
		//判空
		Assert.state(ann != null, "No ExceptionHandler annotation");
		//将注解对象设置的支持的异常类型添加到result中
		result.addAll(Arrays.asList(ann.value()));
	}

	private void addExceptionMapping(Class<? extends Throwable> exceptionType, Method method) {
		//将异常类型和其处理异常的方法的对应关系添加到缓存mappedMethods中
		Method oldMethod = this.mappedMethods.put(exceptionType, method);
		//如果当前异常类型在缓存中已经注册过异常处理方法，并且和当前不是同一个方法，说明冲突，则抛出异常
		if (oldMethod != null && !oldMethod.equals(method)) {
			throw new IllegalStateException("Ambiguous @ExceptionHandler method mapped for [" +
					exceptionType + "]: {" + oldMethod + ", " + method + "}");
		}
	}

	/**
	 * Whether the contained type has any exception mappings.
	 * 判断mappedMethods非空
	 */
	public boolean hasExceptionMappings() {
		return !this.mappedMethods.isEmpty();
	}

	/**
	 * Find a {@link Method} to handle the given exception.
	 * Use {@link ExceptionDepthComparator} if more than one match is found.
	 * 通过解析给定的异常对象，来找到其对应的方法
	 * @param exception the exception
	 * @return a Method to handle the exception, or {@code null} if none found
	 */
	@Nullable
	public Method resolveMethod(Exception exception) {
		return resolveMethodByThrowable(exception);
	}

	/**
	 * Find a {@link Method} to handle the given Throwable.
	 * Use {@link ExceptionDepthComparator} if more than one match is found.
	 * @param exception the exception
	 * @return a Method to handle the exception, or {@code null} if none found
	 * @since 5.0
	 */
	@Nullable
	public Method resolveMethodByThrowable(Throwable exception) {
		//通过解析给定异常获得对应的方法
		Method method = resolveMethodByExceptionType(exception.getClass());
		//如果解析不到，则使用异常cause对应的方法
		if (method == null) {
			//获取异常cause对象
			Throwable cause = exception.getCause();
			//如果cause不为空
			if (cause != null) {
				//则通过解析cause异常来获得对应的方法
				method = resolveMethodByExceptionType(cause.getClass());
			}
		}
		//返回解析得到的方法对象
		return method;
	}

	/**
	 * Find a {@link Method} to handle the given exception type. This can be
	 * useful if an {@link Exception} instance is not available (e.g. for tools).
	 * @param exceptionType the exception type
	 * @return a Method to handle the exception, or {@code null} if none found
	 * 通过解析给定的异常对象来获取其对应的方法
	 */
	@Nullable
	public Method resolveMethodByExceptionType(Class<? extends Throwable> exceptionType) {
		//先从exceptionLookupCache缓存中获取给定异常对应的方法对象
		Method method = this.exceptionLookupCache.get(exceptionType);
		//如果缓存中获取不到
		if (method == null) {
			//则从mappedMethods中获得异常类型对应的方法对象
			method = getMappedMethod(exceptionType);
			//将给定的异常对象和其对应的方法对象，添加到exceptionLookupCache缓存中
			this.exceptionLookupCache.put(exceptionType, method);
		}
		return method;
	}

	/**
	 * Return the {@link Method} mapped to the given exception type, or {@code null} if none.
	 * 获取给定异常类型对象对应的方法对象
	 */
	@Nullable
	private Method getMappedMethod(Class<? extends Throwable> exceptionType) {
		//用于存储已经注册的方法中对应的异常与给定异常类型匹配的异常
		List<Class<? extends Throwable>> matches = new ArrayList<>();
		//遍历mappedMethods数组，匹配异常，将匹配到的异常添加到matchs中
		for (Class<? extends Throwable> mappedException : this.mappedMethods.keySet()) {
			//匹配当前mappedException对象是否为给定的异常类型，如果是，则将其添加到matchs中
			if (mappedException.isAssignableFrom(exceptionType)) {
				matches.add(mappedException);
			}
		}
		//如果存在匹配的结果，则将匹配的结果排序，选择第一个
		if (!matches.isEmpty()) {
			//将匹配到的matches结果进行排序（排序要求：比较它们与目标异常类型的继承层级，越小越匹配）
			//目标类型为exceptionType
			matches.sort(new ExceptionDepthComparator(exceptionType));
			//获取匹配结果排序后的第一个符合异常的方法对象，并返回
			return this.mappedMethods.get(matches.get(0));
		}
		//如果matches为空，则返回null
		else {
			return null;
		}
	}

}
