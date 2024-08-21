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

package org.springframework.aop.scope;

import java.lang.reflect.Modifier;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyConfig;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DelegatingIntroductionInterceptor;
import org.springframework.aop.target.SimpleBeanTargetSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * Convenient proxy factory bean for scoped objects.
 *
 * <p>Proxies created using this factory bean are thread-safe singletons
 * and may be injected into shared objects, with transparent scoping behavior.
 *
 * <p>Proxies returned by this class implement the {@link ScopedObject} interface.
 * This presently allows for removing the corresponding object from the scope,
 * seamlessly creating a new instance in the scope on next access.
 *
 * <p>Please note that the proxies created by this factory are
 * <i>class-based</i> proxies by default. This can be customized
 * through switching the "proxyTargetClass" property to "false".
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 2.0
 * @see #setProxyTargetClass
 */
@SuppressWarnings("serial")
public class ScopedProxyFactoryBean extends ProxyConfig
		implements FactoryBean<Object>, BeanFactoryAware, AopInfrastructureBean {

	/** The TargetSource that manages scoping. */
	// 创建一个SimpleBeanTargetSource，通过beanFactory和beanName获取target
	private final SimpleBeanTargetSource scopedTargetSource = new SimpleBeanTargetSource();

	/** The name of the target bean. */
	@Nullable
	// targetBeanName，即scopedTarget.开头的beanName，在beanDefinition的PropertyValues中会设置，并且会在populateBean这个方法中
	// 通过setTargetBeanName方法设置进来
	private String targetBeanName;

	/** The cached singleton proxy. */
	@Nullable
	private Object proxy;


	/**
	 * Create a new ScopedProxyFactoryBean instance.
	 */
	public ScopedProxyFactoryBean() {
		// 默认将proxyTargetClass设置为true
		setProxyTargetClass(true);
	}


	/**
	 * Set the name of the bean that is to be scoped.
	 */
	public void setTargetBeanName(String targetBeanName) {
		this.targetBeanName = targetBeanName;
		// 将targetBeanName设置进scopedTargetSource中
		this.scopedTargetSource.setTargetBeanName(targetBeanName);
	}

	@Override
	// 在initializeBean方法中的invokeAwareMethods方法中，会调用该方法，将beanFactory设置进来
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableBeanFactory)) {
			throw new IllegalStateException("Not running in a ConfigurableBeanFactory: " + beanFactory);
		}
		ConfigurableBeanFactory cbf = (ConfigurableBeanFactory) beanFactory;

		// 将beanFactory设置进scopedTargetSource中
		this.scopedTargetSource.setBeanFactory(beanFactory);

		// 创建一个ProxyFactory
		ProxyFactory pf = new ProxyFactory();
		// 将自身的ProxyConfig中的属性复制给ProxyFactory，其中proxyTargetClass默认为true
		pf.copyFrom(this);
		// 然后设置targetSource为自身的SimpleBeanTargetSource
		pf.setTargetSource(this.scopedTargetSource);

		// 如果targetBeanName为null，报错
		Assert.notNull(this.targetBeanName, "Property 'targetBeanName' is required");
		// 从beanFactory中获取targetBeanName对应的bean的类型
		Class<?> beanType = beanFactory.getType(this.targetBeanName);
		// 如果beanType为null，报错
		if (beanType == null) {
			throw new IllegalStateException("Cannot create scoped proxy for bean '" + this.targetBeanName +
					"': Target type could not be determined at the time of proxy creation.");
		}
		// 如果proxyTargetClass为false 或者 beanType是接口类型 或者beanType是private类型的，采用JDK代理，解析beanType实现的所有接口设置进pf中
		if (!isProxyTargetClass() || beanType.isInterface() || Modifier.isPrivate(beanType.getModifiers())) {
			pf.setInterfaces(ClassUtils.getAllInterfacesForClass(beanType, cbf.getBeanClassLoader()));
		}

		// Add an introduction that implements only the methods on ScopedObject.
		// 将beanFactory和targetBeanName封装成一个ScopedObject对象
		ScopedObject scopedObject = new DefaultScopedObject(cbf, this.scopedTargetSource.getTargetBeanName());
		// 然后向pf中添加一个DelegatingIntroductionInterceptor类型的Advice
		pf.addAdvice(new DelegatingIntroductionInterceptor(scopedObject));

		// Add the AopInfrastructureBean marker to indicate that the scoped proxy
		// itself is not subject to auto-proxying! Only its target bean is.
		// 添加AopInfrastructureBean接口，跳过autoProxy流程，不进行自动代理。
		// 因为FactoryBean对象调用getObject方法获取实际的bean的时候，仍然会进行bbp的postProcessAfterInitialization增强，
		// autoProxyCreator就会进行自动代理
		pf.addInterface(AopInfrastructureBean.class);

		// 创建出代理对象，赋值给proxy字段
		this.proxy = pf.getProxy(cbf.getBeanClassLoader());
	}


	@Override
	public Object getObject() {
		if (this.proxy == null) {
			throw new FactoryBeanNotInitializedException();
		}
		return this.proxy;
	}

	@Override
	public Class<?> getObjectType() {
		if (this.proxy != null) {
			return this.proxy.getClass();
		}
		return this.scopedTargetSource.getTargetClass();
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

}
