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

package com.thoughtworks.go.server.service.permissions.entity;

import com.thoughtworks.go.config.BasicCruiseConfig;
import com.thoughtworks.go.config.elastic.ClusterProfile;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.newsecurity.utils.SessionUtils;
import com.thoughtworks.go.server.security.GoAuthority;
import com.thoughtworks.go.server.security.userdetail.GoUserPrincipal;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.SecurityService;
import com.thoughtworks.go.server.service.permissions.PermissionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static com.thoughtworks.go.config.policy.SupportedAction.ADMINISTER;
import static com.thoughtworks.go.config.policy.SupportedAction.VIEW;
import static com.thoughtworks.go.config.policy.SupportedEntity.CLUSTER_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ClusterProfilePermissionTest {
    @Mock
    private GoConfigService goConfigService;
    @Mock
    private SecurityService securityService;

    private Username username;
    private PermissionProvider permission;
    private BasicCruiseConfig cruiseConfig;

    @BeforeEach
    void setUp() {

        cruiseConfig = new BasicCruiseConfig();
        username = new Username("Bob" + UUID.randomUUID());
        permission = new ClusterProfilePermission(goConfigService, securityService);

        lenient().when(goConfigService.getMergedConfigForEditing()).thenReturn(cruiseConfig);
        SessionUtils.setCurrentUser(new GoUserPrincipal(username.getUsername().toString(), username.getUsername().toString(), GoAuthority.ROLE_ANONYMOUS.asAuthority()));
    }

    @Test
    void shouldGetNameOfThePermission() {
        assertThat(permission.name()).isEqualTo("cluster_profile");
    }

    @Test
    void shouldReturnEmptyListWhenUserHasNoPermissionsOnClusterProfiles() {
        cruiseConfig.getElasticConfig().getClusterProfiles().add(new ClusterProfile("dev_cluster", "ecs"));
        cruiseConfig.getElasticConfig().getClusterProfiles().add(new ClusterProfile("prod_cluster", "ecs"));

        Map<String, Object> permissions = permission.permissions(username);

        Map<String, Object> clusterProfile = new LinkedHashMap<>();
        clusterProfile.put("view", Collections.emptyList());
        clusterProfile.put("administer", Collections.emptyList());

        assertThat(permissions).isEqualTo(clusterProfile);
    }

    @Test
    void shouldReturnUserPermissibleClusterProfiles() {
        cruiseConfig.getElasticConfig().getClusterProfiles().add(new ClusterProfile("dev_cluster", "ecs"));
        cruiseConfig.getElasticConfig().getClusterProfiles().add(new ClusterProfile("prod_cluster", "ecs"));

        when(securityService.doesUserHasPermissions(SessionUtils.currentUsername(), VIEW, CLUSTER_PROFILE, "dev_cluster", null)).thenReturn(true);
        when(securityService.doesUserHasPermissions(SessionUtils.currentUsername(), VIEW, CLUSTER_PROFILE, "prod_cluster", null)).thenReturn(true);

        when(securityService.doesUserHasPermissions(SessionUtils.currentUsername(), ADMINISTER, CLUSTER_PROFILE, "dev_cluster", null)).thenReturn(true);
        when(securityService.doesUserHasPermissions(SessionUtils.currentUsername(), ADMINISTER, CLUSTER_PROFILE, "prod_cluster", null)).thenReturn(false);

        Map<String, Object> permissions = permission.permissions(username);

        Map<String, Object> clusterProfile = new LinkedHashMap<>();
        clusterProfile.put("view", List.of("dev_cluster", "prod_cluster"));
        clusterProfile.put("administer", List.of("dev_cluster"));

        assertThat(permissions).isEqualTo(clusterProfile);
    }

}
