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
package co.elastic.apm.agent.rocketmq.helper;

import co.elastic.apm.agent.configuration.MessagingConfiguration;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.GlobalTracer;
import co.elastic.apm.agent.impl.context.ServiceTarget;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.matcher.WildcardMatcher;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.protocol.header.SendMessageRequestHeader;

import javax.annotation.Nullable;
import java.util.Map;

public class RocketMQTraceHelper {

    public static final Logger LOGGER = LoggerFactory.getLogger(RocketMQTraceHelper.class);
    private static final RocketMQTraceHelper INSTANCE = new RocketMQTraceHelper(GlobalTracer.requireTracerImpl());
    private static final String COLON = ":";
    private final ElasticApmTracer tracer;
    private final MessagingConfiguration messagingConfiguration;

    public static RocketMQTraceHelper get() {
        return INSTANCE;
    }

    public RocketMQTraceHelper(ElasticApmTracer tracer) {
        this.tracer = tracer;
        messagingConfiguration = tracer.getConfig(MessagingConfiguration.class);
    }

    @Nullable
    public Span onSendStart(String addr, String brokerName, Message message, SendMessageRequestHeader requestHeader) {
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
            .withName("Send To ")
            .appendToName(topic);

        co.elastic.apm.agent.impl.context.Message apmMessage = span.getContext().getMessage().withQueue(topic);
        if (message.getProperties() != null) {
            for (Map.Entry<String, String> entry : message.getProperties().entrySet()) {
                apmMessage.addHeader(entry.getKey(), entry.getValue());
            }
        }

        ServiceTarget serviceTarget = span.getContext().getServiceTarget().withType("RocketMQ").withName(brokerName);
        if (StringUtils.isNotBlank(addr) && addr.contains(COLON)) {
            String[] split = addr.split(COLON);
            serviceTarget.withHostPortName(split[0], Integer.parseInt(split[1]));
        }

        span.activate();
        return span;
    }

    private boolean ignoreTopic(String topicName) {
        return WildcardMatcher.isAnyMatch(messagingConfiguration.getIgnoreMessageQueues(), topicName);
    }

    public void onSendEnd(Message message, Object spanObj, Throwable throwable) {
        final Span span = this.tracer.getActiveExitSpan();
        if (span == null) {
            return;
        }
        if (messagingConfiguration.shouldCollectQueueAddress()) {
            // TODO
        }

        span.captureException(throwable);
        span.deactivate();
        span.end();
    }
}
