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

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.StageConfig;
import com.thoughtworks.go.config.TimerConfig;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.PackageMaterialConfig;
import com.thoughtworks.go.config.materials.ScmMaterial;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterial;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.domain.materials.ModifiedAction;
import com.thoughtworks.go.domain.scm.SCM;
import com.thoughtworks.go.helper.GoConfigMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.helper.StageConfigMother;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.util.GoConfigFileHelper;
import org.hibernate.Query;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import java.io.File;
import java.time.Instant;
import java.util.*;

import static com.thoughtworks.go.domain.config.CaseInsensitiveStringMother.str;
import static java.time.temporal.ChronoUnit.HOURS;

public class ScheduleTestUtil {

    public static final String DEFAULT_GROUP = "test-group";
    private final TransactionTemplate transactionTemplate;
    private final MaterialRepository materialRepository;
    private final DatabaseAccessHelper dbHelper;
    private final GoConfigFileHelper configHelper;
    private final Instant date;

    public ScheduleTestUtil(TransactionTemplate transactionTemplate, MaterialRepository materialRepository, DatabaseAccessHelper dbHelper, GoConfigFileHelper configHelper) {
        this.transactionTemplate = transactionTemplate;
        this.materialRepository = materialRepository;
        this.dbHelper = dbHelper;
        this.configHelper = configHelper;
        date = Instant.now();
    }

    public Date d(int extraHours) {
        return Date.from(date.plus(extraHours, HOURS));
    }

    private Material mw(Material material) {
        material = GoConfigMother.deepClone(material);
        return material;
    }

    public Material mw(AddedPipeline pipeline) {
        return mw(pipeline.material);
    }

    public MaterialRevision mr(Material material, final boolean changed, final String... revs) {
        List<Modification> modifications = new ArrayList<>();
        for (String rev : revs) {
            Modification mod = modForRev(rev);
            modifications.add(0, mod);
        }
        MaterialRevision materialRevision = new MaterialRevision(material, modifications);
        if (changed) {
            materialRevision.markAsChanged();
        }
        return materialRevision;
    }

    public MaterialRevision mr(AddedPipeline pipeline, final boolean changed, final String endRev) {
        return mr(pipeline.material, changed, endRev);
    }

    public String runAndPassWithGivenMDUTimestampAndRevisionObjects(final AddedPipeline pipeline, final Date mduAt, final RevisionsForMaterial... revisions) {
        final Pipeline instance = scheduleWith(pipeline, revisions);
        return pass(pipeline, mduAt, instance);
    }

    public String runAndPass(final AddedPipeline pipeline, String... revisions) {
        return runAndPassWithGivenMDUTimestampAndRevisionStrings(pipeline, Date.from(date), revisions);
    }

    public String runAndPassWithGivenMDUTimestampAndRevisionStrings(final AddedPipeline pipeline, final Date mduAt, final String... revisions) {
        final Pipeline instance = scheduleWith(pipeline, revisions);
        return pass(pipeline, mduAt, instance);
    }

    public Pipeline runAndPassAndReturnPipelineInstance(final AddedPipeline pipeline, final Date mduAt, final String... revisions) {
        final Pipeline instance = scheduleWith(pipeline, revisions);
        pass(pipeline, mduAt, instance);
        return instance;
    }

    public String runAndFail(final AddedPipeline pipeline, @SuppressWarnings("unused") final Date mduAt, final String... revisions) {
        final Pipeline instance = scheduleWith(pipeline, revisions);
        return fail(pipeline, instance);
    }

    public String rerunStageAndCancel(Pipeline pipeline, StageConfig stageConfig) {
        Stage stage = dbHelper.scheduleStage(pipeline, stageConfig);
        dbHelper.cancelStage(stage);
        return stage.getIdentifier().getStageLocator();
    }

    private String pass(final AddedPipeline pipeline, final Date mduAt, final Pipeline instance) {
        dbHelper.pass(instance);
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                for (Stage stage : instance.getStages()) {
                    final String dmrRevStr = new StageIdentifier(new PipelineIdentifier(instance.getName(), instance.getCounter(), instance.getName() + instance.getCounter()), stage.getName(), "1").getStageLocator();
                    Modification modification = new Modification(mduAt, dmrRevStr, instance.getLabel(), instance.getId());
                    materialRepository.saveMaterialRevision(new MaterialRevision(pipeline.material, modification));
                }
            }
        });
        return new StageIdentifier(new PipelineIdentifier(instance.getName(), instance.getCounter(), instance.getName() + instance.getCounter()), pipeline.material.getStageName().toString(), "1").getStageLocator();
    }

    private String fail(final AddedPipeline pipeline, final Pipeline instance) {
        dbHelper.failStage(instance.getFirstStage());
        //should not add dmr to database because it failed.
        return new StageIdentifier(new PipelineIdentifier(instance.getName(), instance.getCounter(), instance.getName() + instance.getCounter()), CaseInsensitiveString.str(
            pipeline.material.getStageName()), "1").getStageLocator();
    }

    public MaterialRevisions mrs(MaterialRevision... revs) {
        MaterialRevisions revisions = new MaterialRevisions();
        for (MaterialRevision rev : revs) {
            revisions.addRevision(rev);
        }
        return revisions;
    }

    public <T extends ScmMaterial> T wf(T material, String folder) {
        material.setFolder(folder);
        return material;
    }

    public TimerConfig timer(String timerSpec, boolean shouldTriggerOnlyOnNewMaterials) {
        return new TimerConfig(timerSpec, shouldTriggerOnlyOnNewMaterials);
    }

    @SafeVarargs
    public final RevisionsForMaterial[] getRevisionsForMaterials(List<String>... materialRevisions) {
        List<RevisionsForMaterial> revisionsForMaterials = new ArrayList<>();
        for (List<String> materialRevision : materialRevisions) {
            revisionsForMaterials.add(new RevisionsForMaterial(materialRevision));
        }
        return revisionsForMaterials.toArray(new RevisionsForMaterial[0]);
    }

    public AddedPipeline renamePipelineAndFirstStage(AddedPipeline pipeline, String newPipelineName, String stageName) {
        PipelineConfig oldConfig = pipeline.config;
        configHelper.removePipeline(CaseInsensitiveString.str(oldConfig.name()));
        PipelineConfig newPipeline = PipelineConfigMother.renamePipeline(oldConfig, newPipelineName);
        StageConfigMother.renameStage(newPipeline.get(0), stageName);
        configHelper.addPipeline(newPipeline);
        return new AddedPipeline(newPipeline, pipeline.material);
    }

    protected static class RevisionsForMaterial {

        List<String> revs;

        private RevisionsForMaterial(List<String> revs) {
            this.revs = revs;
        }

        @Override
        public String toString() {
            return "RevisionsForMaterial{" +
                "revs=" + revs +
                '}';
        }

    }

    public RevisionsForMaterial[] rs(String... revs) {
        return new RevisionsForMaterial[]{new RevisionsForMaterial(Arrays.asList(revs))};
    }

    public List<String> revisions(String... revs) {
        return Arrays.asList(revs);
    }

    private Pipeline scheduleWith(AddedPipeline pipeline, RevisionsForMaterial... revisions) {
        Map<Material, List<Modification>> modMap = new HashMap<>();
        Map<Modification, Modification> identityMap = new HashMap<>();

        MaterialRevisions buildCause = new MaterialRevisions();
        int i = 0;
        for (Material material : new MaterialConfigConverter().toMaterials(pipeline.config.materialConfigs())) {
            List<Modification> modifications = new ArrayList<>();
            for (Modification modification : modForRev(revisions[i++])) {
                if (!identityMap.containsKey(modification)) {
                    identityMap.put(modification, modification);
                }
                modifications.add(identityMap.get(modification));
            }
            MaterialInstance modificationsMaterialInstance = modifications.get(0).getMaterialInstance();
            if (modMap.containsKey(material)) {
                modifications = modMap.get(material);
            } else {
                modMap.put(material, modifications);
            }
            MaterialInstance configuredMaterialInstance = materialRepository.findOrCreateFrom(material);
            if (!configuredMaterialInstance.equals(modificationsMaterialInstance)) {
                throw new RuntimeException(
                    "Please fix the revision order to match material configuration order. Revision given for: " + modificationsMaterialInstance + " against configured material: " + configuredMaterialInstance);
            }
            buildCause.addRevision(new MaterialRevision(material, modifications));
        }

        return dbHelper.schedulePipelineWithAllStages(pipeline.config, BuildCause.createWithModifications(buildCause, "loser"));
    }


    private List<Modification> modForRev(final RevisionsForMaterial revision) {
        return materialRepository.getHibernateTemplate().execute(session -> {
            Query q = session.createQuery("from Modification where revision in (:in) order by id desc");
            q.setParameterList("in", revision.revs);
            @SuppressWarnings("unchecked") List<Modification> list = q.list();
            if (list.isEmpty()) {
                throw new RuntimeException("you are trying to load revision " + revision + " which doesn't exist");
            }
            return list;
        });
    }

    public Pipeline scheduleWith(AddedPipeline pipeline, String... revisions) {
        RevisionsForMaterial[] revsForMat = new RevisionsForMaterial[revisions.length];
        for (int i = 0; i < revisions.length; i++) {
            revsForMat[i] = rs(revisions[i])[0];
        }
        return scheduleWith(pipeline, revsForMat);
    }

    public String runAndFail(final AddedPipeline pipeline, String... revisions) {
        return runAndFail(pipeline, Date.from(date), revisions);
    }

    private Modification modForRev(final String revision) {
        return materialRepository.getHibernateTemplate().execute(session -> {
            Query q = session.createQuery("from Modification where revision = ?");
            q.setParameter(0, revision);
            @SuppressWarnings("unchecked") List<Modification> list = q.list();
            if (list.isEmpty()) {
                throw new RuntimeException("you are trying to load revision " + revision + " which doesn't exist");
            }
            return list.get(0);
        });
    }

    public static final class AddedPipeline {
        public final PipelineConfig config;
        public final DependencyMaterial material;

        public AddedPipeline(PipelineConfig config, DependencyMaterial material) {
            this.config = config;
            this.material = material;
        }

        public MaterialConfig materialConfig() {
            return material.config();
        }
    }

    public static final class MaterialDeclaration {
        final Material material;
        final String dest;

        private MaterialDeclaration(Material material) {
            this(material, null);
        }

        public MaterialDeclaration(Material material, String dest) {
            this.material = material;
            this.dest = dest;
        }

        public MaterialConfig materialConfig() {
            return material.config();
        }
    }

    public MaterialDeclaration m(Material material, String dest) {
        return new MaterialDeclaration(material, dest);
    }

    public MaterialDeclaration m(Material material) {
        return new MaterialDeclaration(material);
    }

    public MaterialDeclaration m(AddedPipeline pipeline) {
        return m(pipeline.material);
    }

    public MaterialDeclaration m(AddedPipeline pipeline, String materialName) {
        return m(new DependencyMaterial(new CaseInsensitiveString(materialName), pipeline.material.getPipelineName(), pipeline.material.getStageName()));
    }

    public AddedPipeline saveConfigWithTimer(String pipelineName, TimerConfig timer, MaterialDeclaration... materialDeclaration) {
        String stageName = AutoTriggerDependencyResolutionTest.STAGE_NAME;
        MaterialConfigs materialConfigs = new MaterialConfigs();
        for (MaterialDeclaration mDecl : materialDeclaration) {
            MaterialConfig materialConfig = GoConfigMother.deepClone(mDecl.material.config());
            materialConfigs.add(materialConfig);
        }
        PipelineConfig cfg = configHelper.addPipelineWithGroupAndTimer(DEFAULT_GROUP, pipelineName, materialConfigs, stageName, timer, "job1");
        return new AddedPipeline(cfg, new DependencyMaterial(str(pipelineName), str(stageName)));
    }

    public AddedPipeline saveConfigWithGroup(String groupName, String pipelineName, MaterialDeclaration... materialDecls) {
        return saveConfigWith(groupName, pipelineName, AutoTriggerDependencyResolutionTest.STAGE_NAME, materialDecls);
    }

    public AddedPipeline saveConfigWith(String pipelineName, String stageName, MaterialDeclaration... materialDecls) {
        return saveConfigWith(DEFAULT_GROUP, pipelineName, stageName, materialDecls);
    }

    public AddedPipeline saveConfigWith(PipelineConfig pipelineConfig) {
        return new AddedPipeline(configHelper.addPipeline(DEFAULT_GROUP, pipelineConfig), null);
    }

    public AddedPipeline saveConfigWith(String pipelineName, String stageName, MaterialDeclaration materialDeclaration, String[] builds) {
        MaterialConfigs materialConfigs = new MaterialConfigs();
        MaterialConfig materialConfig = GoConfigMother.deepClone(materialDeclaration.material.config());
        materialConfigs.add(materialConfig);
        PipelineConfig cfg = configHelper.addPipelineWithGroup(DEFAULT_GROUP, pipelineName, materialConfigs, stageName, builds);
        return new AddedPipeline(cfg, new DependencyMaterial(str(pipelineName), str(stageName)));
    }

    public void addPackageDefinition(PackageMaterialConfig pkgMaterialConfig) {
        configHelper.addPackageDefinition(pkgMaterialConfig);
    }

    public void addSCMConfig(SCM scmConfig) {
        configHelper.addSCMConfig(scmConfig);
    }

    public AddedPipeline saveConfigWith(String pipelineName, MaterialDeclaration... materialDecls) {
        return saveConfigWith(DEFAULT_GROUP, pipelineName, AutoTriggerDependencyResolutionTest.STAGE_NAME, materialDecls);
    }

    private AddedPipeline saveConfigWith(String groupName, String pipelineName, String stageName, MaterialDeclaration... materialDecls) {
        MaterialConfigs materialConfigs = new MaterialConfigs();
        for (MaterialDeclaration mDecl : materialDecls) {
            MaterialConfig materialConfig = GoConfigMother.deepClone(mDecl.material.config());
            materialConfigs.add(materialConfig);
        }
        PipelineConfig cfg = configHelper.addPipelineWithGroup(groupName, pipelineName, materialConfigs, stageName, "job1");
        return new AddedPipeline(cfg, new DependencyMaterial(str(pipelineName), str(stageName)));
    }

    public AddedPipeline changeStagenameForToPipeline(String pipelineName, String oldStageName, String newStageName) {
        PipelineConfig cfg = configHelper.changeStageNameForToPipeline(pipelineName, oldStageName, newStageName);
        return new AddedPipeline(cfg, new DependencyMaterial(str(pipelineName), str(newStageName)));
    }

    public AddedPipeline addStageToPipeline(CaseInsensitiveString pipelineName, String stageName) {
        PipelineConfig config = configHelper.addStageToPipeline(pipelineName.toString(), stageName);
        return new AddedPipeline(config, new DependencyMaterial(pipelineName, new CaseInsensitiveString(stageName)));
    }


    public AddedPipeline addMaterialToPipeline(AddedPipeline pipeline, MaterialDeclaration mDecl) {
        MaterialConfig materialConfig = GoConfigMother.deepClone(mDecl.materialConfig());
        PipelineConfig cfg = configHelper.addMaterialToPipeline(pipeline.config.name().toString(), materialConfig);
        return new AddedPipeline(cfg, pipeline.material);
    }

    public AddedPipeline removeMaterialFromPipeline(AddedPipeline pipeline, MaterialDeclaration mDecl) {
        MaterialConfig materialConfig = GoConfigMother.deepClone(mDecl.materialConfig());
        PipelineConfig cfg = configHelper.removeMaterialFromPipeline(pipeline.config.name().toString(), materialConfig);
        return new AddedPipeline(cfg, pipeline.material);
    }

    public void checkinInOrder(final Material material, final String... revisions) {
        checkinInOrder(material, Date.from(date), revisions);
    }

    public void checkinInOrder(final Material material, final Date dateOfCheckin, final String... revisions) {
        transactionTemplate.execute(status -> {
            for (int i = 0; i < revisions.length; i++) {
                String revision = revisions[i];
                materialRepository.saveMaterialRevision(new MaterialRevision(material,
                    new Modification("loser number " + i, "commit " + i, "e" + i + "@mail", Date.from(dateOfCheckin.toInstant().plus(i, HOURS)), revision)));
            }
            return null;
        });
    }

    public void checkinFile(final Material material, final String revision, final File file, final ModifiedAction modifiedAction) {
        checkinFiles(material, revision, List.of(file), modifiedAction);
    }

    public void checkinFiles(final Material material, final String revision, final List<File> files, final ModifiedAction modifiedAction) {
        transactionTemplate.execute(status -> {
            Modification modification = new Modification("user", "comment", "a@b.com", Date.from(date), revision);
            for (File file : files) {
                modification.createModifiedFile(file.getName(), file.getParent(), modifiedAction);
            }
            materialRepository.saveMaterialRevision(new MaterialRevision(material, modification));
            return null;
        });
    }

    public void checkinFiles(final Material material, final String revision, final List<File> files, final ModifiedAction modifiedAction, final Instant date) {
        transactionTemplate.execute(status -> {
            Modification modification = new Modification("user", "comment", "a@b.com", Date.from(date), revision);
            for (File file : files) {
                modification.createModifiedFile(file.getName(), file.getParent(), modifiedAction);
            }
            materialRepository.saveMaterialRevision(new MaterialRevision(material, modification));
            return null;
        });
    }
}
