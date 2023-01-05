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

import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.rocketmq.oss.helper.RocketMQTraceHelper;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import net.bytebuddy.asm.Advice;
import org.apache.rocketmq.client.impl.CommunicationMode;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.protocol.header.SendMessageRequestHeader;

import javax.annotation.Nullable;

public class MessageSendAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageSendAdvice.class);

    public static final RocketMQTraceHelper HELPER = RocketMQTraceHelper.get();

    @Nullable
    @Advice.AssignReturned.ToArguments(@Advice.AssignReturned.ToArguments.ToArgument(6))
    @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
    public static SendCallback beforeMethod(
        @Advice.Argument(0) String addr,
        @Advice.Argument(1) String brokerName,
        @Advice.Argument(2) Message message,
        @Advice.Argument(3) SendMessageRequestHeader requestHeader,
        @Advice.Argument(5) CommunicationMode communicationMode,
        @Advice.Argument(6) @Nullable SendCallback sendCallback) {

        try {
            Span span = HELPER.onSendStart(addr, brokerName, message, requestHeader.getProducerGroup(), communicationMode);
            if (span == null) {
                return sendCallback;
            }
            if (sendCallback != null) {
                return HELPER.wrapSendCallback(sendCallback, span);
            }
        } catch (Exception e) {
            // 忽略异常不处理，避免影响业务
            LOGGER.error(e.getMessage(), e);
        }
        return null;
    }

    @Nullable
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
    public static void afterMethod(
        @Advice.Argument(6) @Nullable SendCallback sendCallback,
        @Advice.Return @Nullable SendResult sendResult,
        @Advice.Thrown final Throwable throwable) {

        try {
            HELPER.onSendEnd(sendCallback, sendResult, throwable);
        } catch (Exception e) {
            // 忽略异常不处理，避免影响业务
            LOGGER.error(e.getMessage(), e);
        }
    }
}
