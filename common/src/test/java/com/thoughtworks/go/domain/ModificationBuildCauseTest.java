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

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterial;
import com.thoughtworks.go.config.materials.svn.SvnMaterial;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.helper.MaterialConfigsMother;
import com.thoughtworks.go.helper.MaterialsMother;
import com.thoughtworks.go.helper.ModificationsMother;
import com.thoughtworks.go.server.domain.Username;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static com.thoughtworks.go.helper.ModificationsMother.multipleModificationList;
import static com.thoughtworks.go.helper.ModificationsMother.multipleModifications;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public class ModificationBuildCauseTest {

    private BuildCause buildCause;

    @BeforeEach
    public void setUp() {
        MaterialRevisions materialRevisions = multipleModifications();
        buildCause = BuildCause.createWithModifications(materialRevisions, "");
    }

    @Test
    public void shouldAggregateUserNameFromModifications() {
        String message = String.format("modified by %s", ModificationsMother.MOD_USER_WITH_HTML_CHAR);
        assertThat(buildCause.getBuildCauseMessage()).isEqualTo(message);
    }

    @Test
    public void shouldReturnBuildCauseMessageForLegacyDependencyRevision() {
        MaterialRevisions revisions = new MaterialRevisions();
        Modification modification = new Modification(new Date(), "pipelineName/10/stageName/1", "MOCK_LABEL-12", null);
        revisions.addRevision(new DependencyMaterial(new CaseInsensitiveString("cruise"), new CaseInsensitiveString("dev")), modification);
        BuildCause modificationBuildCause = BuildCause.createWithModifications(revisions, "");
        String message = modificationBuildCause.getBuildCauseMessage();
        assertThat(message).contains("triggered by pipelineName/10/stageName/1");
    }

    @Test
    public void shouldReturnBuildCauseMessage() {
        MaterialRevisions revisions = new MaterialRevisions();
        Modification modification = new Modification(new Date(), "pipelineName/123/stageName/1", "MOCK_LABEL-12", null);
        revisions.addRevision(new DependencyMaterial(new CaseInsensitiveString("cruise"), new CaseInsensitiveString("dev")), modification);
        BuildCause modificationBuildCause = BuildCause.createWithModifications(revisions, "");
        String message = modificationBuildCause.getBuildCauseMessage();
        assertThat(message).contains("triggered by pipelineName/123/stageName/1");
    }

    @Test
    public void shouldAggreateUserCommentFromModifications() {
        ModificationSummaries summaries = buildCause.toModificationSummaries();
        String message = summaries.getModification(0).getComment();
        String user = summaries.getModification(0).getUserName();
        assertThat(user).isEqualTo(ModificationsMother.MOD_USER_WITH_HTML_CHAR);
        assertThat(message).isEqualTo(ModificationsMother.MOD_COMMENT_3);
    }

    @Test
    public void shouldDisplayNoModifications() {
        buildCause = BuildCause.createWithModifications(new MaterialRevisions(), "");
        assertThat(buildCause.getBuildCauseMessage()).isEqualTo("No modifications");
    }

    @Test
    public void shouldSafelyGetBuildCausedBy() {
        assertThat(BuildCause.createWithEmptyModifications().getBuildCauseMessage()).isEqualTo("No modifications");
    }

    @Test
    public void shouldGetBuildCausedByIfIsDenpendencyMaterial() {
        MaterialRevisions revisions = new MaterialRevisions();
        Modification modification = new Modification(new Date(), "pipelineName/10/stageName/1", "MOCK_LABEL-12", null);
        revisions.addRevision(new DependencyMaterial(new CaseInsensitiveString("cruise"), new CaseInsensitiveString("dev")), modification);
        assertThat(BuildCause.createWithModifications(revisions, "").getBuildCauseMessage()).isEqualTo("triggered by pipelineName/10/stageName/1");
    }

    @Test
    public void shouldBeInvalidWhenMaterialsFromBuildCauseAreDifferentFromConfigFile() {
        try {
            buildCause.assertMaterialsMatch(new MaterialConfigs(MaterialConfigsMother.hgMaterialConfig()));
            fail("The material from build cause was different from the one in the config");
        } catch (RuntimeException expected) {
        }
    }

    @Test
    public void shouldIncludeUserWhoForcedBuildInManualBuildCause() {
        BuildCause cause = BuildCause.createManualForced(null, new Username(new CaseInsensitiveString("Joe Bloggs")));
        assertThat(cause.getBuildCauseMessage()).contains("Forced by Joe Bloggs");
    }

    @Test
    public void shouldNotAllowNullUsername() {
        BuildCause cause = BuildCause.createManualForced(MaterialRevisions.EMPTY, Username.ANONYMOUS);
        assertThat(cause.getBuildCauseMessage()).contains("Forced by anonymous");
    }

    @Test
    public void shouldNotAllowCreationWithANullUsername() {
        try {
            BuildCause.createManualForced(null, null);
            Assertions.fail("Expected NullPointerException to be thrown");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Username cannot be null");
        }
    }

    @Test
    public void shouldBeValidWithExternalMaterials() {
        SvnMaterial mainRepo = MaterialsMother.svnMaterial("mainRepo");
        MaterialRevisions revisions = new MaterialRevisions();
        revisions.addRevision(mainRepo, multipleModificationList());
        revisions.addRevision(MaterialsMother.svnMaterial("externalRepo"), multipleModificationList());

        buildCause = BuildCause.createWithModifications(revisions, "");
        buildCause.assertMaterialsMatch(new MaterialConfigs(mainRepo.config()));
    }

    @Test
    public void shouldBeInvalidWhenMaterialsFromConfigAreNotInBuildCause() {
        SvnMaterial mainRepo = MaterialsMother.svnMaterial("mainRepo");
        SvnMaterial extRepo = MaterialsMother.svnMaterial("externalRepo");

        MaterialRevisions revisions = new MaterialRevisions();
        revisions.addRevision(mainRepo, multipleModificationList());

        buildCause = BuildCause.createWithModifications(revisions, "");
        try {
            buildCause.assertMaterialsMatch(new MaterialConfigs(mainRepo.config(), extRepo.config()));
            fail("All the materials from config file should be in build cause");
        } catch (Exception expected) {
        }
    }
}
