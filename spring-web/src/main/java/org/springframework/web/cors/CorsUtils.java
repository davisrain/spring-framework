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

package org.springframework.web.cors;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Utility class for CORS request handling based on the
 * <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>.
 *
 * @author Sebastien Deleuze
 * @since 4.2
 */
public abstract class CorsUtils {

	/**
	 * Returns {@code true} if the request is a valid CORS one by checking {@code Origin}
	 * header presence and ensuring that origins are different.
	 */
	public static boolean isCorsRequest(HttpServletRequest request) {
		// 从请求头中获取Origin属性
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		// 如果不存在Origin，那么肯定不是跨域请求，直接返回false
		if (origin == null) {
			return false;
		}
		// 根据Origin生成uriComponents
		UriComponents originUrl = UriComponentsBuilder.fromOriginHeader(origin).build();
		// 获取到请求的协议类型、服务器名称、以及服务器端口
		String scheme = request.getScheme();
		String host = request.getServerName();
		int port = request.getServerPort();
		// 如果来源的协议类型或主机名或者端口有任意一个和请求的服务器不相等，那么说明是跨域请求
		return !(ObjectUtils.nullSafeEquals(scheme, originUrl.getScheme()) &&
				ObjectUtils.nullSafeEquals(host, originUrl.getHost()) &&
				getPort(scheme, port) == getPort(originUrl.getScheme(), originUrl.getPort()));

	}

	private static int getPort(@Nullable String scheme, int port) {
		// 如果传入的端口是-1，那么根据协议类型生成端口，http默认80端口 https默认443端口
		if (port == -1) {
			if ("http".equals(scheme) || "ws".equals(scheme)) {
				port = 80;
			}
			else if ("https".equals(scheme) || "wss".equals(scheme)) {
				port = 443;
			}
		}
		return port;
	}

	/**
	 * Returns {@code true} if the request is a valid CORS pre-flight one by checking {code OPTIONS} method with
	 * {@code Origin} and {@code Access-Control-Request-Method} headers presence.
	 */
	public static boolean isPreFlightRequest(HttpServletRequest request) {
		// 判断是否是预检请求，
		// 即方法是OPTIONS，请求头中带有Origin和Access-Control-Request-Method这两个属性
		return (HttpMethod.OPTIONS.matches(request.getMethod()) &&
				request.getHeader(HttpHeaders.ORIGIN) != null &&
				request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null);
	}

}
