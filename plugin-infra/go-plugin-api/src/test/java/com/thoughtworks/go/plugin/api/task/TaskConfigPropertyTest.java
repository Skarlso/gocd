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
package com.thoughtworks.go.plugin.api.task;

import com.thoughtworks.go.plugin.api.config.Property;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TaskConfigPropertyTest {
    @Test
    public void validateTaskPropertyDefaults() {
        TaskConfigProperty taskConfigProperty = new TaskConfigProperty("Test-Property");
        assertThat(taskConfigProperty.getOptions().size()).isEqualTo(4);
        assertThat(taskConfigProperty.getOption(Property.REQUIRED)).isFalse();
        assertThat(taskConfigProperty.getOption(Property.SECURE)).isFalse();
        taskConfigProperty = new TaskConfigProperty("Test-Property", "Dummy Value");
        taskConfigProperty.with(Property.REQUIRED, true);
        assertThat(taskConfigProperty.getOptions().size()).isEqualTo(4);
        assertThat(taskConfigProperty.getOption(Property.REQUIRED)).isTrue();
        assertThat(taskConfigProperty.getOption(Property.SECURE)).isFalse();
    }

    @Test
    public void shouldAssignDefaults() {
        final TaskConfigProperty property = new TaskConfigProperty("key");
        assertThat(property.getOption(Property.REQUIRED)).isFalse();
        assertThat(property.getOption(Property.SECURE)).isFalse();
        assertThat(property.getOption(Property.DISPLAY_NAME)).isEqualTo("key");
        assertThat(property.getOption(Property.DISPLAY_ORDER)).isEqualTo(0);
    }

    @Test
    public void shouldCompareTwoPropertiesBasedOnOrder() {
        TaskConfigProperty p1 = getTaskConfigProperty("Test-Property", 1);
        TaskConfigProperty p2 = getTaskConfigProperty("Test-Property", 0);
        assertThat(p1.compareTo(p2)).isEqualTo(1);
    }

    private TaskConfigProperty getTaskConfigProperty(@SuppressWarnings("SameParameterValue") String key, int order) {
        TaskConfigProperty property = new TaskConfigProperty(key);
        property.with(Property.DISPLAY_ORDER, order);
        return property;
    }

}
