/*
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.server.messaging;

import com.thoughtworks.go.server.messaging.activemq.JMSMessageListenerAdapter;

public class GoMessageQueue<T extends GoMessage> implements GoMessageChannel<T> {
    private final MessagingService<T> messaging;
    protected String queueName;
    private MessageSender queueSender;

    @SuppressWarnings("unchecked")
    public GoMessageQueue(MessagingService<GoMessage> messaging, String queueName) {
        this.messaging = (MessagingService<T>) messaging;
        this.queueName = queueName;
    }

    protected MessageSender sender() {
        if (queueSender == null) {
            queueSender = messaging.createQueueSender(queueName);
        }
        return queueSender;
    }

    @Override
    public JMSMessageListenerAdapter<T> addListener(GoMessageListener<T> listener) {
        return messaging.addQueueListener(queueName, listener);
    }

    @Override
    public void post(T message) {
        sender().sendMessage(message);
    }

    public void post(T message, long timeToLive) {
        sender().sendMessage(message, timeToLive);
    }

    public void stop() {
        messaging.removeQueue(queueName);
    }
}
