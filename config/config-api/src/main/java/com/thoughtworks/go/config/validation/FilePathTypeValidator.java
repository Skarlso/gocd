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
package com.thoughtworks.go.config.validation;

import com.thoughtworks.go.util.XmlUtils;

import java.util.regex.Pattern;

public class FilePathTypeValidator {
    public static final String PATH_PATTERN = "(([.]\\/)?[.][^. ]+)|([^. ].+[^. ])|([^. ][^. ])|([^. ])";
    private static final Pattern PATH_PATTERN_REGEX = Pattern.compile(String.format("^(%s)$", PATH_PATTERN));

    public boolean isPathValid(String path) {
        return path == null || XmlUtils.matchUsingRegex(PATH_PATTERN_REGEX, path);
    }

    public static String errorMessage(String type, String name) {
        return String.format("Invalid %s name '%s'. %s", type, name, "It should be a valid relative path.");
    }
}
