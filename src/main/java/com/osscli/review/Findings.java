/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.osscli.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Separates a review finding from a description of the diff.
 *
 * <p>A small local model, asked for "concerns", reliably returns the subject matter back: run
 * against a change that guards four exception walks it answered "Circular exception handling",
 * "Causal chain tracking", "Conversion to database columns". Every one of those is true, none is a
 * finding, and printed under the heading "Concerns" beside a confidence of 80% they read as three
 * problems somebody has to work through.
 *
 * <p>The rule that separates them is location. A reviewer's concern is about a place in the change;
 * a summary of the change is not. So a concern has to name one of the files the change touched, and
 * one that names nothing is reported as what it is — noise — rather than dropped in silence, which
 * would leave "no concerns" looking like a clean bill of health.
 */
public final class Findings {

    private Findings() {}

    /** Concerns that name a changed file, and the count of those that named nothing. */
    public record Located(List<String> concerns, int unlocated) {}

    public static Located locate(List<String> concerns, List<String> changedFiles) {
        List<String> names = new ArrayList<>();
        for (String path : changedFiles) {
            String file = path.substring(path.lastIndexOf('/') + 1);
            if (!file.isBlank()) {
                names.add(file.toLowerCase(Locale.ROOT));
                int dot = file.lastIndexOf('.');
                // The class name as well as the file name: a model writing about Java says
                // "ThrowableAttributeConverter", not "ThrowableAttributeConverter.java".
                if (dot > 0) {
                    names.add(file.substring(0, dot).toLowerCase(Locale.ROOT));
                }
            }
        }

        // With no file names there is nothing to locate against, and filtering on an empty list
        // would suppress every concern including the real ones. Unable to judge is not the same as
        // judged and rejected, so everything is kept.
        if (names.isEmpty()) {
            return new Located(
                    concerns.stream().filter(c -> c != null && !c.isBlank()).toList(), 0);
        }

        List<String> kept = new ArrayList<>();
        int unlocated = 0;
        for (String concern : concerns) {
            if (concern == null || concern.isBlank()) {
                continue;
            }
            String haystack = concern.toLowerCase(Locale.ROOT);
            if (names.stream().anyMatch(haystack::contains)) {
                kept.add(concern.strip());
            } else {
                unlocated++;
            }
        }
        return new Located(List.copyOf(kept), unlocated);
    }
}
