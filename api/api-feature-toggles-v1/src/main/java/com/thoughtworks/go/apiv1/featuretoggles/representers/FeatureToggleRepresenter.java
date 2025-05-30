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
package com.thoughtworks.go.apiv1.featuretoggles.representers;

import com.thoughtworks.go.api.base.OutputWriter;
import com.thoughtworks.go.server.domain.support.toggle.FeatureToggle;

public class FeatureToggleRepresenter {
    public static void toJSON(OutputWriter writer, FeatureToggle toggle) {
        writer.add("key", toggle.key());
        writer.add("has_changed", toggle.hasBeenChangedFromDefault());
        writer.add("description", toggle.description());
        writer.add("value", toggle.isOn());
    }
}
