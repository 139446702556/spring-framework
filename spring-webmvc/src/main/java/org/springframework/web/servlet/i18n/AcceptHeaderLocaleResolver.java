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

package org.springframework.web.servlet.i18n;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.LocaleResolver;

/**
 * {@link LocaleResolver} implementation that simply uses the primary locale
 * specified in the "accept-language" header of the HTTP request (that is,
 * the locale sent by the client browser, normally that of the client's OS).
 *
 * <p>Note: Does not support {@code setLocale}, since the accept header
 * can only be changed through changing the client's locale settings.
 *
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @since 27.02.2003
 * @see javax.servlet.http.HttpServletRequest#getLocale()
 * LocaleResolver接口的实现类
 * 简单的使用http请求头里面的Accept-Language来指定Localed对象（即客户端浏览器发送的语言环境，通常是客户端的操作系统）
 * 注意：不支持setLocalef方法，因为只有通过更改客户端的区域来设置更改Accept-Language请求头
 * spring-mvc默认使用
 */
public class AcceptHeaderLocaleResolver implements LocaleResolver {
	/**存储当前支持的Locale数组*/
	private final List<Locale> supportedLocales = new ArrayList<>(4);
	/**默认的Locale*/
	@Nullable
	private Locale defaultLocale;


	/**
	 * Configure supported locales to check against the requested locales
	 * determined via {@link HttpServletRequest#getLocales()}. If this is not
	 * configured then {@link HttpServletRequest#getLocale()} is used instead.
	 * @param locales the supported locales
	 * @since 4.3
	 * 配置支持的区域设置列表
	 */
	public void setSupportedLocales(List<Locale> locales) {
		//清空缓存
		this.supportedLocales.clear();
		//将给定的locales设置到supprtedLocales缓存中
		this.supportedLocales.addAll(locales);
	}

	/**
	 * Return the configured list of supported locales.
	 * 返回配置的支持的区域设置列表
	 * @since 4.3
	 */
	public List<Locale> getSupportedLocales() {
		return this.supportedLocales;
	}

	/**
	 * Configure a fixed default locale to fall back on if the request does not
	 * have an "Accept-Language" header.
	 * 如果Http请求头中没有Accept-Language，则使用该默认的语言环境配置
	 * <p>By default this is not set in which case when there is "Accept-Language"
	 * header, the default locale for the server is used as defined in
	 * {@link HttpServletRequest#getLocale()}.
	 * @param defaultLocale the default locale to use
	 * @since 4.3
	 */
	public void setDefaultLocale(@Nullable Locale defaultLocale) {
		this.defaultLocale = defaultLocale;
	}

	/**
	 * The configured default locale, if any.
	 * 如果有的话，则返回默认的语言环境配置
	 * @since 4.3
	 */
	@Nullable
	public Locale getDefaultLocale() {
		return this.defaultLocale;
	}

	/**从给定的请求中解析得到其对应的Locale配置*/
	@Override
	public Locale resolveLocale(HttpServletRequest request) {
		//获取默认的语言环境配置
		Locale defaultLocale = getDefaultLocale();
		//如果存在默认的语言环境配置，并且当前请求头中没有Accept-Language属性值，则直接返回默认的语言环境配置
		if (defaultLocale != null && request.getHeader("Accept-Language") == null) {
			return defaultLocale;
		}
		//从给定请求中获取其对应的Locale对象
		Locale requestLocale = request.getLocale();
		//获取配置的支持的区域设置列表
		List<Locale> supportedLocales = getSupportedLocales();
		//如果当前解析器支持的Locale列表为空，或者当前请求的Locale可以被此解析器支持，则返回给定请求的Locale对象
		if (supportedLocales.isEmpty() || supportedLocales.contains(requestLocale)) {
			return requestLocale;
		}
		//从当前请求中获取此解析器可以支持的Locale对象，如果获取成功，则直接返回
		Locale supportedLocale = findSupportedLocale(request, supportedLocales);
		if (supportedLocale != null) {
			return supportedLocale;
		}
		//如果设置了默认的语言环境，则直接返回，否者返回requestLocale
		return (defaultLocale != null ? defaultLocale : requestLocale);
	}

	@Nullable
	private Locale findSupportedLocale(HttpServletRequest request, List<Locale> supportedLocales) {
		//从当前请求中获取设置的所有Locale集合
		Enumeration<Locale> requestLocales = request.getLocales();
		//用于存储匹配的Locale对象
		Locale languageMatch = null;
		//迭代requestLocales集合
		while (requestLocales.hasMoreElements()) {
			//获取Locale对象
			Locale locale = requestLocales.nextElement();
			//判断当前Locale对象的解析此解析器是否支持
			if (supportedLocales.contains(locale)) {
				//如果支持，并且还没匹配到合适的Locale对象或者匹配到的Locale对象的语言和当前支持匹配的Locale对象的语言相同
				//则直接返回当前匹配的Locale对象
				if (languageMatch == null || languageMatch.getLanguage().equals(locale.getLanguage())) {
					// Full match: language + country, possibly narrowed from earlier language-only match
					return locale;
				}
			}
			//如果当前解析器不支持解析当前的locale对象，且languageMatch为空
			else if (languageMatch == null) {
				// Let's try to find a language-only match as a fallback
				//遍历supportedLocales数组
				for (Locale candidate : supportedLocales) {
					//如果当前的candidate对象的country属性不为空，并且locale对象和candidate对象支持的语言属性相同
					//则将当前的candidate对象设置到languageMatch中，并结束循环
					if (!StringUtils.hasLength(candidate.getCountry()) &&
							candidate.getLanguage().equals(locale.getLanguage())) {
						languageMatch = candidate;
						break;
					}
				}
			}
		}
		//返回匹配的languageMatch对象
		return languageMatch;
	}
	/**因为此类解析器只支持从客户端的请求头中的Accept-Language属性中解析，所以不支持setLocale方法，所以此处直接抛出异常*/
	@Override
	public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale) {
		throw new UnsupportedOperationException(
				"Cannot change HTTP accept header - use a different locale resolution strategy");
	}

}
