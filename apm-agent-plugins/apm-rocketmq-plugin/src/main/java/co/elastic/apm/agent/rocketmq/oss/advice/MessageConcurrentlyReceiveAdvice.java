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

package co.elastic.apm.agent.rocketmq.oss.advice;

import co.elastic.apm.agent.impl.transaction.Outcome;
import co.elastic.apm.agent.rocketmq.oss.helper.RocketMQTraceHelper;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import net.bytebuddy.asm.Advice;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;

import javax.annotation.Nullable;
import java.util.List;

public class MessageConcurrentlyReceiveAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageConcurrentlyReceiveAdvice.class);

    public static final RocketMQTraceHelper HELPER = RocketMQTraceHelper.get();

    @Nullable
    @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
    public static void beforeMethod(@Advice.Argument(0) List<MessageExt> msgs,
                                    @Advice.Argument(1) ConsumeConcurrentlyContext context) {
        try {
            if (msgs != null && !msgs.isEmpty()) {
                HELPER.onReceiveStart(msgs.get(0), context.getMessageQueue());
            }
        } catch (Exception e) {
            // 忽略异常不处理，避免影响业务
            LOGGER.error(e.getMessage(), e);
        }
    }

    @Nullable
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
    public static void afterMethod(
        @Advice.Return @Nullable ConsumeConcurrentlyStatus status,
        @Advice.Thrown final Throwable throwable) {

        try {
            Outcome outcome =
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS.equals(status) ? Outcome.SUCCESS : Outcome.FAILURE;
            HELPER.onReceiveEnd(outcome, throwable);
        } catch (Exception e) {
            // 忽略异常不处理，避免影响业务
            LOGGER.error(e.getMessage(), e);
        }
    }
}
