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
package com.osscli.storage;

/**
 * A build straight out of {@code target/} was about to migrate the real store.
 *
 * <p>{@link SchemaTooNewException} refuses to read a store written by a newer build, loudly, and
 * says migrations are one-way. The other direction was silent: a development jar opened the same
 * store, ran every pending migration, stamped it, and printed a progress line. Both directions are
 * one-way doors; only one of them asked.
 *
 * <p>What that costs is not hypothetical. On 2026-08-22 a single command was run without
 * {@code OSS_CLI_HOME} pointing somewhere safe, the checkout's jar migrated a 727 MB store from
 * schema 14 to 15, and the installed release then refused it — correctly, and until a release
 * carrying 15 existed the tool was simply unusable. Nothing was lost, and nothing needed to be for
 * that to be the wrong afternoon.
 *
 * <p>So a build output migrating the default store now refuses instead. Both ways forward are
 * named, and neither is "remember next time": point somewhere else, or say yes on purpose.
 *
 * <p>Deliberately narrow. An installed release migrating your store is the whole point of upgrading
 * and must never ask. A build output pointed at a scratch {@code OSS_CLI_HOME} is what every test
 * and every experiment already does, and must never ask either. This fires only where those two
 * meet: unreleased code, real data.
 */
public class SchemaUpgradeRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The environment variable that says yes on purpose. */
    public static final String OVERRIDE = "OSS_ALLOW_SCHEMA_UPGRADE";

    private final int storeVersion;
    private final int buildVersion;
    private final String store;

    public SchemaUpgradeRefusedException(int storeVersion, int buildVersion, String store) {
        super("a development build would migrate " + store + " from schema " + storeVersion + " to " + buildVersion);
        this.storeVersion = storeVersion;
        this.buildVersion = buildVersion;
        this.store = store;
    }

    public int storeVersion() {
        return storeVersion;
    }

    public int buildVersion() {
        return buildVersion;
    }

    public String store() {
        return store;
    }
}
