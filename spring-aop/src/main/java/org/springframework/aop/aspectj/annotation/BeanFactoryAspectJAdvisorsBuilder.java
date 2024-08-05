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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving @AspectJ beans from a BeanFactory and building
 * Spring Advisors based on them, for use with auto-proxying.
 *
 * 从bean factory中查找@AspectJ注解标注的bean 并且 基于它们构建Spring Advisors，用于自动代理
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AnnotationAwareAspectJAutoProxyCreator
 */
public class BeanFactoryAspectJAdvisorsBuilder {

	private final ListableBeanFactory beanFactory;

	private final AspectJAdvisorFactory advisorFactory;

	@Nullable
	private volatile List<String> aspectBeanNames;

	// 对于是singleton的aspect，perClause是singleton的，并且beanFactory的scope也是属于singleton的，
	// 那么会缓存创建出的Advisor集合
	private final Map<String, List<Advisor>> advisorsCache = new ConcurrentHashMap<>();

	// 对于不是singleton的aspect，将AspectInstanceFactory缓存起来，每次都通过AspectJAdvisorFactory和AspectInstanceFactory去创建出对应的advisor
	private final Map<String, MetadataAwareAspectInstanceFactory> aspectFactoryCache = new ConcurrentHashMap<>();


	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory) {
		this(beanFactory, new ReflectiveAspectJAdvisorFactory(beanFactory));
	}

	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 * @param advisorFactory the AspectJAdvisorFactory to build each Advisor with
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		Assert.notNull(advisorFactory, "AspectJAdvisorFactory must not be null");
		this.beanFactory = beanFactory;
		this.advisorFactory = advisorFactory;
	}


	/**
	 * Look for AspectJ-annotated aspect beans in the current bean factory,
	 * and return to a list of Spring AOP Advisors representing them.
	 * <p>Creates a Spring Advisor for each AspectJ advice method.
	 *
	 * 查找被AspectJ的注解标注的那些bean，在当前的bean factory中，
	 * 并且返沪一个Spring的Aop advisor来表示它们。
	 * 为每一个AspectJ的advice方法都创建一个Spring的Advisor
	 *
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	// 查找被标注了@Aspect注解的那些bean，并且返回一个集合的SpringAOP的advisors来表示它们。
	// 为每一个AspectJ的advice方法创建一个Spring的Advisor对象
	public List<Advisor> buildAspectJAdvisors() {
		// 获取自身持有的aspectBeanNames集合
		List<String> aspectNames = this.aspectBeanNames;

		// 如果aspectNames为null
		if (aspectNames == null) {
			// 加锁去获取
			synchronized (this) {
				// double check，防止有多个线程都执行了获取操作
				aspectNames = this.aspectBeanNames;
				if (aspectNames == null) {
					// 创建一个list用于存储Advisor对象
					List<Advisor> advisors = new ArrayList<>();
					// 将aspectNames初始化为一个list
					aspectNames = new ArrayList<>();
					// 获取beanFactory中的所有beanName
					String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
							this.beanFactory, Object.class, true, false);
					// 遍历beanName数组
					for (String beanName : beanNames) {
						// 如果beanName对应的bean是不合格的bean，跳过，
						// 这里的检查逻辑是根据AnnotationAwareAspectJAutoProxyCreator中的includePatterns来决定的
						if (!isEligibleBean(beanName)) {
							continue;
						}
						// We must be careful not to instantiate beans eagerly as in this case they
						// would be cached by the Spring container but would not have been weaved.
						// 获取对应beanName对应的bean的类型
						Class<?> beanType = this.beanFactory.getType(beanName, false);
						// 如果beanType为null的话，直接进行下一次循环
						if (beanType == null) {
							continue;
						}
						// 判断beanType是否是aspect，判断逻辑是beanType上标注了@Aspect注解且声明的字段中名称没有以ajc$开头的
						if (this.advisorFactory.isAspect(beanType)) {
							// 将beanName添加进aspectNames集合中
							aspectNames.add(beanName);
							// 根据beanName和beanType生成一个AspectMetadata对象
							AspectMetadata amd = new AspectMetadata(beanType, beanName);
							// 获取amd中ajType的perClauseKind，如果是SINGLETON的
							if (amd.getAjType().getPerClause().getKind() == PerClauseKind.SINGLETON) {
								// 根据beanFactory和beanName创建一个MetadataAwareAspectInstanceFactory
								MetadataAwareAspectInstanceFactory factory =
										new BeanFactoryAspectInstanceFactory(this.beanFactory, beanName);
								// 根据factory调用advisorFactory的getAdvisors方法
								List<Advisor> classAdvisors = this.advisorFactory.getAdvisors(factory);
								// 如果beanName对应的元素在beanFactory中是单例
								if (this.beanFactory.isSingleton(beanName)) {
									// 将解析出来的Advisor集合根据beanName缓存起来
									this.advisorsCache.put(beanName, classAdvisors);
								} else {
									// 否则只能将aspectInstanceFactory根据beanName缓存起来
									this.aspectFactoryCache.put(beanName, factory);
								}
								// 将该beanName解析出的advisor集合添加到advisors中
								advisors.addAll(classAdvisors);
							}
							// 如果perClauseKind不是单例
							else {
								// Per target or per this.
								// 但对应的beanName在beanFactory中是单例，报错
								if (this.beanFactory.isSingleton(beanName)) {
									throw new IllegalArgumentException("Bean with name '" + beanName +
											"' is a singleton, but aspect instantiation model is not singleton");
								}
								// 根据beanFactory和beanName创建一个PrototypeAspectInstanceFactory
								MetadataAwareAspectInstanceFactory factory =
										new PrototypeAspectInstanceFactory(this.beanFactory, beanName);
								// 将PrototypeAspectInstanceFactory根据beanName缓存起来
								this.aspectFactoryCache.put(beanName, factory);
								// 获取advisor添加到advisors集合中
								advisors.addAll(this.advisorFactory.getAdvisors(factory));
							}
						}
					}
					// 将aspectNames赋值给aspectBeanNames
					this.aspectBeanNames = aspectNames;
					// 然后返回advisors
					return advisors;
				}
			}
		}

		// 如果aspectNames为空的话，返回空集合
		if (aspectNames.isEmpty()) {
			return Collections.emptyList();
		}
		// 否则尝试从缓存中获取
		List<Advisor> advisors = new ArrayList<>();
		// 遍历aspectNames
		for (String aspectName : aspectNames) {
			// 获取单例aspect缓存的advisor集合
			List<Advisor> cachedAdvisors = this.advisorsCache.get(aspectName);
			// 如果集合不为null，添加到advisors中
			if (cachedAdvisors != null) {
				advisors.addAll(cachedAdvisors);
			}
			// 如果集合为null，说明aspectName对应的bean不是单例
			else {
				// 获取缓存的aspectInstanceFactory
				MetadataAwareAspectInstanceFactory factory = this.aspectFactoryCache.get(aspectName);
				// 调用advisorFactory的getAdvisors方法，根据factory获取到advisor并添加进advisors中
				advisors.addAll(this.advisorFactory.getAdvisors(factory));
			}
		}
		// 返回advisors
		return advisors;
	}

	/**
	 * Return whether the aspect bean with the given name is eligible.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
