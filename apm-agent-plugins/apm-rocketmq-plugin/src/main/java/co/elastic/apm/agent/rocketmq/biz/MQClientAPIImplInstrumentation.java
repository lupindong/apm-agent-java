/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.apm.agent.rocketmq.biz;

import co.elastic.apm.agent.rocketmq.AbstractRocketMQInstrumentation;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static co.elastic.apm.agent.bci.bytebuddy.CustomElementMatchers.classLoaderCanLoadClass;
import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * 商业版MQClientAPIImpl
 */
public class MQClientAPIImplInstrumentation extends AbstractRocketMQInstrumentation {

    @Override
    public ElementMatcher.Junction<ClassLoader> getClassLoaderMatcher() {
        return not(isBootstrapClassLoader()).and(classLoaderCanLoadClass("com.aliyun.openservices.ons.api.Producer"));
    }

    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return named("com.aliyun.openservices.shade.com.alibaba.rocketmq.client.impl.MQClientAPIImpl");
    }

    /**
     * {@link com.aliyun.openservices.shade.com.alibaba.rocketmq.client.impl.MQClientAPIImpl#sendMessage}
     */
    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return isPublic().and(
            named("sendMessage").and(takesArguments(12))
        );
    }

    @Override
    public String getAdviceClassName() {
        return "co.elastic.apm.agent.rocketmq.biz.advice.MessageSendAdvice";
    }

}
