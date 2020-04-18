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

package org.springframework.web.servlet.support;

import java.util.EnumSet;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.FilterRegistration.Dynamic;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.core.Conventions;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.web.context.AbstractContextLoaderInitializer;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FrameworkServlet;

/**
 * Base class for {@link org.springframework.web.WebApplicationInitializer}
 * implementations that register a {@link DispatcherServlet} in the servlet context.
 *
 * <p>Most applications should consider extending the Spring Java config subclass
 * {@link AbstractAnnotationConfigDispatcherServletInitializer}.
 *
 * @author Arjen Poutsma
 * @author Chris Beams
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 3.2
 */
public abstract class AbstractDispatcherServletInitializer extends AbstractContextLoaderInitializer {

	/**
	 * The default servlet name. Can be customized by overriding {@link #getServletName}.
	 * 默认的servlet名称
	 */
	public static final String DEFAULT_SERVLET_NAME = "dispatcher";


	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		//调用父类的启动逻辑
		super.onStartup(servletContext);
		//注册DispatcherServlet
		registerDispatcherServlet(servletContext);
	}

	/**
	 * Register a {@link DispatcherServlet} against the given servlet context.
	 * <p>This method will create a {@code DispatcherServlet} with the name returned by
	 * {@link #getServletName()}, initializing it with the application context returned
	 * from {@link #createServletApplicationContext()}, and mapping it to the patterns
	 * returned from {@link #getServletMappings()}.
	 * <p>Further customization can be achieved by overriding {@link
	 * #customizeRegistration(ServletRegistration.Dynamic)} or
	 * {@link #createDispatcherServlet(WebApplicationContext)}.
	 * @param servletContext the context to register the servlet against
	 */
	protected void registerDispatcherServlet(ServletContext servletContext) {
		//获取servlet名称（此处默认实现返回的为默认名称‘dispatcher’，子类可通过重写来进行更改）
		String servletName = getServletName();
		//断言servletName不为空
		Assert.hasLength(servletName, "getServletName() must not return null or empty");
		//创建Servlet WebApplicationContext对象
		WebApplicationContext servletAppContext = createServletApplicationContext();
		//断言创建的servlet webApplicationContext对象不为空
		Assert.notNull(servletAppContext, "createServletApplicationContext() must not return null");
		//创建FrameworkServlet对象
		FrameworkServlet dispatcherServlet = createDispatcherServlet(servletAppContext);
		//断言创建的dispatcherServlet不为空
		Assert.notNull(dispatcherServlet, "createDispatcherServlet(WebApplicationContext) must not return null");
		//将servletApplicationContext初始化器们注册到dispatcherServlet中
		dispatcherServlet.setContextInitializers(getServletApplicationContextInitializers());
		//将给定的dispatcherServlet注册到当前servletContext容器中，并返回当前servlet对象的操作对象
		ServletRegistration.Dynamic registration = servletContext.addServlet(servletName, dispatcherServlet);
		//如果已经有另一个servlet对象以与其相同的名字注册到了servletContext容器中，则抛出异常
		if (registration == null) {
			throw new IllegalStateException("Failed to register servlet with name '" + servletName + "'. " +
					"Check if there is another servlet registered under the same name.");
		}
		//设置当前servlet的loadOnStartup属性（此处的属性皆是存储在servletInfo对象中）
		registration.setLoadOnStartup(1);
		//给当前servlet注册相应的映射关系
		registration.addMapping(getServletMappings());
		//设置servlet的asyncSupported属性
		registration.setAsyncSupported(isAsyncSupported());
		//获取给当前servlet容器配置的要注册的filter集合
		Filter[] filters = getServletFilters();
		//如果有要注册的过滤器
		if (!ObjectUtils.isEmpty(filters)) {
			//遍历过滤器集合
			for (Filter filter : filters) {
				//注册当前配置的过滤器到当前servletContext容器中
				registerServletFilter(servletContext, filter);
			}
		}
		//在注册完servlet之后，执行自定义的注册逻辑
		customizeRegistration(registration);
	}

	/**
	 * Return the name under which the {@link DispatcherServlet} will be registered.
	 * Defaults to {@link #DEFAULT_SERVLET_NAME}.
	 * @see #registerDispatcherServlet(ServletContext)
	 */
	protected String getServletName() {
		return DEFAULT_SERVLET_NAME;
	}

	/**
	 * Create a servlet application context to be provided to the {@code DispatcherServlet}.
	 * <p>The returned context is delegated to Spring's
	 * {@link DispatcherServlet#DispatcherServlet(WebApplicationContext)}. As such,
	 * it typically contains controllers, view resolvers, locale resolvers, and other
	 * web-related beans.
	 * @see #registerDispatcherServlet(ServletContext)
	 */
	protected abstract WebApplicationContext createServletApplicationContext();

	/**
	 * Create a {@link DispatcherServlet} (or other kind of {@link FrameworkServlet}-derived
	 * dispatcher) with the specified {@link WebApplicationContext}.
	 * 使用给定的WebApplicationContext对象创建一个DispatcherServlet实例对象（也相当于是其子类FrameworkServlet实例对象）
	 * <p>Note: This allows for any {@link FrameworkServlet} subclass as of 4.2.3.
	 * Previously, it insisted on returning a {@link DispatcherServlet} or subclass thereof.
	 */
	protected FrameworkServlet createDispatcherServlet(WebApplicationContext servletAppContext) {
		return new DispatcherServlet(servletAppContext);
	}

	/**
	 * Specify application context initializers to be applied to the servlet-specific
	 * application context that the {@code DispatcherServlet} is being created with.
	 * 指定应用程序上下文初始化器以应用于正在使用的DispatcherServlet创建的特定于servlet的应用程序上下文
	 * 此处默认返回null，具体自定义实现交由子类实现
	 * @since 4.2
	 * @see #createServletApplicationContext()
	 * @see DispatcherServlet#setContextInitializers
	 * @see #getRootApplicationContextInitializers()
	 */
	@Nullable
	protected ApplicationContextInitializer<?>[] getServletApplicationContextInitializers() {
		return null;
	}

	/**
	 * Specify the servlet mapping(s) for the {@code DispatcherServlet} &mdash;
	 * for example {@code "/"}, {@code "/app"}, etc.
	 * @see #registerDispatcherServlet(ServletContext)
	 */
	protected abstract String[] getServletMappings();

	/**
	 * Specify filters to add and map to the {@code DispatcherServlet}.
	 * 指定要添加和映射到dispatcherServlet中的过滤器，此处默认实现为返回null
	 * 具体自定义实现交由子类重写实现
	 * @return an array of filters or {@code null}
	 * @see #registerServletFilter(ServletContext, Filter)
	 */
	@Nullable
	protected Filter[] getServletFilters() {
		return null;
	}

	/**
	 * Add the given filter to the ServletContext and map it to the
	 * {@code DispatcherServlet} as follows:
	 * <ul>
	 * <li>a default filter name is chosen based on its concrete type
	 * <li>the {@code asyncSupported} flag is set depending on the
	 * return value of {@link #isAsyncSupported() asyncSupported}
	 * <li>a filter mapping is created with dispatcher types {@code REQUEST},
	 * {@code FORWARD}, {@code INCLUDE}, and conditionally {@code ASYNC} depending
	 * on the return value of {@link #isAsyncSupported() asyncSupported}
	 * </ul>
	 * <p>If the above defaults are not suitable or insufficient, override this
	 * method and register filters directly with the {@code ServletContext}.
	 * @param servletContext the servlet context to register filters with
	 * @param filter the filter to be registered
	 * @return the filter registration
	 */
	protected FilterRegistration.Dynamic registerServletFilter(ServletContext servletContext, Filter filter) {
		String filterName = Conventions.getVariableName(filter);
		Dynamic registration = servletContext.addFilter(filterName, filter);

		if (registration == null) {
			int counter = 0;
			while (registration == null) {
				if (counter == 100) {
					throw new IllegalStateException("Failed to register filter with name '" + filterName + "'. " +
							"Check if there is another filter registered under the same name.");
				}
				registration = servletContext.addFilter(filterName + "#" + counter, filter);
				counter++;
			}
		}

		registration.setAsyncSupported(isAsyncSupported());
		registration.addMappingForServletNames(getDispatcherTypes(), false, getServletName());
		return registration;
	}

	private EnumSet<DispatcherType> getDispatcherTypes() {
		return (isAsyncSupported() ?
				EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.INCLUDE, DispatcherType.ASYNC) :
				EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.INCLUDE));
	}

	/**
	 * A single place to control the {@code asyncSupported} flag for the
	 * {@code DispatcherServlet} and all filters added via {@link #getServletFilters()}.
	 * <p>The default value is "true".
	 */
	protected boolean isAsyncSupported() {
		return true;
	}

	/**
	 * Optionally perform further registration customization once
	 * {@link #registerDispatcherServlet(ServletContext)} has completed.
	 * 一旦注册dispatcherServlet完成，则可以有选择的进一步执行对注册的servlet的自定义配置
	 * 此抽象类此处为模板方法，即空方法；具体的实现逻辑交于其子类来实现（用户可以实现此类并自定义）
	 * @param registration the {@code DispatcherServlet} registration to be customized
	 * @see #registerDispatcherServlet(ServletContext)
	 */
	protected void customizeRegistration(ServletRegistration.Dynamic registration) {
	}

}
