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
package com.thoughtworks.go.domain.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigurationValueTest {

    @Test
    public void shouldCheckForEqualityOfValue() {
        ConfigurationValue configurationValue = new ConfigurationValue(ConfigurationValue.VALUE);
        assertThat(configurationValue).isEqualTo(new ConfigurationValue(ConfigurationValue.VALUE));
    }

    @Test
    public void shouldHandleBooleanValueAsAString() {
        final ConfigurationValue configurationValue = new ConfigurationValue(true);
        assertThat(configurationValue.getValue()).isEqualTo("true");
    }

    @Test
    public void shouldHandleIntegerValueAsAString() {
        final ConfigurationValue configurationValue = new ConfigurationValue(1);
        assertThat(configurationValue.getValue()).isEqualTo("1");
    }

    @Test
    public void shouldHandleLongValueAsAString() {
        final ConfigurationValue configurationValue = new ConfigurationValue(5L);
        assertThat(configurationValue.getValue()).isEqualTo("5");
    }

    @Test
    public void shouldHandleDoubleValueAsAString() {
        final ConfigurationValue configurationValue = new ConfigurationValue(3.1428571429D);
        assertThat(configurationValue.getValue()).isEqualTo("3.1428571429");
    }
}
