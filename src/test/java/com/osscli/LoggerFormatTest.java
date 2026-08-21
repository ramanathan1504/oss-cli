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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a log line does not print its own format specifier.
 *
 * <p>Log4j substitutes <code>{}</code>, not <code>%n</code> or <code>%s</code>. A printf specifier
 * in a logged string is therefore copied to the screen verbatim, which is how {@code oss triage}
 * came to print five headings as <code>[METADATA]%n</code>. Nothing failed and nothing warned; the
 * output was simply wrong, in a command whose whole product is its output.
 *
 * <p>{@code printf} calls are a different matter and are left alone — there the specifier is doing
 * its job.
 */
class LoggerFormatTest {

    /** A logging call and the first thing it is handed. */
    private static final Pattern LOGGED =
            Pattern.compile("LOGGER\\.(?:info|warn|error|debug|trace)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Specifiers Log4j will not substitute, so they reach the reader as typed. */
    private static final Pattern SPECIFIER = Pattern.compile("%[nsdf]\\b|%[nsdf]$");

    @Test
    @DisplayName("no logged string carries a printf specifier")
    void loggersUseBracesNotPercent() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/osscli"))) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = LOGGED.matcher(Files.readString(file));
                while (m.find()) {
                    String literal = m.group(1);
                    if (SPECIFIER.matcher(literal).find()) {
                        offences.add(file.getFileName() + ": \"" + literal + "\"");
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), "Log4j substitutes {} — these would print as typed: " + offences);
    }
}
