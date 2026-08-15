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
package com.osscli.github;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;

/**
 * Whether a failure was the network, and what to say about it.
 *
 * <p>Turning the wifi off produced three different wrong answers from one cause. {@code oss issue}
 * and {@code oss pr} printed {@code error  null}, because {@link ConnectException} raised by the JDK
 * HTTP client carries no message and the catch block printed {@code e.getMessage()} unguarded.
 * {@code oss review} printed forty lines of {@code jdk.internal.net.http} stack, because its catch
 * named {@link IllegalArgumentException} and a connect failure is not one. {@code oss hub} and
 * {@code oss followup} were the worst of the three: they reported seventeen pull requests as
 * "private, deleted, or no token" — three specific, alarming, and wrong explanations for a cable.
 *
 * <p>The last one is why this exists as a shared class rather than three separate catch blocks. A
 * command that guesses at a cause will eventually guess something that sends the reader looking for
 * a problem they do not have.
 *
 * <p>So: {@link #isNetwork} answers the question once, {@link #describe} produces a sentence that is
 * never {@code null} and never empty, and {@link #seen} lets a command that has already collapsed
 * its failures to {@code null} still say which kind they were.
 */
public final class Reachability {

    private Reachability() {}

    /**
     * Set the first time a connectivity failure is seen in this process.
     *
     * <p>For summaries that count failures rather than raising them. {@code hub} walks a ledger and
     * turns every unreadable pull request into {@code null}; by the time it prints a total the
     * exceptions are long gone, and without this it can only guess.
     */
    private static volatile boolean sawNetworkFailure = false;

    /** True when this process has failed to reach the network at least once. */
    public static boolean seen() {
        return sawNetworkFailure;
    }

    /** Test seam: forget what this process has seen. */
    public static void reset() {
        sawNetworkFailure = false;
    }

    /**
     * True when the throwable, or anything it wraps, means the request never reached the far end.
     *
     * <p>A 401, a 403 and a 404 are answers — GitHub was reached and said no. Only the failures that
     * happen before an answer belong here, because only those are fixed by reconnecting.
     */
    public static boolean isNetwork(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof UnresolvedAddressException
                    || c instanceof UnknownHostException
                    || c instanceof ConnectException
                    || c instanceof NoRouteToHostException
                    || c instanceof HttpTimeoutException
                    || c instanceof SocketTimeoutException) {
                return true;
            }
            if (c.getCause() == c) {
                break; // A throwable that causes itself would spin here forever.
            }
        }
        return false;
    }

    /**
     * A sentence for the user, whatever went wrong.
     *
     * <p>Guarantees a non-empty result. That guarantee is the whole point: the bug being fixed was a
     * catch block trusting {@code getMessage()}, which is null on precisely the exception a
     * disconnected machine raises most.
     */
    public static String describe(Throwable t) {
        if (t == null) {
            return "failed, with no reason given";
        }
        if (isNetwork(t)) {
            sawNetworkFailure = true;
            return offlineSentence(t);
        }
        String message = t.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        // No message either. The class name is a poor sentence but an infinitely better one than
        // the word "null", which tells the reader nothing and looks like a crash in the tool.
        return t.getClass().getSimpleName() + " (no further detail)";
    }

    /** Names the specific way the connection failed, since the remedies differ. */
    private static String offlineSentence(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof UnresolvedAddressException || c instanceof UnknownHostException) {
                return "no network — api.github.com could not be resolved. "
                        + "Everything already synced still works offline: oss search, oss inspect, oss prompt.";
            }
            if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException) {
                return "api.github.com did not answer in time — the connection is up but not usable. "
                        + "Everything already synced still works offline: oss search, oss inspect, oss prompt.";
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return "no network — api.github.com could not be reached. "
                + "Everything already synced still works offline: oss search, oss inspect, oss prompt.";
    }

    /**
     * The failure a caller should raise instead of the raw one, so no command has to know about
     * sockets to report a disconnection honestly.
     */
    public static IOException asFailure(Throwable t) {
        return new IOException(describe(t), t);
    }

    /**
     * How to describe a set of things that could not be read.
     *
     * <p>{@code hub} and {@code followup} both count unreachable rows and print one line about them.
     * With the network down the honest answer is that none of the reasons they used to list were
     * involved.
     */
    public static String whyUnreadable() {
        return seen() ? "no network — GitHub was not reachable" : "private, deleted, or no token";
    }
}
