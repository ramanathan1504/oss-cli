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
package com.osscli.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That what the documentation promises about credentials is what the code reads.
 *
 * <p>Every one of these is a name in a table somebody copies out of {@code SETUP.md} into a shell
 * profile. A name that is documented and not read fails the same way a missing key does — except
 * the user has already done the thing the page told them to, so the next place they look is their
 * provider account rather than the tool.
 *
 * <p>That happened here: the page listed {@code GH_TOKEN}, {@code oss doctor} reported it as
 * acceptable, and the method that actually fetches the token only ever read {@code GITHUB_TOKEN}.
 * A green health check followed by "GitHub Token is missing" on the next command.
 */
class CredentialSourcesTest {

    private static final Path SOURCE = Path.of("src/main/java/com/osscli/util/CredentialManager.java");
    private static final Path DOCTOR = Path.of("src/main/java/com/osscli/cli/DoctorCommand.java");
    private static final Path SETUP_DOC = Path.of("SETUP.md");

    @Test
    @DisplayName("every environment variable the docs name is one the code reads")
    void documentedNamesAreRead() throws IOException {
        String code = Files.readString(SOURCE);
        String doc = Files.readString(SETUP_DOC);

        for (String name : List.of("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GITHUB_TOKEN")) {
            assertTrue(doc.contains(name), "SETUP.md no longer documents " + name);
            assertTrue(code.contains(name), "the code no longer reads " + name + ", but SETUP.md promises it");
        }
    }

    @Test
    @DisplayName("the token doctor accepts is the token the tool will use")
    void doctorAndFetchAgreeOnGitHub() throws IOException {
        String code = Files.readString(SOURCE);
        String doctor = Files.readString(DOCTOR);

        // Doctor's whole job is to answer "will this work". If it reports a variable as sufficient,
        // the fetch has to honour the same one, or the health check is the thing that is broken.
        if (doctor.contains("GH_TOKEN")) {
            assertTrue(
                    code.contains("GH_TOKEN"),
                    "doctor reports GH_TOKEN as acceptable and the credential reader ignores it");
        }
    }

    @Test
    @DisplayName("doctor asks the credential reader rather than the environment")
    void doctorDoesNotDoItsOwnLookup() throws IOException {
        String doctor = Files.readString(DOCTOR);

        // The same bug as above, pointing the other way. Doctor tested System.getenv itself, so a
        // token stored in the keychain -- which is what 'oss setup' offers first -- was reported
        // missing while every command that reads GitHub worked. A health check that contradicts the
        // tool sends people to fix a credential that was never wrong.
        assertFalse(
                doctor.contains("System.getenv(\"GITHUB_TOKEN\")") || doctor.contains("System.getenv(\"GH_TOKEN\")"),
                "doctor reads the environment directly, so it cannot see a keychain token the tool will use");
        assertTrue(doctor.contains("gitHubTokenSource"), "doctor must answer from the same lookup the fetch uses");
    }

    @Test
    @DisplayName("the token is not advertised as needed by one command")
    void tokenIsNotClaimedToBeSyncOnly() throws IOException {
        String doctor = Files.readString(DOCTOR);

        // It said "needed only for 'sync'". GitHubClient is what review, pr, issue, prs, hub and
        // followup all read through, so somebody who cannot review a pull request was told the
        // missing credential only mattered for a command they had not run.
        assertFalse(doctor.contains("needed only for"), "the token serves every command that reads GitHub");
    }

    @Test
    @DisplayName("every keychain service name in the docs is the one the code looks up")
    void documentedKeychainNamesMatch() throws IOException {
        String code = Files.readString(SOURCE);
        String doc = Files.readString(SETUP_DOC);

        // These are copied verbatim into a `security add-generic-password -s <name>` command. A
        // mismatch stores the key under a name nothing reads, and the failure looks like the
        // keychain not working rather than like a typo in a table.
        for (String service : List.of("anthropic_api_key", "openai_api_key", "gemini_api_key", "github_token")) {
            assertTrue(doc.contains(service), "SETUP.md no longer documents the keychain name " + service);
            assertTrue(code.contains(service), "the code no longer looks up " + service);
        }
    }

    @Test
    @DisplayName("every ollama config key the docs name is one the code reads")
    void documentedOllamaKeysAreRead() throws IOException {
        String doc = Files.readString(SETUP_DOC);
        String code = readAll(Path.of("src/main/java/com/osscli"));

        // Each of these is copied out of a table into a config value. One that nothing reads is a
        // setting that appears to work and changes nothing -- which is what `ollama.url` itself was
        // before it was wired up: seeded, displayed by doctor, and ignored by every request.
        for (String key : List.of("ollama.url", "ollama.model.guidance", "ollama.model.triage")) {
            assertTrue(doc.contains(key), "SETUP.md no longer documents " + key);
            assertTrue(code.contains(key), "nothing reads " + key + ", but SETUP.md documents it");
        }
    }

    @Test
    @DisplayName("the default address in the docs is the default in the code")
    void theDocumentedDefaultIsTheRealOne() throws IOException {
        String defaults = Files.readString(Path.of("src/main/java/com/osscli/Defaults.java"));
        String doc = Files.readString(SETUP_DOC);

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("OLLAMA_URL\\s*=\\s*\"([^\"]+)\"")
                .matcher(defaults);
        assertTrue(m.find(), "Defaults no longer declares OLLAMA_URL");
        assertTrue(doc.contains(m.group(1)), "SETUP.md prints a different default address than " + m.group(1));
    }

    @Test
    @DisplayName("the docs say there is no environment variable for the address, so there must not be one")
    void noEnvironmentOverrideForOllama() throws IOException {
        String code = readAll(Path.of("src/main/java/com/osscli"));

        // SETUP.md tells people OLLAMA_HOST is not read here, which is a claim that stops being
        // true the moment somebody adds it -- and then the page is confidently wrong about the one
        // thing a person checks when the daemon is on another machine.
        assertTrue(
                !code.contains("OLLAMA_HOST"),
                "OLLAMA_HOST is read now; SETUP.md says it is not, and one of the two has to change");
    }

    /** Every source file under a directory, concatenated -- the question is "anywhere", not "where". */
    private static String readAll(Path dir) throws IOException {
        StringBuilder all = new StringBuilder();
        try (var files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .toList()) {
                all.append(Files.readString(f));
            }
        }
        return all.toString();
    }

    @Test
    @DisplayName("a key wrapped in the punctuation documentation uses still works")
    void wrappingIsStripped() throws Exception {
        // Pages write keys as <your-key-here> and shells quote them, so a key arrives wrapped often
        // enough to expect it. Sending one verbatim is a 401, and a 401 reads as "your key is
        // wrong" -- which is how a good key with a leading angle bracket costs an afternoon.
        var clean = CredentialManager.class.getDeclaredMethod("clean", String.class);
        clean.setAccessible(true);

        assertEquals("sk-ant-123", clean.invoke(null, "<sk-ant-123>"));
        assertEquals("sk-ant-123", clean.invoke(null, "\"sk-ant-123\""));
        assertEquals("sk-ant-123", clean.invoke(null, "  sk-ant-123\n"));
        assertEquals("sk-ant-123", clean.invoke(null, "'sk-ant-123'"));
        // Only the wrapping. A key this quietly rewrote inside would be worse than one it refused.
        assertEquals("sk-ant-1<2>3", clean.invoke(null, "sk-ant-1<2>3"));
    }

    @Test
    @DisplayName("every credential you can demand, you can also merely ask about")
    void everyGetterHasAFinder() {
        // The getters throw when the credential is absent, which is right for a caller about to
        // make a request and wrong for one deciding whether it can. Three of them grew a non-
        // throwing sibling after `Ai.Engine.hasCredential` answered "do you have an Anthropic key?"
        // by raising "Anthropic API Key is missing". The fourth did not, and the identical bug
        // arrived by the identical route: `oss bug` degrades without a GitHub token -- it prints
        // the report and the address to paste it at -- and asked with getGitHubToken(), so on a
        // machine that has one the degraded branch is unreachable and it looks correct. Every CI
        // runner, which is to say every machine the branch exists for, got a stack trace.
        //
        // Structural on purpose: the next credential added will have a getter, and this is what
        // says it needs the other half before anybody has been bitten by which half is missing.
        List<String> missing = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : CredentialManager.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    || !m.getName().startsWith("get")
                    || m.getParameterCount() != 0
                    || m.getReturnType() != String.class) {
                continue;
            }
            String sibling = "find" + m.getName().substring("get".length());
            try {
                CredentialManager.class.getDeclaredMethod(sibling);
            } catch (NoSuchMethodException e) {
                missing.add(m.getName() + " throws when it is absent, and there is no " + sibling + "()");
            }
        }

        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    @Test
    @DisplayName("asking about the GitHub token is never itself an error")
    void findingTheTokenNeverThrows() {
        // Whether this machine has one is not the point and cannot be asserted -- a laptop has a
        // keychain and a runner does not, and both are correct. What must hold on both is that
        // asking returns an answer rather than raising one.
        assertDoesNotThrow(CredentialManager::findGitHubToken);
    }
}
