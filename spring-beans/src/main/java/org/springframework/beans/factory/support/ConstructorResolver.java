/*
 * Copyright 2002-2019 the original author or authors.
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

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.CollectionFactory;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 * Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");

	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * 使用给定的bean工厂和实例化策略创建一个新的ConstructorResolver对象
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			@Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		Constructor<?> constructorToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve, true);
			}
		}

		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			Constructor<?>[] candidates = chosenCtors;
			if (candidates == null) {
				Class<?> beanClass = mbd.getBeanClass();
				try {
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Constructor<?> uniqueCandidate = candidates[0];
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// Need to resolve the constructor.
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			AutowireUtils.sortConstructors(candidates);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Constructor<?>> ambiguousConstructors = null;
			LinkedList<UnsatisfiedDependencyException> causes = null;

			for (Constructor<?> candidate : candidates) {
				Class<?>[] paramTypes = candidate.getParameterTypes();

				if (constructorToUse != null && argsToUse != null && argsToUse.length > paramTypes.length) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					break;
				}
				if (paramTypes.length < minNrOfArgs) {
					continue;
				}

				ArgumentsHolder argsHolder;
				if (resolvedValues != null) {
					try {
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, paramTypes.length);
						if (paramNames == null) {
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new LinkedList<>();
						}
						causes.add(ex);
						continue;
					}
				}
				else {
					// Explicit arguments given -> arguments length must match exactly.
					if (paramTypes.length != explicitArgs.length) {
						continue;
					}
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				if (typeDiffWeight < minTypeDiffWeight) {
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes())) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 * 如果允许对非公开的访问，则获取给定工厂类的全部方法（当前类、父类和实现接口）；否则获取当前给定类的所有公开方法
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
					(mbd.isNonPublicAccessAllowed() ?
						ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
		}
		else {
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		//构造BeanWrapperImpl对象
		BeanWrapperImpl bw = new BeanWrapperImpl();
		//初始化BeanWrapperImpl对象
		//向beanWrapper对象中添加conversionService对象和属性编辑器PropertyEditor对象
		this.beanFactory.initBeanWrapper(bw);
		//获取factoryBean、factoryClass、isStatic和factoryBeanName属性
		Object factoryBean;
		Class<?> factoryClass;
		boolean isStatic;
		//从给定的beanDefinition中获取factoryBean的名称值
		String factoryBeanName = mbd.getFactoryBeanName();
		//factoryBean的名称不为空
		if (factoryBeanName != null) {
			//如果当前BeanDefinition中的factoryBean的名称和bean名称相同，则抛出异常
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			//获取factoryBean的实例对象
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			//如果当前bean是单例模式的并且单例缓存中存在此bean，则无需继续创建，则抛出ImplicitlyAppearedSingletonException异常
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				throw new ImplicitlyAppearedSingletonException();
			}
			//获取factoryBean的类对象
			factoryClass = factoryBean.getClass();
			//实例工厂
			isStatic = false;
		}
		else {
			//工厂名为空，则其可能是一个静态工厂
			//静态工厂创建bean，则必须要提供工厂的全类名
			// It's a static factory method on the bean class.
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			//无工厂bean的实例(因为可能为静态工厂)
			factoryBean = null;
			//静态工厂类对象为beanClass
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		//获得factoryMethodToUse、argsHolderToUse和argsToUse的属性
		//工厂方法
		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		//参数
		Object[] argsToUse = null;
		//如果指定了构造函数的参数则直接使用
		//说明在调用getbean方法的时候指定了方法参数
		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		//没有指定构造方法的参数
		else {
			//则尝试从配置文件中获取
			Object[] argsToResolve = null;
			//全局构造函数参数锁，尝试从缓存中获取
			synchronized (mbd.constructorArgumentLock) {
				//获取缓存中的构造函数或者工厂方法
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				//如果缓存中有构造函数或者工厂方法并且构造函数的参数已经解析完
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					//获取缓存中的构造函数参数（完全解析完缓存的即最终值）
					argsToUse = mbd.resolvedConstructorArguments;
					//如果不存在
					if (argsToUse == null) {
						//从缓存中获取部分准备好的构造函数参数（原始值）
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			//如果缓存中存在参数，则解析在BeanDefinition中的参数
			//如果给定的方法的构造函数为a（int，int），则通过此方法后就会把配置文件中的（“1”，“1”）转换为（1，1）
			//缓存中的值可能是原始值（未经过解析的值），也可能是最终值（解析之后得到的参数值）
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve, true);
			}
		}
		//如果当前要使用的工厂方法或者参数未获取到，即通过以上处理之后仍为空
		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			//获取工厂方法的类型对象（如果此类对象是cglib生成的子类，并且其父类不为Object类，则返回的为其父类型对象）
			factoryClass = ClassUtils.getUserClass(factoryClass);
			//获取所有待定方法
			Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
			List<Method> candidateList = new ArrayList<>();
			//检索所有待定方法，此处是对方法进行过滤
			for (Method candidate : rawCandidates) {
				//如果此候选方法的特征与工厂方法相符，则添加到candidateList集合中
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
					candidateList.add(candidate);
				}
			}
			//如果与工厂方法匹配的方法只有一个并且BeanDefinition定义时没有给定参数值，而且getbean时未传入参数
			if (candidateList.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				//获取此创建bean的工厂方法
				Method uniqueCandidate = candidateList.get(0);
				//如果此方法不需要参数
				if (uniqueCandidate.getParameterCount() == 0) {
					//缓存用于自省的唯一工厂方法
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					//将解析好的工厂方法以及参数信息缓存起来
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					//创建bean对象，并设置到beanWrapper类当中
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}
			//将当前的候选工厂方法集合转化为数组，用于后续使用
			Method[] candidates = candidateList.toArray(new Method[0]);
			//排序工厂方法
			//首先是public的工厂方法优先，其次访问修饰符相同的按照参数个数降序排列
			AutowireUtils.sortFactoryMethods(candidates);
			//用于存储解析后的构造函数参数值
			ConstructorArgumentValues resolvedValues = null;
			//当前beanDefinition使用的是否为自动检测构造函数模式
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			//用于存储模糊不清的工厂方法
			Set<Method> ambiguousFactoryMethods = null;
			//用于存储工厂方法的参数个数
			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				//如果资源文件中定义了构造函数的参数值
				if (mbd.hasConstructorArgumentValues()) {
					//获取beanDefinition中的构造函数参数值
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					//解析构造函数的参数，并返回参数的总个数
					//将该bean的构造函数参数解析为resolvedValues对象，其中会涉及到其它bean
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				//如果资源文件未给出参数信息，则参数个数为0
				else {
					minNrOfArgs = 0;
				}
			}
			//记录UnsatisfiedDependencyException异常的集合
			LinkedList<UnsatisfiedDependencyException> causes = null;
			//遍历candidates数组（创建bean用的构造方法或则工厂方法）
			for (Method candidate : candidates) {
				//获取candidate方法体的参数集合（此类型数组为拷贝得来，并非原存储对象引用）
				Class<?>[] paramTypes = candidate.getParameterTypes();
				//如果此方法需要的参数个数大于等于minNrOfArgs（getbean所给的参数个数，或者配置文件设置的）
				if (paramTypes.length >= minNrOfArgs) {
					//保存参数的对象
					ArgumentsHolder argsHolder;
					//getBean方法传递了参数
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						//getBean方法显示给定的参数长度必须与当前候选方法需要的参数长度必须完全匹配，否则此方法不符合，跳过
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						//根据getBean方法给定的参数创建参数持有者ArgumentsHolder对象
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						//解析构造函数参数：类型转换和/或必要的自动装配
						//未提供参数，解析构造参数
						try {
							String[] paramNames = null;
							//获取ParameterNameDiscoverer对象
							//ParameterNameDiscoverer是用于解析方法和构造函数的参数名称接口，为参数名称的探测器
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							//使用bean工厂中的参数名称解析器获取指定构造方法或者工厂方法的参数名称
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							//在已经解析的构造函数参数值的情况下，创建一个参数持有者ArgumentsHolder对象
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.length == 1);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							//吞掉当前异常后续处理，并跳过以下执行内容
							if (causes == null) {
								causes = new LinkedList<>();
							}
							causes.add(ex);
							continue;
						}
					}
					//isLenientConstructorResolution 判断解析构造函数的时候是否以宽松模式（true）还是严格模式（false）
					//严格模式：解析构造函数时，必须所有的都需要匹配，否则抛出异常
					//宽松模式：使用具有“最接近的模式”进行匹配
					//typeDiffWeight：类型差异权重
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					//类型差异权重值越小，表示越接近要求的类型匹配；找出类型匹配值最小的方法（最接近的类型匹配）
					//作为构造函数，记录此工厂方法或者构造函数的相关信息
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					//如果具有相同参数数量的方法且具有相同的类型差异权重，则收集此类型选项
					//但是，仅在非宽松构造函数解析模式下执行该检查，并显示忽略重写方法（具有相同的参数签名的方法）
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						//保存查找到的多个匹配方法
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}
			//没有可执行的工厂方法，则抛出异常
			if (factoryMethodToUse == null) {
				//如果上述方法参数匹配过程中出现异常
				if (causes != null) {
					//则获取异常列表中的最后一个，即最近的一个
					UnsatisfiedDependencyException ex = causes.removeLast();
					//将其余异常保存到bean工厂的异常集合中，后续处理
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					//抛出最后一个异常
					throw ex;
				}
				//创建存储参数类型名称集合
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				//如果getBean给定的参数集合不为空
				if (explicitArgs != null) {
					//遍历给定参数集合，将参数类型名称添加到argTypes中，如果参数值为空，则添加null字符串
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				//如果getBean方法没有给定参数值集合，则使用通过配置文件给定的解析后得到的参数值集合
				else if (resolvedValues != null) {
					//创建存储给定ValueHolder集合对象，并将转换后得到的全部参数值对象添加到其中
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					//迭代，获取其valueHolders中的每个值的类型名称（只有类名，不包含包命），并添加到argTypes中
					for (ValueHolder value : valueHolders) {
						//如果此对象的type不为空，则取其中的类名称，否则取其值的类型名称；如果都为空，则保存“null”
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				//将参数类型名称的集合转化为逗号隔开的字符串，并抛出异常
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found: " +
						(mbd.getFactoryBeanName() != null ?
							"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			//如果存在可执行的工厂方法，但是其无返回值（void），则抛出异常
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() +
						"': needs to have a non-void return type!");
			}
			//如果存在多个匹配的工厂方法，则抛出异常
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}
			//如果本次使用的参数为配置参数解析得到的，则缓存解析得到的工厂方法或者构造函数
			if (explicitArgs == null && argsHolderToUse != null) {
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}
		//获得的工厂方法或者构造函数的参数不可为空
		Assert.state(argsToUse != null, "Unresolved factory method arguments");
		//创建bean对象，并将其添加到bw（bean的包装类）中
		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}
	/**使用指定beanFactory的实例化策略来创建bean对象*/
	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			//安全校验
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						this.beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * 将此bean的构造函数参数解析为resolvedValues对象
	 * This may involve looking up other beans.
	 * 这可能涉及到查找其它bean
	 * <p>This method is also used for handling invocations of static factory methods.
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {
		//返回当前beanFactory使用的自定义类型解析器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		//如果此bean工厂的自定义类型解析器为空时，则默认使用给定的bw；否则使用获取到的自定义类型解析器customConverter
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		//创建一个BeanDefinitionValueResolver对象
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		//获取资源文件定义的该bean的总参数个数（包括名称定义和索引定义两种的总值）
		int minNrOfArgs = cargs.getArgumentCount();
		//遍历索引参数值的集合
		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			//获取索引
			int index = entry.getKey();
			//检查有效性
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			//如果当前索引数超过记录的参数个数，则更新
			if (index > minNrOfArgs) {
				minNrOfArgs = index + 1;
			}
			//获取索引对象的参数值
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			//如果此值已经解析过了，则直接将其添加到resolvedValues中
			if (valueHolder.isConverted()) {
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			else {
				//如果此参数值未解析过，则进行参数值解析
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				//创建一个ValueHolder对象
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				//设置此对象的解析源（即此对象是通过解析valueHolder对象得到的）
				resolvedValueHolder.setSource(valueHolder);
				//添加解析好的值对象和对应的索引到resolvedValues中存储
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}
		//遍历名称参数值的集合
		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			//当前参数值是否解析完成（即当前参数如果不是直接值的话，是否已经转化为一个值或一个对象）
			if (valueHolder.isConverted()) {
				//添加到resolvedValues对象中
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			else {
				//没有解析，则解析该value值（将一个标识或者引用解析为一个值或者对象）
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				//创建ValueHolder对象
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				//设置源引用（创建其对象的源对象）
				resolvedValueHolder.setSource(valueHolder);
				//添加到resolvedValues中
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}
		//返回参数的总个数
		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 * 通过给定的已经解析的构造函数参数值，创建一个参数数组来调用构造函数或工厂方法
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {
		//获取bean工厂的自定义类型转换器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		//如存在自定义类型解析器，则使用，否则使用bw
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		//创建ArgumentsHolder对象
		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		//创建保存使用的参数值信息的对象集合
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		//自动注入的bean名称集合
		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		//迭代参数类型的集合
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			Class<?> paramType = paramTypes[paramIndex];
			//paramNames数组不为空的话，获取当前索引对应的参数名，否则为空字符串
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			//如果解析生成的构造函数参数值不为空
			if (resolvedValues != null) {
				//从解析之后的参数集合中获取paramIndex索引处与给定的类型与名称相符合的参数值对象
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				//如果我们找不到直接匹配，并且不打算自动装配，那么让我们尝试下一个泛型的、无类型
				// 的参数值作为回退:它可以在类型转换之后匹配(例如，String -> int)。
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			//如果找到了满满足此参数的参数值设置
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				//加入使用过的valueHolder集合中，以避免重复使用
				usedValueHolders.add(valueHolder);
				//获取原始值
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				//如果当前值已经转换完成
				if (valueHolder.isConverted()) {
					//获取转化完之后的值
					convertedValue = valueHolder.getConvertedValue();
					//保存当前值到指定参数值中
					args.preparedArguments[paramIndex] = convertedValue;
				}
				else {
					//根据给定的执行者（方法或者构造函数）和参数索引创建MethodParameter对象
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						//根据原始值和参数的类型进行参数转换
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
										ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
										"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					//获取参数值对象的源
					Object sourceHolder = valueHolder.getSource();
					//获取创建此值对象依赖的源中的值，并添加到参数持有人对象中（如果源为ValueHolder类型）
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						args.resolveNecessary = true;
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				//缓存要使用的参数值对象（转换后的参数值）
				args.arguments[paramIndex] = convertedValue;
				//保存当前参数转化之前的原始值
				args.rawArguments[paramIndex] = originalValue;
			}
			else {
				//根据给定方法对象创建MethodParameter对象
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				//没有找到明确的匹配，我们应该要么自动装配，要么抛出一个异常
				//不自动注入，并且没有找到明确的参数匹配，抛出UnsatisfiedDependencyException异常
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					//自动生成方法当前所需的参数
					Object autowiredArgument = resolveAutowiredArgument(
							methodParam, beanName, autowiredBeanNames, converter, fallback);
					//将参数添加到ArgumentsHolder中
					args.rawArguments[paramIndex] = autowiredArgument;
					args.arguments[paramIndex] = autowiredArgument;
					//用于标记当前的参数为自动注入的
					args.preparedArguments[paramIndex] = new AutowiredArgumentMarker();
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}
		//循环遍历自动生成的bean名称，并且注册相关依赖关系
		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + (executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve, boolean fallback) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Class<?>[] paramTypes = executable.getParameterTypes();

		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
			GenericTypeResolver.resolveParameterType(methodParam, executable.getDeclaringClass());
			if (argValue instanceof AutowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter, fallback);
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
						"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		return resolvedArgs;
	}

	protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 * 用于解析指定参数的模板方法，该参数应该是自动生成的。
	 */
	@Nullable
	protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {
		//获取当前方法参数的类型
		Class<?> paramType = param.getParameterType();
		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			return injectionPoint;
		}
		try {
			return this.beanFactory.resolveDependency(
					new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				}
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			throw ex;
		}
	}

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 * 持有参数组合的私有内部类（用于保存参数）
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}
		/**类型差异权重判断方式比较严格*/
		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			//匹配当前方法的参数集合与给定的转换之后的参数值集合，得到类型差异权重值（按照顺序类型匹配的匹配情况）
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			//获取转换之前的原参数值集合与当前方法参数类型的类型差异权重值
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			//返回类型差异权重值较小的一个
			return (rawTypeDiffWeight < typeDiffWeight ? rawTypeDiffWeight : typeDiffWeight);
		}
		/**只判断给定的值是否是给定参数类型或其子类，如果是都一样比较小，如果不是比较大，判定方式比较宽松*/
		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			//匹配转换之后的参数值类型是否与给定类型相同，或者是其的子类
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			//匹配转换之前的参数值类型是否与给定类型相同，或者是其子类
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			//全局锁
			synchronized (mbd.constructorArgumentLock) {
				//缓存解析得到的工厂方法或构造函数
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				//参数解析为true
				mbd.constructorArgumentsResolved = true;
				//此方法给定的参数是否存在未转换的原始值，如果存在
				if (this.resolveNecessary) {
					//将此转换前的原始参数值数据添加到preparedConstructorArguments缓存中（部分准备好的构造函数或方法的参数）
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					//如果全部参数皆转换完成，则将转换完的参数值数据保存到resolvedConstructorArguments缓存中（缓存完全解析完的构造函数或方法的参数）
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Marker for autowired arguments in a cached argument array.
	 * 在缓存的参数数组中标记自动获取的参数。
 	 */
	private static class AutowiredArgumentMarker {
	}


	/**
	 * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}

}
