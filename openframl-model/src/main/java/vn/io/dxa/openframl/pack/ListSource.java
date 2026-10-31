//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.pack;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * A screening list the country's rules expect a bank to screen against, e.g. the UN Security Council Consolidated
 * List or a national list of designated persons.
 *
 * @param id        the list's source name, as its files are named ({@code <id>@<version>.csv}), e.g. {@code UN-SC}
 * @param name      what it is, in English
 * @param publisher who publishes it
 * @param format    the form its files take in the screening directory
 * @param url       where the publisher makes it available, if it does so in a form a program can read
 * @param basis     the regulation that makes it apply
 */
public record ListSource(String id, String name, String publisher, Format format, Optional<URI> url, String basis) {

    /** The forms a screening directory reads. */
    public enum Format {
        /** OpenFRAML's open CSV form, which the bank converts the publisher's documents to. */
        OPENFRAML_CSV,
        /** The UN Security Council Consolidated List as the UN publishes it in XML. */
        UN_SC_XML
    }

    public ListSource {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("a list id holds letters, digits, '_', '.' and '-' only: " + id);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(basis, "basis");
    }
}
