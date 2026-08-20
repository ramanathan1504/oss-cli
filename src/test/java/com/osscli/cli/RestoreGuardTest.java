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
package com.osscli.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That restoring over a corpus asks, and restoring onto a new machine does not. */
class RestoreGuardTest {

    @Test
    @DisplayName("a fresh machine is not asked, because there is nothing to lose")
    void freshInstallIsNotPrompted() {
        // The case restore exists for. A prompt here would put a question in front of the one path
        // that cannot destroy anything -- and on a new machine there may be no terminal either.
        assertTrue(RestoreCommand.mayReplaceWithoutAsking(0));
    }

    @Test
    @DisplayName("an existing store is never replaced silently")
    void existingStoreIsProtected() {
        // 530 MB of corpus went under an older archive with no question asked and no copy kept,
        // in a repository whose upstream guard stops to confirm before posting one comment.
        assertFalse(RestoreCommand.mayReplaceWithoutAsking(1));
        assertFalse(RestoreCommand.mayReplaceWithoutAsking(530L * 1024 * 1024));
    }
}
