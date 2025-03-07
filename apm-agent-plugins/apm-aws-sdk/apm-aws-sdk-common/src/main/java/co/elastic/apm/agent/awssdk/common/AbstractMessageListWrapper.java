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
package co.elastic.apm.agent.awssdk.common;

import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.transaction.TextHeaderGetter;

import java.util.Collection;
import java.util.List;
import java.util.ListIterator;

public abstract class AbstractMessageListWrapper<Message> implements List<Message> {

    protected List<Message> delegate;
    protected final ElasticApmTracer tracer;
    protected final String queueName;

    protected final AbstractSQSInstrumentationHelper<?, ?, Message> sqsInstrumentationHelper;
    protected final TextHeaderGetter<Message> textHeaderGetter;

    public AbstractMessageListWrapper(List<Message> delegate, ElasticApmTracer tracer, String queueName,
                                      AbstractSQSInstrumentationHelper<?, ?, Message> sqsInstrumentationHelper,
                                      TextHeaderGetter<Message> textHeaderGetter) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.queueName = queueName;
        this.sqsInstrumentationHelper = sqsInstrumentationHelper;
        this.textHeaderGetter = textHeaderGetter;
    }

    public void updateDelegate(List<Message> delegate){
        this.delegate = delegate;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return delegate.contains(o);
    }

    @Override
    public Object[] toArray() {
        return delegate.toArray();
    }

    @SuppressWarnings("SuspiciousToArrayCall")
    @Override
    public <T> T[] toArray(T[] a) {
        return delegate.toArray(a);
    }

    @Override
    public boolean add(Message message) {
        return delegate.add(message);
    }

    @Override
    public boolean remove(Object o) {
        return delegate.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends Message> c) {
        return delegate.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends Message> c) {
        return delegate.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return delegate.retainAll(c);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override
    public boolean equals(Object o) {
        return delegate.equals(o);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public Message get(int index) {
        return delegate.get(index);
    }

    @Override
    public Message set(int index, Message element) {
        return delegate.set(index, element);
    }

    @Override
    public void add(int index, Message element) {
        delegate.add(index, element);
    }

    @Override
    public Message remove(int index) {
        return delegate.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return delegate.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return delegate.lastIndexOf(o);
    }

    @Override
    public ListIterator<Message> listIterator() {
        return delegate.listIterator();
    }

    @Override
    public ListIterator<Message> listIterator(int index) {
        return delegate.listIterator(index);
    }
}
