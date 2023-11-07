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

package org.springframework.transaction.annotation;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * Strategy implementation for parsing Spring's {@link Transactional} annotation.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
@SuppressWarnings("serial")
public class SpringTransactionAnnotationParser implements TransactionAnnotationParser, Serializable {

	@Override
	public boolean isCandidateClass(Class<?> targetClass) {
		// 判断目标类是否是@Transaction注解的候选类
		return AnnotationUtils.isCandidateClass(targetClass, Transactional.class);
	}

	@Override
	@Nullable
	public TransactionAttribute parseTransactionAnnotation(AnnotatedElement element) {
		// 查找element上标注的@Transactional注解的，并获取其属性
		AnnotationAttributes attributes = AnnotatedElementUtils.findMergedAnnotationAttributes(
				element, Transactional.class, false, false);
		// 如果注解属性不为null，解析其属性
		if (attributes != null) {
			return parseTransactionAnnotation(attributes);
		}
		// 如果element上不存在该注解，直接返回null
		else {
			return null;
		}
	}

	public TransactionAttribute parseTransactionAnnotation(Transactional ann) {
		return parseTransactionAnnotation(AnnotationUtils.getAnnotationAttributes(ann, false, false));
	}

	protected TransactionAttribute parseTransactionAnnotation(AnnotationAttributes attributes) {
		// 初始化一个RuleBasedTransactionAttribute -> DefaultTransactionAttribute -> DefaultTransactionDefinition
		RuleBasedTransactionAttribute rbta = new RuleBasedTransactionAttribute();

		// 获取注解的传播行为枚举
		Propagation propagation = attributes.getEnum("propagation");
		// 然后将枚举的value值设置进rbta的传播行为字段中
		rbta.setPropagationBehavior(propagation.value());
		// 获取注解的隔离级别枚举
		Isolation isolation = attributes.getEnum("isolation");
		// 将枚举的value值设置进rbta的隔离级别字段中
		rbta.setIsolationLevel(isolation.value());
		// 将timeout属性设置进rbta中
		rbta.setTimeout(attributes.getNumber("timeout").intValue());
		// 将readOnly属性设置进rbta中
		rbta.setReadOnly(attributes.getBoolean("readOnly"));
		// 将指定的transactionManager的name设置进rbta的qualifier中
		rbta.setQualifier(attributes.getString("value"));

		// 创建一个集合用于保存RollbackRuleAttribute
		List<RollbackRuleAttribute> rollbackRules = new ArrayList<>();
		// 获取注解的rollbackFor属性并且遍历
		for (Class<?> rbRule : attributes.getClassArray("rollbackFor")) {
			// 将异常类型封装成一个RollbackRuleAttribute添加到集合中
			rollbackRules.add(new RollbackRuleAttribute(rbRule));
		}
		// 获取注解的rollbackForClassName属性并且遍历
		for (String rbRule : attributes.getStringArray("rollbackForClassName")) {
			// 将异常类型的全限定名封装成一个RollbackRuleAttribute添加到集合
			rollbackRules.add(new RollbackRuleAttribute(rbRule));
		}
		// 同上面类似，不过获取的是noRollbackFor，即指定的不回滚的异常类型
		for (Class<?> rbRule : attributes.getClassArray("noRollbackFor")) {
			rollbackRules.add(new NoRollbackRuleAttribute(rbRule));
		}
		for (String rbRule : attributes.getStringArray("noRollbackForClassName")) {
			rollbackRules.add(new NoRollbackRuleAttribute(rbRule));
		}
		// 然后将RollbackRuleAttribute集合设置进RuleBasedTransactionAttribute中
		rbta.setRollbackRules(rollbackRules);

		// 然后返回rbta
		return rbta;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		return (this == other || other instanceof SpringTransactionAnnotationParser);
	}

	@Override
	public int hashCode() {
		return SpringTransactionAnnotationParser.class.hashCode();
	}

}
