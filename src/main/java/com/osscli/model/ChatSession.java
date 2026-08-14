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
package com.osscli.model;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * A conversation you can come back to.
 *
 * @param summary older turns folded into prose once the transcript outgrows the model's context;
 *     null until that happens
 * @param overview the one line {@code oss history} shows for this session
 * @param notePath the archive note this session was filed as, so resuming rewrites that file rather
 *     than filing a second overlapping copy of the same conversation
 * @param ownerPid the process that last held this session, with {@code ownerHost}; together with
 *     {@code updatedAt} they are how a second terminal notices the session is already open
 * @param parentId the session this one was forked from, when a fork was needed
 */
public record ChatSession(
        long id,
        String repository,
        long issueNumber,
        String issueTitle,
        String provider,
        String summary,
        String overview,
        String startedAt,
        String updatedAt,
        String endedAt,
        String notePath,
        Long ownerPid,
        String ownerHost,
        Long parentId,
        int turnCount) {

    /**
     * How long a session may go quiet before another terminal treats it as abandoned.
     *
     * <p>A session is held open by a person typing, so the gaps are human-sized: reading the answer,
     * going to look at the code, lunch. Two minutes is well past a process that died and well short
     * of anything a thinking user would trip.
     */
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    public boolean ended() {
        return endedAt != null && !endedAt.isBlank();
    }

    /**
     * True when another live {@code oss} process appears to be holding this session.
     *
     * <p>Deliberately not a lock. A lock file left behind by a killed process locks a user out of
     * their own conversation, which is worse than the collision it prevents -- so this is a
     * heartbeat that goes stale on its own, and every caller treats a positive as something to warn
     * about and offer a way round, never as a refusal the user cannot get past.
     */
    public boolean heldElsewhere(long myPid, String myHost) {
        if (ended() || ownerPid == null || ownerHost == null) {
            return false;
        }
        if (ownerPid == myPid && ownerHost.equals(myHost)) {
            return false;
        }
        // A different machine's pid means nothing here, so only the heartbeat can answer.
        return age().compareTo(STALE_AFTER) < 0;
    }

    /** Time since the last turn. {@link Duration#ZERO} when the stamp cannot be read, which reads as "just now". */
    public Duration age() {
        try {
            return Duration.between(Instant.parse(updatedAt), Instant.now());
        } catch (DateTimeParseException | NullPointerException e) {
            return Duration.ZERO;
        }
    }

    /** "just now", "14m", "3h", "2d" -- a column, so it has to stay short. */
    public String ageLabel() {
        Duration d = age();
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = d.toHours();
        if (hours < 24) {
            return hours + "h";
        }
        return d.toDays() + "d";
    }
}
