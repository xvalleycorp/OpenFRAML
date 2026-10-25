//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.pack;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;

/**
 * A file shipped in a country pack.
 *
 * @param name the file name, for messages, e.g. {@code gate_decision.v1.stone.json}
 * @param url  where to read it
 */
public record PackResource(String name, URL url) {

    public PackResource {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
    }

    public InputStream open() throws IOException {
        return url.openStream();
    }
}
