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
package com.osscli.safety;

import java.io.Console;
import java.util.Locale;

/**
 * Nothing reaches an upstream repository unless it was asked for by name, twice.
 *
 * <p>Reading a public repository is free to get wrong. Writing to one is not: a comment, a review or
 * an issue reaches everybody watching the thread and the mailing list the instant it is sent, and
 * deleting it afterwards reaches neither. There is no undo, only a correction with an audience.
 *
 * <p>So the default is <b>refuse</b>, everywhere, with no way to change the default. There is no
 * setting that arms this, no stored credential that satisfies it and no environment variable that
 * turns it off, because every one of those becomes a thing that is switched on once and then
 * forgotten -- after which the protection exists only in the belief that it exists.
 *
 * <p>Two independent things must both be true:
 *
 * <ol>
 *   <li><b>The operator named the repository on the command line</b>, as
 *       {@code --approve-upstream owner/name}. Naming it is the point: an approval that did not have
 *       to name a target would approve whatever the command happened to be pointed at, which is the
 *       failure it exists to prevent. An approval for one repository is not an approval for another.
 *   <li><b>The operator confirmed this specific write, at a terminal, now.</b> Every time. Approval
 *       is per invocation and is never remembered, because a write that happens on the strength of
 *       something typed earlier is a write nobody decided to make.
 * </ol>
 *
 * <p>This applies to every path equally -- a command, a dispatched extension, a local model, a cloud
 * model. A model that has decided a comment should be posted has decided nothing; it still has to
 * come through here, and here still asks the person.
 */
public final class UpstreamGuard {

    /** The one way to permit an outward write, and it names its target. */
    public static final String APPROVE_FLAG = "--approve-upstream";

    private UpstreamGuard() {}

    /**
     * Decide whether one outward-facing write may proceed.
     *
     * @param action human description, e.g. {@code "log4j hub"} or {@code "post a review comment"}
     * @param targetRepo where it would land, as {@code owner/name}
     * @param approvedRepo what the operator passed to {@link #APPROVE_FLAG}, or null
     * @return true only when approval names this exact repository and the operator confirms now
     */
    public static boolean allow(String action, String targetRepo, String approvedRepo) {
        String target = targetRepo == null ? "" : targetRepo.trim();
        String approved = approvedRepo == null ? "" : approvedRepo.trim();

        System.out.println();
        System.out.println("  This would write to an upstream repository.");
        System.out.println("    action : " + action);
        System.out.println("    target : " + (target.isEmpty() ? "(unknown)" : target));

        // An unknown target cannot be matched against an approval, so it can never be approved.
        // Refusing here rather than prompting keeps "approved" meaning one specific repository.
        if (target.isEmpty()) {
            refuse("the target repository is not known, so no approval can apply to it");
            return false;
        }
        if (approved.isEmpty()) {
            refuse("no " + APPROVE_FLAG + " was given");
            System.err.println("       To allow this one write, re-run with:");
            System.err.println("         " + APPROVE_FLAG + " " + target);
            return false;
        }
        if (!approved.equalsIgnoreCase(target)) {
            refuse("approval names a different repository");
            System.err.println("       approved : " + approved);
            System.err.println("       target   : " + target);
            System.err.println("       An approval for one repository is not an approval for another.");
            return false;
        }

        Console console = System.console();
        if (console == null) {
            refuse("there is no terminal to confirm at");
            System.err.println("       An upstream write is never performed unattended, approved or not.");
            return false;
        }

        // Typing the repository name, not "y". A yes/no prompt is answered by reflex; retyping the
        // target is the smallest thing that cannot be done without reading it.
        System.out.println();
        System.out.println("  Type the repository name to confirm this write, or anything else to cancel.");
        String typed = console.readLine("  %s > ", target);
        if (typed != null && typed.trim().equalsIgnoreCase(target)) {
            System.out.println("  confirmed.");
            return true;
        }
        refuse("not confirmed");
        return false;
    }

    private static void refuse(String why) {
        System.err.println();
        System.err.println("error  refused — " + why + ".");
        System.err.println("       Nothing was sent.");
    }

    /** Normalise and sanity-check an {@code owner/name} approval as given on the command line. */
    public static String normaliseRepo(String raw) {
        if (raw == null) {
            return null;
        }
        String r = raw.trim();
        if (!r.matches("[\\w.-]+/[\\w.-]+")) {
            throw new IllegalArgumentException(
                    APPROVE_FLAG + " takes a repository as owner/name — got \"" + raw + "\"");
        }
        return r.toLowerCase(Locale.ROOT);
    }
}
