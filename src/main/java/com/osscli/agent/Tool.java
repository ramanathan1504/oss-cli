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

/**
 * One thing the loop can do on a model's behalf.
 *
 * <p>{@link #writes()} is the field that matters and the reason this is an interface rather than a
 * lambda. A tool that only reads can run unattended, because the worst it costs is a wasted step. A
 * tool that changes something cannot, and the difference has to be declared by the tool rather than
 * guessed at by the loop from its name — {@code followup} being read-only while
 * {@code followup --comment} posts is the same lesson this repository already learned about
 * extension manifests, where {@code writes} is declared for exactly this reason.
 *
 * <p>Every tool answers with a string the model reads next. Failures are answers too: "no such
 * file" is a sentence to retry against, not an exception to unwind the loop with. A loop that dies
 * on the first wrong path is less useful than one that is told it was wrong.
 */
public interface Tool {

    /** The word a model puts after {@code tool:}. Lowercase, no spaces. */
    String name();

    /** One line the model is shown, including the arguments it takes. */
    String usage();

    /**
     * Whether running this changes anything outside the loop's own head.
     *
     * <p>Declared, never inferred. The loop refuses anything true here unless the operator has said
     * yes, and today nothing in the built-in set returns true.
     */
    boolean writes();

    /** Do it, and answer with what the model should read. Never throws for ordinary failure. */
    String run(Action action, Workspace workspace);
}
