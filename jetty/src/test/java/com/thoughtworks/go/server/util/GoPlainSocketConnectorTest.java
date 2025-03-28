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
package com.thoughtworks.go.server.util;

import com.thoughtworks.go.server.JettyServer;
import com.thoughtworks.go.util.SystemEnvironment;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GoPlainSocketConnectorTest {

    private ServerConnector connector;
    private HttpConfiguration configuration;

    @BeforeEach
    public void setUp() {
        SystemEnvironment systemEnvironment = mock(SystemEnvironment.class);
        when(systemEnvironment.getServerPort()).thenReturn(1234);
        when(systemEnvironment.get(SystemEnvironment.RESPONSE_BUFFER_SIZE)).thenReturn(100);
        when(systemEnvironment.get(SystemEnvironment.GO_SERVER_CONNECTION_IDLE_TIMEOUT_IN_MILLIS)).thenReturn(200L);
        when(systemEnvironment.getListenHost()).thenReturn("foo");
        JettyServer server = new JettyServer(systemEnvironment);

        connector = (ServerConnector) new GoPlainSocketConnector(server, systemEnvironment).getConnector();

        HttpConnectionFactory connectionFactory = (HttpConnectionFactory) connector.getDefaultConnectionFactory();
        configuration = connectionFactory.getHttpConfiguration();
    }

    @Test
    public void shouldCreateAServerConnectorWithConfiguredPortAndBufferSize() {
        assertThat(connector.getPort()).isEqualTo(1234);
        assertThat(connector.getHost()).isEqualTo("foo");
        assertThat(connector.getIdleTimeout()).isEqualTo(200L);

        assertThat(configuration.getOutputBufferSize()).isEqualTo(100);
    }

    @Test
    public void shouldNotSendAServerHeaderForSecurityReasons() {
        assertThat(configuration.getSendServerVersion()).isEqualTo(false);
    }
}
