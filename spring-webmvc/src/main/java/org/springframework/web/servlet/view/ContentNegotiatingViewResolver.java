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

package org.springframework.web.servlet.view;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.accept.ContentNegotiationManagerFactoryBean;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.support.WebApplicationObjectSupport;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.SmartView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Implementation of {@link ViewResolver} that resolves a view based on the request file name
 * or {@code Accept} header.
 *
 * <p>The {@code ContentNegotiatingViewResolver} does not resolve views itself, but delegates to
 * other {@link ViewResolver ViewResolvers}. By default, these other view resolvers are picked up automatically
 * from the application context, though they can also be set explicitly by using the
 * {@link #setViewResolvers viewResolvers} property. <strong>Note</strong> that in order for this
 * view resolver to work properly, the {@link #setOrder order} property needs to be set to a higher
 * precedence than the others (the default is {@link Ordered#HIGHEST_PRECEDENCE}).
 *
 * <p>This view resolver uses the requested {@linkplain MediaType media type} to select a suitable
 * {@link View} for a request. The requested media type is determined through the configured
 * {@link ContentNegotiationManager}. Once the requested media type has been determined, this resolver
 * queries each delegate view resolver for a {@link View} and determines if the requested media type
 * is {@linkplain MediaType#includes(MediaType) compatible} with the view's
 * {@linkplain View#getContentType() content type}). The most compatible view is returned.
 *
 * <p>Additionally, this view resolver exposes the {@link #setDefaultViews(List) defaultViews} property,
 * allowing you to override the views provided by the view resolvers. Note that these default views are
 * offered as candidates, and still need have the content type requested (via file extension, parameter,
 * or {@code Accept} header, described above).
 *
 * <p>For example, if the request path is {@code /view.html}, this view resolver will look for a view
 * that has the {@code text/html} content type (based on the {@code html} file extension). A request
 * for {@code /view} with a {@code text/html} request {@code Accept} header has the same result.
 *
 * @author Arjen Poutsma
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @since 3.0
 * @see ViewResolver
 * @see InternalResourceViewResolver
 * @see BeanNameViewResolver
 * 此类用于基于内容类型来获取对应View的ViewResolver实现类
 * 其中内容类型指的是Content-Type和扩展后缀
 */
public class ContentNegotiatingViewResolver extends WebApplicationObjectSupport
		implements ViewResolver, Ordered, InitializingBean {

	@Nullable
	private ContentNegotiationManager contentNegotiationManager;
	/**ContentNegotiationManager的工厂，用于创建ContentNegotiationManager对象*/
	private final ContentNegotiationManagerFactoryBean cnmFactoryBean = new ContentNegotiationManagerFactoryBean();
	/**在找不到view对象时，返回NOT_ACCEPTABLE_VIEW*/
	private boolean useNotAcceptableStatusCode = false;
	/**默认的View数组*/
	@Nullable
	private List<View> defaultViews;
	/**ViewResolver数组*/
	@Nullable
	private List<ViewResolver> viewResolvers;
	/**排序级别，优先级最高，此字段用于在初始化时对视图解析器的排序*/
	private int order = Ordered.HIGHEST_PRECEDENCE;


	/**
	 * Set the {@link ContentNegotiationManager} to use to determine requested media types.
	 * <p>If not set, ContentNegotiationManager's default constructor will be used,
	 * applying a {@link org.springframework.web.accept.HeaderContentNegotiationStrategy}.
	 * @see ContentNegotiationManager#ContentNegotiationManager()
	 */
	public void setContentNegotiationManager(@Nullable ContentNegotiationManager contentNegotiationManager) {
		this.contentNegotiationManager = contentNegotiationManager;
	}

	/**
	 * Return the {@link ContentNegotiationManager} to use to determine requested media types.
	 * @since 4.1.9
	 */
	@Nullable
	public ContentNegotiationManager getContentNegotiationManager() {
		return this.contentNegotiationManager;
	}

	/**
	 * Indicate whether a {@link HttpServletResponse#SC_NOT_ACCEPTABLE 406 Not Acceptable}
	 * status code should be returned if no suitable view can be found.
	 * <p>Default is {@code false}, meaning that this view resolver returns {@code null} for
	 * {@link #resolveViewName(String, Locale)} when an acceptable view cannot be found.
	 * This will allow for view resolvers chaining. When this property is set to {@code true},
	 * {@link #resolveViewName(String, Locale)} will respond with a view that sets the
	 * response status to {@code 406 Not Acceptable} instead.
	 */
	public void setUseNotAcceptableStatusCode(boolean useNotAcceptableStatusCode) {
		this.useNotAcceptableStatusCode = useNotAcceptableStatusCode;
	}

	/**
	 * Whether to return HTTP Status 406 if no suitable is found.
	 */
	public boolean isUseNotAcceptableStatusCode() {
		return this.useNotAcceptableStatusCode;
	}

	/**
	 * Set the default views to use when a more specific view can not be obtained
	 * from the {@link ViewResolver} chain.
	 */
	public void setDefaultViews(List<View> defaultViews) {
		this.defaultViews = defaultViews;
	}

	public List<View> getDefaultViews() {
		return (this.defaultViews != null ? Collections.unmodifiableList(this.defaultViews) :
				Collections.emptyList());
	}

	/**
	 * Sets the view resolvers to be wrapped by this view resolver.
	 * <p>If this property is not set, view resolvers will be detected automatically.
	 */
	public void setViewResolvers(List<ViewResolver> viewResolvers) {
		this.viewResolvers = viewResolvers;
	}

	public List<ViewResolver> getViewResolvers() {
		return (this.viewResolvers != null ? Collections.unmodifiableList(this.viewResolvers) :
				Collections.emptyList());
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**用于初始化viewResolvers属性*/
	@Override
	protected void initServletContext(ServletContext servletContext) {
		//从当前上下文容器中扫描出所有类型为ViewResolver类型的bean们
		Collection<ViewResolver> matchingBeans =
				BeanFactoryUtils.beansOfTypeIncludingAncestors(obtainApplicationContext(), ViewResolver.class).values();
		//情况一，如果viewResolvers为空，则使用matchingBeans作为viewResolvers
		if (this.viewResolvers == null) {
			//初始化viewResolvers属性
			this.viewResolvers = new ArrayList<>(matchingBeans.size());
			//遍历matchingBeans属性
			for (ViewResolver viewResolver : matchingBeans) {
				//如果当前viewResolver解析器不是当前解析器，则将其加入到viewResolvers中
				if (this != viewResolver) {
					this.viewResolvers.add(viewResolver);
				}
			}
		}
		//情况二，如果viewResolvers非空，则和matchingBeans进行比对，判断哪里未进行初始化，则进行需要的初始化
		else {
			//迭代viewResolvers数组
			for (int i = 0; i < this.viewResolvers.size(); i++) {
				//获取ViewResolver对象
				ViewResolver vr = this.viewResolvers.get(i);
				//判断当前vr对象是否在matchingBeans中存在相同的，如果存在，说明已经初始化了，则直接continue
				if (matchingBeans.contains(vr)) {
					continue;
				}
				//如果不存在，则说明还没有进行初始化，则开始进行初始化
				//生成其名字
				String name = vr.getClass().getName() + i;
				//对当前ViewResolver类型的bean对象进行相应初始化
				obtainApplicationContext().getAutowireCapableBeanFactory().initializeBean(vr, name);
			}

		}
		//排序viewResolvers数组
		AnnotationAwareOrderComparator.sort(this.viewResolvers);
		//设置cnmFactoryBean对象的ServletContext属性
		this.cnmFactoryBean.setServletContext(servletContext);
	}
	/**用于初始化contentNegotiationManager属性*/
	@Override
	public void afterPropertiesSet() {
		//如果contentNegotiationManager属性为空，则进行相应的创建
		if (this.contentNegotiationManager == null) {
			this.contentNegotiationManager = this.cnmFactoryBean.build();
		}
		//如果viewResolvers为空的话，则进行日志记录
		if (this.viewResolvers == null || this.viewResolvers.isEmpty()) {
			logger.warn("No ViewResolvers configured");
		}
	}


	@Override
	@Nullable
	public View resolveViewName(String viewName, Locale locale) throws Exception {
		//获取绑定到当前线程的RequestAttributes对象
		RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
		//断言此attrs变量为ServletRequestAttributes类型的对象
		Assert.state(attrs instanceof ServletRequestAttributes, "No current ServletRequestAttributes");
		//获得MediaType数组
		List<MediaType> requestedMediaTypes = getMediaTypes(((ServletRequestAttributes) attrs).getRequest());
		//如果获取到了
		if (requestedMediaTypes != null) {
			//获取匹配的View数组
			List<View> candidateViews = getCandidateViews(viewName, locale, requestedMediaTypes);
			//筛选出当前最匹配的View对象
			View bestView = getBestView(candidateViews, requestedMediaTypes, attrs);
			//如果筛选成功，则直接返回
			if (bestView != null) {
				return bestView;
			}
		}
		//如果匹配不到对应视图，则用来记录相应日志的信息
		String mediaTypeInfo = logger.isDebugEnabled() && requestedMediaTypes != null ?
				" given " + requestedMediaTypes.toString() : "";
		//如果没有匹配到对应的View对象，则根据useNotAcceptableStatusCode属性来返回对应的NOT_ACCEPTABLE_VIEW或者null
		if (this.useNotAcceptableStatusCode) {
			if (logger.isDebugEnabled()) {
				logger.debug("Using 406 NOT_ACCEPTABLE" + mediaTypeInfo);
			}
			return NOT_ACCEPTABLE_VIEW;
		}
		else {
			logger.debug("View remains unresolved" + mediaTypeInfo);
			return null;
		}
	}

	/**
	 * Determines the list of {@link MediaType} for the given {@link HttpServletRequest}.
	 * 通过解析给定的request对象来获得对应的MediaType数组
	 * @param request the current servlet request
	 * @return the list of media types requested, if any
	 */
	@Nullable
	protected List<MediaType> getMediaTypes(HttpServletRequest request) {
		//断言contentNegotiationManager不为空
		Assert.state(this.contentNegotiationManager != null, "No ContentNegotiationManager set");
		try {
			//使用给定的request对象创建一个新的ServletWebRequest对象
			ServletWebRequest webRequest = new ServletWebRequest(request);
			//解析当前请求，从请求中获得可接受的MediaType数组，默认的实现是从请求头ACCEPT中获取
			List<MediaType> acceptableMediaTypes = this.contentNegotiationManager.resolveMediaTypes(webRequest);
			//获得可生产的MediaType数组
			List<MediaType> producibleMediaTypes = getProducibleMediaTypes(request);
			//通过与acceptableMediaTypes的对比，将当前符合的producibleMediaTypes添加到compatibleMediaTypes结果数组中
			Set<MediaType> compatibleMediaTypes = new LinkedHashSet<>();
			for (MediaType acceptable : acceptableMediaTypes) {
				for (MediaType producible : producibleMediaTypes) {
					if (acceptable.isCompatibleWith(producible)) {
						compatibleMediaTypes.add(getMostSpecificMediaType(acceptable, producible));
					}
				}
			}
			//将当前的set集合转化为list集合
			List<MediaType> selectedMediaTypes = new ArrayList<>(compatibleMediaTypes);
			//按照MediaType的specificity、Quality属性进行排序
			MediaType.sortBySpecificityAndQuality(selectedMediaTypes);
			return selectedMediaTypes;
		}
		catch (HttpMediaTypeNotAcceptableException ex) {
			if (logger.isDebugEnabled()) {
				logger.debug(ex.getMessage());
			}
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private List<MediaType> getProducibleMediaTypes(HttpServletRequest request) {
		//从当前请求的属性中获取MediaType数组
		Set<MediaType> mediaTypes = (Set<MediaType>)
				request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
		//如果获取到了，则直接返回
		if (!CollectionUtils.isEmpty(mediaTypes)) {
			return new ArrayList<>(mediaTypes);
		}
		//如果没有获取到，则返回MediaType.ALL
		else {
			return Collections.singletonList(MediaType.ALL);
		}
	}

	/**
	 * Return the more specific of the acceptable and the producible media types
	 * with the q-value of the former.
	 * 返回更具体的可接受和可生产的媒体类型与前一个的q值。
	 */
	private MediaType getMostSpecificMediaType(MediaType acceptType, MediaType produceType) {
		produceType = produceType.copyQualityValue(acceptType);
		return (MediaType.SPECIFICITY_COMPARATOR.compare(acceptType, produceType) < 0 ? acceptType : produceType);
	}
	/**获得匹配的View数组*/
	private List<View> getCandidateViews(String viewName, Locale locale, List<MediaType> requestedMediaTypes)
			throws Exception {
		//创建View数组，用于存储匹配的View对象
		List<View> candidateViews = new ArrayList<>();
		//数据来源一：
		//如果viewResolvers不为空
		if (this.viewResolvers != null) {
			//断言contentNegotiationManager不为空
			Assert.state(this.contentNegotiationManager != null, "No ContentNegotiationManager set");
			//迭代viewResolvers数组
			for (ViewResolver viewResolver : this.viewResolvers) {
				//通过解析当前viewName来获得对应的View对象
				View view = viewResolver.resolveViewName(viewName, locale);
				//如果解析成功，则将其添加到candidateViews中
				if (view != null) {
					candidateViews.add(view);
				}
				//以下为处理带有扩展后缀的方式，获得View对象，添加到candidateViews中
				//迭代requestedMediaTypes数组
				for (MediaType requestedMediaType : requestedMediaTypes) {
					//获得当前MediaType对应的扩展后缀的数组
					List<String> extensions = this.contentNegotiationManager.resolveFileExtensions(requestedMediaType);
					//遍历扩展后缀的数组
					for (String extension : extensions) {
						//使用带有文件扩展后缀的方式去获取对应的View对象，并将其添加到candidateViews中
						String viewNameWithExtension = viewName + '.' + extension;
						view = viewResolver.resolveViewName(viewNameWithExtension, locale);
						if (view != null) {
							candidateViews.add(view);
						}
					}
				}
			}
		}
		//数据来源二：
		//如果defaultViews属性不为空，则将其添加到candidateViews中
		if (!CollectionUtils.isEmpty(this.defaultViews)) {
			candidateViews.addAll(this.defaultViews);
		}
		return candidateViews;
	}
	/**筛选最匹配的View对象*/
	@Nullable
	private View getBestView(List<View> candidateViews, List<MediaType> requestedMediaTypes, RequestAttributes attrs) {
		//遍历candidateViews数组，如果有重定向的View类型，则返回它（说明View对象中，重定向的View优先级更高）
		for (View candidateView : candidateViews) {
			//如果candidateView是SmartView类型
			if (candidateView instanceof SmartView) {
				//类型转换
				SmartView smartView = (SmartView) candidateView;
				//如果此View对象是重定向视图，则直接返回
				if (smartView.isRedirectView()) {
					return candidateView;
				}
			}
		}
		//迭代MediaType数组
		for (MediaType mediaType : requestedMediaTypes) {
			//迭代candidateViews数组
			for (View candidateView : candidateViews) {
				//如果当前View的ContentType不为空
				if (StringUtils.hasText(candidateView.getContentType())) {
					//将当前View的ContentType转换为对应的MediaType对象
					MediaType candidateContentType = MediaType.parseMediaType(candidateView.getContentType());
					//判断当前View的MediaType是否与当前迭代的mediaType相匹配
					if (mediaType.isCompatibleWith(candidateContentType)) {
						//记录debug日志
						if (logger.isDebugEnabled()) {
							logger.debug("Selected '" + mediaType + "' given " + requestedMediaTypes);
						}
						//设置给定RequestAttributes的属性对象
						attrs.setAttribute(View.SELECTED_CONTENT_TYPE, mediaType, RequestAttributes.SCOPE_REQUEST);
						//返回当前匹配的View对象
						return candidateView;
					}
				}
			}
		}
		//如果上述没有匹配的View对象，则返回null
		return null;
	}


	private static final View NOT_ACCEPTABLE_VIEW = new View() {

		@Override
		@Nullable
		public String getContentType() {
			return null;
		}
		/**用于渲染视图的方法*/
		@Override
		public void render(@Nullable Map<String, ?> model, HttpServletRequest request, HttpServletResponse response) {
			//只是将相应的状态码设置为了406
			response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
		}
	};

}
