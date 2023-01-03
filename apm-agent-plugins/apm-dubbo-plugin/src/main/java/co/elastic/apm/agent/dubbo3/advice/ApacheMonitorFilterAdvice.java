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

package co.elastic.apm.agent.dubbo3.advice;

import co.elastic.apm.agent.dubbo3.helper.ApacheDubboTextMapPropagator;
import co.elastic.apm.agent.dubbo3.helper.DubboTraceHelper;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.GlobalTracer;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Outcome;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.Transaction;
import net.bytebuddy.asm.Advice;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.*;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

public class ApacheMonitorFilterAdvice {

    private static final ElasticApmTracer tracer = GlobalTracer.requireTracerImpl();

    @Nullable
    @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
    public static Object onEnterFilterInvoke(@Advice.Argument(1) Invocation invocation) {

        RpcServiceContext serviceContext = RpcContext.getServiceContext();
        Class<?> apiClass = invocation.getInvoker().getInterface();
        String methodName = invocation.getMethodName();

        AbstractSpan<?> activeSpan = tracer.getActive();

        // for consumer side, just create span, more information will be collected in provider side
        if (serviceContext.isConsumerSide() && activeSpan != null) {
            URL url = serviceContext.getUrl();
            InetSocketAddress socketAddress = new InetSocketAddress(url.getAddress(), url.getPort());
            Span span = DubboTraceHelper.createConsumerSpan(tracer, apiClass, methodName, socketAddress);
            if (span != null) {
                span.propagateTraceContext(serviceContext, ApacheDubboTextMapPropagator.INSTANCE);
                return span;
            }

            // for provider side
        } else if (serviceContext.isProviderSide() && activeSpan == null) {
            Transaction transaction = tracer.startChildTransaction(serviceContext, ApacheDubboTextMapPropagator.INSTANCE, Invocation.class.getClassLoader());
            if (transaction != null) {
                transaction.activate();
                DubboTraceHelper.fillTransaction(transaction, apiClass, methodName);
                return transaction;
            }
        }

        return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
    public static void onExitFilterInvoke(@Advice.Argument(1) Invocation invocation,
                                          @Advice.Return @Nullable Result result,
                                          @Advice.Enter @Nullable final Object spanObj,
                                          @Advice.Thrown @Nullable Throwable t) {

        AbstractSpan<?> span = (AbstractSpan<?>) spanObj;
        if (span == null) {
            return;
        }

        span.deactivate();
        if (result instanceof AsyncRpcResult) {
            RpcContext.getServiceContext().setObjectAttachment(DubboTraceHelper.SPAN_KEY, span);
            result.whenCompleteWithContext(AsyncCallback.INSTANCE);
        } else {
            try {
                handleException(result, t, span);
            } finally {
                span.end();
            }
        }
    }

    public static class AsyncCallback implements BiConsumer<Result, Throwable> {

        private static final BiConsumer<Result, Throwable> INSTANCE = new AsyncCallback();

        @Override
        public void accept(@Nullable Result result, @Nullable Throwable t) {
            AbstractSpan<?> span =
                (AbstractSpan<?>) RpcContext.getServiceContext().getObjectAttachment(DubboTraceHelper.SPAN_KEY);
            if (span != null) {
                try {
                    RpcContext.getServiceContext().removeAttachment(DubboTraceHelper.SPAN_KEY);
                    handleException(result, t, span);
                } finally {
                    span.end();
                }
            }
        }
    }

    private static void handleException(Result result, Throwable t, AbstractSpan<?> span) {
        Throwable resultException = null;
        if (result != null) {
            resultException = result.getException();
        }

        span.captureException(resultException != null ? resultException : t)
            .withOutcome(t != null || resultException != null ? Outcome.FAILURE : Outcome.SUCCESS);
    }
}
