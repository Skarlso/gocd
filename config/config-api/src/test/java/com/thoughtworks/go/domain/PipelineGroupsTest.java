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
package com.thoughtworks.go.domain;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.exceptions.RecordNotFoundException;
import com.thoughtworks.go.config.exceptions.UnprocessableEntityException;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.PackageMaterialConfig;
import com.thoughtworks.go.config.materials.PluggableSCMMaterialConfig;
import com.thoughtworks.go.config.merge.MergePipelineConfigs;
import com.thoughtworks.go.config.remote.ConfigRepoConfig;
import com.thoughtworks.go.config.remote.FileConfigOrigin;
import com.thoughtworks.go.config.remote.RepoConfigOrigin;
import com.thoughtworks.go.helper.MaterialConfigsMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.util.Pair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.thoughtworks.go.helper.PipelineConfigMother.createGroup;
import static com.thoughtworks.go.helper.PipelineConfigMother.createPipelineConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PipelineGroupsTest {

    @Test
    public void shouldOnlySavePipelineToTargetGroup() {
        PipelineConfigs defaultGroup = createGroup("defaultGroup", createPipelineConfig("pipeline1", "stage1"));
        PipelineConfigs defaultGroup2 = createGroup("defaultGroup2", createPipelineConfig("pipeline2", "stage2"));
        PipelineGroups pipelineGroups = new PipelineGroups(defaultGroup, defaultGroup2);
        PipelineConfig pipelineConfig = createPipelineConfig("pipeline3", "stage1");

        pipelineGroups.addPipeline("defaultGroup", pipelineConfig);

        assertThat(defaultGroup).contains(pipelineConfig);
        assertThat(defaultGroup2).doesNotContain(pipelineConfig);
        assertThat(pipelineGroups.size()).isEqualTo(2);
    }

    @Test
    public void shouldRemovePipelineFromTheGroup() {
        PipelineConfig pipelineConfig = createPipelineConfig("pipeline1", "stage1");
        PipelineConfigs defaultGroup = createGroup("defaultGroup", pipelineConfig);
        PipelineGroups pipelineGroups = new PipelineGroups(defaultGroup);

        assertThat(pipelineGroups.size()).isEqualTo(1);
        pipelineGroups.deletePipeline(pipelineConfig);
        assertThat(pipelineGroups.size()).isEqualTo(1);
        assertThat(defaultGroup.size()).isEqualTo(0);
    }

    @Test
    public void shouldSaveNewPipelineGroupOnTheTop() {
        PipelineConfigs defaultGroup = createGroup("defaultGroup", createPipelineConfig("pipeline1", "stage1"));
        PipelineConfigs defaultGroup2 = createGroup("defaultGroup2", createPipelineConfig("pipeline2", "stage2"));
        PipelineGroups pipelineGroups = new PipelineGroups(defaultGroup, defaultGroup2);

        PipelineConfig pipelineConfig = createPipelineConfig("pipeline3", "stage1");

        pipelineGroups.addPipeline("defaultGroup3", pipelineConfig);

        PipelineConfigs group = createGroup("defaultGroup3", pipelineConfig);
        assertThat(pipelineGroups.indexOf(group)).isEqualTo(0);
    }

    @Test
    public void validate_shouldMarkDuplicatePipelineGroupNamesAsError() {
        PipelineConfigs first = createGroup("first", "pipeline");
        PipelineConfigs dup = createGroup("first", "pipeline");
        PipelineGroups groups = new PipelineGroups(first, dup);
        groups.validate(null);
        assertThat(first.errors().on(BasicPipelineConfigs.GROUP)).isEqualTo("Group with name 'first' already exists");
        assertThat(dup.errors().on(BasicPipelineConfigs.GROUP)).isEqualTo("Group with name 'first' already exists");
    }

    @Test
    public void shouldReturnTrueWhenGroupNameIsEmptyAndDefaultGroupExists() {
        PipelineConfig existingPipeline = createPipelineConfig("pipeline1", "stage1");
        PipelineConfigs defaultGroup = createGroup("defaultGroup", existingPipeline);
        PipelineGroups pipelineGroups = new PipelineGroups(defaultGroup);
        PipelineConfig newPipeline = createPipelineConfig("pipeline3", "stage1");

        pipelineGroups.addPipeline("", newPipeline);

        assertThat(pipelineGroups.size()).isEqualTo(1);
        assertThat(defaultGroup).contains(existingPipeline);
        assertThat(defaultGroup).contains(newPipeline);
    }

    @Test
    public void shouldErrorOutIfDuplicatePipelineIsAdded() {
        PipelineConfig pipeline1 = createPipelineConfig("pipeline1", "stage1");
        PipelineConfig pipeline2 = createPipelineConfig("pipeline1", "stage1");
        PipelineConfig pipeline3 = createPipelineConfig("pipeline1", "stage1");
        PipelineConfig pipeline4 = createPipelineConfig("pipeline1", "stage1");
        pipeline3.setOrigin(new RepoConfigOrigin(ConfigRepoConfig.createConfigRepoConfig(MaterialConfigsMother.gitMaterialConfig(), "plugin", "git-id"), "rev1"));
        pipeline4.setOrigin(new RepoConfigOrigin(ConfigRepoConfig.createConfigRepoConfig(MaterialConfigsMother.svnMaterialConfig(), "plugin", "svn-id"), "1"));
        PipelineConfigs defaultGroup = createGroup("defaultGroup", pipeline1);
        PipelineConfigs anotherGroup = createGroup("anotherGroup", pipeline2);
        PipelineConfigs thirdGroup = createGroup("thirdGroup", pipeline3);
        PipelineConfigs fourthGroup = createGroup("fourthGroup", pipeline4);
        PipelineGroups pipelineGroups = new PipelineGroups(defaultGroup, anotherGroup, thirdGroup, fourthGroup);

        pipelineGroups.validate(null);

        List<String> expectedSources = List.of(pipeline1.getOriginDisplayName(), pipeline2.getOriginDisplayName(), pipeline3.getOriginDisplayName(), pipeline4.getOriginDisplayName());
        assertDuplicateNameErrorOnPipeline(pipeline1, expectedSources, 3);
        assertDuplicateNameErrorOnPipeline(pipeline2, expectedSources, 3);
        assertDuplicateNameErrorOnPipeline(pipeline3, expectedSources, 3);
        assertDuplicateNameErrorOnPipeline(pipeline4, expectedSources, 3);
    }

    private void assertDuplicateNameErrorOnPipeline(PipelineConfig pipeline, List<String> expectedSources, int sourceCount) {
        assertThat(pipeline.errors().isEmpty()).isFalse();
        String errorMessage = pipeline.errors().on(PipelineConfig.NAME);
        assertThat(errorMessage).contains("You have defined multiple pipelines named 'pipeline1'. Pipeline names must be unique. Source(s):");
        Matcher matcher = Pattern.compile("^.*\\[(.*),\\s(.*),\\s(.*)\\].*$").matcher(errorMessage);
        assertThat(matcher.matches()).isTrue();
        assertThat(matcher.groupCount()).isEqualTo(sourceCount);
        List<String> actualSources = new ArrayList<>();
        for (int i = 1; i <= matcher.groupCount(); i++) {
            actualSources.add(matcher.group(i));
        }
        assertThat(actualSources.containsAll(expectedSources)).isTrue();
    }

    @Test
    public void shouldErrorOutIfDuplicatePipelineIsAddedToSameGroup() {
        PipelineConfig pipeline1 = createPipelineConfig("pipeline1", "stage1");
        PipelineConfig pipeline2 = createPipelineConfig("pipeline1", "stage1");
        PipelineConfigs defaultGroup = createGroup("defaultGroup", pipeline1, pipeline2);
        PipelineGroups pipelineGroups = new PipelineGroups(defaultGroup);

        pipelineGroups.validate(null);

        assertThat(pipeline1.errors().isEmpty()).isFalse();
        assertThat(pipeline1.errors().on(PipelineConfig.NAME)).isEqualTo("You have defined multiple pipelines named 'pipeline1'. Pipeline names must be unique. Source(s): [cruise-config.xml]");
        assertThat(pipeline2.errors().isEmpty()).isFalse();
        assertThat(pipeline2.errors().on(PipelineConfig.NAME)).isEqualTo("You have defined multiple pipelines named 'pipeline1'. Pipeline names must be unique. Source(s): [cruise-config.xml]");
    }

    @Test
    public void shouldFindAPipelineGroupByName() {
        PipelineConfig pipeline = createPipelineConfig("pipeline1", "stage1");
        PipelineConfigs defaultGroup = createGroup("defaultGroup", pipeline);
        PipelineGroups pipelineGroups = new PipelineGroups(defaultGroup);

        assertThat(pipelineGroups.findGroup("defaultGroup")).isEqualTo(defaultGroup);
    }

    @Test
    public void shouldThrowGroupNotFoundExceptionWhenSearchingForANonExistingGroup() {
        PipelineConfig pipeline = createPipelineConfig("pipeline1", "stage1");
        PipelineConfigs defaultGroup = createGroup("defaultGroup", pipeline);
        PipelineGroups pipelineGroups = new PipelineGroups(defaultGroup);

        assertThrows(RecordNotFoundException.class, () -> pipelineGroups.findGroup("NonExistantGroup"));
    }

    @Test
    public void shouldGetPackageUsageInPipelines() {
        PackageMaterialConfig packageOne = new PackageMaterialConfig("package-id-one");
        PackageMaterialConfig packageTwo = new PackageMaterialConfig("package-id-two");
        final PipelineConfig p1 = PipelineConfigMother.pipelineConfig("pipeline1", new MaterialConfigs(packageOne, packageTwo), new JobConfigs(new JobConfig(new CaseInsensitiveString("jobName"))));
        final PipelineConfig p2 = PipelineConfigMother.pipelineConfig("pipeline2", new MaterialConfigs(packageTwo), new JobConfigs(new JobConfig(new CaseInsensitiveString("jobName"))));

        PipelineGroups groups = new PipelineGroups();
        PipelineConfigs groupOne = new BasicPipelineConfigs(p1);
        PipelineConfigs groupTwo = new BasicPipelineConfigs(p2);
        groups.addAll(List.of(groupOne, groupTwo));

        Map<String, List<Pair<PipelineConfig, PipelineConfigs>>> packageToPipelineMap = groups.getPackageUsageInPipelines();

        assertThat(packageToPipelineMap.get("package-id-one").size()).isEqualTo(1);
        assertThat(packageToPipelineMap.get("package-id-one")).contains(new Pair<>(p1, groupOne));
        assertThat(packageToPipelineMap.get("package-id-two").size()).isEqualTo(2);
        assertThat(packageToPipelineMap.get("package-id-two")).contains(new Pair<>(p1, groupOne), new Pair<>(p2, groupTwo));
    }

    @Test
    public void shouldComputePackageUsageInPipelinesOnlyOnce() {
        PackageMaterialConfig packageOne = new PackageMaterialConfig("package-id-one");
        PackageMaterialConfig packageTwo = new PackageMaterialConfig("package-id-two");
        final PipelineConfig p1 = PipelineConfigMother.pipelineConfig("pipeline1", new MaterialConfigs(packageOne, packageTwo), new JobConfigs(new JobConfig(new CaseInsensitiveString("jobName"))));
        final PipelineConfig p2 = PipelineConfigMother.pipelineConfig("pipeline2", new MaterialConfigs(packageTwo), new JobConfigs(new JobConfig(new CaseInsensitiveString("jobName"))));

        PipelineGroups groups = new PipelineGroups();
        groups.addAll(List.of(new BasicPipelineConfigs(p1), new BasicPipelineConfigs(p2)));

        Map<String, List<Pair<PipelineConfig, PipelineConfigs>>> result1 = groups.getPackageUsageInPipelines();
        Map<String, List<Pair<PipelineConfig, PipelineConfigs>>> result2 = groups.getPackageUsageInPipelines();
        assertSame(result1, result2);
    }

    @Test
    public void shouldGetPluggableSCMMaterialUsageInPipelines() {
        PluggableSCMMaterialConfig pluggableSCMMaterialOne = new PluggableSCMMaterialConfig("scm-id-one");
        PluggableSCMMaterialConfig pluggableSCMMaterialTwo = new PluggableSCMMaterialConfig("scm-id-two");
        final PipelineConfig p1 = PipelineConfigMother.pipelineConfig("pipeline1", new MaterialConfigs(pluggableSCMMaterialOne, pluggableSCMMaterialTwo), new JobConfigs(new JobConfig(new CaseInsensitiveString("jobName"))));
        final PipelineConfig p2 = PipelineConfigMother.pipelineConfig("pipeline2", new MaterialConfigs(pluggableSCMMaterialTwo), new JobConfigs(new JobConfig(new CaseInsensitiveString("jobName"))));

        PipelineGroups groups = new PipelineGroups();
        PipelineConfigs groupOne = new BasicPipelineConfigs(p1);
        PipelineConfigs groupTwo = new BasicPipelineConfigs(p2);
        groups.addAll(List.of(groupOne, groupTwo));

        Map<String, List<Pair<PipelineConfig, PipelineConfigs>>> pluggableSCMMaterialUsageInPipelinesOne = groups.getPluggableSCMMaterialUsageInPipelines();

        assertThat(pluggableSCMMaterialUsageInPipelinesOne.get("scm-id-one").size()).isEqualTo(1);
        assertThat(pluggableSCMMaterialUsageInPipelinesOne.get("scm-id-one")).contains(new Pair<>(p1, groupOne));
        assertThat(pluggableSCMMaterialUsageInPipelinesOne.get("scm-id-two").size()).isEqualTo(2);
        assertThat(pluggableSCMMaterialUsageInPipelinesOne.get("scm-id-two")).contains(new Pair<>(p1, groupOne), new Pair<>(p2, groupTwo));

        Map<String, List<Pair<PipelineConfig, PipelineConfigs>>> pluggableSCMMaterialUsageInPipelinesTwo = groups.getPluggableSCMMaterialUsageInPipelines();
        assertSame(pluggableSCMMaterialUsageInPipelinesOne, pluggableSCMMaterialUsageInPipelinesTwo);
    }


    @Test
    public void shouldGetLocalPartsWhenOriginIsNull() {
        PipelineConfigs defaultGroup = createGroup("defaultGroup", createPipelineConfig("pipeline1", "stage1"));
        PipelineGroups groups = new PipelineGroups(defaultGroup);
        assertThat(groups.getLocal().size()).isEqualTo(1);
        assertThat(groups.getLocal().get(0)).isEqualTo(defaultGroup);
    }

    @Test
    public void shouldGetLocalPartsWhenOriginIsFile() {
        PipelineConfigs defaultGroup = createGroup("defaultGroup", createPipelineConfig("pipeline1", "stage1"));
        defaultGroup.setOrigins(new FileConfigOrigin());
        PipelineGroups groups = new PipelineGroups(defaultGroup);
        assertThat(groups.getLocal().size()).isEqualTo(1);
        assertThat(groups.getLocal().get(0)).isEqualTo(defaultGroup);
    }

    @Test
    public void shouldGetLocalPartsWhenOriginIsRepo() {
        PipelineConfigs defaultGroup = createGroup("defaultGroup", createPipelineConfig("pipeline1", "stage1"));
        defaultGroup.setOrigins(new RepoConfigOrigin());
        PipelineGroups groups = new PipelineGroups(defaultGroup);
        assertThat(groups.getLocal().size()).isEqualTo(0);
        assertThat(groups.getLocal().isEmpty()).isTrue();
    }

    @Test
    public void shouldGetLocalPartsWhenOriginIsMixed() {
        PipelineConfigs localGroup = createGroup("defaultGroup", createPipelineConfig("pipeline1", "stage1"));
        localGroup.setOrigins(new FileConfigOrigin());

        PipelineConfigs remoteGroup = createGroup("defaultGroup", createPipelineConfig("pipeline2", "stage1"));
        remoteGroup.setOrigins(new RepoConfigOrigin());

        MergePipelineConfigs mergePipelineConfigs = new MergePipelineConfigs(localGroup, remoteGroup);
        PipelineGroups groups = new PipelineGroups(mergePipelineConfigs);

        assertThat(groups.getLocal().size()).isEqualTo(1);
        assertThat(groups.getLocal()).contains(localGroup);
    }

    @Test
    public void shouldFindGroupByPipelineName() {
        PipelineConfig p1Config = createPipelineConfig("pipeline1", "stage1");
        PipelineConfig p2Config = createPipelineConfig("pipeline2", "stage1");
        PipelineConfig p3Config = createPipelineConfig("pipeline3", "stage1");

        PipelineConfigs group1 = createGroup("group1", p1Config, p2Config);
        PipelineConfigs group2 = createGroup("group2", p3Config);

        PipelineGroups groups = new PipelineGroups(group1, group2);

        assertThat(groups.findGroupByPipeline(new CaseInsensitiveString("pipeline1"))).isEqualTo(group1);
        assertThat(groups.findGroupByPipeline(new CaseInsensitiveString("pipeline2"))).isEqualTo(group1);
        assertThat(groups.findGroupByPipeline(new CaseInsensitiveString("pipeline3"))).isEqualTo(group2);
    }

    @Test
    public void shouldDeleteGroupWhenEmpty() {
        PipelineConfigs group = createGroup("group", new PipelineConfig[]{});

        PipelineGroups groups = new PipelineGroups(group);
        groups.deleteGroup("group");

        assertThat(groups.size()).isEqualTo(0);
    }

    @Test
    public void shouldDeleteGroupWithSameNameWhenEmpty() {
        PipelineConfigs group = createGroup("group", new PipelineConfig[]{});
        group.setAuthorization(new Authorization(new ViewConfig(new AdminUser(new CaseInsensitiveString("user")))));

        PipelineGroups groups = new PipelineGroups(group);
        groups.deleteGroup("group");

        assertThat(groups.size()).isEqualTo(0);
    }

    @Test
    public void shouldThrowExceptionWhenDeletingGroupWhenNotEmpty() {
        PipelineConfig p1Config = createPipelineConfig("pipeline1", "stage1");

        PipelineConfigs group = createGroup("group", p1Config);

        PipelineGroups groups = new PipelineGroups(group);

        assertThrows(UnprocessableEntityException.class, () -> groups.deleteGroup("group"));
    }
}
