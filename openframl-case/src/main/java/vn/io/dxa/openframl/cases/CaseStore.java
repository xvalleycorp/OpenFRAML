//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases;

import java.util.List;
import java.util.Optional;

/**
 * Where cases are kept. Implementations must make {@link #save} atomic per case and refuse a save based on a
 * state that is no longer current, so two analysts acting at once cannot overwrite each other.
 */
public interface CaseStore {

    Optional<CaseFile> find(String caseId);

    /** The subject's case that is not closed, if any. */
    Optional<CaseFile> openCaseOf(String subject);

    /** The case an alert was added to, if it was. */
    Optional<CaseFile> caseOfAlert(String alertId);

    /**
     * Saves {@code next} if the stored case is still {@code previous} ({@code null} for a new case).
     *
     * @throws java.util.ConcurrentModificationException if it is not
     */
    void save(CaseFile previous, CaseFile next);

    /** Cases with this status, oldest first. */
    List<CaseFile> withStatus(CaseFile.Status status);
}
