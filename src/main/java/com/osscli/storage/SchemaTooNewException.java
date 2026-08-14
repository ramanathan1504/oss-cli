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
 * The store on disk was written by a newer {@code oss} than this one.
 *
 * <p>Migrations only ever run forwards, so an older build has no way to understand a schema it was
 * written before. Until this existed it did not try to: the migration loop simply matched nothing
 * and fell through in silence, and the command carried on reading tables whose meaning may have
 * changed underneath it. Nothing on screen said so.
 *
 * <p>That is the worst available outcome, because the damage is quiet and cumulative — the older
 * build goes on to write rows in the shape it believes in. Refusing costs one command; carrying on
 * costs the store.
 *
 * <p>Unchecked on purpose. Every caller of {@link DatabaseManager#initializeSchema()} would only
 * rethrow it, and there is exactly one thing to do about it, which {@code Main} does.
 */
public class SchemaTooNewException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int storeVersion;
    private final int understoodVersion;

    public SchemaTooNewException(int storeVersion, int understoodVersion) {
        super("database schema " + storeVersion + " is newer than the " + understoodVersion
                + " this build understands");
        this.storeVersion = storeVersion;
        this.understoodVersion = understoodVersion;
    }

    public int storeVersion() {
        return storeVersion;
    }

    public int understoodVersion() {
        return understoodVersion;
    }
}
