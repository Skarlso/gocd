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
package com.thoughtworks.go.agent.service;

import com.thoughtworks.go.agent.AgentAutoRegistrationPropertiesImpl;
import com.thoughtworks.go.agent.common.ssl.GoAgentServerHttpClient;
import com.thoughtworks.go.config.DefaultAgentRegistry;
import com.thoughtworks.go.config.TokenService;
import com.thoughtworks.go.util.SystemUtil;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.*;

public class RemoteRegistrationRequesterTest {

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        tokenService.store("token-from-server");
    }

    @AfterEach
    void tearDown() throws IOException {
        tokenService.delete();
    }

    @Test
    void shouldPassAllParametersToPostForRegistrationOfNonElasticAgent() throws IOException {
        String url = "http://cruise.com/go";
        GoAgentServerHttpClient httpClient = mock(GoAgentServerHttpClient.class);
        final CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        final ProtocolVersion protocolVersion = new ProtocolVersion("https", 1, 2);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(protocolVersion, HttpURLConnection.HTTP_OK, null));
        when(response.getEntity()).thenReturn(new StringEntity(""));
        when(httpClient.execute(isA(HttpRequestBase.class))).thenReturn(response);
        final DefaultAgentRegistry defaultAgentRegistry = new DefaultAgentRegistry();
        Properties properties = new Properties();
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_KEY, "t0ps3cret");
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_RESOURCES, "linux, java");
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_ENVIRONMENTS, "uat, staging");
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_HOSTNAME, "agent01.example.com");

        remoteRegistryRequester(url, httpClient, defaultAgentRegistry, 200).requestRegistration("cruise.com", new AgentAutoRegistrationPropertiesImpl(null, properties));
        verify(httpClient).execute(argThat(hasAllParams(defaultAgentRegistry.uuid(), "", "")));
    }

    @Test
    void shouldPassAllParametersToPostForRegistrationOfElasticAgent() throws IOException {
        String url = "http://cruise.com/go";
        GoAgentServerHttpClient httpClient = mock(GoAgentServerHttpClient.class);
        final CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        final ProtocolVersion protocolVersion = new ProtocolVersion("https", 1, 2);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(protocolVersion, HttpURLConnection.HTTP_OK, null));
        when(response.getEntity()).thenReturn(new StringEntity(""));
        when(httpClient.execute(isA(HttpRequestBase.class))).thenReturn(response);

        final DefaultAgentRegistry defaultAgentRegistry = new DefaultAgentRegistry();
        Properties properties = new Properties();
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_KEY, "t0ps3cret");
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_RESOURCES, "linux, java");
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_ENVIRONMENTS, "uat, staging");
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_HOSTNAME, "agent01.example.com");
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_ELASTIC_AGENT_ID, "42");
        properties.put(AgentAutoRegistrationPropertiesImpl.AGENT_AUTO_REGISTER_ELASTIC_PLUGIN_ID, "tw.go.elastic-agent.docker");

        remoteRegistryRequester(url, httpClient, defaultAgentRegistry, 200).requestRegistration("cruise.com", new AgentAutoRegistrationPropertiesImpl(null, properties));
        verify(httpClient).execute(argThat(hasAllParams(defaultAgentRegistry.uuid(), "42", "tw.go.elastic-agent.docker")));
    }

    private ArgumentMatcher<HttpRequestBase> hasAllParams(final String uuid, final String elasticAgentId, final String elasticPluginId) {
        return new ArgumentMatcher<>() {
            @Override
            public boolean matches(HttpRequestBase item) {
                try {
                    HttpEntityEnclosingRequestBase postMethod = (HttpEntityEnclosingRequestBase) item;
                    List<NameValuePair> params = URLEncodedUtils.parse(postMethod.getEntity());

                    assertThat(getParameter(params, "hostname")).isEqualTo("cruise.com");
                    assertThat(getParameter(params, "uuid")).isEqualTo(uuid);
                    String workingDir = SystemUtil.currentWorkingDirectory();
                    assertThat(getParameter(params, "location")).isEqualTo(workingDir);
                    assertThat(getParameter(params, "operatingSystem")).isNotNull();
                    assertThat(getParameter(params, "agentAutoRegisterKey")).isEqualTo("t0ps3cret");
                    assertThat(getParameter(params, "agentAutoRegisterResources")).isEqualTo("linux, java");
                    assertThat(getParameter(params, "agentAutoRegisterEnvironments")).isEqualTo("uat, staging");
                    assertThat(getParameter(params, "agentAutoRegisterHostname")).isEqualTo("agent01.example.com");
                    assertThat(getParameter(params, "elasticAgentId")).isEqualTo(elasticAgentId);
                    assertThat(getParameter(params, "elasticPluginId")).isEqualTo(elasticPluginId);
                    assertThat(getParameter(params, "token")).isEqualTo("token-from-server");
                    return true;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            private String getParameter(List<NameValuePair> params, String paramName) {
                for (NameValuePair param : params) {
                    if (param.getName().equals(paramName)) {
                        return param.getValue();
                    }
                }
                return null;
            }
        };
    }

    private SslInfrastructureService.RemoteRegistrationRequester remoteRegistryRequester(final String url, final GoAgentServerHttpClient httpClient, final DefaultAgentRegistry defaultAgentRegistry, final int statusCode) {
        return new SslInfrastructureService.RemoteRegistrationRequester(url, defaultAgentRegistry, httpClient) {
            @Override
            protected int getStatusCode(CloseableHttpResponse response) {
                return statusCode;
            }
        };
    }

}
