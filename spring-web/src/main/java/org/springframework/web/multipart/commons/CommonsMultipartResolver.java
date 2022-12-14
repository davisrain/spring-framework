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

package org.springframework.web.multipart.commons;

import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import org.springframework.util.Assert;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.AbstractMultipartHttpServletRequest;
import org.springframework.web.multipart.support.DefaultMultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

/**
 * Servlet-based {@link MultipartResolver} implementation for
 * <a href="https://commons.apache.org/proper/commons-fileupload">Apache Commons FileUpload</a>
 * 1.2 or above.
 *
 * <p>Provides "maxUploadSize", "maxInMemorySize" and "defaultEncoding" settings as
 * bean properties (inherited from {@link CommonsFileUploadSupport}). See corresponding
 * ServletFileUpload / DiskFileItemFactory properties ("sizeMax", "sizeThreshold",
 * "headerEncoding") for details in terms of defaults and accepted values.
 *
 * <p>Saves temporary files to the servlet container's temporary directory.
 * Needs to be initialized <i>either</i> by an application context <i>or</i>
 * via the constructor that takes a ServletContext (for standalone usage).
 *
 * @author Trevor D. Cook
 * @author Juergen Hoeller
 * @since 29.09.2003
 * @see #CommonsMultipartResolver(ServletContext)
 * @see #setResolveLazily
 * @see org.apache.commons.fileupload.servlet.ServletFileUpload
 * @see org.apache.commons.fileupload.disk.DiskFileItemFactory
 */
public class CommonsMultipartResolver extends CommonsFileUploadSupport
		implements MultipartResolver, ServletContextAware {

	private boolean resolveLazily = false;


	/**
	 * Constructor for use as bean. Determines the servlet container's
	 * temporary directory via the ServletContext passed in as through the
	 * ServletContextAware interface (typically by a WebApplicationContext).
	 * @see #setServletContext
	 * @see org.springframework.web.context.ServletContextAware
	 * @see org.springframework.web.context.WebApplicationContext
	 */
	public CommonsMultipartResolver() {
		super();
	}

	/**
	 * Constructor for standalone usage. Determines the servlet container's
	 * temporary directory via the given ServletContext.
	 * @param servletContext the ServletContext to use
	 */
	public CommonsMultipartResolver(ServletContext servletContext) {
		this();
		setServletContext(servletContext);
	}


	/**
	 * Set whether to resolve the multipart request lazily at the time of
	 * file or parameter access.
	 * <p>Default is "false", resolving the multipart elements immediately, throwing
	 * corresponding exceptions at the time of the {@link #resolveMultipart} call.
	 * Switch this to "true" for lazy multipart parsing, throwing parse exceptions
	 * once the application attempts to obtain multipart files or parameters.
	 */
	public void setResolveLazily(boolean resolveLazily) {
		this.resolveLazily = resolveLazily;
	}

	/**
	 * Initialize the underlying {@code org.apache.commons.fileupload.servlet.ServletFileUpload}
	 * instance. Can be overridden to use a custom subclass, e.g. for testing purposes.
	 * @param fileItemFactory the Commons FileItemFactory to use
	 * @return the new ServletFileUpload instance
	 */
	@Override
	protected FileUpload newFileUpload(FileItemFactory fileItemFactory) {
		return new ServletFileUpload(fileItemFactory);
	}

	@Override
	public void setServletContext(ServletContext servletContext) {
		if (!isUploadTempDirSpecified()) {
			getFileItemFactory().setRepository(WebUtils.getTempDir(servletContext));
		}
	}


	@Override
	public boolean isMultipart(HttpServletRequest request) {
		// 判断是否是multipart类型请求的方法是查看请求头中的Content-Type是否是以multipart/开头的
		return ServletFileUpload.isMultipartContent(request);
	}

	@Override
	public MultipartHttpServletRequest resolveMultipart(final HttpServletRequest request) throws MultipartException {
		Assert.notNull(request, "Request must not be null");
		// 如果懒解析参数为true的话，返回一个DefaultMultipartHttpServletRequest类型的匿名类
		if (this.resolveLazily) {
			return new DefaultMultipartHttpServletRequest(request) {
				@Override
				// 在调用这个request的getMultipartFiles方法的时候，就会调用initializeMultipart方法进行延迟初始化
				protected void initializeMultipart() {
					// 调用parseRequest进行解析，得到result
					MultipartParsingResult parsingResult = parseRequest(request);
					// 根据解析的结果设置multipartFiles字段，保存的是表单上传的文件，
					// key为表单字段名，value为持有FileItem对象的CommonsMultipartFile对象
					setMultipartFiles(parsingResult.getMultipartFiles());
					// 设置multipartParameters字段，保存的是表单上传的普通字段
					// key为表单字段名，value为字段值的数组，因为可能存在多个字段重名的情况
					setMultipartParameters(parsingResult.getMultipartParameters());
					// 设置multipartParameterContentTypes字段，保存的是普通字段的ContentType
					setMultipartParameterContentTypes(parsingResult.getMultipartParameterContentTypes());
				}
			};
		}
		else {
			// 即时解析request，并将结果作为构造器参数传入
			MultipartParsingResult parsingResult = parseRequest(request);
			return new DefaultMultipartHttpServletRequest(request, parsingResult.getMultipartFiles(),
					parsingResult.getMultipartParameters(), parsingResult.getMultipartParameterContentTypes());
		}
	}

	/**
	 * Parse the given servlet request, resolving its multipart elements.
	 * @param request the request to parse
	 * @return the parsing result
	 * @throws MultipartException if multipart resolution failed.
	 */
	protected MultipartParsingResult parseRequest(HttpServletRequest request) throws MultipartException {
		// 根据request确定编码格式
		String encoding = determineEncoding(request);
		// 根据获取到的encoding准备fileUpload
		FileUpload fileUpload = prepareFileUpload(encoding);
		try {
			// 使用fileUpload对request进行解析，得到FileItem的list对象
			List<FileItem> fileItems = ((ServletFileUpload) fileUpload).parseRequest(request);
			// 调用解析fileItems的方法
			return parseFileItems(fileItems, encoding);
		}
		catch (FileUploadBase.SizeLimitExceededException ex) {
			throw new MaxUploadSizeExceededException(fileUpload.getSizeMax(), ex);
		}
		catch (FileUploadBase.FileSizeLimitExceededException ex) {
			throw new MaxUploadSizeExceededException(fileUpload.getFileSizeMax(), ex);
		}
		catch (FileUploadException ex) {
			throw new MultipartException("Failed to parse multipart servlet request", ex);
		}
	}

	/**
	 * Determine the encoding for the given request.
	 * Can be overridden in subclasses.
	 * <p>The default implementation checks the request encoding,
	 * falling back to the default encoding specified for this resolver.
	 * @param request current HTTP request
	 * @return the encoding for the request (never {@code null})
	 * @see javax.servlet.ServletRequest#getCharacterEncoding
	 * @see #setDefaultEncoding
	 */
	protected String determineEncoding(HttpServletRequest request) {
		// 从request中获取characterEncoding属性，如果为null的话，获取默认的encoding
		String encoding = request.getCharacterEncoding();
		if (encoding == null) {
			encoding = getDefaultEncoding();
		}
		return encoding;
	}

	@Override
	public void cleanupMultipart(MultipartHttpServletRequest request) {
		if (!(request instanceof AbstractMultipartHttpServletRequest) ||
				((AbstractMultipartHttpServletRequest) request).isResolved()) {
			try {
				cleanupFileItems(request.getMultiFileMap());
			}
			catch (Throwable ex) {
				logger.warn("Failed to perform multipart cleanup for servlet request", ex);
			}
		}
	}

}
