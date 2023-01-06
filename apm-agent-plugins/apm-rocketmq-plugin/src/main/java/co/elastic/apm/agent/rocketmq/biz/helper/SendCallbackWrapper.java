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

import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.objectpool.Recyclable;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.SendCallback;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.SendResult;

import javax.annotation.Nullable;

public class SendCallbackWrapper implements SendCallback, Recyclable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SendCallbackWrapper.class);
    private final RocketMQTraceHelper helper;
    @Nullable
    private SendCallback delegate;
    @SuppressWarnings("NotNullFieldNotInitialized")
    private volatile Span span;

    SendCallbackWrapper(RocketMQTraceHelper helper) {
        this.helper = helper;
    }

    SendCallback wrap(@Nullable SendCallback delegate, Span span) {
        this.delegate = delegate;
        this.span = span;
        return this;
    }

    @Override
    public void onSuccess(SendResult sendResult) {
        try {
            span.activate();
            if (sendResult != null) {
                co.elastic.apm.agent.impl.context.Message apmMessage = span.getContext().getMessage();
                apmMessage.addHeader("sendStatus", sendResult.getSendStatus().name());
                apmMessage.addHeader("msgId", sendResult.getMsgId());
                apmMessage.addHeader("transactionId", sendResult.getTransactionId());
                apmMessage.addHeader("offsetMsgId", sendResult.getOffsetMsgId());
                apmMessage.addHeader("queueId", String.valueOf(sendResult.getMessageQueue().getQueueId()));
                apmMessage.addHeader("queueOffset", String.valueOf(sendResult.getQueueOffset()));
            }
            if (delegate != null) {
                delegate.onSuccess(sendResult);
            }
        } finally {
            span.deactivate().end();
            helper.recycle(this);
        }
    }

    @Override
    public void onException(Throwable e) {
        try {
            span.activate();
            if (delegate != null) {
                delegate.onException(e);
            }
        } finally {
            span.captureException(e);
            span.deactivate().end();
            helper.recycle(this);
        }
    }

    @Override
    public void resetState() {
        delegate = null;
        span = null;
    }
}
