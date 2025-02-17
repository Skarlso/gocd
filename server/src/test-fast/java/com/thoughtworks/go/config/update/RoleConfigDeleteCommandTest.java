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
package com.thoughtworks.go.config.update;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.exceptions.RecordNotFoundException;
import com.thoughtworks.go.helper.GoConfigMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.helper.PipelineTemplateConfigMother;
import com.thoughtworks.go.plugin.access.authorization.AuthorizationExtension;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RoleConfigDeleteCommandTest {
    private BasicCruiseConfig cruiseConfig;
    private AuthorizationExtension extension;
    private GoConfigService goConfigService;


    @BeforeEach
    public void setUp() {
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        extension = mock(AuthorizationExtension.class);
        goConfigService = mock(GoConfigService.class);
    }

    @Test
    public void currentUserShouldBeAnAdminToAddRole() {
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        Username viewUser = new Username("view");
        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        PluginRoleConfig role = new PluginRoleConfig("foo", "ldap");


        cruiseConfig.server().security().addRole(role);
        when(goConfigService.isUserAdmin(viewUser)).thenReturn(false);

        RoleConfigDeleteCommand command = new RoleConfigDeleteCommand(goConfigService, role, null, viewUser, result);

        assertFalse(command.canContinue(cruiseConfig));
        assertFalse(result.isSuccessful());
        assertThat(result.httpCode()).isEqualTo(403);
    }

    @Test
    public void canContinue_shouldCheckIfRoleExists() {
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        Username viewUser = mock(Username.class);
        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        PluginRoleConfig role = new PluginRoleConfig("foo", "ldap");

        when(goConfigService.isUserAdmin(viewUser)).thenReturn(true);

        RoleConfigDeleteCommand command = new RoleConfigDeleteCommand(goConfigService, role, null, viewUser, result);

        assertFalse(command.canContinue(cruiseConfig));
        assertFalse(result.isSuccessful());
        assertThat(result.httpCode()).isEqualTo(404);

    }

    @Test
    public void shouldDeleteARole() {
        PluginRoleConfig role = new PluginRoleConfig("blackbird", "ldap");
        cruiseConfig.server().security().getRoles().add(role);

        RoleConfigCommand command = new RoleConfigDeleteCommand(null, role, extension, null, null);
        command.update(cruiseConfig);

        assertThat(cruiseConfig.server().security().getRoles()).isEmpty();
    }

    @Test
    public void shouldRaiseExceptionInCaseRoleDoesNotExist() {
        PluginRoleConfig role = new PluginRoleConfig("blackbird", "ldap");

        assertThat(cruiseConfig.server().security().getRoles()).isEmpty();
        RoleConfigCommand command = new RoleConfigDeleteCommand(null, role, extension, null, new HttpLocalizedOperationResult());

        assertThatThrownBy(() -> command.update(cruiseConfig)).isInstanceOf(RecordNotFoundException.class);

        assertThat(cruiseConfig.server().security().getRoles()).isEmpty();
    }

    @Test
    public void shouldDeleteAllOccurrencesOfRoleSilentlyIfRoleIsInUse() {
        PluginRoleConfig readOnly = new PluginRoleConfig("guest", "guest");
        cruiseConfig.server().security().addRole(readOnly);

        PluginRoleConfig superAdmin = new PluginRoleConfig("superAdmin", "ldap");
        cruiseConfig.server().security().addRole(superAdmin);

        PluginRoleConfig groupAdmin = new PluginRoleConfig("groupAdmin", "ldap");
        cruiseConfig.server().security().addRole(groupAdmin);

        PluginRoleConfig templateAdmin = new PluginRoleConfig("templateAdmin", "ldap");
        cruiseConfig.server().security().addRole(templateAdmin);


        PluginRoleConfig stageAdmin = new PluginRoleConfig("stageAdmin", "ldap");
        cruiseConfig.server().security().addRole(stageAdmin);

        cruiseConfig.server().security().adminsConfig().add(new AdminRole(readOnly.getName()));
        cruiseConfig.server().security().adminsConfig().add(new AdminRole(superAdmin.getName()));

        PipelineConfig pipelineWithStageRequiringAuth = PipelineConfigMother.createPipelineConfigWithStage("myPipeline", "myStage");
        pipelineWithStageRequiringAuth.getFirstStageConfig().setApproval(new Approval(new AuthConfig(new AdminRole(stageAdmin.getName()))));

        PipelineConfigs pipelineGroupWithAuth = PipelineConfigMother.createGroup("myGroup", pipelineWithStageRequiringAuth);
        pipelineGroupWithAuth.setAuthorization(new Authorization(
                new ViewConfig(new AdminRole(readOnly.getName()), new AdminRole(stageAdmin.getName())),
                new OperationConfig(new AdminRole(stageAdmin.getName())),
                new AdminsConfig(new AdminRole(superAdmin.getName()))
        ));

        cruiseConfig.getGroups().add(pipelineGroupWithAuth);
        cruiseConfig.addTemplate(PipelineTemplateConfigMother.createTemplate("myTemplate", new Authorization(new AdminsConfig(new AdminRole(superAdmin.getName())))));

        RoleConfigCommand command = new RoleConfigDeleteCommand(null, readOnly, extension, null, new HttpLocalizedOperationResult());
        command.update(cruiseConfig);

        assertThat(cruiseConfig.server().security().adminsConfig().getRoles()).hasSize(1);
        assertThat(cruiseConfig.server().security().getRoles().findByName(readOnly.getName())).isNull();
        assertFalse(cruiseConfig.getGroups().get(0).getAuthorization().getViewConfig().getRoles().contains(new AdminRole(readOnly.getName())));
        assertTrue(cruiseConfig.getGroups().get(0).getAuthorization().getViewConfig().getRoles().contains(new AdminRole(stageAdmin.getName())));
    }

    @Test
    public void shouldValidateIfProfileIsNotInUseByPipeline() {
        PluginRoleConfig role = new PluginRoleConfig("blackbird", "ldap");

        assertThat(cruiseConfig.server().security().getRoles()).isEmpty();
        RoleConfigCommand command = new RoleConfigDeleteCommand(null, role, extension, null, new HttpLocalizedOperationResult());
        assertTrue(command.isValid(cruiseConfig));
    }
}
