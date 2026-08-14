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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a key wrapped in the punctuation of the page it was copied from still works.
 *
 * <p>Documentation writes keys as {@code <your-key-here>}, and a key pasted with the brackets still
 * attached is sent verbatim and answered with a 401. A 401 means "your key is wrong", so the
 * obvious next move is to re-issue the credential -- which produces another key, pasted the same
 * way, rejected the same way. Found in the wild: a stored Anthropic key beginning {@code <sk-ant-}.
 */
class CredentialCleaningTest {

    private static String clean(String raw) throws Exception {
        Method m = CredentialManager.class.getDeclaredMethod("clean", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    @Test
    @DisplayName("angle brackets from a documentation placeholder are removed")
    void anglesGo() throws Exception {
        assertEquals("sk-ant-api03-abc", clean("<sk-ant-api03-abc>"));
        assertEquals("sk-ant-api03-abc", clean("<sk-ant-api03-abc"));
    }

    @Test
    @DisplayName("quotes a shell left behind are removed")
    void quotesGo() throws Exception {
        assertEquals("ghp_token", clean("\"ghp_token\""));
        assertEquals("ghp_token", clean("'ghp_token'"));
        assertEquals("ghp_token", clean("`ghp_token`"));
    }

    @Test
    @DisplayName("surrounding whitespace and a trailing newline are removed")
    void whitespaceGoes() throws Exception {
        assertEquals("AIzaKey", clean("  AIzaKey \n"));
        assertEquals("AIzaKey", clean("\t\"AIzaKey\"\n"));
    }

    @Test
    @DisplayName("a clean key is returned exactly as it was given")
    void aGoodKeyIsUntouched() throws Exception {
        String key = "sk-ant-api03-0123456789_abcdefg-HIJKLMNOP";
        assertEquals(key, clean(key));
    }

    @Test
    @DisplayName("punctuation inside the key is never touched")
    void onlyTheWrappingIsStripped() throws Exception {
        // The point of the loop is the ends. A key this method silently rewrote in the middle
        // would be worse than one it rejected outright: it would fail somewhere else, later.
        assertEquals("sk-a'b\"c-d", clean("<sk-a'b\"c-d>"));
    }

    @Test
    @DisplayName("nothing at all is an empty string, not a crash")
    void emptyIsSafe() throws Exception {
        assertEquals("", clean(null));
        assertEquals("", clean("   "));
        assertEquals("", clean("<>"));
    }
}
