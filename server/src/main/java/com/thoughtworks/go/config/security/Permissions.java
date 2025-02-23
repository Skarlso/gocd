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
package com.thoughtworks.go.config.security;

import com.thoughtworks.go.config.security.permissions.PipelinePermission;
import com.thoughtworks.go.config.security.users.Users;

public class Permissions {
    private final Users viewers;
    private final Users operators;
    private final Users admins;
    private final PipelinePermission pipelinePermission;

    public Permissions(Users viewers, Users operators, Users admins, PipelinePermission pipelinePermission) {
        this.viewers = viewers;
        this.operators = operators;
        this.admins = admins;
        this.pipelinePermission = pipelinePermission;
    }

    public Users viewers() {
        return viewers;
    }

    public Users operators() {
        return operators;
    }

    public Users admins() {
        return admins;
    }

    public Users pipelineOperators() {
        return pipelinePermission.getPipelineOperators();
    }

    public Users stageOperators(String stageName) {
        Users stageOperators = this.pipelinePermission.getStageOperators(stageName);
        return stageOperators == null ? operators : stageOperators;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Permissions that = (Permissions) o;

        if (viewers != null ? !viewers.equals(that.viewers) : that.viewers != null) return false;
        if (operators != null ? !operators.equals(that.operators) : that.operators != null) return false;
        if (admins != null ? !admins.equals(that.admins) : that.admins != null) return false;
        return pipelinePermission != null ? pipelinePermission.equals(that.pipelinePermission) : that.pipelinePermission == null;
    }

    @Override
    public int hashCode() {
        int result = viewers != null ? viewers.hashCode() : 0;
        result = 31 * result + (operators != null ? operators.hashCode() : 0);
        result = 31 * result + (admins != null ? admins.hashCode() : 0);
        result = 31 * result + (pipelinePermission != null ? pipelinePermission.hashCode() : 0);
        return result;
    }
}
