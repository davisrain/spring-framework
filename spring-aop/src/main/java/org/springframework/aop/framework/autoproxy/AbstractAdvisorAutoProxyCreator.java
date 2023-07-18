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

package org.springframework.aop.framework.autoproxy;

import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Generic auto proxy creator that builds AOP proxies for specific beans
 * based on detected Advisors for each bean.
 *
 * <p>Subclasses may override the {@link #findCandidateAdvisors()} method to
 * return a custom list of Advisors applying to any object. Subclasses can
 * also override the inherited {@link #shouldSkip} method to exclude certain
 * objects from auto-proxying.
 *
 * <p>Advisors or advices requiring ordering should be annotated with
 * {@link org.springframework.core.annotation.Order @Order} or implement the
 * {@link org.springframework.core.Ordered} interface. This class sorts
 * advisors using the {@link AnnotationAwareOrderComparator}. Advisors that are
 * not annotated with {@code @Order} or don't implement the {@code Ordered}
 * interface will be considered as unordered; they will appear at the end of the
 * advisor chain in an undefined order.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #findCandidateAdvisors
 */
@SuppressWarnings("serial")
public abstract class AbstractAdvisorAutoProxyCreator extends AbstractAutoProxyCreator {

	@Nullable
	private BeanFactoryAdvisorRetrievalHelper advisorRetrievalHelper;


	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		// 调用父类的setBeanFactory，将beanFactory设置进自身属性中
		super.setBeanFactory(beanFactory);
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AdvisorAutoProxyCreator requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		// 调用initBeanFactory
		initBeanFactory((ConfigurableListableBeanFactory) beanFactory);
	}

	protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// 根据beanFactory生成一个BeanFactoryAdvisorRetrievalHelperAdapter赋值给自身的advisorRetrievalHelper属性
		this.advisorRetrievalHelper = new BeanFactoryAdvisorRetrievalHelperAdapter(beanFactory);
	}


	@Override
	@Nullable
	protected Object[] getAdvicesAndAdvisorsForBean(
			Class<?> beanClass, String beanName, @Nullable TargetSource targetSource) {

		// 根据beanClass和beanName查找到合格的advisors
		List<Advisor> advisors = findEligibleAdvisors(beanClass, beanName);
		// 如果找到的合格的advisors为空的话，返回null
		if (advisors.isEmpty()) {
			return DO_NOT_PROXY;
		}
		// 否则将advisors转换为数组返回
		return advisors.toArray();
	}

	/**
	 * Find all eligible Advisors for auto-proxying this class.
	 * @param beanClass the clazz to find advisors for
	 * @param beanName the name of the currently proxied bean
	 * @return the empty List, not {@code null},
	 * if there are no pointcuts or interceptors
	 * @see #findCandidateAdvisors
	 * @see #sortAdvisors
	 * @see #extendAdvisors
	 */
	protected List<Advisor> findEligibleAdvisors(Class<?> beanClass, String beanName) {
		// 调用findCandidateAdvisors查找到所有候选的advisor
		List<Advisor> candidateAdvisors = findCandidateAdvisors();
		// 然后根据beanClass和beanName查找到可以应用给对应bean的那些advisor
		List<Advisor> eligibleAdvisors = findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName);
		// 扩展合格的advisor集合
		extendAdvisors(eligibleAdvisors);
		// 如果合格的advisor集合不为空，将其排序
		// 1.排序逻辑是先按照AnnotationAwareOrderComparator进行排序，比如InstantiationModelAwarePointcutAdvisorImpl就实现了Ordered接口，
		// 会调用它的getOrder方法，该方法的实现逻辑是调用持有的aspectInstanceFactory的getOrder方法，会根据aspectName查找beanFactory中对应的beanType，
		// 如果beanType实现了Ordered接口且是单例，调用getOrder方法返回order值，否则查看beanType上标注的@Order或@Priority接口的value属性，如果都没有找到，
		// 直接返回最低的优先级
		// 2.如果找到的优先级相同且是处于同一个切面内的，那么按照advisor的声明顺序来进行排序
		if (!eligibleAdvisors.isEmpty()) {
			// 优先级高的advisor在切入时先执行，在切出时后执行
			eligibleAdvisors = sortAdvisors(eligibleAdvisors);
		}
		// 返回合格的advisor集合
		return eligibleAdvisors;
	}

	/**
	 * Find all candidate Advisors to use in auto-proxying.
	 * @return the List of candidate Advisors
	 */
	protected List<Advisor> findCandidateAdvisors() {
		Assert.state(this.advisorRetrievalHelper != null, "No BeanFactoryAdvisorRetrievalHelper available");
		// 调用BeanFactoryAdvisorRetrievalHelper的findAdvisorBeans方法进行查找
		return this.advisorRetrievalHelper.findAdvisorBeans();
	}

	/**
	 * Search the given candidate Advisors to find all Advisors that
	 * can apply to the specified bean.
	 * @param candidateAdvisors the candidate Advisors
	 * @param beanClass the target's bean class
	 * @param beanName the target's bean name
	 * @return the List of applicable Advisors
	 * @see ProxyCreationContext#getCurrentProxiedBeanName()
	 */
	protected List<Advisor> findAdvisorsThatCanApply(
			List<Advisor> candidateAdvisors, Class<?> beanClass, String beanName) {

		// 将beanName添加到ProxyCreationContext的threadLocal中，表示正在创建的代理的beanName
		ProxyCreationContext.setCurrentProxiedBeanName(beanName);
		try {
			// 调用AopUtils的findAdvisorsThatCanApply方法获取能够应用给对应beanClass的advisors
			return AopUtils.findAdvisorsThatCanApply(candidateAdvisors, beanClass);
		}
		finally {
			// 将ProxyCreationContext中的threadLocal变量清除
			ProxyCreationContext.setCurrentProxiedBeanName(null);
		}
	}

	/**
	 * Return whether the Advisor bean with the given name is eligible
	 * for proxying in the first place.
	 * @param beanName the name of the Advisor bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleAdvisorBean(String beanName) {
		return true;
	}

	/**
	 * Sort advisors based on ordering. Subclasses may choose to override this
	 * method to customize the sorting strategy.
	 * @param advisors the source List of Advisors
	 * @return the sorted List of Advisors
	 * @see org.springframework.core.Ordered
	 * @see org.springframework.core.annotation.Order
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 */
	protected List<Advisor> sortAdvisors(List<Advisor> advisors) {
		AnnotationAwareOrderComparator.sort(advisors);
		return advisors;
	}

	/**
	 * Extension hook that subclasses can override to register additional Advisors,
	 * given the sorted Advisors obtained to date.
	 * <p>The default implementation is empty.
	 * <p>Typically used to add Advisors that expose contextual information
	 * required by some of the later advisors.
	 * @param candidateAdvisors the Advisors that have already been identified as
	 * applying to a given bean
	 */
	protected void extendAdvisors(List<Advisor> candidateAdvisors) {
	}

	/**
	 * This auto-proxy creator always returns pre-filtered Advisors.
	 */
	@Override
	protected boolean advisorsPreFiltered() {
		return true;
	}


	/**
	 * Subclass of BeanFactoryAdvisorRetrievalHelper that delegates to
	 * surrounding AbstractAdvisorAutoProxyCreator facilities.
	 */
	private class BeanFactoryAdvisorRetrievalHelperAdapter extends BeanFactoryAdvisorRetrievalHelper {

		public BeanFactoryAdvisorRetrievalHelperAdapter(ConfigurableListableBeanFactory beanFactory) {
			super(beanFactory);
		}

		@Override
		protected boolean isEligibleBean(String beanName) {
			return AbstractAdvisorAutoProxyCreator.this.isEligibleAdvisorBean(beanName);
		}
	}

}
