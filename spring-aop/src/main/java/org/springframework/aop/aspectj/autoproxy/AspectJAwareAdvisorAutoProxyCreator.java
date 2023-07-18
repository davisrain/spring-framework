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

package org.springframework.aop.aspectj.autoproxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aspectj.util.PartialOrder;
import org.aspectj.util.PartialOrder.PartialComparable;

import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.aspectj.AspectJPointcutAdvisor;
import org.springframework.aop.aspectj.AspectJProxyUtils;
import org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * {@link org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator}
 * subclass that exposes AspectJ's invocation context and understands AspectJ's rules
 * for advice precedence when multiple pieces of advice come from the same aspect.
 *
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @since 2.0
 */
@SuppressWarnings("serial")
public class AspectJAwareAdvisorAutoProxyCreator extends AbstractAdvisorAutoProxyCreator {

	private static final Comparator<Advisor> DEFAULT_PRECEDENCE_COMPARATOR = new AspectJPrecedenceComparator();


	/**
	 * Sort the supplied {@link Advisor} instances according to AspectJ precedence.
	 * <p>If two pieces of advice come from the same aspect, they will have the same
	 * order. Advice from the same aspect is then further ordered according to the
	 * following rules:
	 * <ul>
	 * <li>If either of the pair is <em>after</em> advice, then the advice declared
	 * last gets highest precedence (i.e., runs last).</li>
	 * <li>Otherwise the advice declared first gets highest precedence (i.e., runs
	 * first).</li>
	 * </ul>
	 * <p><b>Important:</b> Advisors are sorted in precedence order, from highest
	 * precedence to lowest. "On the way in" to a join point, the highest precedence
	 * advisor should run first. "On the way out" of a join point, the highest
	 * precedence advisor should run last.
	 */
	@Override
	protected List<Advisor> sortAdvisors(List<Advisor> advisors) {
		// 创建一个list用于存储PartiallyComparableAdvisorHolder类型的元素
		List<PartiallyComparableAdvisorHolder> partiallyComparableAdvisors = new ArrayList<>(advisors.size());
		// 遍历advisors
		for (Advisor advisor : advisors) {
			// 将advisor都封装成PartiallyComparableAdvisorHolder类型的对象并添加进list中。
			// PartiallyComparableAdvisorHolder持有了一个advisor和一个comparator，其compareTo方法就是使用comparator对两个advisor进行比较。
			// 传入的comparator是DEFAULT_PRECEDENCE_COMPARATOR
			partiallyComparableAdvisors.add(
					new PartiallyComparableAdvisorHolder(advisor, DEFAULT_PRECEDENCE_COMPARATOR));
		}
		// 调用PartialOrder的sort方法对partiallyComparableAdvisors集合进行排序
		List<PartiallyComparableAdvisorHolder> sorted = PartialOrder.sort(partiallyComparableAdvisors);
		if (sorted != null) {
			List<Advisor> result = new ArrayList<>(advisors.size());
			for (PartiallyComparableAdvisorHolder pcAdvisor : sorted) {
				result.add(pcAdvisor.getAdvisor());
			}
			return result;
		}
		else {
			return super.sortAdvisors(advisors);
		}
	}

	/**
	 * Add an {@link ExposeInvocationInterceptor} to the beginning of the advice chain.
	 * <p>This additional advice is needed when using AspectJ pointcut expressions
	 * and when using AspectJ-style advice.
	 */
	@Override
	protected void extendAdvisors(List<Advisor> candidateAdvisors) {
		AspectJProxyUtils.makeAdvisorChainAspectJCapableIfNecessary(candidateAdvisors);
	}

	@Override
	protected boolean shouldSkip(Class<?> beanClass, String beanName) {
		// TODO: Consider optimization by caching the list of the aspect names
		// 查找到候选的advisor集合
		// 1.首先调用父类AbstractAdvisorAutoProxyCreator中的BeanFactoryAdvisorRetrievalHelper查找beanFactory中类型是Advisor的bean
		// 2.然后通过子类AnnotationAwareAspectJAutoProxyCreator中的BeanFactoryAspectJAdvisorBuilder去查找beanFactory中所有标注了@Aspect注解的bean，
		// 然后解析beanClass中声明的标注了@AspectJ相关注解的方法，将其解析为Advisor
		List<Advisor> candidateAdvisors = findCandidateAdvisors();
		// 遍历这些候选的Advisor
		for (Advisor advisor : candidateAdvisors) {
			// 如果advisor是AspectJPointcutAdvisor类型的 并且 advisor的aspectName就等于beanName，那么该beanName需要被跳过
			if (advisor instanceof AspectJPointcutAdvisor &&
					((AspectJPointcutAdvisor) advisor).getAspectName().equals(beanName)) {
				return true;
			}
		}
		// 如果上述步骤没有返回，调用父类的shouldSkip方法进行判断
		return super.shouldSkip(beanClass, beanName);
	}


	/**
	 * Implements AspectJ's {@link PartialComparable} interface for defining partial orderings.
	 */
	private static class PartiallyComparableAdvisorHolder implements PartialComparable {

		private final Advisor advisor;

		private final Comparator<Advisor> comparator;

		public PartiallyComparableAdvisorHolder(Advisor advisor, Comparator<Advisor> comparator) {
			this.advisor = advisor;
			this.comparator = comparator;
		}

		@Override
		public int compareTo(Object obj) {
			Advisor otherAdvisor = ((PartiallyComparableAdvisorHolder) obj).advisor;
			return this.comparator.compare(this.advisor, otherAdvisor);
		}

		@Override
		public int fallbackCompareTo(Object obj) {
			return 0;
		}

		public Advisor getAdvisor() {
			return this.advisor;
		}

		@Override
		public String toString() {
			Advice advice = this.advisor.getAdvice();
			StringBuilder sb = new StringBuilder(ClassUtils.getShortName(advice.getClass()));
			boolean appended = false;
			if (this.advisor instanceof Ordered) {
				sb.append(": order = ").append(((Ordered) this.advisor).getOrder());
				appended = true;
			}
			if (advice instanceof AbstractAspectJAdvice) {
				sb.append(!appended ? ": " : ", ");
				AbstractAspectJAdvice ajAdvice = (AbstractAspectJAdvice) advice;
				sb.append("aspect name = ");
				sb.append(ajAdvice.getAspectName());
				sb.append(", declaration order = ");
				sb.append(ajAdvice.getDeclarationOrder());
			}
			return sb.toString();
		}
	}

}
