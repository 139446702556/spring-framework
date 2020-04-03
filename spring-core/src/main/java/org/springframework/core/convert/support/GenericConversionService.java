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

package org.springframework.core.convert.support;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.DecoratingProxy;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalConverter;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.core.convert.converter.ConverterRegistry;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.convert.converter.GenericConverter.ConvertiblePair;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

/**
 * Base {@link ConversionService} implementation suitable for use in most environments.
 * Indirectly implements {@link ConverterRegistry} as registration API through the
 * {@link ConfigurableConversionService} interface.
 * ConversionService接口的基本实现，适用于大部分条件下的转换工作，通过ConfigurableConversionService接口
 * 间接的将ConverterRegistry实现为注册API
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Phillip Webb
 * @author David Haraburda
 * @since 3.0
 */
public class GenericConversionService implements ConfigurableConversionService {

	/**
	 * General NO-OP converter used when conversion is not required.
	 * 一般无操作转换器使用时，操作是不需要的
	 * 此转换器为默认的转换器
	 */
	private static final GenericConverter NO_OP_CONVERTER = new NoOpConverter("NO_OP");

	/**
	 * Used as a cache entry when no converter is available.
	 * This converter is never returned.
	 * 此转换器是当没有可用的转换器时作为缓存项使用，标记当前类型没有对应的转换器
	 * 此转换器用于不会返回，支持做来标记使用，其对应返回的是null
	 */
	private static final GenericConverter NO_MATCH = new NoOpConverter("NO_MATCH");

	/**所有Converter集合的封装对象*/
	private final Converters converters = new Converters();
	/**GenericConverter的缓存*/
	private final Map<ConverterCacheKey, GenericConverter> converterCache = new ConcurrentReferenceHashMap<>(64);


	// ConverterRegistry implementation

	@Override
	public void addConverter(Converter<?, ?> converter) {
		//基于converter.getClass()类获取ResolvableType对象数组
		ResolvableType[] typeInfo = getRequiredTypeInfo(converter.getClass(), Converter.class);
		//如果未获取到，并且converter是DecoratingProxy类型，则基于（（DecoratingProxy）converter）.getDecoratedClass()类来获取
		if (typeInfo == null && converter instanceof DecoratingProxy) {
			typeInfo = getRequiredTypeInfo(((DecoratingProxy) converter).getDecoratedClass(), Converter.class);
		}
		//如果还获取不到，则抛出IllegalArgumentException异常
		if (typeInfo == null) {
			throw new IllegalArgumentException("Unable to determine source type <S> and target type <T> for your " +
					"Converter [" + converter.getClass().getName() + "]; does the class parameterize those types?");
		}
		//将获取到的信息封装成ConverteAdapter对象，并添加到converters中
		addConverter(new ConverterAdapter(converter, typeInfo[0], typeInfo[1]));
	}

	@Override
	public <S, T> void addConverter(Class<S> sourceType, Class<T> targetType, Converter<? super S, ? extends T> converter) {
		addConverter(new ConverterAdapter(
				converter, ResolvableType.forClass(sourceType), ResolvableType.forClass(targetType)));
	}

	@Override
	public void addConverter(GenericConverter converter) {
		//将给定的converter（转换器）添加到converters中
		this.converters.add(converter);
		//过期缓存
		invalidateCache();
	}

	@Override
	public void addConverterFactory(ConverterFactory<?, ?> factory) {
		ResolvableType[] typeInfo = getRequiredTypeInfo(factory.getClass(), ConverterFactory.class);
		if (typeInfo == null && factory instanceof DecoratingProxy) {
			typeInfo = getRequiredTypeInfo(((DecoratingProxy) factory).getDecoratedClass(), ConverterFactory.class);
		}
		if (typeInfo == null) {
			throw new IllegalArgumentException("Unable to determine source type <S> and target type <T> for your " +
					"ConverterFactory [" + factory.getClass().getName() + "]; does the class parameterize those types?");
		}
		addConverter(new ConverterFactoryAdapter(factory,
				new ConvertiblePair(typeInfo[0].toClass(), typeInfo[1].toClass())));
	}

	@Override
	public void removeConvertible(Class<?> sourceType, Class<?> targetType) {
		this.converters.remove(sourceType, targetType);
		invalidateCache();
	}


	// ConversionService implementation

	@Override
	public boolean canConvert(@Nullable Class<?> sourceType, Class<?> targetType) {
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		return canConvert((sourceType != null ? TypeDescriptor.valueOf(sourceType) : null),
				TypeDescriptor.valueOf(targetType));
	}

	@Override
	public boolean canConvert(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		if (sourceType == null) {
			return true;
		}
		GenericConverter converter = getConverter(sourceType, targetType);
		return (converter != null);
	}

	/**
	 * Return whether conversion between the source type and the target type can be bypassed.
	 * <p>More precisely, this method will return true if objects of sourceType can be
	 * converted to the target type by returning the source object unchanged.
	 * @param sourceType context about the source type to convert from
	 * (may be {@code null} if source is {@code null})
	 * @param targetType context about the target type to convert to (required)
	 * @return {@code true} if conversion can be bypassed; {@code false} otherwise
	 * @throws IllegalArgumentException if targetType is {@code null}
	 * @since 3.2
	 */
	public boolean canBypassConvert(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		if (sourceType == null) {
			return true;
		}
		GenericConverter converter = getConverter(sourceType, targetType);
		return (converter == NO_OP_CONVERTER);
	}

	@Override
	@SuppressWarnings("unchecked")
	@Nullable
	public <T> T convert(@Nullable Object source, Class<T> targetType) {
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		return (T) convert(source, TypeDescriptor.forObject(source), TypeDescriptor.valueOf(targetType));
	}

	@Override
	@Nullable
	public Object convert(@Nullable Object source, @Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		//目标类型描述符不能为空
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		//如果sourceType是空，则直接处理结果
		if (sourceType == null) {
			//sourceType是空，source对象必须也是空
			Assert.isTrue(source == null, "Source must be [null] if source type == [null]");
			//直接处理结果
			return handleResult(null, targetType, convertNullSource(null, targetType));
		}
		//如果源对象和给定的原类型不匹配，则抛出异常
		if (source != null && !sourceType.getObjectType().isInstance(source)) {
			throw new IllegalArgumentException("Source to convert from must be an instance of [" +
					sourceType + "]; instead it was a [" + source.getClass().getName() + "]");
		}
		//获取对应的GenericConverter对象
		GenericConverter converter = getConverter(sourceType, targetType);
		//converter不为空时，则进行转换，然后在进行结果处理
		if (converter != null) {
			//执行转换
			Object result = ConversionUtils.invokeConverter(converter, source, sourceType, targetType);
			//处理结果
			return handleResult(sourceType, targetType, result);
		}
		//处理converter为空的情况
		return handleConverterNotFound(source, sourceType, targetType);
	}

	/**
	 * Convenience operation for converting a source object to the specified targetType,
	 * where the target type is a descriptor that provides additional conversion context.
	 * Simply delegates to {@link #convert(Object, TypeDescriptor, TypeDescriptor)} and
	 * encapsulates the construction of the source type descriptor using
	 * {@link TypeDescriptor#forObject(Object)}.
	 * @param source the source object
	 * @param targetType the target type
	 * @return the converted value
	 * @throws ConversionException if a conversion exception occurred
	 * @throws IllegalArgumentException if targetType is {@code null},
	 * or sourceType is {@code null} but source is not {@code null}
	 */
	@Nullable
	public Object convert(@Nullable Object source, TypeDescriptor targetType) {
		return convert(source, TypeDescriptor.forObject(source), targetType);
	}

	@Override
	public String toString() {
		return this.converters.toString();
	}


	// Protected template methods

	/**
	 * Template method to convert a {@code null} source.
	 * 转换一个空的源对象到目标类型
	 * <p>The default implementation returns {@code null} or the Java 8
	 * {@link java.util.Optional#empty()} instance if the target type is
	 * {@code java.util.Optional}. Subclasses may override this to return
	 * custom {@code null} objects for specific target types.
	 * @param sourceType the source type to convert from
	 * @param targetType the target type to convert to
	 * @return the converted null object
	 */
	@Nullable
	protected Object convertNullSource(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		//如果目标类型为Optional类型，则返回Optional空对象，否则直接返回null
		if (targetType.getObjectType() == Optional.class) {
			return Optional.empty();
		}
		return null;
	}

	/**
	 * Hook method to lookup the converter for a given sourceType/targetType pair.
	 * First queries this ConversionService's converter cache.
	 * On a cache miss, then performs an exhaustive search for a matching converter.
	 * If no converter matches, returns the default converter.
	 * @param sourceType the source type to convert from
	 * @param targetType the target type to convert to
	 * @return the generic converter that will perform the conversion,
	 * or {@code null} if no suitable converter was found
	 * @see #getDefaultConverter(TypeDescriptor, TypeDescriptor)
	 */
	@Nullable
	protected GenericConverter getConverter(TypeDescriptor sourceType, TypeDescriptor targetType) {
		//创建ConverterCacheKey对象
		ConverterCacheKey key = new ConverterCacheKey(sourceType, targetType);
		//从缓存中获取指定的key对应的通用转换器对象
		GenericConverter converter = this.converterCache.get(key);
		//如果获取得到，则返回converter（其中转换器若等于NO_MATCH则表示当前key未获取到转换器，则返回null）
		if (converter != null) {
			//如果匹配到了直接返回此转换器，否则返回null
			return (converter != NO_MATCH ? converter : null);
		}
		//如果获取不到，则从converters中查找
		converter = this.converters.find(sourceType, targetType);
		//如果未获取到指定的转换器，则使用默认转换器（默认Converter对象）
		if (converter == null) {
			converter = getDefaultConverter(sourceType, targetType);
		}
		//如果converter不为空（表示查找到了对应的converter或者原类型与目标类型有父子关系），则保存到缓存中，并返回converter
		if (converter != null) {
			this.converterCache.put(key, converter);
			return converter;
		}
		//如果未匹配到指定的转换器，则将NO_MATCH占位符（此占位符表示当前类型描述信息未获取到对应的converter）添加到缓存中，并返回null
		this.converterCache.put(key, NO_MATCH);
		return null;
	}

	/**
	 * Return the default converter if no converter is found for the given sourceType/targetType pair.
	 * <p>Returns a NO_OP Converter if the source type is assignable to the target type.
	 * Returns {@code null} otherwise, indicating no suitable converter could be found.
	 * @param sourceType the source type to convert from
	 * @param targetType the target type to convert to
	 * @return the default generic converter that will perform the conversion
	 */
	@Nullable
	protected GenericConverter getDefaultConverter(TypeDescriptor sourceType, TypeDescriptor targetType) {
		return (sourceType.isAssignableTo(targetType) ? NO_OP_CONVERTER : null);
	}


	// Internal helpers

	@Nullable
	private ResolvableType[] getRequiredTypeInfo(Class<?> converterClass, Class<?> genericIfc) {
		ResolvableType resolvableType = ResolvableType.forClass(converterClass).as(genericIfc);
		ResolvableType[] generics = resolvableType.getGenerics();
		if (generics.length < 2) {
			return null;
		}
		Class<?> sourceType = generics[0].resolve();
		Class<?> targetType = generics[1].resolve();
		if (sourceType == null || targetType == null) {
			return null;
		}
		return generics;
	}

	private void invalidateCache() {
		//清除converterCache中的转换器缓存
		this.converterCache.clear();
	}

	@Nullable
	private Object handleConverterNotFound(
			@Nullable Object source, @Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		//如果source为null，则抛出异常或者返回null
		if (source == null) {
			//检测要转换的目标类型是否为原始类型，如果是抛出异常，因为null无法转换给原始类型，不是则返回null
			assertNotPrimitiveTargetType(sourceType, targetType);
			return null;
		}
		//如果源类型为空或者目标类型是原类型的父类，并且当前source对象是目标类型的实例化对象，则直接返回
		if ((sourceType == null || sourceType.isAssignableTo(targetType)) &&
				targetType.getObjectType().isInstance(source)) {
			return source;
		}
		//如果不满足上述直接返回的情况，则抛出ConverterNotFoundException异常
		throw new ConverterNotFoundException(sourceType, targetType);
	}

	@Nullable
	private Object handleResult(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType, @Nullable Object result) {
		//如果给定的转换结果为null，则判断其目标类型是否是原始类型，如果是抛出异常，否则直接返回给定的result值
		if (result == null) {
			assertNotPrimitiveTargetType(sourceType, targetType);
		}
		return result;
	}

	private void assertNotPrimitiveTargetType(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		//判断目标类型是否是一个原始类型（int、boolen等），如果是，则直接抛出异常，因为null值不能够转换为一个原始类型
		if (targetType.isPrimitive()) {
			throw new ConversionFailedException(sourceType, targetType, null,
					new IllegalArgumentException("A null value cannot be assigned to a primitive type"));
		}
	}


	/**
	 * Adapts a {@link Converter} to a {@link GenericConverter}.
	 * 此类表示转换器的一个适配器，它实现了ConditionalConverter和GenericConverter两个类型转换器
	 */
	@SuppressWarnings("unchecked")
	private final class ConverterAdapter implements ConditionalGenericConverter {

		private final Converter<Object, Object> converter;

		private final ConvertiblePair typeInfo;

		private final ResolvableType targetType;

		public ConverterAdapter(Converter<?, ?> converter, ResolvableType sourceType, ResolvableType targetType) {
			this.converter = (Converter<Object, Object>) converter;
			this.typeInfo = new ConvertiblePair(sourceType.toClass(), targetType.toClass());
			this.targetType = targetType;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(this.typeInfo);
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			// Check raw type first...
			if (this.typeInfo.getTargetType() != targetType.getObjectType()) {
				return false;
			}
			// Full check for complex generic type match required?
			ResolvableType rt = targetType.getResolvableType();
			if (!(rt.getType() instanceof Class) && !rt.isAssignableFrom(this.targetType) &&
					!this.targetType.hasUnresolvableGenerics()) {
				return false;
			}
			return !(this.converter instanceof ConditionalConverter) ||
					((ConditionalConverter) this.converter).matches(sourceType, targetType);
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			if (source == null) {
				return convertNullSource(sourceType, targetType);
			}
			return this.converter.convert(source);
		}

		@Override
		public String toString() {
			return (this.typeInfo + " : " + this.converter);
		}
	}


	/**
	 * Adapts a {@link ConverterFactory} to a {@link GenericConverter}.
	 */
	@SuppressWarnings("unchecked")
	private final class ConverterFactoryAdapter implements ConditionalGenericConverter {

		private final ConverterFactory<Object, Object> converterFactory;

		private final ConvertiblePair typeInfo;

		public ConverterFactoryAdapter(ConverterFactory<?, ?> converterFactory, ConvertiblePair typeInfo) {
			this.converterFactory = (ConverterFactory<Object, Object>) converterFactory;
			this.typeInfo = typeInfo;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(this.typeInfo);
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			boolean matches = true;
			if (this.converterFactory instanceof ConditionalConverter) {
				matches = ((ConditionalConverter) this.converterFactory).matches(sourceType, targetType);
			}
			if (matches) {
				Converter<?, ?> converter = this.converterFactory.getConverter(targetType.getType());
				if (converter instanceof ConditionalConverter) {
					matches = ((ConditionalConverter) converter).matches(sourceType, targetType);
				}
			}
			return matches;
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			if (source == null) {
				return convertNullSource(sourceType, targetType);
			}
			return this.converterFactory.getConverter(targetType.getObjectType()).convert(source);
		}

		@Override
		public String toString() {
			return (this.typeInfo + " : " + this.converterFactory);
		}
	}


	/**
	 * Key for use with the converter cache.
	 */
	private static final class ConverterCacheKey implements Comparable<ConverterCacheKey> {

		private final TypeDescriptor sourceType;

		private final TypeDescriptor targetType;

		public ConverterCacheKey(TypeDescriptor sourceType, TypeDescriptor targetType) {
			this.sourceType = sourceType;
			this.targetType = targetType;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof ConverterCacheKey)) {
				return false;
			}
			ConverterCacheKey otherKey = (ConverterCacheKey) other;
			return (this.sourceType.equals(otherKey.sourceType)) &&
					this.targetType.equals(otherKey.targetType);
		}

		@Override
		public int hashCode() {
			return (this.sourceType.hashCode() * 29 + this.targetType.hashCode());
		}

		@Override
		public String toString() {
			return ("ConverterCacheKey [sourceType = " + this.sourceType +
					", targetType = " + this.targetType + "]");
		}

		@Override
		public int compareTo(ConverterCacheKey other) {
			int result = this.sourceType.getResolvableType().toString().compareTo(
					other.sourceType.getResolvableType().toString());
			if (result == 0) {
				result = this.targetType.getResolvableType().toString().compareTo(
						other.targetType.getResolvableType().toString());
			}
			return result;
		}
	}


	/**
	 * Manages all converters registered with the service.
	 * 管理在服务中注册的全部转换器
	 */
	private static class Converters {

		private final Set<GenericConverter> globalConverters = new LinkedHashSet<>();

		private final Map<ConvertiblePair, ConvertersForPair> converters = new LinkedHashMap<>(36);

		public void add(GenericConverter converter) {
			//获取ConvertiblePair对象集合
			Set<ConvertiblePair> convertibleTypes = converter.getConvertibleTypes();
			//如果获取到的可转换类型集合为空
			if (convertibleTypes == null) {
				//判断converter是否为ConditionalConverter类型，不是，则抛出异常
				Assert.state(converter instanceof ConditionalConverter,
						"Only conditional converters may return null convertible types");
				//如果是ConditionalConverter类型，则将其添加到globalConverters中
				this.globalConverters.add(converter);
			}
			else {
				//迭代给定转换器（converter）可以转换的全部类型对象数组，并根据指定类型获取到其可以使用的转换器集合对象，并将给定的converter添加到ConvertersForPair中
				for (ConvertiblePair convertiblePair : convertibleTypes) {
					//从converters中获取给定类型对应的ConvertersForPair对象
					ConvertersForPair convertersForPair = getMatchableConverters(convertiblePair);
					//添加给定的converter到ConvertersForPair中
					convertersForPair.add(converter);
				}
			}
		}

		private ConvertersForPair getMatchableConverters(ConvertiblePair convertiblePair) {
			//通过给定的类型从converters中获取对应的ConvertersForPair对象
			ConvertersForPair convertersForPair = this.converters.get(convertiblePair);
			//如果未获取到，则初始化，并将其和convertiblePair对象关系重新添加到converters中
			if (convertersForPair == null) {
				convertersForPair = new ConvertersForPair();
				this.converters.put(convertiblePair, convertersForPair);
			}
			//返回convertibleForPair对象
			return convertersForPair;
		}

		public void remove(Class<?> sourceType, Class<?> targetType) {
			this.converters.remove(new ConvertiblePair(sourceType, targetType));
		}

		/**
		 * Find a {@link GenericConverter} given a source and target type.
		 * <p>This method will attempt to match all possible converters by working
		 * through the class and interface hierarchy of the types.
		 * @param sourceType the source type
		 * @param targetType the target type
		 * @return a matching {@link GenericConverter}, or {@code null} if none found
		 */
		@Nullable
		public GenericConverter find(TypeDescriptor sourceType, TypeDescriptor targetType) {
			// Search the full type hierarchy
			//搜索整个类型的层次结构（即当前类和其的继承类、接口）
			List<Class<?>> sourceCandidates = getClassHierarchy(sourceType.getType());
			List<Class<?>> targetCandidates = getClassHierarchy(targetType.getType());
			//遍历sourceCandidates数组
			for (Class<?> sourceCandidate : sourceCandidates) {
				//遍历targetCandidates数组
				for (Class<?> targetCandidate : targetCandidates) {
					//创建ConvertiblePair对象
					ConvertiblePair convertiblePair = new ConvertiblePair(sourceCandidate, targetCandidate);
					//获取GenericConverter对象
					GenericConverter converter = getRegisteredConverter(sourceType, targetType, convertiblePair);
					//如果获取到了，则直接返回
					if (converter != null) {
						return converter;
					}
				}
			}
			//全部都没有获取到，则返回null
			return null;
		}

		@Nullable
		private GenericConverter getRegisteredConverter(TypeDescriptor sourceType,
				TypeDescriptor targetType, ConvertiblePair convertiblePair) {

			// Check specifically registered converters
			//从converters中获取convertiblePair对应的converter对象
			ConvertersForPair convertersForPair = this.converters.get(convertiblePair);
			//如果获取到了
			if (convertersForPair != null) {
				//通过给定的类型对象，从获取到的对象中获取converter
				GenericConverter converter = convertersForPair.getConverter(sourceType, targetType);
				//如果获取到，则返回
				if (converter != null) {
					return converter;
				}
			}
			// Check ConditionalConverters for a dynamic match
			//从globalConverters集合中获取与给定类型匹配的globalConverter
			for (GenericConverter globalConverter : this.globalConverters) {
				if (((ConditionalConverter) globalConverter).matches(sourceType, targetType)) {
					return globalConverter;
				}
			}
			return null;
		}

		/**
		 * Returns an ordered class hierarchy for the given type.
		 * @param type the type
		 * @return an ordered list of all classes that the given type extends or implements
		 */
		private List<Class<?>> getClassHierarchy(Class<?> type) {
			List<Class<?>> hierarchy = new ArrayList<>(20);
			Set<Class<?>> visited = new HashSet<>(20);
			addToClassHierarchy(0, ClassUtils.resolvePrimitiveIfNecessary(type), false, hierarchy, visited);
			boolean array = type.isArray();

			int i = 0;
			while (i < hierarchy.size()) {
				Class<?> candidate = hierarchy.get(i);
				candidate = (array ? candidate.getComponentType() : ClassUtils.resolvePrimitiveIfNecessary(candidate));
				Class<?> superclass = candidate.getSuperclass();
				if (superclass != null && superclass != Object.class && superclass != Enum.class) {
					addToClassHierarchy(i + 1, candidate.getSuperclass(), array, hierarchy, visited);
				}
				addInterfacesToClassHierarchy(candidate, array, hierarchy, visited);
				i++;
			}

			if (Enum.class.isAssignableFrom(type)) {
				addToClassHierarchy(hierarchy.size(), Enum.class, array, hierarchy, visited);
				addToClassHierarchy(hierarchy.size(), Enum.class, false, hierarchy, visited);
				addInterfacesToClassHierarchy(Enum.class, array, hierarchy, visited);
			}

			addToClassHierarchy(hierarchy.size(), Object.class, array, hierarchy, visited);
			addToClassHierarchy(hierarchy.size(), Object.class, false, hierarchy, visited);
			return hierarchy;
		}

		private void addInterfacesToClassHierarchy(Class<?> type, boolean asArray,
				List<Class<?>> hierarchy, Set<Class<?>> visited) {

			for (Class<?> implementedInterface : type.getInterfaces()) {
				addToClassHierarchy(hierarchy.size(), implementedInterface, asArray, hierarchy, visited);
			}
		}

		private void addToClassHierarchy(int index, Class<?> type, boolean asArray,
				List<Class<?>> hierarchy, Set<Class<?>> visited) {

			if (asArray) {
				type = Array.newInstance(type, 0).getClass();
			}
			if (visited.add(type)) {
				hierarchy.add(index, type);
			}
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("ConversionService converters =\n");
			for (String converterString : getConverterStrings()) {
				builder.append('\t').append(converterString).append('\n');
			}
			return builder.toString();
		}

		private List<String> getConverterStrings() {
			List<String> converterStrings = new ArrayList<>();
			for (ConvertersForPair convertersForPair : this.converters.values()) {
				converterStrings.add(convertersForPair.toString());
			}
			Collections.sort(converterStrings);
			return converterStrings;
		}
	}


	/**
	 * Manages converters registered with a specific {@link ConvertiblePair}.
	 * 此类用于管理使用特定的ConvertiblePair注册的转换器
	 */
	private static class ConvertersForPair {
		/**该集合用于存储当前转换器对对象中包含的全部通用类型转换器*/
		private final LinkedList<GenericConverter> converters = new LinkedList<>();
		/**插入转换器使用头插法，即新添加的转换器在链表的最前端*/
		public void add(GenericConverter converter) {
			this.converters.addFirst(converter);
		}

		@Nullable
		public GenericConverter getConverter(TypeDescriptor sourceType, TypeDescriptor targetType) {
			//遍历当前对象所包含的全部转换器集合
			for (GenericConverter converter : this.converters) {
				//如果当前转换器未实现ConditionalConverter接口（不需要条件验证）或者给定的原类型和目标类型满足匹配条件，则返回当前转化器
				//所以一般使用的转换器为最近新添加的，且满足条件的转换器
				if (!(converter instanceof ConditionalGenericConverter) ||
						((ConditionalGenericConverter) converter).matches(sourceType, targetType)) {
					return converter;
				}
			}
			return null;
		}

		@Override
		public String toString() {
			return StringUtils.collectionToCommaDelimitedString(this.converters);
		}
	}


	/**
	 * Internal converter that performs no operation.
	 */
	private static class NoOpConverter implements GenericConverter {

		private final String name;

		public NoOpConverter(String name) {
			this.name = name;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return null;
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			return source;
		}

		@Override
		public String toString() {
			return this.name;
		}
	}

}
