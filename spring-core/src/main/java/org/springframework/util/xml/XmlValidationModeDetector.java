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

package org.springframework.util.xml;

import java.io.BufferedReader;
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Detects whether an XML stream is using DTD- or XSD-based validation.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 2.0
 */
public class XmlValidationModeDetector {

	/**
	 * Indicates that the validation should be disabled.
	 */
	public static final int VALIDATION_NONE = 0;

	/**
	 * Indicates that the validation mode should be auto-guessed, since we cannot find
	 * a clear indication (probably choked on some special characters, or the like).
	 */
	public static final int VALIDATION_AUTO = 1;

	/**
	 * Indicates that DTD validation should be used (we found a "DOCTYPE" declaration).
	 */
	public static final int VALIDATION_DTD = 2;

	/**
	 * Indicates that XSD validation should be used (found no "DOCTYPE" declaration).
	 */
	public static final int VALIDATION_XSD = 3;


	/**
	 * The token in a XML document that declares the DTD to use for validation
	 * and thus that DTD validation is being used.
	 */
	private static final String DOCTYPE = "DOCTYPE";

	/**
	 * The token that indicates the start of an XML comment.
	 * 表示xml注释开始的标记
	 */
	private static final String START_COMMENT = "<!--";

	/**
	 * The token that indicates the end of an XML comment.
	 * 表示xml注释结束的标记
	 */
	private static final String END_COMMENT = "-->";


	/**
	 * Indicates whether or not the current parse position is inside an XML comment.
	 * 指示当前解析位置是否在XML的注释中
	 */
	private boolean inComment;


	/**
	 * Detect the validation mode for the XML document in the supplied {@link InputStream}.
	 * Note that the supplied {@link InputStream} is closed by this method before returning.
	 * @param inputStream the InputStream to parse
	 * @throws IOException in case of I/O failure
	 * @see #VALIDATION_DTD
	 * @see #VALIDATION_XSD
	 * 通过输入的xml document输入流来检测验证模式，返回结果之前要关闭相应的资源
	 */
	public int detectValidationMode(InputStream inputStream) throws IOException {
		// Peek into the file to look for DOCTYPE.
		//查看文件以查找文件类型
		BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
		try {
			//是否为DTD验证模式，默认为否，及不是DTD验证模式而是XSD验证模式
			boolean isDtdValidated = false;
			String content;
			//循环，逐行读取xml文件数据流中的内容
			while ((content = reader.readLine()) != null) {
				//过滤掉读取内容中的注释内容
				content = consumeCommentTokens(content);
				//如果当前内容在注释中或者当前读取的行内容没有有效内容，跳过以下操作，继续读取
				if (this.inComment || !StringUtils.hasText(content)) {
					continue;
				}
				//当前xml文档是否使用的是DTD验证模式
				if (hasDoctype(content)) {
					isDtdValidated = true;
					break;
				}
				//判断当前资源的验证模式是否为XSD模式
				if (hasOpeningTag(content)) {
					// End of meaningful data...
					break;
				}
			}
			//返回验证模式为DTD或者XSD
			return (isDtdValidated ? VALIDATION_DTD : VALIDATION_XSD);
		}
		catch (CharConversionException ex) {
			// Choked on some character encoding...
			// Leave the decision up to the caller.
			//返回VALIDATION_AUTO模式
			return VALIDATION_AUTO;
		}
		finally {
			//关闭缓冲区读取器
			reader.close();
		}
	}


	/**
	 * Does the content contain the DTD DOCTYPE declaration?
	 * 检测当前字符串中是否有“DOCTYPE”子字符串，有的话此xml则使用的是DTD验证模式
	 */
	private boolean hasDoctype(String content) {
		return content.contains(DOCTYPE);
	}

	/**
	 * Does the supplied content contain an XML opening tag. If the parse state is currently
	 * in an XML comment then this method always returns false. It is expected that all comment
	 * tokens will have consumed for the supplied content before passing the remainder to this method.
	 * 根据当前内容判断此资源的验证模式是否为XSD验证模式（内容中有 < 符号，并且此符号后面有字母）
	 */
	private boolean hasOpeningTag(String content) {
		//如果当前为xml中的注释，则直接返回false
		if (this.inComment) {
			return false;
		}
		int openTagIndex = content.indexOf('<');
		return (openTagIndex > -1    //存在 < 符号
				&& (content.length() > openTagIndex + 1)  //并且< 后面还有内容
				&& Character.isLetter(content.charAt(openTagIndex + 1)));  //并且 < 后面的字符是字母
	}

	/**
	 * Consume all leading and trailing comments in the given String and return
	 * the remaining content, which may be empty since the supplied content might
	 * be all comment data.
	 * 过滤掉给定字符串中的所有前导和尾随注释，并返回其余内容，这些内容可能为空，因为提供的内容可能所有的都是注释数据
	 */
	@Nullable
	private String consumeCommentTokens(String line) {
		int indexOfStartComment = line.indexOf(START_COMMENT);
		//如果此内容非注释则直接返回当前字符串
		if (indexOfStartComment == -1 && !line.contains(END_COMMENT)) {
			return line;
		}

		String result = "";
		String currLine = line;
		if (indexOfStartComment >= 0) {
			result = line.substring(0, indexOfStartComment);
			currLine = line.substring(indexOfStartComment);
		}

		while ((currLine = consume(currLine)) != null) {
			if (!this.inComment && !currLine.trim().startsWith(START_COMMENT)) {
				return result + currLine;
			}
		}
		return null;
	}

	/**
	 * Consume the next comment token, update the "inComment" flag
	 * and return the remaining content.
	 * 检测当前字符串中是否有xml注释的标识符，如果有返回过滤掉标识后的内容，反之返回null
	 */
	@Nullable
	private String consume(String line) {
		//如果inComment为true表示已经在注释其中，因此要检测是否包含结束符；反之未在注释中，检测是否包含开始符
		int index = (this.inComment ? endComment(line) : startComment(line));
		//如果包含注释标识符，则返回过滤掉标识符后边的内容
		return (index == -1 ? null : line.substring(index));
	}

	/**
	 * Try to consume the {@link #START_COMMENT} token.
	 * @see #commentToken(String, String, boolean)
	 * 检测此行数据是否包含xml注释开始标记
	 */
	private int startComment(String line) {
		return commentToken(line, START_COMMENT, true);
	}
    /**
	 * 检测此行数据是否包含xml注释的结束标记
	 */
	private int endComment(String line) {
		return commentToken(line, END_COMMENT, false);
	}

	/**
	 * Try to consume the supplied token against the supplied content and update the
	 * in comment parse state to the supplied value. Returns the index into the content
	 * which is after the token or -1 if the token is not found.
	 * 检测line中是否包含token字符串，如果包含则把inComment标识设置为inCommentIfPresent
	 */
	private int commentToken(String line, String token, boolean inCommentIfPresent) {
		int index = line.indexOf(token);
		if (index > - 1) {
			this.inComment = inCommentIfPresent;
		}
		//如果line中不包含token，则返回-1，否则返回跳过token字符串后的第一个字符的索引位置
		return (index == -1 ? index : index + token.length());
	}

}
