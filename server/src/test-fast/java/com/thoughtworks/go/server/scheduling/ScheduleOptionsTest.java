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
package com.thoughtworks.go.server.scheduling;

import com.thoughtworks.go.config.EnvironmentVariableConfig;
import com.thoughtworks.go.security.GoCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ScheduleOptionsTest {

    private GoCipher goCipher;

    @BeforeEach
    public void setUp() {
        goCipher = new GoCipher();
    }

    @Test
    public void shouldReturnEnvironmentVariablesConfig() {
        Map<String, String> variables = new HashMap<>();
        variables.put("name", "value");
        variables.put("foo", "bar");
        ScheduleOptions scheduleOptions = new ScheduleOptions(new HashMap<>(), variables, new HashMap<>());
        assertThat(scheduleOptions.getVariables().size()).isEqualTo(2);
        assertThat(scheduleOptions.getVariables()).contains(new EnvironmentVariableConfig("name","value"), new EnvironmentVariableConfig("foo","bar"));
    }

    @Test
    public void shouldReturnSecureEnvironmentVariablesConfig() {
        String plainText = "secure_value";
        Map<String, String> secureVariables = new HashMap<>();
        secureVariables.put("secure_name", plainText);
        ScheduleOptions scheduleOptions = new ScheduleOptions(goCipher, new HashMap<>(), new HashMap<>(), secureVariables);
        assertThat(scheduleOptions.getVariables().size()).isEqualTo(1);
        assertThat(scheduleOptions.getVariables()).contains(new EnvironmentVariableConfig(goCipher, "secure_name", plainText, true));
    }
}
