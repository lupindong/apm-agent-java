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
package co.elastic.apm.agent.rocketmq.biz.helper;

import co.elastic.apm.agent.configuration.MessagingConfiguration;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.GlobalTracer;
import co.elastic.apm.agent.impl.context.ServiceTarget;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.matcher.WildcardMatcher;
import co.elastic.apm.agent.objectpool.Allocator;
import co.elastic.apm.agent.objectpool.ObjectPool;
import co.elastic.apm.agent.objectpool.impl.QueueBasedObjectPool;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.impl.CommunicationMode;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.SendCallback;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.SendResult;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.Message;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.MessageExt;
import org.apache.commons.lang3.StringUtils;
import org.jctools.queues.atomic.AtomicQueueFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.jctools.queues.spec.ConcurrentQueueSpec.createBoundedMpmc;

public class RocketMQTraceHelper {

    public static final Logger LOGGER = LoggerFactory.getLogger(RocketMQTraceHelper.class);
    private static final RocketMQTraceHelper INSTANCE = new RocketMQTraceHelper(GlobalTracer.requireTracerImpl());
    private final ObjectPool<SendCallbackWrapper> sendCallbackWrapperObjectPool;
    private final ElasticApmTracer tracer;
    private final MessagingConfiguration messagingConfiguration;

    private final List<String> ignoreKey = new ArrayList<>();

    public static RocketMQTraceHelper get() {
        return INSTANCE;
    }

    public RocketMQTraceHelper(ElasticApmTracer tracer) {
        this.tracer = tracer;
        messagingConfiguration = tracer.getConfig(MessagingConfiguration.class);
        this.sendCallbackWrapperObjectPool = QueueBasedObjectPool.ofRecyclable(
            AtomicQueueFactory.<SendCallbackWrapper>newQueue(
                createBoundedMpmc(256)),
            false,
            new SendCallbackWrapperAllocator()
        );

        ignoreKey.add("ID");
        ignoreKey.add("CONTENTTYPE");
        ignoreKey.add("UNIQ_KEY");
        ignoreKey.add("INSTANCE_ID");
        ignoreKey.add("WAIT");
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
     * @param addr
     * @param brokerName
     * @param message
     * @param producerGroup
     * @param communicationMode
     * @return
     */
    @Nullable
    public Span onSendStart(String addr, String brokerName, Message message, String producerGroup,
                            CommunicationMode communicationMode) {
        Map<String, String> properties = message.getProperties() != null
            ? message.getProperties() : new HashMap<String, String>(1);
        String instanceId = properties.getOrDefault("INSTANCE_ID", "UNKNOWN");
        String sourceTopic = message.getTopic();
        String topic = sourceTopic.startsWith(instanceId) ? sourceTopic.substring(instanceId.length() + 1) : sourceTopic;

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
            .withName(communicationMode.name() + " Send To ")
            .appendToName(topic);

        co.elastic.apm.agent.impl.context.Message apmMessage = span.getContext().getMessage().withQueue(topic);

        producerGroup = producerGroup.startsWith(producerGroup) ? producerGroup.substring(instanceId.length() + 1) : producerGroup;
        apmMessage.addHeader("producerGroup", producerGroup);

        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (!ignoreKey.contains(key.toUpperCase())) {
                apmMessage.addHeader(key, value);
            } else if ("UNIQ_KEY".equals(key)) {
                apmMessage.addHeader("msgId", value);
            }
        }

        String uniqKey = properties.get("UNIQ_KEY");
        if (StringUtils.isNotBlank(uniqKey)) {
            apmMessage.addHeader("msgId", uniqKey);
        }

        if (!"UNKNOWN".equals(StringUtils.isNotBlank(instanceId))) {
            apmMessage.addHeader("instanceId", instanceId);
        }

        ServiceTarget serviceTarget = span.getContext().getServiceTarget().withType("RocketMQ").withName(brokerName);
        if (StringUtils.isNotBlank(addr) && addr.contains(":")) {
            String[] split = addr.split(":");
            serviceTarget.withHostPortName(split[0], Integer.parseInt(split[1]));
        }

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
        try {
            if (sendResult != null) {
                co.elastic.apm.agent.impl.context.Message apmMessage = span.getContext().getMessage();
                apmMessage.addHeader("sendStatus", sendResult.getSendStatus().name());
                apmMessage.addHeader("msgId", sendResult.getMsgId());
                apmMessage.addHeader("transactionId", sendResult.getTransactionId());
                apmMessage.addHeader("offsetMsgId", sendResult.getOffsetMsgId());
                apmMessage.addHeader("queueId", String.valueOf(sendResult.getMessageQueue().getQueueId()));
                apmMessage.addHeader("queueOffset", String.valueOf(sendResult.getQueueOffset()));
            }
        } finally {
            span.captureException(throwable);
            if (sendCallback != null) {
                // Not ending here, ending in the callback
                span.deactivate();
            } else {
                span.deactivate().end();
            }
        }
    }


    @Nullable
    public void onReceiveStart(List<MessageExt> msgs) {
        final AbstractSpan<?> activeSpan = tracer.getActive();
        if (activeSpan == null) {
            LOGGER.warn("[onReceiveStart]activeSpan == null");
            return;
        }

        Span span = activeSpan.createExitSpan();
        if (span == null) {
            LOGGER.warn("[onReceiveStart]createExitSpan == null");
            return;
        }

        String topic = msgs.get(0).getTopic();

        span.withType("messaging")
            .withSubtype("RocketMQ")
            .withAction("receive")
            .withName("Receive from " + topic, AbstractSpan.PRIO_HIGH_LEVEL_FRAMEWORK);

        span.getContext().getServiceTarget().withType("RocketMQ");

        span.activate();
    }

    public void onReceiveEnd(List<MessageExt> msgs, ConsumeConcurrentlyStatus status, Throwable throwable) {
        Span span = tracer.getActiveSpan();
        if (span != null && "RocketMQ".equals(span.getSubtype()) && "receive".equals(span.getAction())) {
            if (ConsumeConcurrentlyStatus.RECONSUME_LATER.equals(status)) {
                span.captureException(throwable);
            }
            span.deactivate().end();
        }
    }
}
