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

package com.thoughtworks.go.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.*
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec

import javax.inject.Inject

import static com.thoughtworks.go.build.OperatingSystemHelper.normalizeEnvironmentPath

@CacheableTask
class YarnRunTask extends DefaultTask {
  private ExecOperations execOperations
  private File workingDir

  private List<String> yarnCommand = new ArrayList<>()
  private List<Object> sourceFiles = new ArrayList<Object>()
  private File destinationDir
  private String additionalPath

  @Inject
  YarnRunTask(ExecOperations execOperations) {
    this.execOperations = execOperations
    inputs.property('os', OperatingSystem.current().toString())
    project.afterEvaluate({
      source(project.file("${getWorkingDir()}/package.json"))
      source(project.file("${getWorkingDir()}/yarn.lock"))
      source(project.file("${getWorkingDir()}/node_modules"))
    })
  }

  @Input // not an @InputFile/InputDirectory, because we don't care about the contents of the workingDir itself
  String getWorkingDir() {
    return workingDir.toString()
  }

  @Input
  List<String> getYarnCommand() {
    return yarnCommand
  }

  @OutputDirectory
  @Optional
  File getDestinationDir() {
    return destinationDir
  }

  @Input
  @Optional
  String getAdditionalPath() {
    return additionalPath
  }

  @InputFiles
  @PathSensitive(PathSensitivity.NONE)
  FileTree getSourceFiles() {
    List<Object> copy = new ArrayList<Object>(this.sourceFiles)
    FileTree src = getProject().files(copy).getAsFileTree()
    src == null ? getProject().files().getAsFileTree() : src
  }

  void setYarnCommand(List<String> yarnCommand) {
    this.yarnCommand = new ArrayList<>(yarnCommand)
  }

  void setWorkingDir(Object workingDir) {
    this.workingDir = project.file(workingDir)
  }

  void source(Object... sources) {
    this.sourceFiles.addAll(sources)
  }

  void setDestinationDir(File destinationDir) {
    this.destinationDir = destinationDir
  }

  void setAdditionalPath(String additionalPath) {
    this.additionalPath = additionalPath
  }

  @TaskAction
  def execute() {
    if (getDestinationDir() != null) {
      project.delete(getDestinationDir())
    }

    execOperations.exec { ExecSpec execSpec ->
      if (additionalPath) {
        execSpec.environment = normalizeEnvironmentPath(execSpec.environment)
        execSpec.environment("PATH", ([additionalPath] + execSpec.environment["PATH"].toString()).join(File.pathSeparator))
      }
      execSpec.environment("FORCE_COLOR", "true")
      execSpec.standardOutput = System.out
      execSpec.errorOutput = System.err

      execSpec.workingDir = this.getWorkingDir()
      execSpec.commandLine = [OperatingSystem.current().isWindows() ? "yarn.cmd" : "yarn", "run"] + getYarnCommand()
      println "[${execSpec.workingDir}]\$ ${execSpec.executable} ${execSpec.args.join(' ')}"
    }
  }
}
