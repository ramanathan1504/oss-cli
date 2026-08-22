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
package com.osscli.agent;

import java.util.function.Function;

/**
 * Ask what this machine already knows, before anything goes outward.
 *
 * <p>This is the tool that makes the loop worth having here rather than anywhere else. The other
 * command-line agents start from the working tree and the network; this one has 15,000 issues, the
 * threads they came from, and the notes their owner wrote, indexed by meaning and sitting on the
 * same disk. A question the archive already answers should never become a round trip, and the loop
 * having a way to ask is what makes that true in practice rather than in a design note.
 *
 * <p>The searcher is passed in rather than reached for, so this is testable without a 727 MB
 * database — the same seam as everything else here, and for the same reason.
 */
public final class Recall implements Tool {

    private final Function<String, String> searcher;

    public Recall(Function<String, String> searcher) {
        this.searcher = searcher;
    }

    @Override
    public String name() {
        return "recall";
    }

    @Override
    public String usage() {
        return "recall — query: <words>   search everything already synced and indexed on this machine";
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public String run(Action action, Workspace workspace) {
        String query = action.argument("query");
        if (query.isEmpty()) {
            return "error: recall needs a query";
        }
        try {
            String found = searcher.apply(query);
            // "Nothing" is a real answer and a useful one: it tells the model to stop looking here
            // and say what it does not know, rather than to ask the same thing three more ways.
            return found == null || found.isBlank() ? "nothing indexed here matches: " + query : found;
        } catch (Exception e) {
            return "error: could not search this machine's corpus — " + e.getMessage();
        }
    }
}
