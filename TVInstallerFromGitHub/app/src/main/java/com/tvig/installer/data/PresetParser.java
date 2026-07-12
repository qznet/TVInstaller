package com.tvig.installer.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Parses the UTF-8 preset format: {@code owner/repository = optional description}. */
public final class PresetParser {
    private static final Pattern REPOSITORY_PATTERN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]+");

    private PresetParser() {
    }

    public static List<RepositoryItem> parse(String text) throws IOException {
        if (text == null) {
            throw new IllegalArgumentException("preset text must not be null");
        }
        return parse(new StringReader(text));
    }

    public static List<RepositoryItem> parse(Reader reader) throws IOException {
        if (reader == null) {
            throw new IllegalArgumentException("preset reader must not be null");
        }

        BufferedReader buffered = reader instanceof BufferedReader
                ? (BufferedReader) reader
                : new BufferedReader(reader);
        List<RepositoryItem> result = new ArrayList<>();
        String line;
        int lineNumber = 0;
        while ((line = buffered.readLine()) != null) {
            lineNumber++;
            if (lineNumber == 1 && !line.isEmpty() && line.charAt(0) == '\ufeff') {
                line = line.substring(1);
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int separator = trimmed.indexOf('=');
            String repository = separator >= 0
                    ? trimmed.substring(0, separator).trim()
                    : trimmed;
            String description = separator >= 0
                    ? trimmed.substring(separator + 1).trim()
                    : "";
            if (!isValidRepository(repository)) {
                throw new IllegalArgumentException(
                        "Invalid repository at line " + lineNumber + ": " + repository);
            }
            result.add(new RepositoryItem(
                    repository,
                    description,
                    RepositoryItem.Source.PRESET));
        }
        return result;
    }

    public static boolean isValidRepository(String repository) {
        return repository != null
                && REPOSITORY_PATTERN.matcher(repository.trim()).matches();
    }
}
