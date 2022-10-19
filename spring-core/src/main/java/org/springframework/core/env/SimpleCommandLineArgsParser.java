/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.core.env;

/**
 * Parses a {@code String[]} of command line arguments in order to populate a
 * {@link CommandLineArgs} object.
 *
 * <h3>Working with option arguments</h3>
 * <p>Option arguments must adhere to the exact syntax:
 *
 * <pre class="code">--optName[=optValue]</pre>
 *
 * <p>That is, options must be prefixed with "{@code --}" and may or may not
 * specify a value. If a value is specified, the name and value must be separated
 * <em>without spaces</em> by an equals sign ("="). The value may optionally be
 * an empty string.
 *
 * <h4>Valid examples of option arguments</h4>
 * <pre class="code">
 * --foo
 * --foo=
 * --foo=""
 * --foo=bar
 * --foo="bar then baz"
 * --foo=bar,baz,biz</pre>
 *
 * <h4>Invalid examples of option arguments</h4>
 * <pre class="code">
 * -foo
 * --foo bar
 * --foo = bar
 * --foo=bar --foo=baz --foo=biz</pre>
 *
 * <h3>Working with non-option arguments</h3>
 * <p>Any and all arguments specified at the command line without the "{@code --}"
 * option prefix will be considered as "non-option arguments" and made available
 * through the {@link CommandLineArgs#getNonOptionArgs()} method.
 *
 * @author Chris Beams
 * @author Sam Brannen
 * @since 3.1
 */
class SimpleCommandLineArgsParser {

	/**
	 * Parse the given {@code String} array based on the rules described {@linkplain
	 * SimpleCommandLineArgsParser above}, returning a fully-populated
	 * {@link CommandLineArgs} object.
	 * @param args command line arguments, typically from a {@code main()} method
	 */
	// 命令行参数的解析方法
	public CommandLineArgs parse(String... args) {
		// 创建一个CommandLineArgs对象来保存命令行参数，
		// 其中以--开头的optionArgs是Map<String,List<String>>结构的，
		// 不以--开头的nonOptionArgs是List<String>结构的
		CommandLineArgs commandLineArgs = new CommandLineArgs();
		// 循环命令行参数数组
		for (String arg : args) {
			// 如果参数以--开头，属于optionArg，
			// 根据=分隔解析出optionName和optionValue；如果没有=，则只有optionName
			if (arg.startsWith("--")) {
				String optionText = arg.substring(2);
				String optionName;
				String optionValue = null;
				int indexOfEqualsSign = optionText.indexOf('=');
				if (indexOfEqualsSign > -1) {
					optionName = optionText.substring(0, indexOfEqualsSign);
					optionValue = optionText.substring(indexOfEqualsSign + 1);
				}
				else {
					optionName = optionText;
				}
				if (optionName.isEmpty()) {
					throw new IllegalArgumentException("Invalid argument syntax: " + arg);
				}
				// 调用addOptionArg方法将optionName和optionValue存入
				commandLineArgs.addOptionArg(optionName, optionValue);
			}
			// 不以--开头的 属于NonOptionArg
			else {
				commandLineArgs.addNonOptionArg(arg);
			}
		}
		return commandLineArgs;
	}

}
