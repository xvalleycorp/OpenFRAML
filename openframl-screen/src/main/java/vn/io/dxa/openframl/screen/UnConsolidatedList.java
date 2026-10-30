//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the United Nations Security Council Consolidated List in its XML form, as published at
 * {@code https://scsanctions.un.org/resources/xml/en/consolidated.xml}.
 *
 * <ul>
 *   <li>Individuals become {@link ListEntry.Kind#PERSON PERSON} entries, entities
 *       {@link ListEntry.Kind#ORGANISATION ORGANISATION} entries, with the UN reference number (e.g.
 *       {@code QDi.001}) as id.</li>
 *   <li>Names: the individual's name parts in order; an entity's name, split where the list joins several with
 *       {@code ;}; aliases; and names in their original script.</li>
 *   <li>Aliases of low quality are left out by default: the UN itself says they are not enough to identify anyone,
 *       and they cause most false positives.</li>
 *   <li>Birth years: from exact or approximate dates and years, and every year of a range.</li>
 *   <li>Identifiers: the numbers of the listed documents. Nationalities: country names as the list gives them.</li>
 * </ul>
 *
 * <p>The list's {@code dateGenerated} is its version. The parser reads no DTD and resolves no external entity.
 */
public final class UnConsolidatedList {

    /** The source name of every match from this list. */
    public static final String SOURCE = "UN-SC";

    private UnConsolidatedList() {}

    public static ScreeningList read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, false);
        }
    }

    /**
     * @param lowQualityAliases also keep the aliases the list marks as of low quality
     * @throws IllegalArgumentException if the document is not a consolidated list
     */
    public static ScreeningList read(InputStream in, boolean lowQualityAliases) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        try {
            XMLStreamReader xml = factory.createXMLStreamReader(in);
            try {
                return read(xml, lowQualityAliases);
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("not a readable consolidated list: " + e.getMessage(), e);
        }
    }

    private static ScreeningList read(XMLStreamReader xml, boolean lowQualityAliases) throws XMLStreamException {
        xml.nextTag();
        if (!xml.getLocalName().equals("CONSOLIDATED_LIST")) {
            throw new IllegalArgumentException("not a consolidated list: root element " + xml.getLocalName());
        }
        String version = xml.getAttributeValue(null, "dateGenerated");
        List<ListEntry> entries = new ArrayList<>();
        while (xml.next() != XMLStreamConstants.END_DOCUMENT) {
            if (xml.isStartElement() && (xml.getLocalName().equals("INDIVIDUAL")
                    || xml.getLocalName().equals("ENTITY"))) {
                entries.add(entry(xml, lowQualityAliases));
            }
        }
        return new ScreeningList(SOURCE, version == null ? "" : version, entries);
    }

    /** One INDIVIDUAL or ENTITY element, read to its end. */
    private static ListEntry entry(XMLStreamReader xml, boolean lowQualityAliases) throws XMLStreamException {
        String element = xml.getLocalName();
        boolean person = element.equals("INDIVIDUAL");
        Map<String, String> fields = new HashMap<>();
        List<String> aliases = new ArrayList<>();
        Set<String> scripts = new LinkedHashSet<>();
        Set<Integer> years = new LinkedHashSet<>();
        Set<String> nationalities = new LinkedHashSet<>();
        Set<String> documents = new LinkedHashSet<>();
        while (!(xml.next() == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals(element))) {
            if (!xml.isStartElement()) {
                continue;
            }
            switch (xml.getLocalName()) {
                case "INDIVIDUAL_ALIAS", "ENTITY_ALIAS" -> {
                    Map<String, String> alias = children(xml);
                    String name = alias.getOrDefault("ALIAS_NAME", "");
                    if (!name.isBlank() && (lowQualityAliases || !"Low".equalsIgnoreCase(alias.get("QUALITY")))) {
                        aliases.add(name);
                    }
                }
                case "INDIVIDUAL_DATE_OF_BIRTH" -> {
                    Map<String, String> birth = children(xml);
                    year(birth.get("DATE")).ifPresent(years::add);
                    year(birth.get("YEAR")).ifPresent(years::add);
                    Optional<Integer> from = year(birth.get("FROM_YEAR"));
                    Optional<Integer> to = year(birth.get("TO_YEAR"));
                    if (from.isPresent() && to.isPresent() && to.get() - from.get() <= 20) {
                        for (int y = from.get(); y <= to.get(); y++) {
                            years.add(y);
                        }
                    }
                }
                case "NATIONALITY" -> children(xml, "VALUE").forEach(nationalities::add);
                case "INDIVIDUAL_DOCUMENT" -> {
                    String number = children(xml).get("NUMBER");
                    if (number != null && !number.isBlank()) {
                        documents.add(number);
                    }
                }
                case "NAME_ORIGINAL_SCRIPT" -> {
                    String name = xml.getElementText().strip();
                    if (!name.isEmpty()) {
                        scripts.add(name);
                    }
                }
                case "FIRST_NAME", "SECOND_NAME", "THIRD_NAME", "FOURTH_NAME", "REFERENCE_NUMBER", "DATAID",
                        "UN_LIST_TYPE", "LISTED_ON" -> fields.put(xml.getLocalName(), xml.getElementText().strip());
                default -> skip(xml);
            }
        }

        List<String> names = new ArrayList<>();
        if (person) {
            String full = String.join(" ", List.of(fields.getOrDefault("FIRST_NAME", ""),
                    fields.getOrDefault("SECOND_NAME", ""), fields.getOrDefault("THIRD_NAME", ""),
                    fields.getOrDefault("FOURTH_NAME", ""))).replaceAll("\\s+", " ").strip();
            names.add(full);
        } else {
            for (String name : fields.getOrDefault("FIRST_NAME", "").split(";")) {
                if (!name.isBlank()) {
                    names.add(name.strip());
                }
            }
        }
        names.addAll(aliases);
        names.addAll(scripts);
        String id = fields.getOrDefault("REFERENCE_NUMBER", "");
        if (id.isEmpty()) {
            id = "DATAID-" + fields.getOrDefault("DATAID", "?");
        }
        String remark = (fields.getOrDefault("UN_LIST_TYPE", "") + " list, listed on "
                + fields.getOrDefault("LISTED_ON", "?")).strip();
        return new ListEntry(id, person ? ListEntry.Kind.PERSON : ListEntry.Kind.ORGANISATION,
                names.stream().distinct().toList(), years, nationalities, documents, remark);
    }

    /** The text of each child element of the current element, which is read to its end. */
    private static Map<String, String> children(XMLStreamReader xml) throws XMLStreamException {
        Map<String, String> values = new HashMap<>();
        int depth = 1;
        while (depth > 0) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = xml.getLocalName();
                String text = xml.getElementText().strip();
                values.putIfAbsent(name, text);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return values;
    }

    /** The text of every child element named {@code name}. */
    private static List<String> children(XMLStreamReader xml, String name) throws XMLStreamException {
        List<String> values = new ArrayList<>();
        int depth = 1;
        while (depth > 0) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (xml.getLocalName().equals(name)) {
                    String text = xml.getElementText().strip();
                    if (!text.isEmpty()) {
                        values.add(text);
                    }
                } else {
                    skip(xml);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return values;
    }

    private static void skip(XMLStreamReader xml) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static Optional<Integer> year(String value) {
        if (value == null || value.length() < 4 || !value.substring(0, 4).chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(value.substring(0, 4)));
    }
}
