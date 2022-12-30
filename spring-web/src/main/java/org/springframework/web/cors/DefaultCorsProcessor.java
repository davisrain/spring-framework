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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;

/**
 * The default implementation of {@link CorsProcessor}, as defined by the
 * <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>.
 *
 * <p>Note that when input {@link CorsConfiguration} is {@code null}, this
 * implementation does not reject simple or actual requests outright but simply
 * avoid adding CORS headers to the response. CORS processing is also skipped
 * if the response already contains CORS headers.
 *
 * @author Sebastien Deleuze
 * @author Rossen Stoyanchev
 * @since 4.2
 */
public class DefaultCorsProcessor implements CorsProcessor {

	private static final Log logger = LogFactory.getLog(DefaultCorsProcessor.class);


	@Override
	@SuppressWarnings("resource")
	public boolean processRequest(@Nullable CorsConfiguration config, HttpServletRequest request,
			HttpServletResponse response) throws IOException {

		// 从response的返回头中获取Vary属性
		Collection<String> varyHeaders = response.getHeaders(HttpHeaders.VARY);
		// 如果Vary属性中不包含Origin，添加进去
		if (!varyHeaders.contains(HttpHeaders.ORIGIN)) {
			response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
		}
		// 如果Vary属性中不包含Access-Control-Request-Method，添加进去
		if (!varyHeaders.contains(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)) {
			response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
		}
		// 如果Vary属性中不包含Access-Control-Request-Headers，添加进去
		if (!varyHeaders.contains(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)) {
			response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
		}

		// 如果请求不是跨域请求，直接返回true
		if (!CorsUtils.isCorsRequest(request)) {
			return true;
		}

		// 如果返回头中已经携带有Access-Control-Allow-Origin了，返回true，直接跳过拦截器
		if (response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) != null) {
			logger.trace("Skip: response already contains \"Access-Control-Allow-Origin\"");
			return true;
		}

		// 判断请求是否是预检请求
		boolean preFlightRequest = CorsUtils.isPreFlightRequest(request);
		// 如果跨域配置为null的话
		if (config == null) {
			// 如果是预检请求，调用rejectRequest方法
			if (preFlightRequest) {
				rejectRequest(new ServletServerHttpResponse(response));
				return false;
			}
			// 如果不是，返回true，放行拦截器
			else {
				return true;
			}
		}

		// 调用handleInternal方法进行进一步的处理
		return handleInternal(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response), config, preFlightRequest);
	}

	/**
	 * Invoked when one of the CORS checks failed.
	 * The default implementation sets the response status to 403 and writes
	 * "Invalid CORS request" to the response.
	 */
	protected void rejectRequest(ServerHttpResponse response) throws IOException {
		// 将返回状态码设置为403返回
		response.setStatusCode(HttpStatus.FORBIDDEN);
		response.getBody().write("Invalid CORS request".getBytes(StandardCharsets.UTF_8));
		response.flush();
	}

	/**
	 * Handle the given request.
	 */
	protected boolean handleInternal(ServerHttpRequest request, ServerHttpResponse response,
			CorsConfiguration config, boolean preFlightRequest) throws IOException {

		// 获取到请求头中的Origin属性
		String requestOrigin = request.getHeaders().getOrigin();
		// 根据跨域配置和Origin属性判断允许的allowOrigin
		String allowOrigin = checkOrigin(config, requestOrigin);
		HttpHeaders responseHeaders = response.getHeaders();

		// 如果allowOrigin为null的话，response返回403
		if (allowOrigin == null) {
			logger.debug("Reject: '" + requestOrigin + "' origin is not allowed");
			rejectRequest(response);
			return false;
		}

		// 获取到需要使用的method
		HttpMethod requestMethod = getMethodToUse(request, preFlightRequest);
		// 根据跨域配置检查method，获取允许的methods
		List<HttpMethod> allowMethods = checkMethods(config, requestMethod);
		// 如果允许methods为null，response返回403
		if (allowMethods == null) {
			logger.debug("Reject: HTTP '" + requestMethod + "' is not allowed");
			rejectRequest(response);
			return false;
		}

		// 获取到需要使用的headers
		List<String> requestHeaders = getHeadersToUse(request, preFlightRequest);
		// 根据跨域配置，获取允许的headers
		List<String> allowHeaders = checkHeaders(config, requestHeaders);
		// 如果是预检请求且允许的headers为null，response返回403
		if (preFlightRequest && allowHeaders == null) {
			logger.debug("Reject: headers '" + requestHeaders + "' are not allowed");
			rejectRequest(response);
			return false;
		}

		// 在返回头中设置Access-Control-Allow-Origin参数
		responseHeaders.setAccessControlAllowOrigin(allowOrigin);

		// 如果是预检请求，在返回头中设置Access-Control-Allow-Methods参数，其中allowMethod的name用逗号分隔
		if (preFlightRequest) {
			responseHeaders.setAccessControlAllowMethods(allowMethods);
		}

		// 如果是预检请求，且allowHeaders不为空的话，在返回头中设置Access-Control-Allow-Headers参数，allowHeaders用逗号分隔
		if (preFlightRequest && !allowHeaders.isEmpty()) {
			responseHeaders.setAccessControlAllowHeaders(allowHeaders);
		}

		// 如果跨域配置中的exposedHeaders不为空的话，在返回头中设置Access-Control-Expose-Headers参数，用逗号分隔
		if (!CollectionUtils.isEmpty(config.getExposedHeaders())) {
			responseHeaders.setAccessControlExposeHeaders(config.getExposedHeaders());
		}

		// 如果跨域配置中的allowCredentials为true的话，在返回头中设置Access-Control-Allow-Credentials参数
		if (Boolean.TRUE.equals(config.getAllowCredentials())) {
			responseHeaders.setAccessControlAllowCredentials(true);
		}

		// 如果是预检请求，且跨域配置中的maxAge不为null的话，在返回头中设置Access-Control-Max-Age参数
		if (preFlightRequest && config.getMaxAge() != null) {
			responseHeaders.setAccessControlMaxAge(config.getMaxAge());
		}

		response.flush();
		// 放行拦截器
		return true;
	}

	/**
	 * Check the origin and determine the origin for the response. The default
	 * implementation simply delegates to
	 * {@link org.springframework.web.cors.CorsConfiguration#checkOrigin(String)}.
	 */
	@Nullable
	protected String checkOrigin(CorsConfiguration config, @Nullable String requestOrigin) {
		return config.checkOrigin(requestOrigin);
	}

	/**
	 * Check the HTTP method and determine the methods for the response of a
	 * pre-flight request. The default implementation simply delegates to
	 * {@link org.springframework.web.cors.CorsConfiguration#checkHttpMethod(HttpMethod)}.
	 */
	@Nullable
	protected List<HttpMethod> checkMethods(CorsConfiguration config, @Nullable HttpMethod requestMethod) {
		return config.checkHttpMethod(requestMethod);
	}

	@Nullable
	private HttpMethod getMethodToUse(ServerHttpRequest request, boolean isPreFlight) {
		// 如果是预检请求，从请求头中获取Access-Control-Request-Method属性，如果是正常请求，直接获取method
		return (isPreFlight ? request.getHeaders().getAccessControlRequestMethod() : request.getMethod());
	}

	/**
	 * Check the headers and determine the headers for the response of a
	 * pre-flight request. The default implementation simply delegates to
	 * {@link org.springframework.web.cors.CorsConfiguration#checkOrigin(String)}.
	 */
	@Nullable
	protected List<String> checkHeaders(CorsConfiguration config, List<String> requestHeaders) {
		return config.checkHeaders(requestHeaders);
	}

	private List<String> getHeadersToUse(ServerHttpRequest request, boolean isPreFlight) {
		HttpHeaders headers = request.getHeaders();
		// 如果是预检请求，从请求头中获取Access-Control-Request-Headers属性，否则直接获取headers
		return (isPreFlight ? headers.getAccessControlRequestHeaders() : new ArrayList<>(headers.keySet()));
	}

}
