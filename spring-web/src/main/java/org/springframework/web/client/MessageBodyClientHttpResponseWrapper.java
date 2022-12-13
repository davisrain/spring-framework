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

package org.springframework.web.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.Nullable;

/**
 * Implementation of {@link ClientHttpResponse} that can not only check if
 * the response has a message body, but also if its length is 0 (i.e. empty)
 * by actually reading the input stream.
 *
 * @author Brian Clozel
 * @since 4.1.5
 * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3.3">RFC 7230 Section 3.3.3</a>
 */
class MessageBodyClientHttpResponseWrapper implements ClientHttpResponse {

	private final ClientHttpResponse response;

	@Nullable
	private PushbackInputStream pushbackInputStream;


	public MessageBodyClientHttpResponseWrapper(ClientHttpResponse response) throws IOException {
		this.response = response;
	}


	/**
	 * Indicates whether the response has a message body.
	 * <p>Implementation returns {@code false} for:
	 * <ul>
	 * <li>a response status of {@code 1XX}, {@code 204} or {@code 304}</li>
	 * <li>a {@code Content-Length} header of {@code 0}</li>
	 * </ul>
	 * @return {@code true} if the response has a message body, {@code false} otherwise
	 * @throws IOException in case of I/O errors
	 */
	public boolean hasMessageBody() throws IOException {
		HttpStatus status = HttpStatus.resolve(getRawStatusCode());
		// 如果http状态码是1开头或者是204或者是304，返回false，表示没有返回内容
		if (status != null && (status.is1xxInformational() || status == HttpStatus.NO_CONTENT ||
				status == HttpStatus.NOT_MODIFIED)) {
			return false;
		}
		// 如果返回头中，content-length属性为0，表示没有返回内容，也返回false
		if (getHeaders().getContentLength() == 0) {
			return false;
		}
		// 否则返回true
		return true;
	}

	/**
	 * Indicates whether the response has an empty message body.
	 * <p>Implementation tries to read the first bytes of the response stream:
	 * <ul>
	 * <li>if no bytes are available, the message body is empty</li>
	 * <li>otherwise it is not empty and the stream is reset to its start for further reading</li>
	 * </ul>
	 * @return {@code true} if the response has a zero-length message body, {@code false} otherwise
	 * @throws IOException in case of I/O errors
	 */
	@SuppressWarnings("ConstantConditions")
	public boolean hasEmptyMessageBody() throws IOException {
		// 获取返回中的返回体
		InputStream body = this.response.getBody();
		// Per contract body shouldn't be null, but check anyway..
		// 如果body为null，返回true
		if (body == null) {
			return true;
		}
		// 如果输入流是可以mark的
		if (body.markSupported()) {
			// 将mark设置到1的位置
			body.mark(1);
			// 如果没有读取到数据，返回true
			if (body.read() == -1) {
				return true;
			}
			// 否则，调用reset方法，将pos重新设置为mark的位置
			else {
				body.reset();
				return false;
			}
		}
		// 如果是不支持mark的
		else {
			// 使用装饰器模式将返回体的inputStream修饰
			this.pushbackInputStream = new PushbackInputStream(body);
			// 调用装饰器的读取方法读取一个字节
			int b = this.pushbackInputStream.read();
			// 如果没有读取到数据，说明是返回体为空，返回true
			if (b == -1) {
				return true;
			}
			// 否则调用unread方法将读取出来的字段放入pushbackInputStream中，这样下次读取还能读到
			else {
				this.pushbackInputStream.unread(b);
				return false;
			}
		}
	}


	@Override
	public HttpHeaders getHeaders() {
		return this.response.getHeaders();
	}

	@Override
	public InputStream getBody() throws IOException {
		return (this.pushbackInputStream != null ? this.pushbackInputStream : this.response.getBody());
	}

	@Override
	public HttpStatus getStatusCode() throws IOException {
		return this.response.getStatusCode();
	}

	@Override
	public int getRawStatusCode() throws IOException {
		return this.response.getRawStatusCode();
	}

	@Override
	public String getStatusText() throws IOException {
		return this.response.getStatusText();
	}

	@Override
	public void close() {
		this.response.close();
	}

}
