/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.lang.Nullable;

/**
 * {@link PathMatcher} implementation for Ant-style path patterns.
 *
 * <p>Part of this mapping code has been kindly borrowed from <a href="https://ant.apache.org">Apache Ant</a>.
 *
 * <p>The mapping matches URLs using the following rules:<br>
 * <ul>
 * <li>{@code ?} matches one character</li>
 * <li>{@code *} matches zero or more characters</li>
 * <li>{@code **} matches zero or more <em>directories</em> in a path</li>
 * <li>{@code {spring:[a-z]+}} matches the regexp {@code [a-z]+} as a path variable named "spring"</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <ul>
 * <li>{@code com/t?st.jsp} &mdash; matches {@code com/test.jsp} but also
 * {@code com/tast.jsp} or {@code com/txst.jsp}</li>
 * <li>{@code com/*.jsp} &mdash; matches all {@code .jsp} files in the
 * {@code com} directory</li>
 * <li><code>com/&#42;&#42;/test.jsp</code> &mdash; matches all {@code test.jsp}
 * files underneath the {@code com} path</li>
 * <li><code>org/springframework/&#42;&#42;/*.jsp</code> &mdash; matches all
 * {@code .jsp} files underneath the {@code org/springframework} path</li>
 * <li><code>org/&#42;&#42;/servlet/bla.jsp</code> &mdash; matches
 * {@code org/springframework/servlet/bla.jsp} but also
 * {@code org/springframework/testing/servlet/bla.jsp} and {@code org/servlet/bla.jsp}</li>
 * <li>{@code com/{filename:\\w+}.jsp} will match {@code com/test.jsp} and assign the value {@code test}
 * to the {@code filename} variable</li>
 * </ul>
 *
 * <p><strong>Note:</strong> a pattern and a path must both be absolute or must
 * both be relative in order for the two to match. Therefore it is recommended
 * that users of this implementation to sanitize patterns in order to prefix
 * them with "/" as it makes sense in the context in which they're used.
 *
 * @author Alef Arendsen
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Sam Brannen
 * @since 16.07.2003
 */
public class AntPathMatcher implements PathMatcher {

	/** Default path separator: "/". */
	public static final String DEFAULT_PATH_SEPARATOR = "/";

	private static final int CACHE_TURNOFF_THRESHOLD = 65536;

	private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{[^/]+?\\}");

	private static final char[] WILDCARD_CHARS = { '*', '?', '{' };


	private String pathSeparator;

	private PathSeparatorPatternCache pathSeparatorPatternCache;

	private boolean caseSensitive = true;

	private boolean trimTokens = false;

	@Nullable
	private volatile Boolean cachePatterns;

	private final Map<String, String[]> tokenizedPatternCache = new ConcurrentHashMap<>(256);

	final Map<String, AntPathStringMatcher> stringMatcherCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new instance with the {@link #DEFAULT_PATH_SEPARATOR}.
	 */
	public AntPathMatcher() {
		this.pathSeparator = DEFAULT_PATH_SEPARATOR;
		this.pathSeparatorPatternCache = new PathSeparatorPatternCache(DEFAULT_PATH_SEPARATOR);
	}

	/**
	 * A convenient, alternative constructor to use with a custom path separator.
	 * @param pathSeparator the path separator to use, must not be {@code null}.
	 * @since 4.1
	 */
	public AntPathMatcher(String pathSeparator) {
		Assert.notNull(pathSeparator, "'pathSeparator' is required");
		this.pathSeparator = pathSeparator;
		this.pathSeparatorPatternCache = new PathSeparatorPatternCache(pathSeparator);
	}


	/**
	 * Set the path separator to use for pattern parsing.
	 * <p>Default is "/", as in Ant.
	 */
	public void setPathSeparator(@Nullable String pathSeparator) {
		this.pathSeparator = (pathSeparator != null ? pathSeparator : DEFAULT_PATH_SEPARATOR);
		this.pathSeparatorPatternCache = new PathSeparatorPatternCache(this.pathSeparator);
	}

	/**
	 * Specify whether to perform pattern matching in a case-sensitive fashion.
	 * <p>Default is {@code true}. Switch this to {@code false} for case-insensitive matching.
	 * @since 4.2
	 */
	public void setCaseSensitive(boolean caseSensitive) {
		this.caseSensitive = caseSensitive;
	}

	/**
	 * Specify whether to trim tokenized paths and patterns.
	 * <p>Default is {@code false}.
	 */
	public void setTrimTokens(boolean trimTokens) {
		this.trimTokens = trimTokens;
	}

	/**
	 * Specify whether to cache parsed pattern metadata for patterns passed
	 * into this matcher's {@link #match} method. A value of {@code true}
	 * activates an unlimited pattern cache; a value of {@code false} turns
	 * the pattern cache off completely.
	 * <p>Default is for the cache to be on, but with the variant to automatically
	 * turn it off when encountering too many patterns to cache at runtime
	 * (the threshold is 65536), assuming that arbitrary permutations of patterns
	 * are coming in, with little chance for encountering a recurring pattern.
	 * @since 4.0.1
	 * @see #getStringMatcher(String)
	 */
	public void setCachePatterns(boolean cachePatterns) {
		this.cachePatterns = cachePatterns;
	}

	private void deactivatePatternCache() {
		this.cachePatterns = false;
		this.tokenizedPatternCache.clear();
		this.stringMatcherCache.clear();
	}


	@Override
	public boolean isPattern(@Nullable String path) {
		if (path == null) {
			return false;
		}
		boolean uriVar = false;
		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			// 当path里面含有*或者?符号时，返回true
			if (c == '*' || c == '?') {
				return true;
			}
			// 或者path里面含有一对{}符号时，返回true
			if (c == '{') {
				uriVar = true;
				continue;
			}
			if (c == '}' && uriVar) {
				return true;
			}
		}
		// 否则返回false
		return false;
	}

	@Override
	public boolean match(String pattern, String path) {
		return doMatch(pattern, path, true, null);
	}

	@Override
	public boolean matchStart(String pattern, String path) {
		return doMatch(pattern, path, false, null);
	}

	/**
	 * Actually match the given {@code path} against the given {@code pattern}.
	 * @param pattern the pattern to match against
	 * @param path the path to test
	 * @param fullMatch whether a full pattern match is required (else a pattern match
	 * as far as the given base path goes is sufficient)
	 * @return {@code true} if the supplied {@code path} matched, {@code false} if it didn't
	 */
	protected boolean doMatch(String pattern, @Nullable String path, boolean fullMatch,
			@Nullable Map<String, String> uriTemplateVariables) {

		// 如果path为null，或者path和pattern是否以/开头的情况不一致的情况，返回false
		if (path == null || path.startsWith(this.pathSeparator) != pattern.startsWith(this.pathSeparator)) {
			return false;
		}

		// 将pattern按/分隔成数组
		String[] pattDirs = tokenizePattern(pattern);
		// 如果fullMatch是true并且是大小写敏感的并且没有潜在匹配的可能，返回false
		if (fullMatch && this.caseSensitive && !isPotentialMatch(path, pattDirs)) {
			return false;
		}

		// 将path按/分隔成数组
		String[] pathDirs = tokenizePath(path);
		int pattIdxStart = 0;
		int pattIdxEnd = pattDirs.length - 1;
		int pathIdxStart = 0;
		int pathIdxEnd = pathDirs.length - 1;

		// Match all elements up to the first **
		while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
			String pattDir = pattDirs[pattIdxStart];
			// 当pattern的目录内容是**时，跳出循环
			if ("**".equals(pattDir)) {
				break;
			}
			// 调用matchStrings方法来匹配pattern和path的目录内容，如果匹配失败，返回false
			if (!matchStrings(pattDir, pathDirs[pathIdxStart], uriTemplateVariables)) {
				return false;
			}
			// 否则将数组下表都加1，匹配下一层目录的内容
			pattIdxStart++;
			pathIdxStart++;
		}

		// 如果path已经耗尽了
		if (pathIdxStart > pathIdxEnd) {
			// Path is exhausted, only match if rest of pattern is * or **'s
			// 如果pattern也已经耗尽了
			if (pattIdxStart > pattIdxEnd) {
				// 那么比较path和pattern以/结尾的情况是否一样，如果一样，返回true，否则返回false
				return (pattern.endsWith(this.pathSeparator) == path.endsWith(this.pathSeparator));
			}
			// 如果不需要完整匹配，直接返回true
			// 比如pattern是/path1/path2/path3，path是/path1/path2
			if (!fullMatch) {
				return true;
			}
			// 如果此时pattern正好还剩最后一个目录，并且最后一个目录里面的内容是*，并且path以/结尾，那么返回true
			// 比如pattern是/path1/path2/*,path是/path1/path2/
			if (pattIdxStart == pattIdxEnd && pattDirs[pattIdxStart].equals("*") && path.endsWith(this.pathSeparator)) {
				return true;
			}
			// 如果pattern剩下的目录内容全部是**的话，返回true，否则返回false
			// 比如pattern是/path1/path2/**,path是/path1/path2
			for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
				if (!pattDirs[i].equals("**")) {
					return false;
				}
			}
			return true;
		}
		// 如果pattern先耗尽的话，直接返回false
		else if (pattIdxStart > pattIdxEnd) {
			// String not exhausted, but pattern is. Failure.
			return false;
		}
		// 如果不是完整匹配且pattern下一个目录的内容是**，直接返回true。
		// 比如pattern是/path1/path2/**/path3, path是/path1/path2/path4
		else if (!fullMatch && "**".equals(pattDirs[pattIdxStart])) {
			// Path start definitely matches due to "**" part in pattern.
			return true;
		}

		// up to last '**'
		// 从后往前遍历，找到pattern最后一个**
		while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
			String pattDir = pattDirs[pattIdxEnd];
			// 如果pattern的目录内容是**，跳出循环
			if (pattDir.equals("**")) {
				break;
			}
			// 否则将pattern和path的目录内容进行比较，如果不匹配，返回false
			if (!matchStrings(pattDir, pathDirs[pathIdxEnd], uriTemplateVariables)) {
				return false;
			}
			pattIdxEnd--;
			pathIdxEnd--;
		}
		// 如果path耗尽了
		// 比如pattern是/path1/path2/**/path3，path是/path1/path2/path3
		if (pathIdxStart > pathIdxEnd) {
			// String is exhausted
			// 那么需要判断剩下的pattern是否都是**的内容，如果是的话，返回true，否则返回false
			for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
				if (!pattDirs[i].equals("**")) {
					return false;
				}
			}
			return true;
		}

		// 当pattern的开始指针不等于结束指针，并且path也还没有耗尽时
		// 比如pattern是/1/**/2/3/**/4/**/5, path是/1/x/2/3/y/4/z/a/5
		while (pattIdxStart != pattIdxEnd && pathIdxStart <= pathIdxEnd) {
			int patIdxTmp = -1;
			// 从开始指针+1开始循环
			for (int i = pattIdxStart + 1; i <= pattIdxEnd; i++) {
				// 如果遇到内容等于**,就将临时指针指向这个位置，并跳出循环
				if (pattDirs[i].equals("**")) {
					patIdxTmp = i;
					break;
				}
			}
			// 如果临时指针就指向开始指针+1，说明有两个连续的**，跳过一个
			if (patIdxTmp == pattIdxStart + 1) {
				// '**/**' situation, so skip one
				pattIdxStart++;
				continue;
			}
			// Find the pattern between padIdxStart & padIdxTmp in str between
			// strIdxStart & strIdxEnd
			// 获取到需要比较的pattern的目录长度
			int patLength = (patIdxTmp - pattIdxStart - 1);
			// 需要比较的path的目录长度
			int strLength = (pathIdxEnd - pathIdxStart + 1);
			int foundIdx = -1;

			strLoop:
			// 外层循环是最多需要比较多少次，如果需要比较的path长度为5，需要比较的pattern长度为2的话，那么最多需要比较4次
			// 比如需要比较的pattern是/3/4 需要比较的path是/1/2/3/4/5，那么最多需要比较四次
			for (int i = 0; i <= strLength - patLength; i++) {
				// 内层循环是每次比较 pattern需要比较的目录个数，如果需要比较的pattern是/3/4，那么要比较的目录个数就是2
				for (int j = 0; j < patLength; j++) {
					// 取出比较的pattern，示例中取出的为3
					String subPat = pattDirs[pattIdxStart + j + 1];
					// 取出比较的path，示例中取出的为1
					String subStr = pathDirs[pathIdxStart + i + j];
					// 当path和pattern不相等的时候，将需要比较的path向前进一位，即从2开始
					if (!matchStrings(subPat, subStr, uriTemplateVariables)) {
						continue strLoop;
					}
				}
				// 直到path前进到从3开始，内层循环能够走完一次，即/3/4和path的目录匹配成功了，记录此时的下标，跳出循环
				foundIdx = pathIdxStart + i;
				break;
			}

			// 如果不能匹配存成功，即没有找到对应的指针，如pattern为/3/4，path为/1/2/3/5，则返回false
			if (foundIdx == -1) {
				return false;
			}

			// 将pattern的开始指针指向临时指针，即之前找到的为**的目录
			pattIdxStart = patIdxTmp;
			// 将path的开始指针指向找到的成功匹配的开始下标加上匹配了pattern的长度，换句话说也就是指向还未匹配的位置
			pathIdxStart = foundIdx + patLength;
			// 然后继续循环，查看pattern中还有没有需要匹配的目录
		}

		// 判断pattern剩下的目录是否都是**，如果是，返回true，否则返回false
		for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
			if (!pattDirs[i].equals("**")) {
				return false;
			}
		}

		return true;
	}

	private boolean isPotentialMatch(String path, String[] pattDirs) {
		// 如果trimTokens为false的话
		if (!this.trimTokens) {
			int pos = 0;
			// 遍历pattern的目录
			for (String pattDir : pattDirs) {
				int skipped = skipSeparator(path, pos, this.pathSeparator);
				pos += skipped;
				skipped = skipSegment(path, pos, pattDir);
				if (skipped < pattDir.length()) {
					return (skipped > 0 || (pattDir.length() > 0 && isWildcardChar(pattDir.charAt(0))));
				}
				pos += skipped;
			}
		}
		return true;
	}

	private int skipSegment(String path, int pos, String prefix) {
		int skipped = 0;
		for (int i = 0; i < prefix.length(); i++) {
			char c = prefix.charAt(i);
			if (isWildcardChar(c)) {
				return skipped;
			}
			int currPos = pos + skipped;
			if (currPos >= path.length()) {
				return 0;
			}
			if (c == path.charAt(currPos)) {
				skipped++;
			}
		}
		return skipped;
	}

	private int skipSeparator(String path, int pos, String separator) {
		int skipped = 0;
		while (path.startsWith(separator, pos + skipped)) {
			skipped += separator.length();
		}
		return skipped;
	}

	private boolean isWildcardChar(char c) {
		for (char candidate : WILDCARD_CHARS) {
			if (c == candidate) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tokenize the given path pattern into parts, based on this matcher's settings.
	 * <p>Performs caching based on {@link #setCachePatterns}, delegating to
	 * {@link #tokenizePath(String)} for the actual tokenization algorithm.
	 * @param pattern the pattern to tokenize
	 * @return the tokenized pattern parts
	 */
	protected String[] tokenizePattern(String pattern) {
		String[] tokenized = null;
		Boolean cachePatterns = this.cachePatterns;
		// 如果cachePatterns参数为null或者是true，尝试从缓存中获取tokenized
		if (cachePatterns == null || cachePatterns.booleanValue()) {
			tokenized = this.tokenizedPatternCache.get(pattern);
		}
		// 如果tokenized仍然为null
		if (tokenized == null) {
			// 调用tokenizePath方法
			tokenized = tokenizePath(pattern);
			// 如果cachePatterns参数为null，并且缓存的数量大于65535了，那么需要将缓存失效
			if (cachePatterns == null && this.tokenizedPatternCache.size() >= CACHE_TURNOFF_THRESHOLD) {
				// Try to adapt to the runtime situation that we're encountering:
				// There are obviously too many different patterns coming in here...
				// So let's turn off the cache since the patterns are unlikely to be reoccurring.
				deactivatePatternCache();
				return tokenized;
			}
			// 如果cachePatterns为null或者为true，将得到的结果放入缓存中
			if (cachePatterns == null || cachePatterns.booleanValue()) {
				this.tokenizedPatternCache.put(pattern, tokenized);
			}
		}
		return tokenized;
	}

	/**
	 * Tokenize the given path into parts, based on this matcher's settings.
	 * @param path the path to tokenize
	 * @return the tokenized path parts
	 */
	protected String[] tokenizePath(String path) {
		// 根据/符号将path分隔成数组，并且忽略到空的元素，且不对每个分隔出来的元素调用trim方法
		return StringUtils.tokenizeToStringArray(path, this.pathSeparator, this.trimTokens, true);
	}

	/**
	 * Test whether or not a string matches against a pattern.
	 * @param pattern the pattern to match against (never {@code null})
	 * @param str the String which must be matched against the pattern (never {@code null})
	 * @return {@code true} if the string matches against the pattern, or {@code false} otherwise
	 */
	private boolean matchStrings(String pattern, String str,
			@Nullable Map<String, String> uriTemplateVariables) {

		return getStringMatcher(pattern).matchStrings(str, uriTemplateVariables);
	}

	/**
	 * Build or retrieve an {@link AntPathStringMatcher} for the given pattern.
	 * <p>The default implementation checks this AntPathMatcher's internal cache
	 * (see {@link #setCachePatterns}), creating a new AntPathStringMatcher instance
	 * if no cached copy is found.
	 * <p>When encountering too many patterns to cache at runtime (the threshold is 65536),
	 * it turns the default cache off, assuming that arbitrary permutations of patterns
	 * are coming in, with little chance for encountering a recurring pattern.
	 * <p>This method may be overridden to implement a custom cache strategy.
	 * @param pattern the pattern to match against (never {@code null})
	 * @return a corresponding AntPathStringMatcher (never {@code null})
	 * @see #setCachePatterns
	 */
	protected AntPathStringMatcher getStringMatcher(String pattern) {
		AntPathStringMatcher matcher = null;
		Boolean cachePatterns = this.cachePatterns;
		// 先尝试从缓存中取stringMatcher
		if (cachePatterns == null || cachePatterns.booleanValue()) {
			matcher = this.stringMatcherCache.get(pattern);
		}
		if (matcher == null) {
			// 如果没有取到，则根据pattern和caseSensitive自己new一个
			matcher = new AntPathStringMatcher(pattern, this.caseSensitive);
			// 判断缓存是否已经超出阈值，如果超出需要禁用缓存
			if (cachePatterns == null && this.stringMatcherCache.size() >= CACHE_TURNOFF_THRESHOLD) {
				// Try to adapt to the runtime situation that we're encountering:
				// There are obviously too many different patterns coming in here...
				// So let's turn off the cache since the patterns are unlikely to be reoccurring.
				deactivatePatternCache();
				return matcher;
			}
			// 将stringMatcher放入缓存中
			if (cachePatterns == null || cachePatterns.booleanValue()) {
				this.stringMatcherCache.put(pattern, matcher);
			}
		}
		return matcher;
	}

	/**
	 * Given a pattern and a full path, determine the pattern-mapped part. <p>For example: <ul>
	 * <li>'{@code /docs/cvs/commit.html}' and '{@code /docs/cvs/commit.html} -> ''</li>
	 * <li>'{@code /docs/*}' and '{@code /docs/cvs/commit} -> '{@code cvs/commit}'</li>
	 * <li>'{@code /docs/cvs/*.html}' and '{@code /docs/cvs/commit.html} -> '{@code commit.html}'</li>
	 * <li>'{@code /docs/**}' and '{@code /docs/cvs/commit} -> '{@code cvs/commit}'</li>
	 * <li>'{@code /docs/**\/*.html}' and '{@code /docs/cvs/commit.html} -> '{@code cvs/commit.html}'</li>
	 * <li>'{@code /*.html}' and '{@code /docs/cvs/commit.html} -> '{@code docs/cvs/commit.html}'</li>
	 * <li>'{@code *.html}' and '{@code /docs/cvs/commit.html} -> '{@code /docs/cvs/commit.html}'</li>
	 * <li>'{@code *}' and '{@code /docs/cvs/commit.html} -> '{@code /docs/cvs/commit.html}'</li> </ul>
	 * <p>Assumes that {@link #match} returns {@code true} for '{@code pattern}' and '{@code path}', but
	 * does <strong>not</strong> enforce this.
	 */
	@Override
	public String extractPathWithinPattern(String pattern, String path) {
		String[] patternParts = StringUtils.tokenizeToStringArray(pattern, this.pathSeparator, this.trimTokens, true);
		String[] pathParts = StringUtils.tokenizeToStringArray(path, this.pathSeparator, this.trimTokens, true);
		StringBuilder builder = new StringBuilder();
		boolean pathStarted = false;

		for (int segment = 0; segment < patternParts.length; segment++) {
			String patternPart = patternParts[segment];
			if (patternPart.indexOf('*') > -1 || patternPart.indexOf('?') > -1) {
				for (; segment < pathParts.length; segment++) {
					if (pathStarted || (segment == 0 && !pattern.startsWith(this.pathSeparator))) {
						builder.append(this.pathSeparator);
					}
					builder.append(pathParts[segment]);
					pathStarted = true;
				}
			}
		}

		return builder.toString();
	}

	@Override
	public Map<String, String> extractUriTemplateVariables(String pattern, String path) {
		// 创建一个map用于保存uriTemplateVariables
		Map<String, String> variables = new LinkedHashMap<>();
		// 调用doMatch方法进行匹配，因为有之前的缓存存在，AntPathStringMatcher能够快速的获得，因此速度会快很多
		boolean result = doMatch(pattern, path, true, variables);
		// 如果不匹配，报错
		if (!result) {
			throw new IllegalStateException("Pattern \"" + pattern + "\" is not a match for \"" + path + "\"");
		}
		// 返回map，里面key是pattern中路径变量参数，value是path中具体路径变量的值
		return variables;
	}

	/**
	 * Combine two patterns into a new pattern.
	 * <p>This implementation simply concatenates the two patterns, unless
	 * the first pattern contains a file extension match (e.g., {@code *.html}).
	 * In that case, the second pattern will be merged into the first. Otherwise,
	 * an {@code IllegalArgumentException} will be thrown.
	 * <h3>Examples</h3>
	 * <table border="1">
	 * <tr><th>Pattern 1</th><th>Pattern 2</th><th>Result</th></tr>
	 * <tr><td>{@code null}</td><td>{@code null}</td><td>&nbsp;</td></tr>
	 * <tr><td>/hotels</td><td>{@code null}</td><td>/hotels</td></tr>
	 * <tr><td>{@code null}</td><td>/hotels</td><td>/hotels</td></tr>
	 * <tr><td>/hotels</td><td>/bookings</td><td>/hotels/bookings</td></tr>
	 * <tr><td>/hotels</td><td>bookings</td><td>/hotels/bookings</td></tr>
	 * <tr><td>/hotels/*</td><td>/bookings</td><td>/hotels/bookings</td></tr>
	 * <tr><td>/hotels/&#42;&#42;</td><td>/bookings</td><td>/hotels/&#42;&#42;/bookings</td></tr>
	 * <tr><td>/hotels</td><td>{hotel}</td><td>/hotels/{hotel}</td></tr>
	 * <tr><td>/hotels/*</td><td>{hotel}</td><td>/hotels/{hotel}</td></tr>
	 * <tr><td>/hotels/&#42;&#42;</td><td>{hotel}</td><td>/hotels/&#42;&#42;/{hotel}</td></tr>
	 * <tr><td>/*.html</td><td>/hotels.html</td><td>/hotels.html</td></tr>
	 * <tr><td>/*.html</td><td>/hotels</td><td>/hotels.html</td></tr>
	 * <tr><td>/*.html</td><td>/*.txt</td><td>{@code IllegalArgumentException}</td></tr>
	 * </table>
	 * @param pattern1 the first pattern
	 * @param pattern2 the second pattern
	 * @return the combination of the two patterns
	 * @throws IllegalArgumentException if the two patterns cannot be combined
	 */
	@Override
	public String combine(String pattern1, String pattern2) {
		if (!StringUtils.hasText(pattern1) && !StringUtils.hasText(pattern2)) {
			return "";
		}
		if (!StringUtils.hasText(pattern1)) {
			return pattern2;
		}
		if (!StringUtils.hasText(pattern2)) {
			return pattern1;
		}

		boolean pattern1ContainsUriVar = (pattern1.indexOf('{') != -1);
		if (!pattern1.equals(pattern2) && !pattern1ContainsUriVar && match(pattern1, pattern2)) {
			// /* + /hotel -> /hotel ; "/*.*" + "/*.html" -> /*.html
			// However /user + /user -> /usr/user ; /{foo} + /bar -> /{foo}/bar
			return pattern2;
		}

		// /hotels/* + /booking -> /hotels/booking
		// /hotels/* + booking -> /hotels/booking
		if (pattern1.endsWith(this.pathSeparatorPatternCache.getEndsOnWildCard())) {
			return concat(pattern1.substring(0, pattern1.length() - 2), pattern2);
		}

		// /hotels/** + /booking -> /hotels/**/booking
		// /hotels/** + booking -> /hotels/**/booking
		if (pattern1.endsWith(this.pathSeparatorPatternCache.getEndsOnDoubleWildCard())) {
			return concat(pattern1, pattern2);
		}

		int starDotPos1 = pattern1.indexOf("*.");
		if (pattern1ContainsUriVar || starDotPos1 == -1 || this.pathSeparator.equals(".")) {
			// simply concatenate the two patterns
			return concat(pattern1, pattern2);
		}

		String ext1 = pattern1.substring(starDotPos1 + 1);
		int dotPos2 = pattern2.indexOf('.');
		String file2 = (dotPos2 == -1 ? pattern2 : pattern2.substring(0, dotPos2));
		String ext2 = (dotPos2 == -1 ? "" : pattern2.substring(dotPos2));
		boolean ext1All = (ext1.equals(".*") || ext1.isEmpty());
		boolean ext2All = (ext2.equals(".*") || ext2.isEmpty());
		if (!ext1All && !ext2All) {
			throw new IllegalArgumentException("Cannot combine patterns: " + pattern1 + " vs " + pattern2);
		}
		String ext = (ext1All ? ext2 : ext1);
		return file2 + ext;
	}

	private String concat(String path1, String path2) {
		boolean path1EndsWithSeparator = path1.endsWith(this.pathSeparator);
		boolean path2StartsWithSeparator = path2.startsWith(this.pathSeparator);

		if (path1EndsWithSeparator && path2StartsWithSeparator) {
			return path1 + path2.substring(1);
		}
		else if (path1EndsWithSeparator || path2StartsWithSeparator) {
			return path1 + path2;
		}
		else {
			return path1 + this.pathSeparator + path2;
		}
	}

	/**
	 * Given a full path, returns a {@link Comparator} suitable for sorting patterns in order of
	 * explicitness.
	 * <p>This {@code Comparator} will {@linkplain java.util.List#sort(Comparator) sort}
	 * a list so that more specific patterns (without URI templates or wild cards) come before
	 * generic patterns. So given a list with the following patterns, the returned comparator
	 * will sort this list so that the order will be as indicated.
	 * <ol>
	 * <li>{@code /hotels/new}</li>
	 * <li>{@code /hotels/{hotel}}</li>
	 * <li>{@code /hotels/*}</li>
	 * </ol>
	 * <p>The full path given as parameter is used to test for exact matches. So when the given path
	 * is {@code /hotels/2}, the pattern {@code /hotels/2} will be sorted before {@code /hotels/1}.
	 * @param path the full path to use for comparison
	 * @return a comparator capable of sorting patterns in order of explicitness
	 */
	@Override
	public Comparator<String> getPatternComparator(String path) {
		return new AntPatternComparator(path);
	}


	/**
	 * Tests whether or not a string matches against a pattern via a {@link Pattern}.
	 * <p>The pattern may contain special characters: '*' means zero or more characters; '?' means one and
	 * only one character; '{' and '}' indicate a URI template pattern. For example <tt>/users/{user}</tt>.
	 */
	protected static class AntPathStringMatcher {

		private static final Pattern GLOB_PATTERN = Pattern.compile("\\?|\\*|\\{((?:\\{[^/]+?\\}|[^/{}]|\\\\[{}])+?)\\}");

		private static final String DEFAULT_VARIABLE_PATTERN = "(.*)";

		private final Pattern pattern;

		private final List<String> variableNames = new LinkedList<>();

		public AntPathStringMatcher(String pattern) {
			this(pattern, true);
		}

		public AntPathStringMatcher(String pattern, boolean caseSensitive) {
			StringBuilder patternBuilder = new StringBuilder();
			// 根据pattern生成matcher
			Matcher matcher = GLOB_PATTERN.matcher(pattern);
			int end = 0;
			// 循环调用matcher的find方法，找到pattern中满足正则表达式的部分
			while (matcher.find()) {
				// 将pattern不满足正则的前置部分\Q\E包裹添加到StringBuilder中
				patternBuilder.append(quote(pattern, end, matcher.start()));
				// 返回正则匹配的部分
				String match = matcher.group();
				// 如果匹配的是?，那么转换为正则表达式就是. 添加进sb中
				if ("?".equals(match)) {
					patternBuilder.append('.');
				}
				// 如果匹配的是*，转换为正则表达式是.*
				else if ("*".equals(match)) {
					patternBuilder.append(".*");
				}
				// 如果匹配到的是{xxx}格式
				else if (match.startsWith("{") && match.endsWith("}")) {
					// 判断内容中是否含有冒号
					int colonIdx = match.indexOf(':');
					// 如果不含冒号的话
					if (colonIdx == -1) {
						// 向正则中添加默认的变量正则(.*)
						patternBuilder.append(DEFAULT_VARIABLE_PATTERN);
						// 并且通过group(1)获取到变量名并保存起来
						this.variableNames.add(matcher.group(1));
					}
					// 如果含有冒号的话，说明自定义了正则表达式的格式
					// 例如{xxx:\\d{10}}这种格式
					else {
						// 获取到自定义的正则表达式
						String variablePattern = match.substring(colonIdx + 1, match.length() - 1);
						// 将其添加进sb中
						patternBuilder.append('(');
						patternBuilder.append(variablePattern);
						patternBuilder.append(')');
						// 然后将变量名截取出来，保存进list中
						String variableName = match.substring(1, colonIdx);
						this.variableNames.add(variableName);
					}
				}
				// 将end赋值为匹配的end，进行循环匹配，继续解析pattern
				end = matcher.end();
			}
			// 最后将剩余的没有匹配的内容用\Q\E包裹添加进sb中
			patternBuilder.append(quote(pattern, end, pattern.length()));
			// 根据是否大小写敏感创建出对应的正则的pattern
			this.pattern = (caseSensitive ? Pattern.compile(patternBuilder.toString()) :
					Pattern.compile(patternBuilder.toString(), Pattern.CASE_INSENSITIVE));
		}

		private String quote(String s, int start, int end) {
			if (start == end) {
				return "";
			}
			return Pattern.quote(s.substring(start, end));
		}

		/**
		 * Main entry point.
		 * @return {@code true} if the string matches against the pattern, or {@code false} otherwise.
		 */
		public boolean matchStrings(String str, @Nullable Map<String, String> uriTemplateVariables) {
			// 根据自身的pattern和输入的字符串构建一个matcher
			Matcher matcher = this.pattern.matcher(str);
			// 如果matches成功，返回true
			if (matcher.matches()) {
				// 如果用于存储路径变量的map不为空的话，将路径变量解析并存入
				if (uriTemplateVariables != null) {
					// SPR-8455
					// 首先判断路径变量的数量和match的group数量是否一致，因此在构造pattern时，每有一个{}类型的路径变量就会有一个()的正则组
					if (this.variableNames.size() != matcher.groupCount()) {
						throw new IllegalArgumentException("The number of capturing groups in the pattern segment " +
								this.pattern + " does not match the number of URI template variables it defines, " +
								"which can occur if capturing groups are used in a URI template regex. " +
								"Use non-capturing groups instead.");
					}
					// 遍历路径变量参数名，分别调用group(i)取出路径变量的值，放入map中
					for (int i = 1; i <= matcher.groupCount(); i++) {
						String name = this.variableNames.get(i - 1);
						String value = matcher.group(i);
						uriTemplateVariables.put(name, value);
					}
				}
				return true;
			}
			// 否则返回false
			else {
				return false;
			}
		}
	}

	public static void main(String[] args) {
		AntPathStringMatcher stringMatcher = new AntPathStringMatcher("t?st{param1:\\w{5}}t*t{param2}test", true);
		System.out.println("resolved pattern is :" + stringMatcher.pattern);
		System.out.println("resolved uri variables is : " + Arrays.toString(stringMatcher.variableNames.toArray()));
		Map<String, String> map = new HashMap<>();
		boolean match = stringMatcher.matchStrings("testvaluetestvalue2test", map);
		System.out.println("match result: " + match);

	}


	/**
	 * The default {@link Comparator} implementation returned by
	 * {@link #getPatternComparator(String)}.
	 * <p>In order, the most "generic" pattern is determined by the following:
	 * <ul>
	 * <li>if it's null or a capture all pattern (i.e. it is equal to "/**")</li>
	 * <li>if the other pattern is an actual match</li>
	 * <li>if it's a catch-all pattern (i.e. it ends with "**"</li>
	 * <li>if it's got more "*" than the other pattern</li>
	 * <li>if it's got more "{foo}" than the other pattern</li>
	 * <li>if it's shorter than the other pattern</li>
	 * </ul>
	 */
	protected static class AntPatternComparator implements Comparator<String> {

		private final String path;

		public AntPatternComparator(String path) {
			this.path = path;
		}

		/**
		 * Compare two patterns to determine which should match first, i.e. which
		 * is the most specific regarding the current path.
		 * @return a negative integer, zero, or a positive integer as pattern1 is
		 * more specific, equally specific, or less specific than pattern2.
		 */
		@Override
		public int compare(String pattern1, String pattern2) {
			// 根据pattern1和pattern2分别初始化出PatternInfo
			PatternInfo info1 = new PatternInfo(pattern1);
			PatternInfo info2 = new PatternInfo(pattern2);

			// 如果info中的pattern为null 或者是pattern等于/**的话，那么两个pattern的优先级一样
			if (info1.isLeastSpecific() && info2.isLeastSpecific()) {
				return 0;
			}
			// 如果info1中pattern是null或/**，那么pattern1的优先级低于pattern2
			else if (info1.isLeastSpecific()) {
				return 1;
			}
			// 如果info2中的pattern是null或/**，那么pattern1的优先级高于pattern2
			else if (info2.isLeastSpecific()) {
				return -1;
			}

			// 判断两个pattern是否和path相等，来决定优先级
			boolean pattern1EqualsPath = pattern1.equals(this.path);
			boolean pattern2EqualsPath = pattern2.equals(this.path);
			if (pattern1EqualsPath && pattern2EqualsPath) {
				return 0;
			}
			else if (pattern1EqualsPath) {
				return -1;
			}
			else if (pattern2EqualsPath) {
				return 1;
			}


			// 如果两个pattern是否是以/**结尾的
			if (info1.isPrefixPattern() && info2.isPrefixPattern()) {
				// 那么长度更长的那个优先级更低
				return info2.getLength() - info1.getLength();
			}
			// 如果pattern1以/**结尾而pattern2拥有**的数量为0，那么pattern2的优先级更高
			else if (info1.isPrefixPattern() && info2.getDoubleWildcards() == 0) {
				return 1;
			}
			// 反之，pattern1的优先级更高
			else if (info2.isPrefixPattern() && info1.getDoubleWildcards() == 0) {
				return -1;
			}

			// 比较两个pattern中{ * **的数量 **算两个* 更少的那个优先级更高
			if (info1.getTotalCount() != info2.getTotalCount()) {
				return info1.getTotalCount() - info2.getTotalCount();
			}

			// 如果两个pattern的长度不一样，那么长度越长的优先级越高
			if (info1.getLength() != info2.getLength()) {
				return info2.getLength() - info1.getLength();
			}

			// 比较*的数量
			if (info1.getSingleWildcards() < info2.getSingleWildcards()) {
				return -1;
			}
			else if (info2.getSingleWildcards() < info1.getSingleWildcards()) {
				return 1;
			}

			// 比较路径变量的数量
			if (info1.getUriVars() < info2.getUriVars()) {
				return -1;
			}
			else if (info2.getUriVars() < info1.getUriVars()) {
				return 1;
			}

			// 如果上述比较都没返回，那么二者优先级一样
			return 0;
		}


		/**
		 * Value class that holds information about the pattern, e.g. number of
		 * occurrences of "*", "**", and "{" pattern elements.
		 */
		private static class PatternInfo {

			@Nullable
			private final String pattern;

			private int uriVars;

			private int singleWildcards;

			private int doubleWildcards;

			private boolean catchAllPattern;

			private boolean prefixPattern;

			@Nullable
			private Integer length;

			public PatternInfo(@Nullable String pattern) {
				// 将pattern赋值给pattern属性
				this.pattern = pattern;
				if (this.pattern != null) {
					// 如果pattern不为null的话，调用initCounters方法计算它的路径变量的数量 以及 * 和 **存在的数量
					initCounters();
					// 判断pattern是否等于/**
					this.catchAllPattern = this.pattern.equals("/**");
					// 如果catchAllPattern是false，判断pattern是否是以/**结尾的
					this.prefixPattern = !this.catchAllPattern && this.pattern.endsWith("/**");
				}
				// 如果不存在路径变量的话，将length赋值为pattern的长度
				if (this.uriVars == 0) {
					this.length = (this.pattern != null ? this.pattern.length() : 0);
				}
			}

			protected void initCounters() {
				int pos = 0;
				if (this.pattern != null) {
					// 从0这个下标开始循环
					while (pos < this.pattern.length()) {
						// 如果该位置的字符等于{，那么uriVars参数+1，表示有路径变量
						if (this.pattern.charAt(pos) == '{') {
							this.uriVars++;
							pos++;
						}
						// 如果该位置的字符等于*
						else if (this.pattern.charAt(pos) == '*') {
							// 并且它的下一个位置的字符仍然是*
							if (pos + 1 < this.pattern.length() && this.pattern.charAt(pos + 1) == '*') {
								// 那么doubleWildcard参数+1
								this.doubleWildcards++;
								// 并且将位置往后移两个位置
								pos += 2;
							}
							// 如果pos>0 并且从它的上一个位置往后截取出的字符串不是.*，说明不是文件后缀的通配
							else if (pos > 0 && !this.pattern.substring(pos - 1).equals(".*")) {
								// 将singleWildcard参数+1
								this.singleWildcards++;
								pos++;
							}
							// 其余情况，pos向后移
							else {
								pos++;
							}
						}
						// 其余情况pos向后移
						else {
							pos++;
						}
					}
				}
			}

			public int getUriVars() {
				return this.uriVars;
			}

			public int getSingleWildcards() {
				return this.singleWildcards;
			}

			public int getDoubleWildcards() {
				return this.doubleWildcards;
			}

			public boolean isLeastSpecific() {
				return (this.pattern == null || this.catchAllPattern);
			}

			public boolean isPrefixPattern() {
				return this.prefixPattern;
			}

			public int getTotalCount() {
				return this.uriVars + this.singleWildcards + (2 * this.doubleWildcards);
			}

			/**
			 * Returns the length of the given pattern, where template variables are considered to be 1 long.
			 */
			public int getLength() {
				if (this.length == null) {
					this.length = (this.pattern != null ?
							VARIABLE_PATTERN.matcher(this.pattern).replaceAll("#").length() : 0);
				}
				return this.length;
			}
		}
	}


	/**
	 * A simple cache for patterns that depend on the configured path separator.
	 */
	private static class PathSeparatorPatternCache {

		private final String endsOnWildCard;

		private final String endsOnDoubleWildCard;

		public PathSeparatorPatternCache(String pathSeparator) {
			this.endsOnWildCard = pathSeparator + "*";
			this.endsOnDoubleWildCard = pathSeparator + "**";
		}

		public String getEndsOnWildCard() {
			return this.endsOnWildCard;
		}

		public String getEndsOnDoubleWildCard() {
			return this.endsOnDoubleWildCard;
		}
	}

}
