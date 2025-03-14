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
package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.policy.SupportedAction;
import com.thoughtworks.go.config.policy.SupportedEntity;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.util.SystemEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.thoughtworks.go.util.SystemEnvironment.ALLOW_EVERYONE_TO_VIEW_OPERATE_GROUPS_WITH_NO_GROUP_AUTHORIZATION_SETUP;

@Service
public class SecurityService {
    private final SystemEnvironment systemEnvironment;
    private final GoConfigService goConfigService;

    @Autowired
    public SecurityService(GoConfigService goConfigService, SystemEnvironment systemEnvironment) {
        this.goConfigService = goConfigService;
        this.systemEnvironment = systemEnvironment;
    }

    public boolean hasViewPermissionForPipeline(Username username, String pipelineName) {
        String groupName = goConfigService.findGroupNameByPipeline(new CaseInsensitiveString(pipelineName));
        if (groupName == null) {
            return true;
        }
        return hasViewPermissionForGroup(CaseInsensitiveString.str(username.getUsername()), groupName);
    }

    public boolean hasViewPermissionForGroup(String userName, String pipelineGroupName) {
        CruiseConfig cruiseConfig = goConfigService.getCurrentConfig();

        if (!cruiseConfig.isSecurityEnabled()) {
            return true;
        }

        CaseInsensitiveString username = new CaseInsensitiveString(userName);
        if (isUserAdmin(new Username(username))) {
            return true;
        }

        PipelineConfigs group = cruiseConfig.getGroups().findGroup(pipelineGroupName);
        boolean everyoneIsAllowedToViewIfNoAuthIsDefined = systemEnvironment.get(ALLOW_EVERYONE_TO_VIEW_OPERATE_GROUPS_WITH_NO_GROUP_AUTHORIZATION_SETUP);
        return isUserAdminOfGroup(username, group) || group.hasViewPermission(username, new UserRoleMatcherImpl(cruiseConfig.server().security()), everyoneIsAllowedToViewIfNoAuthIsDefined);
    }

    private boolean isUserAdminOfGroup(final CaseInsensitiveString userName, PipelineConfigs group) {
        return goConfigService.isUserAdminOfGroup(userName, group);
    }

    public boolean isUserAdminOfGroup(final CaseInsensitiveString userName, String groupName) {
        return goConfigService.isUserAdminOfGroup(userName, groupName);
    }

    public boolean isUserAdminOfGroup(final Username username, String groupName) {
        return goConfigService.isUserAdminOfGroup(username.getUsername(), groupName);
    }

    public boolean hasOperatePermissionForPipeline(final CaseInsensitiveString username, String pipelineName) {
        String groupName = goConfigService.findGroupNameByPipeline(new CaseInsensitiveString(pipelineName));
        if (groupName == null) {
            return true;
        }
        return hasOperatePermissionForGroup(username, groupName);
    }

    public boolean hasAdminPermissionsForPipeline(Username username, CaseInsensitiveString pipelineName) {
        String groupName = goConfigService.findGroupNameByPipeline(pipelineName);
        if (groupName == null) {
            return true;
        }

        return isUserAdminOfGroup(username.getUsername(), groupName);
    }

    public boolean hasOperatePermissionForGroup(final CaseInsensitiveString username, String groupName) {
        CruiseConfig cruiseConfig = goConfigService.getCurrentConfig();

        if (!cruiseConfig.isSecurityEnabled()) {
            return true;
        }

        if (isUserAdmin(new Username(username))) {
            return true;
        }

        PipelineConfigs group = cruiseConfig.getGroups().findGroup(groupName);
        boolean everyoneIsAllowedToOperateIfNoAuthIsDefined = systemEnvironment.get(ALLOW_EVERYONE_TO_VIEW_OPERATE_GROUPS_WITH_NO_GROUP_AUTHORIZATION_SETUP);
        return isUserAdminOfGroup(username, group) || group.hasOperatePermission(username, new UserRoleMatcherImpl(cruiseConfig.server().security()), everyoneIsAllowedToOperateIfNoAuthIsDefined);
    }

    public boolean hasOperatePermissionForStage(String pipelineName, String stageName, String username) {
        if (!goConfigService.isSecurityEnabled()) {
            return true;
        }
        if (!goConfigService.hasStageConfigNamed(pipelineName, stageName)) {
            return false;
        }
        StageConfig stage = goConfigService.stageConfigNamed(pipelineName, stageName);
        CaseInsensitiveString userName = new CaseInsensitiveString(username);

        //TODO - #2517 - stage not exist
        if (stage.hasOperatePermissionDefined()) {
            String groupName = goConfigService.findGroupNameByPipeline(new CaseInsensitiveString(pipelineName));
            PipelineConfigs group = goConfigService.getCurrentConfig().findGroup(groupName);
            if (isUserAdmin(new Username(userName)) || isUserAdminOfGroup(userName, group)) {
                return true;
            }
            return goConfigService.readAclBy(pipelineName, stageName).isGranted(userName);
        }

        return hasOperatePermissionForPipeline(new CaseInsensitiveString(username), pipelineName);
    }

    public boolean isUserAdmin(Username username) {
        if (!isSecurityEnabled()) {
            return true;
        }
        return goConfigService.isUserAdmin(username);
    }

    public boolean isSecurityEnabled() {
        return goConfigService.isSecurityEnabled();
    }

    public boolean hasOperatePermissionForFirstStage(String pipelineName, String userName) {
        StageConfig stage = goConfigService.findFirstStageOfPipeline(new CaseInsensitiveString(pipelineName));
        return hasOperatePermissionForStage(pipelineName, CaseInsensitiveString.str(stage.name()), userName);
    }

    public boolean canViewAdminPage(Username username) {
        return isUserAdmin(username) || isUserGroupAdmin(username) || isAuthorizedToViewAndEditTemplates(username) || isAuthorizedToViewTemplates(username);
    }

    public boolean canCreatePipelines(Username username) {
        return isUserAdmin(username) || isUserGroupAdmin(username);
    }

    public boolean hasOperatePermissionForAgents(Username username) {
        return isUserAdmin(username);
    }

    public boolean hasViewOrOperatePermissionForPipeline(Username username, String pipelineName) {
        return hasViewPermissionForPipeline(username, pipelineName) ||
                hasOperatePermissionForPipeline(username.getUsername(), pipelineName);
    }

    public String casServiceBaseUrl() {
        return goConfigService.serverConfig().getSiteUrlPreferablySecured().getUrl();
    }

    public List<CaseInsensitiveString> viewablePipelinesFor(Username username) {
        List<CaseInsensitiveString> pipelines = new ArrayList<>();
        for (String group : goConfigService.allGroups()) {
            if (hasViewPermissionForGroup(CaseInsensitiveString.str(username.getUsername()), group)) {
                pipelines.addAll(goConfigService.pipelines(group));
            }
        }
        return pipelines;
    }

    public boolean isUserGroupAdmin(Username username) {
        return goConfigService.isGroupAdministrator(username.getUsername());
    }

    public List<String> modifiableGroupsForUser(Username userName) {
        if (isUserAdmin(userName)) {
            return goConfigService.allGroups();
        }
        List<String> modifiableGroups = new ArrayList<>();
        for (String group : goConfigService.allGroups()) {
            if (isUserAdminOfGroup(userName.getUsername(), group)) {
                modifiableGroups.add(group);
            }
        }
        return modifiableGroups;
    }

    public boolean isAuthorizedToViewAndEditTemplates(Username username) {
        return goConfigService.cruiseConfig().canViewAndEditTemplates(username.getUsername());
    }

    public boolean isAuthorizedToEditTemplate(CaseInsensitiveString templateName, Username username) {
        return goConfigService.cruiseConfig().isAuthorizedToEditTemplate(templateName, username.getUsername());
    }

    public boolean isAuthorizedToViewTemplate(CaseInsensitiveString templateName, Username username) {
        return goConfigService.cruiseConfig().isAuthorizedToViewTemplate(templateName, username.getUsername());
    }

    public boolean isAuthorizedToViewTemplates(Username username) {
        return goConfigService.cruiseConfig().isAuthorizedToViewTemplates(username.getUsername());
    }

    //todo: needs refactoring use AbstractAuthenticationHelper.doesUserHasPermissions
    //a specific method to check whether a user has permission to access agent status report has been added
    public boolean doesUserHasPermissions(Username username, SupportedAction action, SupportedEntity entity, String resource, String resourceToOperateWithin) {
        if (this.isUserAdmin(username)) {
            return true;
        }

        List<Role> roles = goConfigService.rolesForUser(username.getUsername());

        boolean hasPermission = false;
        for (Role role : roles) {
            if (role.hasExplicitDenyPermissionsFor(action, entity.getEntityType(), resource, resourceToOperateWithin)) {
                return false;
            }

            if (role.hasPermissionsFor(action, entity.getEntityType(), resource, resourceToOperateWithin)) {
                hasPermission = true;
            }
        }

        return hasPermission;
    }

    public static class UserRoleMatcherImpl implements UserRoleMatcher {
        private final SecurityConfig securityConfig;

        public UserRoleMatcherImpl(SecurityConfig securityConfig) {
            this.securityConfig = securityConfig;
        }

        @Override
        public boolean match(CaseInsensitiveString user, CaseInsensitiveString role) {
            return securityConfig.isUserMemberOfRole(user, role);
        }
    }
}
