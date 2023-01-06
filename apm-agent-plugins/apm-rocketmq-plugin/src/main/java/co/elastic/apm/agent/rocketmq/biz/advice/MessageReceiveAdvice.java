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
package co.elastic.apm.agent.rocketmq.biz.advice;

import co.elastic.apm.agent.rocketmq.biz.helper.RocketMQTraceHelper;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.MessageExt;
import net.bytebuddy.asm.Advice;

import javax.annotation.Nullable;
import java.util.List;

public class MessageReceiveAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageReceiveAdvice.class);

    public static final RocketMQTraceHelper HELPER = RocketMQTraceHelper.get();

    @Nullable
    @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
    public static void beforeMethod(@Advice.Argument(0) List<MessageExt> msgs) {
        LOGGER.info("[MessageReceiveAdvice.beforeMethod]msgs.size = {}, msgs ==> {}", msgs.size(), msgs);
        try {
            HELPER.onReceiveStart(msgs);
        } catch (Exception e) {
            // 忽略异常不处理，避免影响业务
            LOGGER.error(e.getMessage(), e);
        }
    }

    @Nullable
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
    public static void afterMethod(
        @Advice.Argument(0) List<MessageExt> msgs,
        @Advice.Return @Nullable ConsumeConcurrentlyStatus status,
        @Advice.Thrown final Throwable throwable) {

        LOGGER.info("[MessageReceiveAdvice.afterMethod]msgs.size = {}, msgs ==> {}", msgs.size(), msgs);
        try {
            HELPER.onReceiveEnd(msgs, status, throwable);
        } catch (Exception e) {
            // 忽略异常不处理，避免影响业务
            LOGGER.error(e.getMessage(), e);
        }
    }
}
