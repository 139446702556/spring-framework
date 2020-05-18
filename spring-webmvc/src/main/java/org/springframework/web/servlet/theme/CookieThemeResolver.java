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

package org.springframework.web.servlet.theme;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.ThemeResolver;
import org.springframework.web.util.CookieGenerator;
import org.springframework.web.util.WebUtils;

/**
 * {@link ThemeResolver} implementation that uses a cookie sent back to the user
 * in case of a custom setting, with a fallback to the default theme.
 * This is particularly useful for stateless applications without user sessions.
 *
 * <p>Custom controllers can thus override the user's theme by calling
 * {@code setThemeName}, e.g. responding to a certain theme change request.
 *
 * @author Jean-Pierre Pawlak
 * @author Juergen Hoeller
 * @since 17.06.2003
 * @see #setThemeName
 * 此类也是ThemeResolver接口的简单实现，就是将themeName保存到Cookie中
 */
public class CookieThemeResolver extends CookieGenerator implements ThemeResolver {

	/**
	 * The default theme name used if no alternative is provided.
	 * 如果没有提供其它的选项，则使用此默认的主题名称
	 */
	public static final String ORIGINAL_DEFAULT_THEME_NAME = "theme";

	/**
	 * Name of the request attribute that holds the theme name. Only used
	 * for overriding a cookie value if the theme has been changed in the
	 * course of the current request! Use RequestContext.getTheme() to
	 * retrieve the current theme in controllers or views.
	 * @see org.springframework.web.servlet.support.RequestContext#getTheme
	 * 用于保存主题名称的请求属性的名称
	 */
	public static final String THEME_REQUEST_ATTRIBUTE_NAME = CookieThemeResolver.class.getName() + ".THEME";

	/**
	 * The default name of the cookie that holds the theme name.
	 * 保存主题名称使用的默认的cookie名称
	 */
	public static final String DEFAULT_COOKIE_NAME = CookieThemeResolver.class.getName() + ".THEME";

	/**默认的主题名称*/
	private String defaultThemeName = ORIGINAL_DEFAULT_THEME_NAME;


	public CookieThemeResolver() {
		//将DEFAULT_COOKIE_NAME的值设置为默认的cookie key的名称
		setCookieName(DEFAULT_COOKIE_NAME);
	}


	/**
	 * Set the name of the default theme.
	 * 设置默认的主题名称
	 */
	public void setDefaultThemeName(String defaultThemeName) {
		this.defaultThemeName = defaultThemeName;
	}

	/**
	 * Return the name of the default theme.
	 * 获取默认的主题名称
	 */
	public String getDefaultThemeName() {
		return this.defaultThemeName;
	}


	@Override
	public String resolveThemeName(HttpServletRequest request) {
		// Check request for preparsed or preset theme.
		//从当前给定的请求中获取key为THEME_REQUEST_ATTRIBUTE_NAME的属性值，作为themeName值
		String themeName = (String) request.getAttribute(THEME_REQUEST_ATTRIBUTE_NAME);
		//如果请求的属性中存在，则直接返回
		if (themeName != null) {
			return themeName;
		}

		// Retrieve cookie value from request.
		//获取cookieName
		String cookieName = getCookieName();
		//如果cookieName不为空
		if (cookieName != null) {
			//从当前请求中获取指定cookieName对应cookie对象
			Cookie cookie = WebUtils.getCookie(request, cookieName);
			//如果获取到了
			if (cookie != null) {
				//获取cookie对象的值
				String value = cookie.getValue();
				//如果此cookie的值不为空，则将其设置到themeName中
				if (StringUtils.hasText(value)) {
					themeName = value;
				}
			}
		}

		// Fall back to default theme.
		//如果从当前请求的属性和cookie中皆为获取到themeName的值，则使用默认的defaultThemeName的值
		if (themeName == null) {
			themeName = getDefaultThemeName();
		}
		//将themeName设置到当前请求的属性对象中，key为THEME_REQUEST_ATTRIBUTE_NAME
		request.setAttribute(THEME_REQUEST_ATTRIBUTE_NAME, themeName);
		return themeName;
	}

	@Override
	public void setThemeName(
			HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable String themeName) {
		//断言给定的响应对象不为空
		Assert.notNull(response, "HttpServletResponse is required for CookieThemeResolver");
		//如果给定的themeName不为空
		if (StringUtils.hasText(themeName)) {
			// Set request attribute and add cookie.
			//则将themeName设置到当前给定请求的属性中
			request.setAttribute(THEME_REQUEST_ATTRIBUTE_NAME, themeName);
			//将themeName添加到给定response的cookie中
			addCookie(response, themeName);
		}
		//如果给定的themeName值为空
		else {
			// Set request attribute to fallback theme and remove cookie.
			//则将默认的themeName设置到给定请求的属性中
			request.setAttribute(THEME_REQUEST_ATTRIBUTE_NAME, getDefaultThemeName());
			//并移除当前响应中的所有cookie缓存
			removeCookie(response);
		}
	}

}
