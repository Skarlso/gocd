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

import com.thoughtworks.go.config.materials.git.GitMaterialConfig;
import com.thoughtworks.go.config.validation.GoConfigValidity;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.domain.packagerepository.PackageDefinition;
import com.thoughtworks.go.domain.packagerepository.PackageRepository;
import com.thoughtworks.go.helper.ConfigFileFixture;
import com.thoughtworks.go.security.CryptoException;
import com.thoughtworks.go.security.GoCipher;
import com.thoughtworks.go.security.ResetCipher;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.service.ConfigRepository;
import com.thoughtworks.go.util.*;
import org.apache.commons.lang3.StringUtils;
import org.jdom2.Document;
import org.jdom2.filter.ElementFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.xmlunit.assertj.XmlAssert;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static com.thoughtworks.go.config.PipelineConfig.LOCK_VALUE_LOCK_ON_FAILURE;
import static com.thoughtworks.go.config.PipelineConfig.LOCK_VALUE_NONE;
import static com.thoughtworks.go.helper.ConfigFileFixture.configWithArtifactSourceAs;
import static com.thoughtworks.go.helper.ConfigFileFixture.pipelineWithAttributes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@ExtendWith(ResetCipher.class)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class GoConfigMigrationIntegrationTest {
    private File configFile;
    ConfigRepository configRepository;
    @Autowired
    private SystemEnvironment systemEnvironment;
    @Autowired
    private GoConfigService goConfigService;
    @Autowired
    private ServerHealthService serverHealthService;

    private MagicalGoConfigXmlLoader loader;

    @BeforeEach
    public void setUp(@TempDir File temporaryFolder, ResetCipher resetCipher) throws Exception {
        configFile = new File(temporaryFolder, "cruise-config.xml");
        new SystemEnvironment().setProperty(SystemEnvironment.CONFIG_FILE_PROPERTY, configFile.getAbsolutePath());
        GoConfigFileHelper.clearConfigVersions();
        configRepository = new ConfigRepository(systemEnvironment);
        configRepository.initialize();
        serverHealthService.removeAllLogs();
        loader = new MagicalGoConfigXmlLoader(new ConfigCache(), ConfigElementImplementationRegistryMother.withNoPlugins());
        resetCipher.setupDESCipherFile();
        resetCipher.setupAESCipherFile();
    }

    @AfterEach
    public void tearDown() throws Exception {
        GoConfigFileHelper.clearConfigVersions();
        configFile.delete();
        serverHealthService.removeAllLogs();
    }

    @Test
    public void shouldMigrateConfigContentAsAString() {
        String newContent = new GoConfigMigration(new TimeProvider())
                .upgradeIfNecessary(ConfigFileFixture.VERSION_0);
        assertThat(newContent).contains("schemaVersion=\"" + GoConfigSchema.currentSchemaVersion() + "\"");
    }

    @Test
    public void shouldNotMigrateConfigContentAsAStringWhenAlreadyUpToDate() {
        GoConfigMigration configMigration = new GoConfigMigration(new TimeProvider());
        String newContent = configMigration.upgradeIfNecessary(ConfigFileFixture.VERSION_0);
        assertThat(newContent).isEqualTo(configMigration.upgradeIfNecessary(newContent));
    }

    @Test
    public void shouldMigrateToRevision22() throws Exception {
        final String content = contentFromResource("cruise-config-escaping-migration-test-fixture.xml");

        String migratedContent = ConfigMigrator.migrate(content, 21, 22);

        String expected = content.replaceAll("(?<!do_not_sub_)#", "##").replace("<cruise schemaVersion=\"21\">", "<cruise schemaVersion=\"22\">");
        assertStringsIgnoringCarriageReturnAreEqual(expected, migratedContent);
    }

    @Test
    public void shouldMigrateToRevision28() throws Exception {
        final String content = contentFromResource("no-tracking-tool-group-holder-config.xml");

        String migratedContent = migrateXmlString(content, 27);

        assertThat(migratedContent).contains("\"http://foo.bar/baz/${ID}\"");
        assertThat(migratedContent).contains("\"http://hello.world/${ID}/hello\"");
    }

    @Test
    public void shouldMigrateToRevision34() throws Exception {
        final String content = contentFromResource("svn-p4-with-parameterized-passwords.xml");

        String migratedContent = ConfigMigrator.migrate(content, 22, 34);

        String expected = content.replaceAll("#\\{jez_passwd\\}", "badger")
                .replace("<cruise schemaVersion=\"22\">", "<cruise schemaVersion=\"34\">")
                .replaceAll("##", "#");
        assertStringsIgnoringCarriageReturnAreEqual(expected, migratedContent);
    }

    @Test
    public void shouldMigrateToRevision35_escapeHash() throws Exception {
        final String content = contentFromResource("escape_param_for_nant_p4.xml").trim();

        String migratedContent = ConfigMigrator.migrate(content, 22, 35);

        String expected = content.replace("<cruise schemaVersion=\"22\">", "<cruise schemaVersion=\"35\">")
                .replace("<view>##foo#</view>", "<view>####foo##</view>").replace("nantpath=\"#foo##\"", "nantpath=\"##foo####\"");
        assertStringsIgnoringCarriageReturnAreEqual(expected, migratedContent);
    }

    @Test
    public void shouldMigrateToRevision58_deleteVMMS() {
        String migratedContent = ConfigMigrator.migrate(ConfigFileFixture.WITH_VMMS_CONFIG, 50, 58);

        assertThat(migratedContent.contains("vmms")).isFalse();
    }

    @Test
    public void shouldMigrateExecTaskArgValueToTextNode() {
        String migratedContent = migrateXmlString(ConfigFileFixture.VALID_XML_3169, 14);
        assertThat(migratedContent).contains("<arg>test</arg>");
    }

    @Test
    public void shouldMigrateToRevision23_IsLockedIsFalseByDefault() {
        final String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="22">
                    <server artifactsdir="artifacts"/>
                    <pipelines>
                      <pipeline name="in_env">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="some_stage">
                             <jobs>
                             <job name="some_job">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                      <pipeline name="not_in_env">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="some_stage">
                             <jobs>
                             <job name="some_job">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                      <pipeline name="in_env_unLocked" isLocked="false">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="some_stage">
                             <jobs>
                             <job name="some_job">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                    <environments>
                    <environment name="some_env">
                        <pipelines>
                            <pipeline name="in_env"/>
                            <pipeline name="in_env_unLocked"/>
                        </pipelines>
                    </environment>
                    </environments>
                 </cruise>""";
        String migratedContent = ConfigMigrator.migrate(content, 22, 23);

        assertThat(migratedContent).contains("<pipeline isLocked=\"true\" name=\"in_env\">");
        assertThat(migratedContent).contains("<pipeline isLocked=\"false\" name=\"in_env_unLocked\">");
        assertThat(migratedContent).contains("<pipeline name=\"not_in_env\">");
    }

    @Test
    public void shouldSetServerId_toARandomUUID_ifServerTagDoesntExist() {
        GoConfigService.XmlPartialSaver<CruiseConfig> fileSaver = goConfigService.fileSaver(true);
        GoConfigValidity configValidity = fileSaver.saveXml("<cruise schemaVersion='" + 53 + "'>\n"
                + "</cruise>", goConfigService.configFileMd5());
        assertThat(configValidity.isValid()).as("Has no error").isTrue();

        CruiseConfig config = goConfigService.getCurrentConfig();
        ServerConfig server = config.server();

        assertThat(server.getServerId().matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")).isTrue();
    }

    @Test
    public void shouldSetServerId_toARandomUUID_ifOneDoesntExist() {
        GoConfigService.XmlPartialSaver<CruiseConfig> fileSaver = goConfigService.fileSaver(true);
        GoConfigValidity configValidity = fileSaver.saveXml("""
                <cruise schemaVersion='55'>
                <server artifactsdir="logs" siteUrl="http://go-server-site-url:8153" secureSiteUrl="https://go-server-site-url" jobTimeout="60">
                  </server>
                </cruise>""", goConfigService.configFileMd5());
        assertThat(configValidity.isValid()).as("Has no error").isTrue();

        CruiseConfig config = goConfigService.getCurrentConfig();
        ServerConfig server = config.server();

        assertThat(server.getServerId().matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")).isTrue();
    }

    @Test
    public void shouldLoadServerId_ifOneExists() {
        GoConfigService.XmlPartialSaver<CruiseConfig> fileSaver = goConfigService.fileSaver(true);
        GoConfigValidity configValidity = fileSaver.saveXml("""
                <cruise schemaVersion='55'>
                <server artifactsdir="logs" siteUrl="http://go-server-site-url:8153" secureSiteUrl="https://go-server-site-url" jobTimeout="60" serverId="foo">
                  </server>
                </cruise>""", goConfigService.configFileMd5());
        assertThat(configValidity.isValid()).as("Has no error").isTrue();

        CruiseConfig config = goConfigService.getCurrentConfig();
        ServerConfig server = config.server();

        assertThat(server.getServerId()).isEqualTo("foo");
    }

    @Test
    public void shouldRemoveAllLuauConfigurationFromConfig() throws Exception {
        String configString =
                """
                        <cruise schemaVersion='66'>
                        <server siteUrl='https://hostname'>
                        <security>
                              <luau url='https://luau.url.com' clientKey='0d010cf97ec505ee3788a9b5b8cf71d482c394ae88d32f0333' authState='authorized' />
                              <ldap uri='ldap' managerDn='managerDn' encryptedManagerPassword='+XhtUNvVAxJdHGF4qQGnWw==' searchFilter='(sAMAccountName={0})'>
                                <bases>
                                  <base value='ou=Enterprise,ou=Principal,dc=corporate,dc=thoughtworks,dc=com' />
                                </bases>
                              </ldap>
                              <roles>
                                 <role name='luau-role'><groups><luauGroup>luau-group</luauGroup></groups></role>
                                 <role name='ldap-role'><users><user>some-user</user></users></role>
                        </roles>
                        </security>
                        </server>
                        </cruise>""";

        String migratedContent = migrateXmlString(configString, 66);
        Document document = XmlUtils.buildXmlDocument(migratedContent);

        assertThat(document.getDescendants(new ElementFilter("luau")).hasNext()).isFalse();
        assertThat(document.getDescendants(new ElementFilter("groups")).hasNext()).isFalse();
    }

    @Test
    public void shouldAddAttributeAutoUpdateOnPackage_AsPartOfMigration68() throws Exception {
        String configString =
                """
                        <cruise schemaVersion='67'>
                        <repositories>
                        	<repository id='2ef830d7-dd66-42d6-b393-64a84646e557' name='GoYumRepo'>
                        		<pluginConfiguration id='yum' version='1' />
                               <configuration>
                                   <property>
                                       <key>REPO_URL</key>
                                       <value>http://random-yum-repo/go/yum/no-arch</value>
                                       </property>
                               </configuration>
                        	    <packages>
                                   <package id='88a3beca-cbe2-4c4d-9744-aa0cda3f371c' name='1'>
                                       <configuration>
                                           <property>
                                               <key>REPO_URL</key>
                                               <value>http://random-yum-repo/go/yum/no-arch</value>
                                           </property>
                                       </configuration>
                                   </package>
                        	     </packages>
                           </repository>
                        </repositories>
                        </cruise>""";

        String migratedContent = migrateXmlString(configString, 67);
        GoConfigHolder holder = loader.loadConfigHolder(migratedContent);
        PackageRepository packageRepository = holder.config.getPackageRepositories().find("2ef830d7-dd66-42d6-b393-64a84646e557");
        PackageDefinition aPackage = packageRepository.findPackage("88a3beca-cbe2-4c4d-9744-aa0cda3f371c");
        assertThat(aPackage.isAutoUpdate()).isTrue();
    }

    @Test
    public void shouldAllowAuthorizationUnderEachTemplate_asPartOfMigration69() throws Exception {
        String configString =
                """
                        <cruise schemaVersion='69'>
                           <templates>
                               <pipeline name='template-name'>
                                   <authorization>
                                       <admins>
                                           <user>admin1</user>
                                           <user>admin2</user>
                                       </admins>
                                   </authorization>
                                   <stage name='stage-name'>
                                       <jobs>
                                           <job name='job-name'/>
                                       </jobs>
                                   </stage>
                               </pipeline>
                           </templates>
                        </cruise>""";

        String migratedContent = migrateXmlString(configString, 69);
        assertThat(migratedContent).contains("<authorization>");
        CruiseConfig configForEdit = loader.loadConfigHolder(migratedContent).configForEdit;
        PipelineTemplateConfig template = configForEdit.getTemplateByName(new CaseInsensitiveString("template-name"));
        Authorization authorization = template.getAuthorization();
        assertThat(authorization).isNotNull();
        assertThat(authorization.hasAdminsDefined()).isTrue();
        assertThat(authorization.getAdminsConfig().getUsers()).contains(new AdminUser(new CaseInsensitiveString("admin1")), new AdminUser(new CaseInsensitiveString("admin2")));
    }

    @Test
    public void shouldRemoveLicenseSection_asPartOfMigration72() {
        String licenseUser = "Go UAT Thoughtworks";
        String configWithLicenseSection =
                "<cruise schemaVersion='71'>\n" +
                        "<server artifactsdir=\"logs\" commandRepositoryLocation=\"default\" serverId=\"dev-id\">\n" +
                        "    <license user=\"" + licenseUser + "\">kTr+1ZBEr/5EiWlADIM6gUMtedtaLKPh6WRGp/2qISy1QczZpqJP5vmfydvx\n" +
                        "            Hq6o5X+nrb69sGOaBAvmjJ4cZBaIq+/4Yb+ufQCUM2DkacG/BjdEDpIoPHRA\n" +
                        "            fUnmjddxMnVKh2CW7gn7ZnmZUyasS9621UH2uNsfms3gfIK/1PRfbdrFuu5d\n" +
                        "            6xQEiEhjRVhKGFH4Uq2Cb0BVYCnQ+9eJ7WNwcV4pZCt1AoaMAxo4dox4NLpS\n" +
                        "            pKtgCp1Is/7ui+MGzKEyLCuO/LLMt0ChxWSN62vXiwdW3jl2HCEsLpb70FYR\n" +
                        "            Gj8eif3vuIB2rkOSvLkiAXqDFdEBEmb+GNV3nA4qOw==" +
                        "</license>\n" +
                        "  </server>\n" +
                        "</cruise>";

        String migratedContent = migrateXmlString(configWithLicenseSection, 71);
        assertThat(migratedContent).doesNotContain("license");
        assertThat(migratedContent).doesNotContain(licenseUser);
    }

    @Test
    public void shouldPerformNOOPWhenNoLicenseIsPresent_asPartOfMigration72() {
        String licenseUser = "Go UAT Thoughtworks";
        String configWithLicenseSection =
                """
                        <cruise schemaVersion='71'>
                        <server artifactsdir="logs" commandRepositoryLocation="default" serverId="dev-id">
                          </server>
                        </cruise>""";

        String migratedContent = migrateXmlString(configWithLicenseSection, 71);
        assertThat(migratedContent).doesNotContain("license");
        assertThat(migratedContent).doesNotContain(licenseUser);
    }

    @Test
    public void shouldNotRemoveNonEmptyUserTags_asPartOfMigration78() {
        String configXml =
                """
                        <cruise schemaVersion='77'>
                          <pipelines group='first'>
                            <authorization>
                               <view>
                                 <user>abc</user>
                               </view>
                            </authorization>
                            <pipeline name='Test' template='test_template'>
                              <materials>
                                <hg url='../manual-testing/ant_hg/dummy' />
                              </materials>
                             </pipeline>
                          </pipelines>
                        </cruise>""";
        String migratedXml = migrateXmlString(configXml, 77);
        assertThat(migratedXml).contains("<user>");
    }

    @Test
    public void shouldRemoveEmptyTags_asPartOfMigration78() {
        String configXml =
                """
                        <cruise schemaVersion='77'>
                          <pipelines group='first'>
                            <authorization>
                               <view>
                                 <user>foo</user>
                                 <user />
                                 <user>        </user>
                               </view>
                               <operate>
                                  <user></user>
                               </operate>
                            </authorization>
                            <pipeline name='Test' template='test_template'>
                              <materials>
                                <hg url='../manual-testing/ant_hg/dummy' />
                              </materials>
                             </pipeline>
                          </pipelines>
                        </cruise>""";
        String migratedXml = migrateXmlString(configXml, 77);
        assertThat(StringUtils.countMatches(migratedXml, "<user>")).isEqualTo(1);
    }

    @Test
    public void shouldRemoveEmptyTagsRecursively_asPartOfMigration78() {
        String configXml =
                """
                        <cruise schemaVersion='77'>
                          <pipelines group='first'>
                            <authorization>
                               <view>
                                 <user></user>
                               </view>
                            </authorization>
                            <pipeline name='Test' template='test_template'>
                              <materials>
                                <hg url='../manual-testing/ant_hg/dummy' />
                              </materials>
                             </pipeline>
                          </pipelines>
                        </cruise>""";
        String migratedXml = migrateXmlString(configXml, 77);
        assertThat(migratedXml).doesNotContain("<user>");
        assertThat(migratedXml).doesNotContain("<view>");
        assertThat(migratedXml).doesNotContain("<authorization>");
    }

    @Test
    public void shouldAddIdOnConfigRepoAsPartOfMigration94() {
        String configXml = """
                <cruise schemaVersion='93'>
                <config-repos>
                   <config-repo plugin="json.config.plugin">
                     <git url="https://github.com/tomzo/gocd-json-config-example.git" />
                   </config-repo>
                </config-repos>
                </cruise>""";

        String migratedContent = migrateXmlString(configXml, 93);
        assertThat(migratedContent).contains("id=");
    }

    @Test
    public void shouldConvertPluginToPluginIdOnConfigRepoAsPartOfMigration95() {
        String configXml = """
                <cruise schemaVersion='94'>
                <config-repos>
                   <config-repo plugin="json.config.plugin" id="config-repo-1">
                     <git url="https://github.com/tomzo/gocd-json-config-example.git" />
                   </config-repo>
                </config-repos>
                </cruise>""";

        assertThat(configXml).doesNotContain("pluginId=\"json.config.plugin\"");
        String migratedContent = migrateXmlString(configXml, 94);
        assertThat(migratedContent).contains("pluginId=\"json.config.plugin\"");
    }

    @Test
    public void shouldConvertIsLockedAttributeToATristateNamedLockBehavior() {
        String defaultPipeline = pipelineWithAttributes("name=\"default1\"", 97);
        String lockedPipeline = pipelineWithAttributes("name=\"locked1\" isLocked=\"true\"", 97);
        String unLockedPipeline = pipelineWithAttributes("name=\"unlocked1\" isLocked=\"false\"", 97);

        String defaultPipelineAfterMigration = pipelineWithAttributes("name=\"default1\"", 98);
        String lockedPipelineAfterMigration = pipelineWithAttributes("name=\"locked1\" lockBehavior=\"" + LOCK_VALUE_LOCK_ON_FAILURE + "\"", 98);
        String unLockedPipelineAfterMigration = pipelineWithAttributes("name=\"unlocked1\" lockBehavior=\"" + LOCK_VALUE_NONE + "\"", 98);

        assertStringsIgnoringCarriageReturnAreEqual(defaultPipelineAfterMigration, ConfigMigrator.migrate(defaultPipeline, 97, 98));
        assertStringsIgnoringCarriageReturnAreEqual(lockedPipelineAfterMigration, ConfigMigrator.migrate(lockedPipeline, 97, 98));
        assertStringsIgnoringCarriageReturnAreEqual(unLockedPipelineAfterMigration, ConfigMigrator.migrate(unLockedPipeline, 97, 98));
    }

    @Test
    public void shouldNotSupportedUncesseryMaterialFieldsAsPartOfMigration99() {
        String configXml = """
                <cruise schemaVersion='99'>
                <config-repos>
                   <config-repo pluginId="json.config.plugin" id="config-repo-1">
                     <git url="https://github.com/tomzo/gocd-json-config-example.git" dest="dest"/>
                   </config-repo>
                </config-repos>
                </cruise>""";

        String message = "Attribute 'dest' is not allowed to appear in element 'git'.";

        try {
            migrateXmlString(configXml, 99);
            fail(String.format("Expected a failure. Reason: Cruise config file with version 98 is invalid. Unable to upgrade. Message:%s", message));
        } catch (Exception e) {
            assertThat(e.getCause().getMessage()).isEqualTo(message);
        }
    }

    @Test
    public void migration99_shouldMigrateGitMaterialsUnderConfigRepoAndRetainOnlyTheMinimalRequiredAttributes() throws Exception {
        String configXml = """
                <cruise schemaVersion='98'>
                <config-repos>
                   <config-repo pluginId="json.config.plugin" id="config-repo-1">
                      <git url="test-repo" dest='dest' shallowClone='true' autoUpdate='true' invertFilter='true' materialName="foo">
                        <filter>
                          <ignore pattern="asdsd" />
                        </filter>
                      </git>
                   </config-repo>
                </config-repos>
                </cruise>""";

        assertThat(configXml).contains("<filter>");
        assertThat(configXml).contains("dest='dest'");
        assertThat(configXml).contains("autoUpdate='true'");
        assertThat(configXml).contains("invertFilter='true'");
        assertThat(configXml).contains("shallowClone='true'");

        String migratedContent = migrateXmlString(configXml, 98);
        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        GitMaterialConfig materialConfig = (GitMaterialConfig) cruiseConfig.getConfigRepos().getConfigRepo("config-repo-1").getRepo();

        assertThat(migratedContent).doesNotContain("<filter>");
        assertThat(migratedContent).doesNotContain("dest='dest'");
        assertThat(migratedContent).doesNotContain("invertFilter='true'");
        assertThat(migratedContent).doesNotContain("shallowClone='true'");

        assertThat(materialConfig.getFolder()).isNull();
        assertThat(materialConfig.filter().size()).isEqualTo(0);
        assertThat(materialConfig.isAutoUpdate()).isTrue();
        assertThat(materialConfig.isInvertFilter()).isFalse();
        assertThat(materialConfig.isShallowClone()).isFalse();
    }

    @Test
    public void migration99_shouldMigrateSvnMaterialsUnderConfigRepoAndRetainOnlyTheMinimalRequiredAttributes() throws Exception {
        String configXml = """
                <cruise schemaVersion='98'>
                <config-repos>
                   <config-repo pluginId="json.config.plugin" id="config-repo-1">
                      <svn url="test-repo" dest='dest' autoUpdate='true' checkexternals='false' invertFilter='true' materialName="foo">
                        <filter>
                          <ignore pattern="asdsd" />
                        </filter>
                      </svn>
                   </config-repo>
                </config-repos>
                </cruise>""";

        assertThat(configXml).contains("<filter>");
        assertThat(configXml).contains("dest='dest'");
        assertThat(configXml).contains("autoUpdate='true'");
        assertThat(configXml).contains("invertFilter='true'");

        String migratedContent = migrateXmlString(configXml, 98);
        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        MaterialConfig materialConfig = cruiseConfig.getConfigRepos().getConfigRepo("config-repo-1").getRepo();

        assertThat(migratedContent).doesNotContain("<filter>");
        assertThat(migratedContent).doesNotContain("dest='dest'");
        assertThat(migratedContent).doesNotContain("invertFilter='true'");

        assertThat(materialConfig.getFolder()).isNull();
        assertThat(materialConfig.filter().size()).isEqualTo(0);
        assertThat(materialConfig.isAutoUpdate()).isTrue();
        assertThat(materialConfig.isInvertFilter()).isFalse();
    }

    @Test
    public void migration99_shouldMigrateP4MaterialsUnderConfigRepoAndRetainOnlyTheMinimalRequiredAttributes() throws Exception {
        String configXml = """
                <cruise schemaVersion='98'>
                <config-repos>
                   <config-repo pluginId="json.config.plugin" id="config-repo-1">
                      <p4 port="10.18.3.241:9999" username="cruise" password="password" autoUpdate='true' invertFilter='true' dest="dest">
                          <view><![CDATA[//depot/dev/... //lumberjack/...]]></view>
                        <filter>
                          <ignore pattern="asdsd" />
                        </filter>
                      </p4>
                   </config-repo>
                </config-repos>
                </cruise>""";

        assertThat(configXml).contains("dest=\"dest\"");
        assertThat(configXml).contains("<filter>");
        assertThat(configXml).contains("autoUpdate='true'");
        assertThat(configXml).contains("invertFilter='true'");

        String migratedContent = migrateXmlString(configXml, 98);
        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        MaterialConfig materialConfig = cruiseConfig.getConfigRepos().getConfigRepo("config-repo-1").getRepo();

        assertThat(migratedContent).doesNotContain("dest=\"dest\"");
        assertThat(migratedContent).doesNotContain("<filter>");
        assertThat(migratedContent).doesNotContain("invertFilter='true'");

        assertThat(materialConfig.getFolder()).isNull();
        assertThat(materialConfig.filter().size()).isEqualTo(0);
        assertThat(materialConfig.isAutoUpdate()).isTrue();
        assertThat(materialConfig.isInvertFilter()).isFalse();
    }

    @Test
    public void migration99_shouldMigrateHgMaterialsUnderConfigRepoAndRetainOnlyTheMinimalRequiredAttributes() throws Exception {
        String configXml = """
                <cruise schemaVersion='98'>
                <config-repos>
                   <config-repo pluginId="json.config.plugin" id="config-repo-1">
                      <hg url="test-repo" dest='dest' autoUpdate='true' invertFilter='true' materialName="foo">
                        <filter>
                          <ignore pattern="asdsd" />
                        </filter>
                      </hg>
                   </config-repo>
                </config-repos>
                </cruise>""";

        assertThat(configXml).contains("<filter>");
        assertThat(configXml).contains("dest='dest'");
        assertThat(configXml).contains("autoUpdate='true'");
        assertThat(configXml).contains("invertFilter='true'");

        String migratedContent = migrateXmlString(configXml, 98);
        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        MaterialConfig materialConfig = cruiseConfig.getConfigRepos().getConfigRepo("config-repo-1").getRepo();

        assertThat(migratedContent).doesNotContain("<filter>");
        assertThat(migratedContent).doesNotContain("dest='dest'");
        assertThat(migratedContent).doesNotContain("invertFilter='true'");

        assertThat(materialConfig.getFolder()).isNull();
        assertThat(materialConfig.filter().size()).isEqualTo(0);
        assertThat(materialConfig.isAutoUpdate()).isTrue();
        assertThat(materialConfig.isInvertFilter()).isFalse();
    }

    @Test
    public void migration99_shouldMigrateTfsMaterialsUnderConfigRepoAndRetainOnlyTheMinimalRequiredAttributes() throws Exception {
        String configXml = """
                <cruise schemaVersion='98'>
                <config-repos>
                   <config-repo pluginId="json.config.plugin" id="config-repo-1">
                      <tfs url='tfsurl' dest='dest' autoUpdate='true' invertFilter='true' username='foo' password='bar' projectPath='project-path'>
                        <filter>
                          <ignore pattern="asdsd" />
                        </filter>
                      </tfs>
                   </config-repo>
                </config-repos>
                </cruise>""";

        assertThat(configXml).contains("<filter>");
        assertThat(configXml).contains("dest='dest'");
        assertThat(configXml).contains("autoUpdate='true'");
        assertThat(configXml).contains("invertFilter='true'");

        String migratedContent = migrateXmlString(configXml, 98);
        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        MaterialConfig materialConfig = cruiseConfig.getConfigRepos().getConfigRepo("config-repo-1").getRepo();

        assertThat(migratedContent).doesNotContain("<filter>");
        assertThat(migratedContent).doesNotContain("dest='dest'");
        assertThat(migratedContent).doesNotContain("invertFilter='true'");

        assertThat(materialConfig.getFolder()).isNull();
        assertThat(materialConfig.filter().size()).isEqualTo(0);
        assertThat(materialConfig.isAutoUpdate()).isTrue();
        assertThat(materialConfig.isInvertFilter()).isFalse();
    }

    @Test
    public void migration99_shouldMigrateScmMaterialsUnderConfigRepoAndRetainOnlyTheMinimalRequiredAttributes() throws Exception {
        String configXml = """
                <cruise schemaVersion='98'>
                <config-repos>
                   <config-repo pluginId="json.config.plugin" id="config-repo-1">
                      <scm ref='some-ref' dest='dest'>
                        <filter>
                          <ignore pattern="asdsd" />
                        </filter>
                      </scm>
                   </config-repo>
                </config-repos>
                </cruise>""";

        assertThat(configXml).contains("<filter>");
        assertThat(configXml).contains("dest='dest'");

        String migratedContent = migrateXmlString(configXml, 98);
        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        MaterialConfig materialConfig = cruiseConfig.getConfigRepos().getConfigRepo("config-repo-1").getRepo();

        assertThat(migratedContent).doesNotContain("<filter>");
        assertThat(migratedContent).doesNotContain("dest='dest'");

        assertThat(materialConfig.getFolder()).isNull();
        assertThat(materialConfig.filter().size()).isEqualTo(0);

    }

    @Test
    public void shouldRemoveAgentWithDuplicateElasticAgentId_asPartOf102To103Migration() {
        String configXml = """
                <cruise schemaVersion='102'>
                <agents>
                    <agent hostname="hostname" ipaddress="127.0.0.1" uuid="c46a08a7-921c-4e77-b748-6128975a3e7d" elasticAgentId="16649813-4cb3-4682-8702-8e202824dd73" elasticPluginId="elastic-plugin-id" />
                    <agent hostname="hostname" ipaddress="127.0.0.1" uuid="c46a08a7-921c-4e77-b748-6128975a3e7e" elasticAgentId="16649813-4cb3-4682-8702-8e202824dd73" elasticPluginId="elastic-plugin-id" />
                    <agent hostname="hostname" ipaddress="127.0.0.1" uuid="537d36f9-bf4b-48b2-8d09-5d20357d4f16" elasticAgentId="a38d2559-0703-4e69-a30d-a21245d740af" elasticPluginId="elastic-plugin-id" />
                    <agent hostname="hostname" ipaddress="127.0.0.1" uuid="c46a08a7-921c-4e77-b748-6128975a3e7f" elasticAgentId="16649813-4cb3-4682-8702-8e202824dd73" elasticPluginId="elastic-plugin-id" />
                    <agent hostname="hostname" ipaddress="127.0.0.1" uuid="537d36f9-bf4b-48b2-8d09-5d20357d4f17" elasticAgentId="a38d2559-0703-4e69-a30d-a21245d740af" elasticPluginId="elastic-plugin-id" />
                  </agents>
                </cruise>""";

        //before migration should contain 5 elastic agents 3 duplicates
        assertThat(configXml).contains("c46a08a7-921c-4e77-b748-6128975a3e7d");
        assertThat(configXml).contains("537d36f9-bf4b-48b2-8d09-5d20357d4f16");
        assertThat(configXml).contains("c46a08a7-921c-4e77-b748-6128975a3e7e");
        assertThat(configXml).contains("c46a08a7-921c-4e77-b748-6128975a3e7f");
        assertThat(configXml).contains("537d36f9-bf4b-48b2-8d09-5d20357d4f17");

        String migratedContent = ConfigMigrator.migrate(configXml, 102, 103);

        //after migration should contain 2 unique elastic agents
        assertThat(configXml).contains("c46a08a7-921c-4e77-b748-6128975a3e7d");
        assertThat(configXml).contains("537d36f9-bf4b-48b2-8d09-5d20357d4f16");

        //after migration should remove 3 duplicate elastic agents
        assertThat(migratedContent).doesNotContain("c46a08a7-921c-4e77-b748-6128975a3e7e");
        assertThat(migratedContent).doesNotContain("c46a08a7-921c-4e77-b748-6128975a3e7f");
        assertThat(migratedContent).doesNotContain("537d36f9-bf4b-48b2-8d09-5d20357d4f17");
    }

    @Test
    public void shouldIntroduceTypeOnBuildArtifacts_asPartOf106Migration() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="105">
                    <server artifactsdir="artifacts"/>
                    <pipelines>
                      <pipeline name="foo">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="some_stage">
                             <jobs>
                             <job name="some_job">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                                 <artifacts>
                                     <artifact src='foo.txt' dest='cruise-output' />
                                     <artifact src='dir/**' dest='dir' />
                                     <artifact src='build' />
                                 </artifacts>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                </cruise>""";

        String migratedContent = migrateXmlString(configXml, 105);

        assertThat(migratedContent).contains("<artifact type=\"build\" src=\"foo.txt\" dest=\"cruise-output\"/>");
        assertThat(migratedContent).contains("<artifact type=\"build\" src=\"dir/**\" dest=\"dir\"/>");
        assertThat(migratedContent).contains("<artifact type=\"build\" src=\"build\"/>");
    }

    @Test
    public void shouldConvertTestTagToArtifactWithTypeOnTestArtifacts_asPartOf106Migration() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="105">
                    <server artifactsdir="artifacts"/>
                    <pipelines>
                      <pipeline name="foo">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="some_stage">
                             <jobs>
                             <job name="some_job">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                                 <artifacts>
                                     <test src='foo.txt' dest='cruise-output' />
                                     <test src='dir/**' dest='dir' />
                                     <test src='build' />
                                 </artifacts>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                </cruise>""";

        String migratedContent = migrateXmlString(configXml, 105);

        assertThat(migratedContent).contains("<artifact type=\"test\" src=\"foo.txt\" dest=\"cruise-output\"/>");
        assertThat(migratedContent).contains("<artifact type=\"test\" src=\"dir/**\" dest=\"dir\"/>");
        assertThat(migratedContent).contains("<artifact type=\"test\" src=\"build\"/>");
    }

    @Test
    public void shouldConvertPluggableArtifactTagToArtifactWithTypeOnPluggableArtifacts_asPartOf106Migration() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="105">
                    <server artifactsdir="artifacts"/>
                    <artifactStores>
                      <artifactStore id="foo" pluginId="cd.go.artifact.docker.registry">
                        <property>
                          <key>RegistryURL</key>
                          <value>http://foo</value>
                        </property>
                      </artifactStore>
                    </artifactStores>
                    <pipelines>
                      <pipeline name="foo">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="some_stage">
                             <jobs>
                             <job name="some_job">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                                 <artifacts>
                                     <pluggableArtifact id='artifactId1' storeId='foo' />
                                     <pluggableArtifact id='artifactId2' storeId='foo'>
                                         <property>
                                             <key>BuildFile</key>
                                             <value>foo.json</value>
                                         </property>
                                     </pluggableArtifact>
                                     <pluggableArtifact id='artifactId3' storeId='foo'>
                                         <property>
                                             <key>SecureProperty</key>
                                             <encryptedValue>trMHp15AjUE=</encryptedValue>
                                         </property>
                                     </pluggableArtifact>
                                 </artifacts>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                </cruise>""";

        String migratedContent = ConfigMigrator.migrate(configXml, 105, 106);
        String artifactId2 = """
                <artifact type="external" id="artifactId2" storeId="foo">
                                         <property>
                                             <key>BuildFile</key>
                                             <value>foo.json</value>
                                         </property>
                                     </artifact>""";

        String artifactId3 = """
                <artifact type="external" id="artifactId3" storeId="foo">
                                         <property>
                                             <key>SecureProperty</key>
                                             <encryptedValue>trMHp15AjUE=</encryptedValue>
                                         </property>
                                     </artifact>""";
        assertThat(migratedContent).contains("<artifact type=\"external\" id=\"artifactId1\" storeId=\"foo\"/>");
        assertThat(migratedContent).containsIgnoringNewLines(artifactId2);
        assertThat(migratedContent).containsIgnoringNewLines(artifactId3);
    }

    @Test
    public void shouldAddTypeAttributeOnFetchArtifactTag_asPartOf107Migration() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="106">
                    <server artifactsdir="artifacts"/>
                    <pipelines>
                      <pipeline name="foo">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="stage1">
                             <jobs>
                             <job name="job1">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                                 <artifacts>
                                     <artifact type='build' src='foo/**' dest='cruise-output' />
                                 </artifacts>
                             </job>
                             </jobs>
                         </stage>
                         <stage name="stage2">
                             <jobs>
                             <job name="job2">
                                 <tasks>
                                    <exec command="ls"/>
                                     <fetchartifact pipeline='foo' stage='stage1' job='job1' srcfile='foo/foo.txt'/>
                                     <fetchartifact pipeline='foo' stage='stage1' job='job1' srcdir='foo'/>
                                     <fetchartifact stage='stage1' job='job1' srcdir='foo' dest='dest_on_agent'/>
                                 </tasks>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                </cruise>""";

        String migratedContent = migrateXmlString(configXml, 106);

        assertThat(migratedContent).contains("<fetchartifact artifactOrigin=\"gocd\" pipeline=\"foo\" stage=\"stage1\" job=\"job1\" srcfile=\"foo/foo.txt\"");
        assertThat(migratedContent).contains("<fetchartifact artifactOrigin=\"gocd\" pipeline=\"foo\" stage=\"stage1\" job=\"job1\" srcdir=\"foo\"");
        assertThat(migratedContent).contains("<fetchartifact artifactOrigin=\"gocd\" stage=\"stage1\" job=\"job1\" srcdir=\"foo\" dest=\"dest_on_agent\"");
    }

    @Test
    public void shouldConvertFetchPluggableArtifactToFetchArtifactTagWithType_asPartOf107Migration() throws CryptoException {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="106">
                    <server artifactsdir="artifacts"/>
                    <artifactStores>
                      <artifactStore id="foobar" pluginId="cd.go.artifact.docker.registry">
                        <property>
                          <key>RegistryURL</key>
                          <value>http://foo</value>
                        </property>
                      </artifactStore>
                    </artifactStores>
                    <pipelines>
                      <pipeline name="foo">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="stage1">
                             <jobs>
                             <job name="job1">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                                 <artifacts>
                                     <artifact type='external' id='artifactId1' storeId='foobar' />
                                     <artifact type='external' id='artifactId2' storeId='foobar' />
                                     <artifact type='external' id='artifactId3' storeId='foobar' />
                                 </artifacts>
                             </job>
                             </jobs>
                         </stage>
                         <stage name="stage2">
                             <jobs>
                             <job name="job2">
                                 <tasks>
                                    <exec command="ls"/>
                                     <fetchPluggableArtifact pipeline='foo' stage='stage1' job='job1' artifactId='artifactId1'/>
                                     <fetchPluggableArtifact pipeline='foo' stage='stage1' job='job1' artifactId='artifactId2'>
                                         <configuration>
                                             <property>
                                                 <key>dest</key>
                                                 <value>destination</value>
                                             </property>
                                         </configuration>
                                     </fetchPluggableArtifact>
                                     <fetchPluggableArtifact pipeline='foo' stage='stage1' job='job1' artifactId='artifactId3'>
                                         <configuration>
                                             <property>
                                                 <key>SomeSecureProperty</key>
                                                 <encryptedValue>trMHp15AjUE=</encryptedValue>
                                             </property>
                                         </configuration>
                                     </fetchPluggableArtifact>
                                 </tasks>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                </cruise>""";

        String migratedContent = migrateXmlString(configXml, 106);

        String artifactId2 = """
                <fetchartifact artifactOrigin="external" pipeline="foo" stage="stage1" job="job1" artifactId="artifactId2">
                                         <configuration>
                                             <property>
                                                 <key>dest</key>
                                                 <value>destination</value>
                                             </property>
                                         </configuration>
                                     </fetchartifact>""";

        String artifactId3 = ("""
                <fetchartifact artifactOrigin="external" pipeline="foo" stage="stage1" job="job1" artifactId="artifactId3">
                                         <configuration>
                                             <property>
                                                 <key>SomeSecureProperty</key>
                                                 <encryptedValue>%s</encryptedValue>
                                             </property>
                                         </configuration>
                                     </fetchartifact>""").formatted(new GoCipher().encrypt("abcd"));

        assertThat(migratedContent).contains("<fetchartifact artifactOrigin=\"external\" pipeline=\"foo\" stage=\"stage1\" job=\"job1\" artifactId=\"artifactId1\"");
        assertThat(migratedContent).containsIgnoringNewLines(artifactId2);
        assertThat(migratedContent).containsIgnoringNewLines(artifactId3);
    }

    @Test
    public void shouldAddTheConfigurationSubTagOnExternalArtifacts_asPartOf108Migration() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="107">
                    <server artifactsdir="artifacts"/>
                    <artifactStores>
                      <artifactStore id="foobar" pluginId="cd.go.artifact.docker.registry">
                        <property>
                          <key>RegistryURL</key>
                          <value>http://foo</value>
                        </property>
                      </artifactStore>
                    </artifactStores>
                    <pipelines>
                      <pipeline name="p1">
                         <materials>
                           <hg url="blah"/>
                         </materials>
                           <stage name="s1">
                             <jobs>
                             <job name="j1">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                                 <artifacts>
                                     <artifact type="external" id="artifactId1" storeId="foobar" />
                                     <artifact type="external" id="artifactId2" storeId="foobar">
                                         <property>
                                             <key>BuildFile</key>
                                             <value>foo.json</value>
                                         </property>
                                     </artifact>
                                 </artifacts>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                </cruise>""";

        String migratedContent = migrateXmlString(configXml, 107);
        String migratedArtifact1 = "<artifact type=\"external\" id=\"artifactId1\" storeId=\"foobar\"/>";
        String migratedArtifact2 = """
                <artifact type="external" id="artifactId2" storeId="foobar"><configuration>
                                         <property>
                                             <key>BuildFile</key>
                                             <value>foo.json</value>
                                         </property>
                                     </configuration></artifact>""";

        assertThat(migratedContent).contains(migratedArtifact1);
        assertThat(migratedContent).containsIgnoringNewLines(migratedArtifact2);

    }

    @Test
    public void shouldOnlyUpdateSchemaVersionForMigration114() {
        String configContent = """
                <pipelines>
                      <pipeline name="p1">
                         <materials>
                           <hg url="blah"/>
                         </materials>
                           <stage name="s1">
                             <jobs>
                             <job name="j1">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>""";

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<cruise schemaVersion=\"113\">\n"
                + configContent
                + "</cruise>";

        String migratedContent = ConfigMigrator.migrate(configXml, 113, 114);

        assertThat(migratedContent).contains("<cruise schemaVersion=\"114\"");
        assertThat(migratedContent).containsIgnoringNewLines(configContent);
    }

    @Test
    public void shouldRenameOriginAttributeOnFetchArtifactToArtifactOrigin_AsPartOf110To111Migration() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="110">
                    <server artifactsdir="artifacts"/>
                    <artifactStores>
                      <artifactStore id="foobar" pluginId="cd.go.artifact.docker.registry">
                        <property>
                          <key>RegistryURL</key>
                          <value>http://foo</value>
                        </property>
                      </artifactStore>
                    </artifactStores>
                    <pipelines>
                      <pipeline name="foo">
                         <materials>
                           <hg url="blah"/>
                         </materials>
                           <stage name="stage1">
                             <jobs>
                             <job name="job1">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                                 <artifacts>
                                     <artifact type='build' src='foo' dest='bar'/>
                                     <artifact type='external' id='artifactId1' storeId='foobar' />
                                 </artifacts>
                             </job>
                             </jobs>
                         </stage>
                         <stage name="stage2">
                             <jobs>
                             <job name="job2">
                                 <tasks>
                                    <exec command="ls"/>
                                     <fetchartifact origin='gocd' pipeline='foo' stage='stage1' job='job1' srcdir='dist/zip' dest='target'/>
                                     <fetchartifact origin='external' pipeline='foo' stage='stage1' job='job1' artifactId='artifactId1'>
                                         <configuration>
                                             <property>
                                                 <key>dest</key>
                                                 <value>destination</value>
                                             </property>
                                         </configuration>
                                     </fetchartifact>
                                 </tasks>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                </cruise>""";

        String migratedContent = migrateXmlString(configXml, 110);

        assertThat(migratedContent).contains("<fetchartifact artifactOrigin=\"gocd\" pipeline=\"foo\" stage=\"stage1\" job=\"job1\" srcdir=\"dist/zip\" dest=\"target\"/>");
        assertThat(migratedContent).contains("<fetchartifact artifactOrigin=\"external\" pipeline=\"foo\" stage=\"stage1\" job=\"job1\" artifactId=\"artifactId1\">");
    }

    @Test
    public void shouldRemoveMaterialNameFromConfigRepos_AsPartOf114To115Migration() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion='114'>
                <config-repos>
                    <config-repo pluginId="yaml.config.plugin" id="test">
                      <git url="test" branch="test" materialName="test" />
                    </config-repo>
                    <config-repo pluginId="yaml.config.plugin" id="test1">
                      <svn url="test" username="" materialName="test" />
                    </config-repo>
                    <config-repo pluginId="yaml.config.plugin" id="test2">
                      <hg url="test" materialName="test" />
                    </config-repo>
                    <config-repo pluginId="yaml.config.plugin" id="asd">
                      <tfs url="test" username="admin" domain="test" encryptedPassword="AES:09M8nDpEgOgRGVVWAnEiMQ==:7lAsVu5nZ6iYhoZ4Alwc5g==" projectPath="test" materialName="test" />
                    </config-repo>
                    <config-repo pluginId="yaml.config.plugin" id="asdasd">
                      <p4 port="test" username="admin" encryptedPassword="AES:A7h8pqjGyz372Kogx5xX/w==:tG1WNNd680UyqOUM1BVrfQ==" materialName="test">
                        <view><![CDATA[<h1>test</h1>]]></view>
                      </p4>
                    </config-repo>
                  </config-repos>
                </cruise>""";
        String migratedContent = ConfigMigrator.migrate(configXml, 114, 115);

        assertStringContainsIgnoringCarriageReturn(migratedContent,
                """
                        <config-repos>
                            <config-repo pluginId="yaml.config.plugin" id="test">
                              <git url="test" branch="test"/>
                            </config-repo>
                            <config-repo pluginId="yaml.config.plugin" id="test1">
                              <svn url="test" username=""/>
                            </config-repo>
                            <config-repo pluginId="yaml.config.plugin" id="test2">
                              <hg url="test"/>
                            </config-repo>
                            <config-repo pluginId="yaml.config.plugin" id="asd">
                              <tfs url="test" username="admin" domain="test" encryptedPassword="AES:09M8nDpEgOgRGVVWAnEiMQ==:7lAsVu5nZ6iYhoZ4Alwc5g==" projectPath="test"/>
                            </config-repo>
                            <config-repo pluginId="yaml.config.plugin" id="asdasd">
                              <p4 port="test" username="admin" encryptedPassword="AES:A7h8pqjGyz372Kogx5xX/w==:tG1WNNd680UyqOUM1BVrfQ==">
                                <view>&lt;h1&gt;test&lt;/h1&gt;</view>
                              </p4>
                            </config-repo>
                          </config-repos>""");
    }

    @Test
    public void shouldOnlyUpdateSchemaVersionForMigration116() {
        String configContent = """
                <pipelines>
                      <pipeline name="p1">
                         <materials>            <hg url="blah"/>
                         </materials>           <stage name="s1">
                             <jobs>
                             <job name="j1">
                                 <tasks>
                                    <exec command="ls"/>
                                 </tasks>
                             </job>
                             </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>""";

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<cruise schemaVersion=\"115\">\n"
                + configContent
                + "</cruise>";

        String migratedContent = ConfigMigrator.migrate(configXml, 115, 116);

        assertThat(migratedContent).contains("<cruise schemaVersion=\"116\"");
        assertThat(migratedContent).containsIgnoringNewLines(configContent);
    }

    @Test
    public void shouldRenameProfilesToAgentProfilesAsPartOfMigration120() throws Exception {
        String configContent = """
                <elastic jobStarvationTimeout="1">
                    <profiles>
                      <profile clusterProfileId="4ca85ebb-3fad-45f6-a4fc-0894f714ecdc" id="ecs-gocd-dev-build-dind" pluginId="com.thoughtworks.gocd.elastic-agent.ecs">
                        <property>
                          <key>Image</key>
                          <value>docker.gocd.io/gocddev/gocd-dev-build:centos-7-v2.0.67</value>
                        </property>
                      </profile>
                      <profile clusterProfileId="4ca85ebb-3fad-45f6-a4fc-0894f714ecdc" id="ecs-gocd-dev-build-dind-docker-compose" pluginId="com.thoughtworks.gocd.elastic-agent.ecs">
                        <property>
                          <key>Image</key>
                          <value>docker.gocd.io/gocddev/gocd-dev-build:centos-7-v2.0.67</value>
                        </property>
                      </profile>
                    </profiles>
                    <clusterProfiles>
                      <clusterProfile id="no-op-cluster-for-cd.go.contrib.elasticagent.kubernetes" pluginId="cd.go.contrib.elasticagent.kubernetes"/>
                      <clusterProfile id="4ca85ebb-3fad-45f6-a4fc-0894f714ecdc" pluginId="com.thoughtworks.gocd.elastic-agent.ecs">
                        <property>
                          <key>GoServerUrl</key>
                          <value>https://build.gocd.io/go</value>
                        </property>
                      </clusterProfile>
                    </clusterProfiles>
                  </elastic>""";

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<cruise schemaVersion=\"119\">\n"
                + configContent
                + "</cruise>";

        String migratedContent = migrateXmlString(configXml, 119);

        assertThat(migratedContent).doesNotContain("<profiles");
        assertThat(migratedContent).doesNotContain("<profile");
        assertThat(migratedContent).contains("<agentProfiles");
        assertThat(migratedContent).contains("<agentProfile");

        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        assertThat(cruiseConfig.getElasticConfig().getJobStarvationTimeout()).isEqualTo(1 * 60 * 1000);
        assertThat(cruiseConfig.getElasticConfig().getProfiles()).hasSize(2);
        assertThat(cruiseConfig.getElasticConfig().getProfiles().find("ecs-gocd-dev-build-dind")).isNotNull();
        assertThat(cruiseConfig.getElasticConfig().getProfiles().find("ecs-gocd-dev-build-dind-docker-compose")).isNotNull();
    }

    @Test
    public void shouldRemovePluginIdFromAgentProfilesMigration122() throws Exception {
        String configContent = """
                <elastic jobStarvationTimeout="1">
                    <agentProfiles>
                      <agentProfile clusterProfileId="4ca85ebb-3fad-45f6-a4fc-0894f714ecdc" id="ecs-gocd-dev-build-dind" pluginId="com.thoughtworks.gocd.elastic-agent.ecs">
                        <property>
                          <key>Image</key>
                          <value>docker.gocd.io/gocddev/gocd-dev-build:centos-7-v2.0.67</value>
                        </property>
                      </agentProfile>
                      <agentProfile clusterProfileId="4ca85ebb-3fad-45f6-a4fc-0894f714ecdc" id="ecs-gocd-dev-build-dind-docker-compose" pluginId="com.thoughtworks.gocd.elastic-agent.ecs">
                        <property>
                          <key>Image</key>
                          <value>docker.gocd.io/gocddev/gocd-dev-build:centos-7-v2.0.67</value>
                        </property>
                      </agentProfile>
                    </agentProfiles>
                  </elastic>""";

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<cruise schemaVersion=\"121\">\n"
                + configContent
                + "</cruise>";

        String migratedContent = migrateXmlString(configXml, 121);
        assertThat(migratedContent).doesNotContain("pluginId=\"com.thoughtworks.gocd.elastic-agent.ecs\"");

        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        assertThat(cruiseConfig.getElasticConfig().getJobStarvationTimeout()).isEqualTo(1 * 60 * 1000);
        assertThat(cruiseConfig.getElasticConfig().getProfiles()).hasSize(2);
        assertThat(cruiseConfig.getElasticConfig().getProfiles().find("ecs-gocd-dev-build-dind")).isNotNull();
        assertThat(cruiseConfig.getElasticConfig().getProfiles().find("ecs-gocd-dev-build-dind-docker-compose")).isNotNull();
    }

    @Test
    public void shouldMigrateEverythingAsItIs_Migration120To121() {
        String originalConfig = """
                <pipelines group="first">
                    <pipeline name="Test" template="test_template">
                      <materials>
                          <git url="http://" dest="dest_dir14" />
                      </materials>
                     </pipeline>
                  </pipelines>
                  <templates>
                    <pipeline name="test_template">
                      <stage name="Functional">
                        <jobs>
                          <job name="Functional">
                            <tasks>
                              <exec command="echo" args="Hello World!!!" />
                            </tasks>
                           </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>""";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"120\">" + originalConfig + "</cruise>";

        String expectedConfig =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"121\">" + originalConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 120, 121);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void shouldMigrateEverythingAsItIs_Migration122To123() {
        String originalConfig = """
                <pipelines group="first">
                    <pipeline name="Test" template="test_template">
                      <materials>
                          <git url="http://" dest="dest_dir14" />
                      </materials>
                     </pipeline>
                  </pipelines>
                  <templates>
                    <pipeline name="test_template">
                      <stage name="Functional">
                        <jobs>
                          <job name="Functional">
                            <tasks>
                              <exec command="echo" args="Hello World!!!" />
                            </tasks>
                           </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>""";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"122\">" + originalConfig + "</cruise>";

        String expectedConfig =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"123\">" + originalConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 122, 123);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void shouldMigrateEverythingAsItIs_Migration123To124() {
        String originalConfig = """
                <pipelines group="first">
                    <pipeline name="Test" template="test_template">
                      <materials>
                          <git url="http://" dest="dest_dir14" />
                      </materials>
                     </pipeline>
                  </pipelines>
                  <templates>
                    <pipeline name="test_template">
                      <stage name="Functional">
                        <jobs>
                          <job name="Functional">
                            <tasks>
                              <exec command="echo" args="Hello World!!!" />
                            </tasks>
                           </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>""";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<cruise schemaVersion=\"123\">\n" + originalConfig + "</cruise>";

        String expectedConfig =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<cruise schemaVersion=\"124\">\n" + originalConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 123, 124);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void migration125_shouldRemoveTlsAttributeFromMailHostWhenItIsSetToFalse() {
        String configXml =
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <cruise schemaVersion="124">
                          <server>
                            <mailhost hostname='smtp.example.com' port='25' tls='false' from='alice@example.com' admin='bob@example.com' />
                          </server>
                        </cruise>""";

        String expectedConfig =
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <cruise schemaVersion="125">
                          <server>
                            <mailhost hostname='smtp.example.com' port='25' from='alice@example.com' admin='bob@example.com' />
                          </server>
                        </cruise>""";

        final String migratedXml = ConfigMigrator.migrate(configXml, 124, 125);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void migration125_shouldRetainTlsAttributeFromMailHostWhenItIsSetToTrue() {
        String configXml =
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <cruise schemaVersion="124">
                          <server>
                            <mailhost hostname='smtp.example.com' port='25' tls='true' from='alice@example.com' admin='bob@example.com' />
                          </server>
                        </cruise>""";

        String expectedConfig =
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <cruise schemaVersion="125">
                          <server>
                            <mailhost hostname='smtp.example.com' port='25' tls='true' from='alice@example.com' admin='bob@example.com' />
                          </server>
                        </cruise>""";

        final String migratedXml = ConfigMigrator.migrate(configXml, 124, 125);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void shouldMigrateEverythingAsItIs_Migration125To126() {
        String originalConfig = """
                <pipelines group="first">
                    <pipeline name="Test" template="test_template">
                      <materials>
                          <git url="http://" dest="dest_dir14" />
                      </materials>
                     </pipeline>
                  </pipelines>
                  <templates>
                    <pipeline name="test_template">
                      <stage name="Functional">
                        <approval type="manual" />
                        <jobs>
                          <job name="Functional">
                            <tasks>
                              <exec command="echo" args="Hello World!!!" />
                            </tasks>
                           </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>""";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"125\">" + originalConfig + "</cruise>";

        String expectedConfig =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"126\">" + originalConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 125, 126);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void shouldRemoveSiteUrlsAsAnAttributesAndAddAsAChildElement_Migration126To127() {
        String originalConfig = "<server siteUrl=\"http://foo.com\" " +
                "secureSiteUrl=\"https://bar.com\" " +
                "artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "</server>";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"126\">" + originalConfig + "</cruise>";

        String expectedConfig = "<server " +
                "artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "<siteUrls>" +
                "<siteUrl>http://foo.com</siteUrl>" +
                "<secureSiteUrl>https://bar.com</secureSiteUrl>" +
                "</siteUrls>" +
                "</server>";

        String expectedXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"127\">" + expectedConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 126, 127);
        XmlAssert.assertThat(migratedXml).and(expectedXml).areIdentical();
    }

    @Test
    public void shouldDoNothingWhenSiteUrlAndSecureSiteUrlIsNotSpecified_Migration126To127() {
        String originalConfig = "<server " +
                "artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "</server>";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"126\">" + originalConfig + "</cruise>";

        String expectedConfig = "<server " +
                "artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "</server>";

        String expectedXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"127\">" + expectedConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 126, 127);
        XmlAssert.assertThat(migratedXml).and(expectedXml).areIdentical();
    }

    @Test
    public void shouldAddOnlySiteUrlWhenSecureSiteUrlIsNotSpecified_Migration126To127() {
        String originalConfig = "<server siteUrl=\"http://foo.com\" " +
                "artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "</server>";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"126\">" + originalConfig + "</cruise>";

        String expectedConfig = "<server " +
                "artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "<siteUrls>" +
                "<siteUrl>http://foo.com</siteUrl>" +
                "<secureSiteUrl/>" +
                "</siteUrls>" +
                "</server>";

        String expectedXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"127\">" + expectedConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 126, 127);
        XmlAssert.assertThat(migratedXml).and(expectedXml).areIdentical();
    }

    @Test
    public void shouldAddOnlySecureSiteUrlWhenSiteUrlIsNotSpecified_Migration126To127() {
        String originalConfig = "<server " +
                "secureSiteUrl=\"https://bar.com\" " +
                "artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "</server>";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"126\">" + originalConfig + "</cruise>";

        String expectedConfig = "<server " +
                "artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "<siteUrls>" +
                "<siteUrl/>" +
                "<secureSiteUrl>https://bar.com</secureSiteUrl>" +
                "</siteUrls>" +
                "</server>";

        String expectedXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"127\">" + expectedConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 126, 127);
        XmlAssert.assertThat(migratedXml).and(expectedXml).areIdentical();
    }

    private void assertStringsIgnoringCarriageReturnAreEqual(String expected, String actual) {
        assertThat(actual.replaceAll("\\r", "").trim()).isEqualTo(expected.replaceAll("\\r", "").trim());
    }

    @Test
    public void shouldMigrateAnEmptyArtifactSourceToStar() throws Exception {
        String migratedContent = migrateXmlString(configWithArtifactSourceAs(""), 28);

        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        JobConfig plan = cruiseConfig.jobConfigByName("pipeline", "stage", "job", true);
        assertThat(plan.artifactTypeConfigs().getBuiltInArtifactConfigs().get(0).getSource()).isEqualTo("*");
    }

    @Test
    public void shouldMigrateAnArtifactSourceWithJustWhitespaceToStar() throws Exception {
        String migratedContent = migrateXmlString(configWithArtifactSourceAs(" \t "), 28);

        CruiseConfig cruiseConfig = loader.deserializeConfig(migratedContent);
        JobConfig plan = cruiseConfig.jobConfigByName("pipeline", "stage", "job", true);
        assertThat(plan.artifactTypeConfigs().getBuiltInArtifactConfigs().get(0).getSource()).isEqualTo("*");
    }

    @Test
    public void migration130_shouldRemovePropertiesFromJobsUnderPipelinesAndTemplates() {
        String configXml =
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <cruise schemaVersion="129">
                            <pipelines>
                              <pipeline name="in_env">
                                 <materials>
                                   <hg url="blah"/>
                                 </materials>
                                 <stage name="some_stage">
                                     <jobs>
                                     <job name="some_job">
                                         <tasks>
                                             <ant target="emma" />
                                         </tasks>
                                         <properties>
                                             <property name="coverage.class" src="target/emma/coverage.xml" xpath="substring-before(//report/data/all/coverage[starts-with(@type,'class')]/@value, '%')" />
                                         </properties>
                                     </job>
                                     </jobs>
                                 </stage>
                              </pipeline>
                            </pipelines>
                            <templates>
                                <pipeline name="project-template">
                                    <authorization>
                                        <admins>
                                            <user>jez</user>
                                        </admins>
                                    </authorization>
                                    <stage name="ut">
                                        <jobs>
                                        <job name="linux">
                                            <tasks>
                                                <ant target="emma" />
                                            </tasks>
                                            <properties>
                                                <property name="coverage.class" src="target/emma/coverage.xml" xpath="substring-before(//report/data/all/coverage[starts-with(@type,'class')]/@value, '%')" />
                                            </properties>
                                        </job>
                                        </jobs>
                                    </stage>
                                </pipeline>
                            </templates>
                        </cruise>""";

        String expectedConfig =
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <cruise schemaVersion="130">
                            <pipelines>
                              <pipeline name="in_env">
                                 <materials>
                                   <hg url="blah"/>
                                 </materials>
                                 <stage name="some_stage">
                                     <jobs>
                                     <job name="some_job">
                                         <tasks>
                                             <ant target="emma" />
                                         </tasks>
                                        \s
                                     </job>
                                     </jobs>
                                 </stage>
                              </pipeline>
                            </pipelines>
                            <templates>
                                <pipeline name="project-template">
                                    <authorization>
                                        <admins>
                                            <user>jez</user>
                                        </admins>
                                    </authorization>
                                    <stage name="ut">
                                        <jobs>
                                        <job name="linux">
                                            <tasks>
                                                <ant target="emma" />
                                            </tasks>
                                           \s
                                        </job>
                                        </jobs>
                                    </stage>
                                </pipeline>
                            </templates>
                        </cruise>""";

        final String migratedXml = ConfigMigrator.migrate(configXml, 129, 130);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();

    }

    @Test
    public void migration131_shouldRemoveMingleTagFromPipelineTag() {
        String configXml =
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <cruise schemaVersion="130">
                            <pipelines>
                              <pipeline name="in_env">
                                 <mingle             baseUrl='http://mingle.example.com'             projectIdentifier='my_project'>
                                     <mqlGroupingConditions>status > 'In Dev'</mqlGroupingConditions>
                                 </mingle>
                                 <materials>
                                   <hg url="blah"/>
                                 </materials>
                                 <stage name="some_stage">
                                     <jobs>
                                     <job name="some_job">
                                         <tasks>
                                            <exec command="ls"/>
                                         </tasks>
                                     </job>
                                     </jobs>
                                 </stage>
                              </pipeline>
                            </pipelines>
                        </cruise>""";

        String expectedConfig =
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <cruise schemaVersion="131">
                            <pipelines>
                              <pipeline name="in_env">
                                \s
                                 <materials>
                                   <hg url="blah"/>
                                 </materials>
                                 <stage name="some_stage">
                                     <jobs>
                                     <job name="some_job">
                                         <tasks>
                                            <exec command="ls"/>
                                         </tasks>
                                     </job>
                                     </jobs>
                                 </stage>
                              </pipeline>
                            </pipelines>
                        </cruise>""";

        final String migratedXml = ConfigMigrator.migrate(configXml, 130, 131);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void shouldRemoveArtifactRelatedAttributesAndAddAsChildElements_Migration131To132() {
        String originalConfig = "<server artifactsdir=\"artifacts\" " +
                "purgeStart=\"50.0\" " +
                "purgeUpto=\"100.0\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "</server>";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"131\">" + originalConfig + "</cruise>";

        String expectedConfig = "<server " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "<artifacts>" +
                    "<artifactsDir>artifacts</artifactsDir>" +
                    "<purgeSettings>" +
                        "<purgeStartDiskSpace>50.0</purgeStartDiskSpace>" +
                        "<purgeUptoDiskSpace>100.0</purgeUptoDiskSpace>" +
                    "</purgeSettings>" +
                "</artifacts>" +
                "</server>";

        String expectedXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"132\">" + expectedConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 131, 132);

        XmlAssert.assertThat(migratedXml).and(expectedXml).areIdentical();
    }

    @Test
    public void shouldNotBreakIfPurgeSettingsAreNotPresent_Migration131To132() {
        String originalConfig = "<server artifactsdir=\"artifacts\" " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "</server>";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"131\">" + originalConfig + "</cruise>";

        String expectedConfig = "<server " +
                "agentAutoRegisterKey=\"323040d4-f2e4-4b8a-8394-7a2d122054d1\" " +
                "webhookSecret=\"3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6\" " +
                "commandRepositoryLocation=\"default\" " +
                "serverId=\"60f5f682-5248-4ba9-bb35-72c92841bd75\" " +
                "tokenGenerationKey=\"8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c\">" +
                "<artifacts>" +
                    "<artifactsDir>artifacts</artifactsDir>" +
                "</artifacts>" +
                "</server>";

        String expectedXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<cruise schemaVersion=\"132\">" + expectedConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 131, 132);

        XmlAssert.assertThat(migratedXml).and(expectedXml).areIdentical();
    }

    @Test
    public void shouldMigrate_allowOnlyKnownUsersToLogin_attributeFromSecurityToAuthConfig_Migration132To133() {
        String originalConfig = """
                <server agentAutoRegisterKey="323040d4-f2e4-4b8a-8394-7a2d122054d1" webhookSecret="3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6" commandRepositoryLocation="default" serverId="60f5f682-5248-4ba9-bb35-72c92841bd75" tokenGenerationKey="8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c">
                <security allowOnlyKnownUsersToLogin="true" >
                      <authConfigs>
                        <authConfig id="9cad79b0-4d9e-4a62-829c-eb4d9488062f" pluginId="cd.go.authentication.passwordfile">
                          <property>
                            <key>PasswordFilePath</key>
                            <value>config/password.properties</value>
                          </property>
                        </authConfig>
                      </authConfigs>
                    </security>
                  </server>
                """;

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<cruise schemaVersion=\"132\">" + originalConfig + "</cruise>";

        final String migratedXml = migrateXmlString(configXml, 132);

        XmlAssert.assertThat(migratedXml).nodesByXPath("//security").doNotHaveAttribute("allowOnlyKnownUsersToLogin");
        XmlAssert.assertThat(migratedXml).nodesByXPath("//authConfig").haveAttribute("allowOnlyKnownUsersToLogin", "true");
    }

    @Test
    public void shouldDefine_allowOnlyKnownUsersToLogin_attributeOnAuthConfigAttributeDoesNotExistOnSecurity_Migration132to133() {
        String originalConfig = """
                <server agentAutoRegisterKey="323040d4-f2e4-4b8a-8394-7a2d122054d1" webhookSecret="3d5cd2f5-7fe7-43c0-ba34-7e01678ba8b6" commandRepositoryLocation="default" serverId="60f5f682-5248-4ba9-bb35-72c92841bd75" tokenGenerationKey="8c3c8dc9-08bf-4cd7-ac80-cecb3e7ae86c">
                <security>
                      <authConfigs>
                        <authConfig id="9cad79b0-4d9e-4a62-829c-eb4d9488062f" pluginId="cd.go.authentication.passwordfile">
                          <property>
                            <key>PasswordFilePath</key>
                            <value>config/password.properties</value>
                          </property>
                        </authConfig>
                      </authConfigs>
                    </security>
                  </server>
                """;

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<cruise schemaVersion=\"132\">" + originalConfig + "</cruise>";

        final String migratedXml = migrateXmlString(configXml, 132);

        XmlAssert.assertThat(migratedXml).nodesByXPath("//security").doNotHaveAttribute("allowOnlyKnownUsersToLogin");
        //verify authConfig has no allowOnlyKnownUsersToLogin as the default value is false,
        XmlAssert.assertThat(migratedXml).nodesByXPath("//authConfig").doNotHaveAttribute("allowOnlyKnownUsersToLogin");
    }

    @Test
    public void shouldMigrateEverythingAsItIs_Migration133To134() {
        String originalConfig = """
                <server>
                   <security>
                      <roles>
                         <role name='luau-role'><users><user>some-user</user></users></role>
                      </roles>
                   </security>
                </server>
                <pipelines group="first">
                    <pipeline name="Test" template="test_template">
                      <materials>
                          <git url="http://" dest="dest_dir14" />
                      </materials>
                     </pipeline>
                  </pipelines>
                  <templates>
                    <pipeline name="test_template">
                      <stage name="Functional">
                        <approval type="manual" />
                        <jobs>
                          <job name="Functional">
                            <tasks>
                              <exec command="echo" args="Hello World!!!" />
                            </tasks>
                           </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>""";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<cruise schemaVersion=\"133\">\n" + originalConfig + "</cruise>";

        String expectedConfig =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<cruise schemaVersion=\"134\">\n" + originalConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 133, 134);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void shouldMigratePipelineLabelTemplateColonToUnderscore_Migration134To135() {
        String originalConfig = """
                <repositories>
                    <repository id="0c24bd20-2b24-4fba-9410-aea494d29bf5" name="npm">
                      <pluginConfiguration id="npm" version="1" />
                      <configuration>
                        <property>
                          <key>REPO_URL</key>
                          <value>http://npmjs.com/</value>
                        </property>
                      </configuration>
                      <packages>
                        <package id="07951410-c70e-4a91-be53-8968cf0c07bc" name="my-package">
                          <configuration>
                            <property>
                              <key>PACKAGE_ID</key>
                              <value>v1.0.0</value>
                            </property>
                          </configuration>
                        </package>
                      </packages>
                    </repository>
                  </repositories>
                <pipelines group="first">
                    <pipeline name="Test" template="test_template" labeltemplate="${npm:my-package}">
                      <materials>
                          <git url="http://" dest="dest_dir14" />
                      </materials>
                     </pipeline>
                  </pipelines>
                  <templates>
                    <pipeline name="test_template">
                      <stage name="Functional">
                        <approval type="manual" />
                        <jobs>
                          <job name="Functional">
                            <tasks>
                              <exec command="echo" args="Hello World!!!" />
                            </tasks>
                           </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>""";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<cruise schemaVersion=\"134\">\n" + originalConfig + "</cruise>";

        String expectedConfig = configXml.replace("134", "135").replace("npm:my-package", "npm_my-package");

        final String migratedXml = ConfigMigrator.migrate(configXml, 134, 135);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void shouldAddADefaultAllowRuleForConfigRepos_Migration135To136() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="135">
                   <config-repos>
                      <config-repo id="json" pluginId="yaml.config.plugin">
                          <git url="/tmp/config" />
                      </config-repo>
                   </config-repos>
                </cruise>""";

        String defaultRuleAdded = "<allow action=\"refer\" type=\"*\">*</allow>";

        final String migratedXml = migrateXmlString(configXml, 135);

        XmlAssert.assertThat(migratedXml).nodesByXPath("/cruise/config-repos/config-repo/rules").exist();
        assertThat(migratedXml).contains(defaultRuleAdded);
    }

    @Test
    public void migration137_shouldAddEchoTasksForEmptyJob() {
        String configXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="136">
                    <pipelines>
                      <pipeline name="in_env">
                         <materials>
                           <hg url="blah"/>
                         </materials>
                         <stage name="some_stage">
                           <jobs>
                             <job name="ls_job">
                               <tasks>
                                 <exec command="ls"/>
                               </tasks>
                             </job>
                             <job name="empty_job">
                             </job>
                           </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                   <templates>
                    <pipeline name="template1">
                      <stage name="defaultStage">
                        <jobs>
                          <job name="empty_job" />
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>
                </cruise>""";

        String expectedConfig = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="137">
                    <pipelines>
                      <pipeline name="in_env">
                         <materials>
                           <hg url="blah"/>
                         </materials>
                         <stage name="some_stage">
                           <jobs>
                             <job name="ls_job">
                               <tasks>
                                 <exec command="ls"/>
                               </tasks>
                             </job>
                             <job name="empty_job">
                               <tasks>
                                 <exec command="echo">
                                   <runif status="passed" />
                                 </exec>
                               </tasks>
                             </job>
                           </jobs>
                         </stage>
                      </pipeline>
                    </pipelines>
                   <templates>
                    <pipeline name="template1">
                      <stage name="defaultStage">
                        <jobs>
                          <job name="empty_job">
                            <tasks>
                              <exec command="echo">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>
                </cruise>""";

        final String migratedXml = ConfigMigrator.migrate(configXml, 136, 137);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).ignoreWhitespace().areIdentical();
    }

    @Test
    public void shouldMigrateEverythingAsItIs_Migration137To138() {
        String originalConfig = """
                <pipelines group="first">
                    <pipeline name="Test" template="test_template">
                      <materials>
                          <git url="http://" dest="dest_dir14" />
                      </materials>
                     </pipeline>
                  </pipelines>
                  <templates>
                    <pipeline name="test_template">
                      <stage name="Functional">
                        <jobs>
                          <job name="Functional">
                            <tasks>
                              <exec command="echo" args="Hello World!!!" />
                            </tasks>
                           </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>""";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<cruise schemaVersion=\"137\">\n" + originalConfig + "</cruise>";

        String expectedConfig =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<cruise schemaVersion=\"138\">\n" + originalConfig + "</cruise>";

        final String migratedXml = ConfigMigrator.migrate(configXml, 137, 138);
        XmlAssert.assertThat(migratedXml).and(expectedConfig).areIdentical();
    }

    @Test
    public void shouldRemove_commandRepositoryLocation_And_CopyEverythingAsIs_AsPartOfMigrationFrom_138_to_139() {
        String originalConfig = """
                <pipelines group="first">
                    <pipeline name="Test" template="test_template">
                      <materials>
                          <git url="http://" dest="dest_dir14" />
                      </materials>
                     </pipeline>
                  </pipelines>
                  <templates>
                    <pipeline name="test_template">
                      <stage name="Functional">
                        <jobs>
                          <job name="Functional">
                            <tasks>
                              <exec command="echo" args="Hello World!!!" />
                            </tasks>
                           </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </templates>""";

        String configXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<cruise schemaVersion=\"138\"><server commandRepositoryLocation=\"default\"/>\n" + originalConfig + "</cruise>";


        final String migratedXml = ConfigMigrator.migrate(configXml, 138, 139);
        XmlAssert.assertThat(migratedXml).nodesByXPath("//server").doNotHaveAttribute("commandRepositoryLocation");
    }

    private void assertStringContainsIgnoringCarriageReturn(String actual, String substring) {
        assertThat(actual.replaceAll("\\r", "")).contains(substring.replaceAll("\\r", ""));
    }

    private String migrateXmlString(String content, int fromVersion) {
        return ConfigMigrator.migrate(content, fromVersion, GoConfigSchema.currentSchemaVersion());
    }

    private String contentFromResource(String resource) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            return new String(Objects.requireNonNull(is).readAllBytes(), UTF_8);
        }
    }

}
