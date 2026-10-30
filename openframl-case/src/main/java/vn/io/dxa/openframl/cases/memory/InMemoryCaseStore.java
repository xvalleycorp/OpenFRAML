//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.memory;

import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseStore;

/** Cases in memory: for tests and trials. Everything is lost on restart. */
public final class InMemoryCaseStore implements CaseStore {

    private final Map<String, CaseFile> cases = new HashMap<>();
    private final Map<String, String> openBySubject = new HashMap<>();
    private final Map<String, String> byAlert = new HashMap<>();

    @Override
    public synchronized Optional<CaseFile> find(String caseId) {
        return Optional.ofNullable(cases.get(caseId));
    }

    @Override
    public synchronized Optional<CaseFile> openCaseOf(String subject) {
        return Optional.ofNullable(openBySubject.get(subject)).map(cases::get);
    }

    @Override
    public synchronized Optional<CaseFile> caseOfAlert(String alertId) {
        return Optional.ofNullable(byAlert.get(alertId)).map(cases::get);
    }

    @Override
    public synchronized void save(CaseFile previous, CaseFile next) {
        CaseFile stored = cases.get(next.id());
        if (previous == null ? stored != null : !previous.equals(stored)) {
            throw new ConcurrentModificationException("case " + next.id() + " changed since it was read");
        }
        if (previous == null && next.isOpen() && openBySubject.containsKey(next.subject())) {
            throw new ConcurrentModificationException("subject already has an open case");
        }
        cases.put(next.id(), next);
        if (next.isOpen()) {
            openBySubject.put(next.subject(), next.id());
        } else {
            openBySubject.remove(next.subject(), next.id());
        }
        for (Alert alert : next.alerts()) {
            byAlert.put(alert.id(), next.id());
        }
    }

    @Override
    public synchronized List<CaseFile> withStatus(CaseFile.Status status) {
        return cases.values().stream().filter(c -> c.status() == status)
                .sorted(Comparator.comparing(CaseFile::openedAt)).toList();
    }
}
