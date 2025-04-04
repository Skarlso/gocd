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
package com.thoughtworks.go.apiv2.packages.representers;

import com.thoughtworks.go.api.base.OutputWriter;
import com.thoughtworks.go.api.representers.JsonReader;
import com.thoughtworks.go.domain.packagerepository.PackageRepository;
import com.thoughtworks.go.spark.Routes;

public class PackageRepositoryRepresenter {
    public static void toJSON(OutputWriter outputWriter, PackageRepository packageRepository) {
        outputWriter.addLinks(linkWriter -> {
            linkWriter.addLink("self", Routes.PackageRepository.self(packageRepository.getId()));
            linkWriter.addAbsoluteLink("doc", Routes.PackageRepository.DOC);
            linkWriter.addLink("find", Routes.PackageRepository.FIND);
        });
        outputWriter.add("id", packageRepository.getId());
        outputWriter.add("name", packageRepository.getName());
    }

    public static PackageRepository fromJSON(JsonReader jsonReader) {
        PackageRepository packageRepository = new PackageRepository();
        jsonReader.readStringIfPresent("id", packageRepository::setId);
        jsonReader.readStringIfPresent("name", packageRepository::setName);
        return packageRepository;
    }
}
