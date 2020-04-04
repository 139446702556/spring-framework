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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.cglib.core.ClassGenerator;
import org.springframework.cglib.core.DefaultGeneratorStrategy;
import org.springframework.cglib.core.SpringNamingPolicy;
import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.CallbackFilter;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.cglib.proxy.NoOp;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Default object instantiation strategy for use in BeanFactories.
 *
 * <p>Uses CGLIB to generate subclasses dynamically if methods need to be
 * overridden by the container to implement <em>Method Injection</em>.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 1.1
 */
public class CglibSubclassingInstantiationStrategy extends SimpleInstantiationStrategy {

	/**
	 * Index in the CGLIB callback array for passthrough behavior,
	 * in which case the subclass won't override the original class.
	 * 此标识表示子类只是直接调用父类的对应方法，返回其返回的结果，无需任何覆盖处理（透传）
	 */
	private static final int PASSTHROUGH = 0;

	/**
	 * Index in the CGLIB callback array for a method that should
	 * be overridden to provide <em>method lookup</em>.
	 * 表示是解析lookup-method子标签生成的对象
	 */
	private static final int LOOKUP_OVERRIDE = 1;

	/**
	 * Index in the CGLIB callback array for a method that should
	 * be overridden using generic <em>method replacer</em> functionality.
	 * 表示是解析replace-method子标签生成的对象
	 */
	private static final int METHOD_REPLACER = 2;


	@Override
	protected Object instantiateWithMethodInjection(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner) {
		return instantiateWithMethodInjection(bd, beanName, owner, null);
	}

	@Override
	protected Object instantiateWithMethodInjection(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner,
			@Nullable Constructor<?> ctor, Object... args) {

		// Must generate CGLIB subclass...
		//通过cglib生成一个子类对象
		return new CglibSubclassCreator(bd, owner).instantiate(ctor, args);
	}


	/**
	 * An inner class created for historical reasons to avoid external CGLIB dependency
	 * in Spring versions earlier than 3.2.
	 */
	private static class CglibSubclassCreator {

		private static final Class<?>[] CALLBACK_TYPES = new Class<?>[]
				{NoOp.class, LookupOverrideMethodInterceptor.class, ReplaceOverrideMethodInterceptor.class};

		private final RootBeanDefinition beanDefinition;

		private final BeanFactory owner;
		/**使用给定的BeanDefinition和bean工厂创建一个cglib子类创建器对象*/
		CglibSubclassCreator(RootBeanDefinition beanDefinition, BeanFactory owner) {
			this.beanDefinition = beanDefinition;
			this.owner = owner;
		}

		/**
		 * Create a new instance of a dynamically generated subclass implementing the
		 * required lookups.
		 * 通过cglib创建一个给定类的子类，并返回此子类创建的对象
		 * @param ctor constructor to use. If this is {@code null}, use the
		 * no-arg constructor (no parameterization, or Setter Injection)
		 * @param args arguments to use for the constructor.
		 * Ignored if the {@code ctor} parameter is {@code null}.
		 * @return new instance of the dynamically generated subclass
		 */
		public Object instantiate(@Nullable Constructor<?> ctor, Object... args) {
			//通过cglib创建一个该类的增强的子类代理类
			Class<?> subclass = createEnhancedSubclass(this.beanDefinition);
			Object instance;
			//如果没有给定构造器，则通过BeanUtils使用默认构造器创建一个代理类对象
			if (ctor == null) {
				instance = BeanUtils.instantiateClass(subclass);
			}
			//如果给定了构造器
			else {
				try {
					//则获取代理类中与给定构造器相对应的构造器对象
					Constructor<?> enhancedSubclassConstructor = subclass.getConstructor(ctor.getParameterTypes());
					//通过给定参数与代理类构造器对象，反射创建bean实例对象
					instance = enhancedSubclassConstructor.newInstance(args);
				}
				catch (Exception ex) {
					throw new BeanInstantiationException(this.beanDefinition.getBeanClass(),
							"Failed to invoke constructor for CGLIB enhanced subclass [" + subclass.getName() + "]", ex);
				}
			}
			// SPR-10785: set callbacks directly on the instance instead of in the
			// enhanced class (via the Enhancer) in order to avoid memory leaks.
			//直接在实例对象上而不是增强类中设置回调（通过增强器），以避免内存泄漏
			Factory factory = (Factory) instance;
			factory.setCallbacks(new Callback[] {NoOp.INSTANCE,
					new LookupOverrideMethodInterceptor(this.beanDefinition, this.owner),
					new ReplaceOverrideMethodInterceptor(this.beanDefinition, this.owner)});
			return instance;
		}

		/**
		 * Create an enhanced subclass of the bean class for the provided bean
		 * definition, using CGLIB.
		 * 使用cglib为提供的BeanDefinition创建bean类的增强子类
		 */
		private Class<?> createEnhancedSubclass(RootBeanDefinition beanDefinition) {
			//创建Enhancer（增强器）对象
			Enhancer enhancer = new Enhancer();
			//设置要被增强的父类（bean类）
			enhancer.setSuperclass(beanDefinition.getBeanClass());
			//设置spring的命名策略
			enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);
			//如果给定的beanFactory为ConfigurableBeanFactory类型
			if (this.owner instanceof ConfigurableBeanFactory) {
				//获取给定bean工厂的beanClassLoader
				ClassLoader cl = ((ConfigurableBeanFactory) this.owner).getBeanClassLoader();
				//设置增强器的生成策略
				enhancer.setStrategy(new ClassLoaderAwareGeneratorStrategy(cl));
			}
			//设置增强器的回调过滤器
			//过滤，自定义逻辑来指定调用的callback下标
			enhancer.setCallbackFilter(new MethodOverrideCallbackFilter(beanDefinition));
			//通过给定的回调类对象集合，设置当前增强器要使用的回调类型数组
			enhancer.setCallbackTypes(CALLBACK_TYPES);
			//使用增强器创建给定bean类对应的增强子类
			return enhancer.createClass();
		}
	}


	/**
	 * Class providing hashCode and equals methods required by CGLIB to
	 * ensure that CGLIB doesn't generate a distinct class per bean.
	 * Identity is based on class and bean definition.
	 */
	private static class CglibIdentitySupport {

		private final RootBeanDefinition beanDefinition;

		public CglibIdentitySupport(RootBeanDefinition beanDefinition) {
			this.beanDefinition = beanDefinition;
		}

		public RootBeanDefinition getBeanDefinition() {
			return this.beanDefinition;
		}

		@Override
		public boolean equals(Object other) {
			return (other != null && getClass() == other.getClass() &&
					this.beanDefinition.equals(((CglibIdentitySupport) other).beanDefinition));
		}

		@Override
		public int hashCode() {
			return this.beanDefinition.hashCode();
		}
	}


	/**
	 * CGLIB GeneratorStrategy variant which exposes the application ClassLoader
	 * as thread context ClassLoader for the time of class generation
	 * (in order for ASM to pick it up when doing common superclass resolution).
	 */
	private static class ClassLoaderAwareGeneratorStrategy extends DefaultGeneratorStrategy {

		@Nullable
		private final ClassLoader classLoader;

		public ClassLoaderAwareGeneratorStrategy(@Nullable ClassLoader classLoader) {
			this.classLoader = classLoader;
		}

		@Override
		public byte[] generate(ClassGenerator cg) throws Exception {
			if (this.classLoader == null) {
				return super.generate(cg);
			}

			Thread currentThread = Thread.currentThread();
			ClassLoader threadContextClassLoader;
			try {
				threadContextClassLoader = currentThread.getContextClassLoader();
			}
			catch (Throwable ex) {
				// Cannot access thread context ClassLoader - falling back...
				return super.generate(cg);
			}

			boolean overrideClassLoader = !this.classLoader.equals(threadContextClassLoader);
			if (overrideClassLoader) {
				currentThread.setContextClassLoader(this.classLoader);
			}
			try {
				return super.generate(cg);
			}
			finally {
				if (overrideClassLoader) {
					// Reset original thread context ClassLoader.
					currentThread.setContextClassLoader(threadContextClassLoader);
				}
			}
		}
	}


	/**
	 * CGLIB callback for filtering method interception behavior.
	 * 用于过滤方法拦截行为的cglib回调
	 * CallbackFilter 是cglib的一个回调过滤器
	 * CglibIdentirySupport为cglib提供了hashcode()和equals(Object o)方法，以确保cglib不会为每个bean生成不同的类
	 */
	private static class MethodOverrideCallbackFilter extends CglibIdentitySupport implements CallbackFilter {

		private static final Log logger = LogFactory.getLog(MethodOverrideCallbackFilter.class);

		public MethodOverrideCallbackFilter(RootBeanDefinition beanDefinition) {
			super(beanDefinition);
		}

		@Override
		public int accept(Method method) {
			//从BeanDefinition中获取到当前给定方法method对象对应的覆盖方法对象（methodOverride0）
			MethodOverride methodOverride = getBeanDefinition().getMethodOverrides().getOverride(method);
			if (logger.isTraceEnabled()) {
				logger.trace("MethodOverride for " + method + ": " + methodOverride);
			}
			//以下返回的皆为回调类型的下标值（CALLBACK_TYPES数组字段的下标）
			//如果没有获取到此方法对应的methodOverride对象，则证明此方法无需覆盖，支持在子类中进行透传即可
			if (methodOverride == null) {
				return PASSTHROUGH;
			}
			//如果methodOverride是LookupOverride类型（lookup-method子标签定义的）
			else if (methodOverride instanceof LookupOverride) {
				return LOOKUP_OVERRIDE;
			}
			//如果methodOverride是ReplaceOverride类型（replace-method子标签定义的）
			else if (methodOverride instanceof ReplaceOverride) {
				return METHOD_REPLACER;
			}
			//如果获取到的methodOverride不为空，并且不为上述两种类型的实例化对象，则抛出异常
			throw new UnsupportedOperationException("Unexpected MethodOverride subclass: " +
					methodOverride.getClass().getName());
		}
	}


	/**
	 * CGLIB MethodInterceptor to override methods, replacing them with an
	 * implementation that returns a bean looked up in the container.
	 */
	private static class LookupOverrideMethodInterceptor extends CglibIdentitySupport implements MethodInterceptor {

		private final BeanFactory owner;

		public LookupOverrideMethodInterceptor(RootBeanDefinition beanDefinition, BeanFactory owner) {
			super(beanDefinition);
			this.owner = owner;
		}

		@Override
		public Object intercept(Object obj, Method method, Object[] args, MethodProxy mp) throws Throwable {
			// Cast is safe, as CallbackFilter filters are used selectively.
			//根据当前调用的方法（method）去对应BeanDefinition中获取对应的LookupOverride对象（即lookup-method子元素标签设置的值构成的）
			LookupOverride lo = (LookupOverride) getBeanDefinition().getMethodOverrides().getOverride(method);
			//判断获取到的LookupOverride对象是否为空
			Assert.state(lo != null, "LookupOverride not found");
			//获得参数
			Object[] argsToUse = (args.length > 0 ? args : null);  // if no-arg, don't insist on args at all
			//如果LookupOverride设置了BeanName，则使用BeanName和参数来去BeanFactory中获取对应bean对象
			if (StringUtils.hasText(lo.getBeanName())) {
				return (argsToUse != null ? this.owner.getBean(lo.getBeanName(), argsToUse) :
						this.owner.getBean(lo.getBeanName()));
			}
			//如果未设置BeanName,则使用当前调用方法的返回值类型和参数来获取
			else {
				return (argsToUse != null ? this.owner.getBean(method.getReturnType(), argsToUse) :
						this.owner.getBean(method.getReturnType()));
			}
		}
	}


	/**
	 * CGLIB MethodInterceptor to override methods, replacing them with a call
	 * to a generic MethodReplacer.
	 */
	private static class ReplaceOverrideMethodInterceptor extends CglibIdentitySupport implements MethodInterceptor {

		private final BeanFactory owner;

		public ReplaceOverrideMethodInterceptor(RootBeanDefinition beanDefinition, BeanFactory owner) {
			super(beanDefinition);
			this.owner = owner;
		}

		@Override
		public Object intercept(Object obj, Method method, Object[] args, MethodProxy mp) throws Throwable {
			//获取method对应的ReplaceOverride对象
			ReplaceOverride ro = (ReplaceOverride) getBeanDefinition().getMethodOverrides().getOverride(method);
			//断言判断ReplaceOverride对象不为空
			Assert.state(ro != null, "ReplaceOverride not found");
			// TODO could cache if a singleton for minor performance optimization
			//获取MethodReplacer对象
			MethodReplacer mr = this.owner.getBean(ro.getMethodReplacerBeanName(), MethodReplacer.class);
			//进行执行替换
			return mr.reimplement(obj, method, args);
		}
	}

}
