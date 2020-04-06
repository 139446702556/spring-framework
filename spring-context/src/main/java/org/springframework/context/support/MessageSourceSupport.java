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

package org.springframework.context.support;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;

/**
 * Base class for message source implementations, providing support infrastructure
 * such as {@link java.text.MessageFormat} handling but not implementing concrete
 * methods defined in the {@link org.springframework.context.MessageSource}.
 *
 * <p>{@link AbstractMessageSource} derives from this class, providing concrete
 * {@code getMessage} implementations that delegate to a central template
 * method for message code resolution.
 *
 * @author Juergen Hoeller
 * @since 2.5.5
 */
public abstract class MessageSourceSupport {

	private static final MessageFormat INVALID_MESSAGE_FORMAT = new MessageFormat("");

	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	private boolean alwaysUseMessageFormat = false;

	/**
	 * Cache to hold already generated MessageFormats per message.
	 * Used for passed-in default messages. MessageFormats for resolved
	 * codes are cached on a specific basis in subclasses.
	 * 缓存保存已生成的每个信息的消息格式
	 * key：某个需要格式化的信息
	 * value：是一个map格式，其中key：用于格式化的区域设置 value:信息格式
	 */
	private final Map<String, Map<Locale, MessageFormat>> messageFormatsPerMessage = new HashMap<>();


	/**
	 * Set whether to always apply the {@code MessageFormat} rules,
	 * parsing even messages without arguments.
	 * <p>Default is "false": Messages without arguments are by default
	 * returned as-is, without parsing them through MessageFormat.
	 * Set this to "true" to enforce MessageFormat for all messages,
	 * expecting all message texts to be written with MessageFormat escaping.
	 * <p>For example, MessageFormat expects a single quote to be escaped
	 * as "''". If your message texts are all written with such escaping,
	 * even when not defining argument placeholders, you need to set this
	 * flag to "true". Else, only message texts with actual arguments
	 * are supposed to be written with MessageFormat escaping.
	 * @see java.text.MessageFormat
	 */
	public void setAlwaysUseMessageFormat(boolean alwaysUseMessageFormat) {
		this.alwaysUseMessageFormat = alwaysUseMessageFormat;
	}

	/**
	 * Return whether to always apply the MessageFormat rules, parsing even
	 * messages without arguments.
	 * 返回是否总是应用MessageFormat规则，甚至在没有参数的情况下解析消息
	 */
	protected boolean isAlwaysUseMessageFormat() {
		return this.alwaysUseMessageFormat;
	}


	/**
	 * Render the given default message String. The default message is
	 * passed in as specified by the caller and can be rendered into
	 * a fully formatted default message shown to the user.
	 * <p>The default implementation passes the String to {@code formatMessage},
	 * resolving any argument placeholders found in them. Subclasses may override
	 * this method to plug in custom processing of default messages.
	 * @param defaultMessage the passed-in default message String
	 * @param args array of arguments that will be filled in for params within
	 * the message, or {@code null} if none.
	 * @param locale the Locale used for formatting
	 * @return the rendered default message (with resolved arguments)
	 * @see #formatMessage(String, Object[], java.util.Locale)
	 */
	protected String renderDefaultMessage(String defaultMessage, @Nullable Object[] args, Locale locale) {
		return formatMessage(defaultMessage, args, locale);
	}

	/**
	 * Format the given message String, using cached MessageFormats.
	 * By default invoked for passed-in default messages, to resolve
	 * any argument placeholders found in them.
	 * @param msg the message to format 要格式化的信息
	 * @param args array of arguments that will be filled in for params within
	 * the message, or {@code null} if none
	 * 将在消息中为参数填充的参数数组，如果没有参数则为null
	 * @param locale the Locale used for formatting 用于格式化的区域设置
	 * @return the formatted message (with resolved arguments) 格式话之后的信息
	 */
	protected String formatMessage(String msg, @Nullable Object[] args, Locale locale) {
		//如果不只使用MessageFormat规则解析，并且给定参数为空，则直接返回给定msg信息
		if (!isAlwaysUseMessageFormat() && ObjectUtils.isEmpty(args)) {
			return msg;
		}
		MessageFormat messageFormat = null;
		//全局锁
		synchronized (this.messageFormatsPerMessage) {
			//从缓存中获取当前信息对应的map
			Map<Locale, MessageFormat> messageFormatsPerLocale = this.messageFormatsPerMessage.get(msg);
			//如果获取的map不为空
			if (messageFormatsPerLocale != null) {
				//则获取当前信息对应的给定区域的信息格式
				messageFormat = messageFormatsPerLocale.get(locale);
			}
			//如果未获取到给定信息对应的map
			else {
				//初始化
				messageFormatsPerLocale = new HashMap<>();
				//将给定信息和map添加到缓存中
				this.messageFormatsPerMessage.put(msg, messageFormatsPerLocale);
			}
			//如果未获取到当前信息和给定区域对应的信息格式
			if (messageFormat == null) {
				try {
					//根据给定信息和给定区域对象创建一个新的MessageFormat对象
					messageFormat = createMessageFormat(msg, locale);
				}
				catch (IllegalArgumentException ex) {
					// Invalid message format - probably not intended for formatting,
					// rather using a message structure with no arguments involved...
					if (isAlwaysUseMessageFormat()) {
						throw ex;
					}
					// Silently proceed with raw message if format not enforced...
					//如果创建MessageFormat时抛出异常，则使用默认的信息格式
					messageFormat = INVALID_MESSAGE_FORMAT;
				}
				//将区域对象和信息格式的关系添加到缓存中
				messageFormatsPerLocale.put(locale, messageFormat);
			}
		}
		//如果messageFormat等于默认的信息格式，则说明创建时发生了异常，则直接返回原给定信息msg
		if (messageFormat == INVALID_MESSAGE_FORMAT) {
			return msg;
		}
		//如果存在messageFormat对象，则同步锁锁住该对象
		synchronized (messageFormat) {
			//使用messageFormat对给定的msg信息进行格式化，并返回
			return messageFormat.format(resolveArguments(args, locale));
		}
	}

	/**
	 * Create a MessageFormat for the given message and Locale.
	 * @param msg the message to create a MessageFormat for
	 * @param locale the Locale to create a MessageFormat for
	 * @return the MessageFormat instance
	 */
	protected MessageFormat createMessageFormat(String msg, Locale locale) {
		return new MessageFormat(msg, locale);
	}

	/**
	 * Template method for resolving argument objects.
	 * 用于解析参数对象的模板方法
	 * <p>The default implementation simply returns the given argument array as-is.
	 * Can be overridden in subclasses in order to resolve special argument types.
	 * @param args the original argument array
	 * @param locale the Locale to resolve against
	 * @return the resolved argument array
	 */
	protected Object[] resolveArguments(@Nullable Object[] args, Locale locale) {
		return (args != null ? args : new Object[0]);
	}

}
