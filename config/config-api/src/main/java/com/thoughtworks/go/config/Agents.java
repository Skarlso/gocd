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
package com.thoughtworks.go.config;

import com.thoughtworks.go.domain.NullAgent;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Agents extends ArrayList<Agent> {
    public Agents() {
        super();
    }

    public Agents(List<Agent> agents) {
        if (agents != null) {
            this.addAll(agents);
        }
    }

    public Agents(Agent... agents) {
        this.addAll(Arrays.asList(agents));
    }

    public Agent getAgentByUUID(String uuid) {
        return this.stream()
            .filter(agent -> Strings.CS.equals(agent.getUuid(), uuid))
            .findFirst()
            .orElseGet(() -> NullAgent.createNullAgent(uuid));
    }

    public boolean hasAgent(String uuid) {
        return !getAgentByUUID(uuid).isNull();
    }

    public boolean add(Agent agent) {
        if (agent != null) {
            if (contains(agent)) {
                throw new IllegalArgumentException("Agent with same UUID already exists: " + agent);
            }
            return super.add(agent);
        }
        return false;
    }
}
