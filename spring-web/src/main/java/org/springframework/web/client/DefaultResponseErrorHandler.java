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

package org.springframework.web.client;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.core.log.LogFormatUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.ObjectUtils;

/**
 * Spring's default implementation of the {@link ResponseErrorHandler} interface.
 *
 * <p>This error handler checks for the status code on the
 * {@link ClientHttpResponse}. Any code in the 4xx or 5xx series is considered
 * to be an error. This behavior can be changed by overriding
 * {@link #hasError(HttpStatus)}. Unknown status codes will be ignored by
 * {@link #hasError(ClientHttpResponse)}.
 *
 * <p>See {@link #handleError(ClientHttpResponse)} for more details on specific
 * exception types.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.0
 * @see RestTemplate#setErrorHandler
 */
public class DefaultResponseErrorHandler implements ResponseErrorHandler {

	/**
	 * Delegates to {@link #hasError(HttpStatus)} (for a standard status enum value) or
	 * {@link #hasError(int)} (for an unknown status code) with the response status code.
	 * @see ClientHttpResponse#getRawStatusCode()
	 * @see #hasError(HttpStatus)
	 * @see #hasError(int)
	 */
	@Override
	public boolean hasError(ClientHttpResponse response) throws IOException {
		// 获取返回的状态码
		int rawStatusCode = response.getRawStatusCode();
		// 将int类型的状态码解析为枚举
		HttpStatus statusCode = HttpStatus.resolve(rawStatusCode);
		// 如果找到了枚举，根据枚举判断是否存在错误，否则根据int类型的状态码判断
		return (statusCode != null ? hasError(statusCode) : hasError(rawStatusCode));
	}

	/**
	 * Template method called from {@link #hasError(ClientHttpResponse)}.
	 * <p>The default implementation checks {@link HttpStatus#isError()}.
	 * Can be overridden in subclasses.
	 * @param statusCode the HTTP status code as enum value
	 * @return {@code true} if the response indicates an error; {@code false} otherwise
	 * @see HttpStatus#isError()
	 */
	protected boolean hasError(HttpStatus statusCode) {
		return statusCode.isError();
	}

	/**
	 * Template method called from {@link #hasError(ClientHttpResponse)}.
	 * <p>The default implementation checks if the given status code is
	 * {@link org.springframework.http.HttpStatus.Series#CLIENT_ERROR CLIENT_ERROR} or
	 * {@link org.springframework.http.HttpStatus.Series#SERVER_ERROR SERVER_ERROR}.
	 * Can be overridden in subclasses.
	 * @param unknownStatusCode the HTTP status code as raw value
	 * @return {@code true} if the response indicates an error; {@code false} otherwise
	 * @since 4.3.21
	 * @see org.springframework.http.HttpStatus.Series#CLIENT_ERROR
	 * @see org.springframework.http.HttpStatus.Series#SERVER_ERROR
	 */
	protected boolean hasError(int unknownStatusCode) {
		// 如果状态码以4或者5开头，说明有错误
		HttpStatus.Series series = HttpStatus.Series.resolve(unknownStatusCode);
		return (series == HttpStatus.Series.CLIENT_ERROR || series == HttpStatus.Series.SERVER_ERROR);
	}

	/**
	 * Handle the error in the given response with the given resolved status code.
	 * <p>The default implementation throws:
	 * <ul>
	 * <li>{@link HttpClientErrorException} if the status code is in the 4xx
	 * series, or one of its sub-classes such as
	 * {@link HttpClientErrorException.BadRequest} and others.
	 * <li>{@link HttpServerErrorException} if the status code is in the 5xx
	 * series, or one of its sub-classes such as
	 * {@link HttpServerErrorException.InternalServerError} and others.
	 * <li>{@link UnknownHttpStatusCodeException} for error status codes not in the
	 * {@link HttpStatus} enum range.
	 * </ul>
	 * @throws UnknownHttpStatusCodeException in case of an unresolvable status code
	 * @see #handleError(ClientHttpResponse, HttpStatus)
	 */
	@Override
	public void handleError(ClientHttpResponse response) throws IOException {
		HttpStatus statusCode = HttpStatus.resolve(response.getRawStatusCode());
		// 如果是未知的错误状态码
		if (statusCode == null) {
			// 从返回中拿到返回体并转换为字节数组
			byte[] body = getResponseBody(response);
			// 根据状态码和返回体等信息构建错误信息
			String message = getErrorMessage(response.getRawStatusCode(),
					response.getStatusText(), body, getCharset(response));
			// 抛出未识别的http状态码异常
			throw new UnknownHttpStatusCodeException(message,
					response.getRawStatusCode(), response.getStatusText(),
					response.getHeaders(), body, getCharset(response));
		}
		// 如果是可以转换为枚举的错误状态码，调用带枚举参数的handleError方法
		handleError(response, statusCode);
	}

	/**
	 * Return error message with details from the response body. For example:
	 * <pre>
	 * 404 Not Found: [{'id': 123, 'message': 'my message'}]
	 * </pre>
	 */
	private String getErrorMessage(
			int rawStatusCode, String statusText, @Nullable byte[] responseBody, @Nullable Charset charset) {

		String preface = rawStatusCode + " " + statusText + ": ";

		if (ObjectUtils.isEmpty(responseBody)) {
			return preface + "[no body]";
		}

		charset = (charset != null ? charset : StandardCharsets.UTF_8);

		String bodyText = new String(responseBody, charset);
		bodyText = LogFormatUtils.formatValue(bodyText, -1, true);

		return preface + bodyText;
	}

	/**
	 * Handle the error based on the resolved status code.
	 *
	 * <p>The default implementation delegates to
	 * {@link HttpClientErrorException#create} for errors in the 4xx range, to
	 * {@link HttpServerErrorException#create} for errors in the 5xx range,
	 * or otherwise raises {@link UnknownHttpStatusCodeException}.
	 *
	 * @since 5.0
	 * @see HttpClientErrorException#create
	 * @see HttpServerErrorException#create
	 */
	protected void handleError(ClientHttpResponse response, HttpStatus statusCode) throws IOException {
		String statusText = response.getStatusText();
		HttpHeaders headers = response.getHeaders();
		byte[] body = getResponseBody(response);
		Charset charset = getCharset(response);
		String message = getErrorMessage(statusCode.value(), statusText, body, charset);

		// 根据状态码，抛出客户端或服务端异常或者未识别的状态码异常
		switch (statusCode.series()) {
			case CLIENT_ERROR:
				throw HttpClientErrorException.create(message, statusCode, statusText, headers, body, charset);
			case SERVER_ERROR:
				throw HttpServerErrorException.create(message, statusCode, statusText, headers, body, charset);
			default:
				throw new UnknownHttpStatusCodeException(message, statusCode.value(), statusText, headers, body, charset);
		}
	}

	/**
	 * Determine the HTTP status of the given response.
	 * @param response the response to inspect
	 * @return the associated HTTP status
	 * @throws IOException in case of I/O errors
	 * @throws UnknownHttpStatusCodeException in case of an unknown status code
	 * that cannot be represented with the {@link HttpStatus} enum
	 * @since 4.3.8
	 * @deprecated as of 5.0, in favor of {@link #handleError(ClientHttpResponse, HttpStatus)}
	 */
	@Deprecated
	protected HttpStatus getHttpStatusCode(ClientHttpResponse response) throws IOException {
		HttpStatus statusCode = HttpStatus.resolve(response.getRawStatusCode());
		if (statusCode == null) {
			throw new UnknownHttpStatusCodeException(response.getRawStatusCode(), response.getStatusText(),
					response.getHeaders(), getResponseBody(response), getCharset(response));
		}
		return statusCode;
	}

	/**
	 * Read the body of the given response (for inclusion in a status exception).
	 * @param response the response to inspect
	 * @return the response body as a byte array,
	 * or an empty byte array if the body could not be read
	 * @since 4.3.8
	 */
	protected byte[] getResponseBody(ClientHttpResponse response) {
		try {
			// 将response中的返回体转换为字节数组返回
			return FileCopyUtils.copyToByteArray(response.getBody());
		}
		catch (IOException ex) {
			// ignore
		}
		return new byte[0];
	}

	/**
	 * Determine the charset of the response (for inclusion in a status exception).
	 * @param response the response to inspect
	 * @return the associated charset, or {@code null} if none
	 * @since 4.3.8
	 */
	@Nullable
	protected Charset getCharset(ClientHttpResponse response) {
		HttpHeaders headers = response.getHeaders();
		MediaType contentType = headers.getContentType();
		return (contentType != null ? contentType.getCharset() : null);
	}

}
