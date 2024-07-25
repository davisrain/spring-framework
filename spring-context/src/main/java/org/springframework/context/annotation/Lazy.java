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

package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates whether a bean is to be lazily initialized.
 * 表示一个bean是否需要被懒初始化
 *
 * <p>May be used on any class directly or indirectly annotated with {@link
 * org.springframework.stereotype.Component @Component} or on methods annotated with
 * {@link Bean @Bean}.
 * 可能会被直接地标注在类上 或者 间接地同@Component一起标注 或者 标注在@Bean注解的方法上
 *
 * <p>If this annotation is not present on a {@code @Component} or {@code @Bean} definition,
 * eager initialization will occur. If present and set to {@code true}, the {@code @Bean} or
 * {@code @Component} will not be initialized until referenced by another bean or explicitly
 * retrieved from the enclosing {@link org.springframework.beans.factory.BeanFactory
 * BeanFactory}. If present and set to {@code false}, the bean will be instantiated on
 * startup by bean factories that perform eager initialization of singletons.
 *
 * 如果这个注解不在@Component或者@Bean的beanDefinition上，那么急切初始化将会发生。
 * 如果存在并且value设置为true，那么对应的bean不会初始化直到被其他bean引用或者 显示地从BeanFactory中查找。
 * 如果存在并且value设置为false，那么bean将会在启动阶段被实例化，并且会被急切的初始化
 *
 * <p>If Lazy is present on a {@link Configuration @Configuration} class, this
 * indicates that all {@code @Bean} methods within that {@code @Configuration}
 * should be lazily initialized. If {@code @Lazy} is present and false on a {@code @Bean}
 * method within a {@code @Lazy}-annotated {@code @Configuration} class, this indicates
 * overriding the 'default lazy' behavior and that the bean should be eagerly initialized.
 *
 * 如果@Lazy注解出现在了@Configuration标注的类上，这表示类中所有标注了@Bean的方法都应该被懒初始化。
 * 如果Configuration类中其中一个@Bean方法上@Lazy存在但是value为false，那么该方法上的@Lazy注解会重写默认的lazy行为，该@Bean方法定义的bean是会急切初始化的
 *
 * <p>In addition to its role for component initialization, this annotation may also be placed
 * on injection points marked with {@link org.springframework.beans.factory.annotation.Autowired}
 * or {@link javax.inject.Inject}: In that context, it leads to the creation of a
 * lazy-resolution proxy for all affected dependencies, as an alternative to using
 * {@link org.springframework.beans.factory.ObjectFactory} or {@link javax.inject.Provider}.
 * Please note that such a lazy-resolution proxy will always be injected; if the target
 * dependency does not exist, you will only be able to find out through an exception on
 * invocation. As a consequence, such an injection point results in unintuitive behavior
 * for optional dependencies. For a programmatic equivalent, allowing for lazy references
 * with more sophistication, consider {@link org.springframework.beans.factory.ObjectProvider}.
 *
 * 除了用于component的初始化，这个注解也可以被放在注入点的位置（即被@Autowired 或者 @Inject 标注的地方，或者在以构造器注入时在需要被注入的参数上）
 * 在这个上下文中，它会导致为所有受影响的依赖项创建一个延迟解析代理，作为ObjectFactory或者Provider的替代来使用。
 * 需要注意的是这样的一个延迟解析代理将总是会被注入，如果这个目标依赖不存在的话，你将仅能够通过在调用时抛出的一个异常来感知。
 * 因此，这样的注入点会导致可选依赖的不直观行为。对于编程等效来说，允许更复杂的懒引用行为，考虑使用ObjectProvider
 *
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.0
 * @see Primary
 * @see Bean
 * @see Configuration
 * @see org.springframework.stereotype.Component
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Lazy {

	/**
	 * Whether lazy initialization should occur.
	 */
	boolean value() default true;

}
