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

package co.elastic.apm.agent.rocketmq.oss.helper;

import co.elastic.apm.agent.configuration.MessagingConfiguration;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.GlobalTracer;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Outcome;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.matcher.WildcardMatcher;
import co.elastic.apm.agent.objectpool.Allocator;
import co.elastic.apm.agent.objectpool.ObjectPool;
import co.elastic.apm.agent.objectpool.impl.QueueBasedObjectPool;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import org.apache.rocketmq.client.impl.CommunicationMode;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.jctools.queues.atomic.AtomicQueueFactory;

import javax.annotation.Nullable;
import java.util.Map;

import static org.jctools.queues.spec.ConcurrentQueueSpec.createBoundedMpmc;

public class RocketMQTraceHelper {

    public static final Logger LOGGER = LoggerFactory.getLogger(RocketMQTraceHelper.class);
    private static final RocketMQTraceHelper INSTANCE = new RocketMQTraceHelper(GlobalTracer.requireTracerImpl());
    private final ObjectPool<SendCallbackWrapper> sendCallbackWrapperObjectPool;
    private final ElasticApmTracer tracer;
    private final MessagingConfiguration messagingConfiguration;

    public static RocketMQTraceHelper get() {
        return INSTANCE;
    }

    public RocketMQTraceHelper(ElasticApmTracer tracer) {
        this.tracer = tracer;
        messagingConfiguration = tracer.getConfig(MessagingConfiguration.class);
        this.sendCallbackWrapperObjectPool = QueueBasedObjectPool.ofRecyclable(
            AtomicQueueFactory.newQueue(
                createBoundedMpmc(256)),
            false,
            new SendCallbackWrapperAllocator()
        );
    }


    private final class SendCallbackWrapperAllocator implements Allocator<SendCallbackWrapper> {
        @Override
        public SendCallbackWrapper createInstance() {
            return new SendCallbackWrapper(RocketMQTraceHelper.this);
        }
    }

    @Nullable
    public SendCallback wrapSendCallback(@Nullable SendCallback callback, Span span) {
        if (callback instanceof SendCallbackWrapper) {
            return callback;
        }
        try {
            return sendCallbackWrapperObjectPool.createInstance().wrap(callback, span);
        } catch (Throwable throwable) {
            LOGGER.error("Failed to wrap RocketMQ send callback", throwable);
            return callback;
        }
    }

    void recycle(SendCallbackWrapper sendCallbackWrapper) {
        this.sendCallbackWrapperObjectPool.recycle(sendCallbackWrapper);
    }

    /**
     * 发送开始
     *
     * @param brokerName
     * @param message
     * @param group
     * @param mode
     * @return
     */
    @Nullable
    public Span onSendStart(String brokerName, Message message, String group, CommunicationMode mode) {
        String topic = message.getTopic();
        if (ignoreTopic(topic)) {
            return null;
        }

        final Span span = this.tracer.createExitChildSpan();
        if (span == null) {
            return null;
        }

        span.withType("messaging")
            .withSubtype("RocketMQ")
            .withAction("send")
            .withName(mode.name() + " Send To ")
            .appendToName(topic);

        span.getContext().getServiceTarget().withName(brokerName).withNameOnlyDestinationResource();
        co.elastic.apm.agent.impl.context.Message apmMessage = span.getContext().getMessage().withQueue(topic);
        setMsgHeader(message.getProperties(), apmMessage);
        apmMessage.addHeader("group", group);

        span.activate();
        return span;
    }

    private boolean ignoreTopic(String topicName) {
        return WildcardMatcher.isAnyMatch(messagingConfiguration.getIgnoreMessageQueues(), topicName);
    }

    /**
     * 发送结束
     *
     * @param sendCallback
     * @param sendResult
     * @param throwable
     */
    public void onSendEnd(SendCallback sendCallback, SendResult sendResult, Throwable throwable) {
        final Span span = this.tracer.getActiveExitSpan();
        if (span == null) {
            return;
        }
        span.captureException(throwable);
        if (sendResult != null) {
            co.elastic.apm.agent.impl.context.Message apmMessage = span.getContext().getMessage();
            apmMessage.addHeader("sendStatus", sendResult.getSendStatus().name());
            apmMessage.addHeader("transactionId", sendResult.getTransactionId());
            apmMessage.addHeader("offsetMsgId", sendResult.getOffsetMsgId());
            apmMessage.addHeader("queueId", String.valueOf(sendResult.getMessageQueue().getQueueId()));
            apmMessage.addHeader("queueOffset", String.valueOf(sendResult.getQueueOffset()));
        }
        if (sendCallback != null) {
            // Not ending here, ending in the callback
            span.deactivate();
        } else {
            span.deactivate().end();
        }

    }


    @Nullable
    public void onReceiveStart(MessageExt messageExt, MessageQueue messageQueue) {
        Transaction transaction = this.tracer.startRootTransaction(MessageExt.class.getClassLoader());
        if (transaction != null) {
            String topic = messageQueue.getTopic();
            transaction.withType(Transaction.TYPE_REQUEST);
            transaction.withName("Receive from " + topic);
            transaction.setFrameworkName("RocketMQ");
            transaction.setFrameworkVersion(MQVersion.getVersionDesc(MQVersion.CURRENT_VERSION));
            transaction.getContext().getServiceOrigin().withName(messageQueue.getBrokerName());

            co.elastic.apm.agent.impl.context.Message apmMessage =
                transaction.getContext().getMessage().withQueue(topic);
            setMsgHeader(messageExt.getProperties(), apmMessage);
            apmMessage.addHeader("queueId", String.valueOf(messageQueue.getQueueId()));

            transaction.activate();
        }
    }

    private static void setMsgHeader(Map<String, String> properties,
                                     co.elastic.apm.agent.impl.context.Message apmMessage) {
        if (properties != null && !properties.isEmpty()) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                apmMessage.addHeader(entry.getKey(), entry.getValue());
            }
        }
    }

    public void onReceiveEnd(Outcome outcome, Throwable throwable) {
        final AbstractSpan<?> activeSpan = this.tracer.getActive();
        if (activeSpan != null) {
            activeSpan.withOutcome(outcome);
            activeSpan.captureException(throwable);
            activeSpan.deactivate().end();
        }

    }
}
