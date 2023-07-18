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

import java.util.Comparator;

import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AspectJAopUtils;
import org.springframework.aop.aspectj.AspectJPrecedenceInformation;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;

/**
 * Orders AspectJ advice/advisors by precedence (<i>not</i> invocation order).
 *
 * <p>Given two pieces of advice, {@code A} and {@code B}:
 * <ul>
 * <li>If {@code A} and {@code B} are defined in different aspects, then the advice
 * in the aspect with the lowest order value has the highest precedence.</li>
 * <li>If {@code A} and {@code B} are defined in the same aspect, if one of
 * {@code A} or {@code B} is a form of <em>after</em> advice, then the advice declared
 * last in the aspect has the highest precedence. If neither {@code A} nor {@code B}
 * is a form of <em>after</em> advice, then the advice declared first in the aspect
 * has the highest precedence.</li>
 * </ul>
 *
 * <p>Important: This comparator is used with AspectJ's
 * {@link org.aspectj.util.PartialOrder PartialOrder} sorting utility. Thus, unlike
 * a normal {@link Comparator}, a return value of {@code 0} from this comparator
 * means we don't care about the ordering, not that the two elements must be sorted
 * identically.
 *
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @since 2.0
 */
class AspectJPrecedenceComparator implements Comparator<Advisor> {

	private static final int HIGHER_PRECEDENCE = -1;

	private static final int SAME_PRECEDENCE = 0;

	private static final int LOWER_PRECEDENCE = 1;


	private final Comparator<? super Advisor> advisorComparator;


	/**
	 * Create a default {@code AspectJPrecedenceComparator}.
	 */
	public AspectJPrecedenceComparator() {
		this.advisorComparator = AnnotationAwareOrderComparator.INSTANCE;
	}

	/**
	 * Create an {@code AspectJPrecedenceComparator}, using the given {@link Comparator}
	 * for comparing {@link org.springframework.aop.Advisor} instances.
	 * @param advisorComparator the {@code Comparator} to use for advisors
	 */
	public AspectJPrecedenceComparator(Comparator<? super Advisor> advisorComparator) {
		Assert.notNull(advisorComparator, "Advisor comparator must not be null");
		this.advisorComparator = advisorComparator;
	}


	@Override
	public int compare(Advisor o1, Advisor o2) {
		// 首先使用AnnotationAwareOrderComparator对两个advisor进行比较
		int advisorPrecedence = this.advisorComparator.compare(o1, o2);
		// 如果发现它们的优先级相同 并且 它们是声明在同一个切面里的
		if (advisorPrecedence == SAME_PRECEDENCE && declaredInSameAspect(o1, o2)) {
			// 在切面中比较它们的优先级
			advisorPrecedence = comparePrecedenceWithinAspect(o1, o2);
		}
		// 返回比较结果
		return advisorPrecedence;
	}

	private int comparePrecedenceWithinAspect(Advisor advisor1, Advisor advisor2) {
		// 如果两个advisor之中存在一个AfterAdvice，将该标志置为true
		boolean oneOrOtherIsAfterAdvice =
				(AspectJAopUtils.isAfterAdvice(advisor1) || AspectJAopUtils.isAfterAdvice(advisor2));
		// 用advisor1的declarationOrder减去advisor2的declarationOrder，等到它们的声明的顺序差
		int adviceDeclarationOrderDelta = getAspectDeclarationOrder(advisor1) - getAspectDeclarationOrder(advisor2);

		// 如果二者之间存在AfterAdvice，那么声明在后面的有更高的优先级
		if (oneOrOtherIsAfterAdvice) {
			// the advice declared last has higher precedence
			// 因为advice1声明在advice2前面，因此advice1有更低的优先级
			if (adviceDeclarationOrderDelta < 0) {
				// advice1 was declared before advice2
				// so advice1 has lower precedence
				return LOWER_PRECEDENCE;
			}
			// 如果它们的声明顺序的差为0，则有相同的优先级
			else if (adviceDeclarationOrderDelta == 0) {
				return SAME_PRECEDENCE;
			}
			// 如果advice1声明在advice2的后面，那么advice1有更高的优先级
			else {
				return HIGHER_PRECEDENCE;
			}
		}
		// 如果二者之间不存在AfterAdvice，那么声明在前面的有更高的优先级
		else {
			// the advice declared first has higher precedence
			if (adviceDeclarationOrderDelta < 0) {
				// advice1 was declared before advice2
				// so advice1 has higher precedence
				return HIGHER_PRECEDENCE;
			}
			else if (adviceDeclarationOrderDelta == 0) {
				return SAME_PRECEDENCE;
			}
			else {
				return LOWER_PRECEDENCE;
			}
		}
	}

	private boolean declaredInSameAspect(Advisor advisor1, Advisor advisor2) {
		// 如果两个advisor都存在aspectName且它们的aspectName相等，那么说明它们是声明在相同切面里面的
		return (hasAspectName(advisor1) && hasAspectName(advisor2) &&
				getAspectName(advisor1).equals(getAspectName(advisor2)));
	}

	private boolean hasAspectName(Advisor advisor) {
		// 如果advisor是属于AspectJPrecedenceInformation类型的 或者 其持有的advice是属于AspectJPrecedenceInformation类型的。
		// 说明它们存在aspectName
		return (advisor instanceof AspectJPrecedenceInformation ||
				advisor.getAdvice() instanceof AspectJPrecedenceInformation);
	}

	// pre-condition is that hasAspectName returned true
	private String getAspectName(Advisor advisor) {
		AspectJPrecedenceInformation precedenceInfo = AspectJAopUtils.getAspectJPrecedenceInformationFor(advisor);
		Assert.state(precedenceInfo != null, () -> "Unresolvable AspectJPrecedenceInformation for " + advisor);
		return precedenceInfo.getAspectName();
	}

	private int getAspectDeclarationOrder(Advisor advisor) {
		AspectJPrecedenceInformation precedenceInfo = AspectJAopUtils.getAspectJPrecedenceInformationFor(advisor);
		return (precedenceInfo != null ? precedenceInfo.getDeclarationOrder() : 0);
	}

}
