package com.tvig.installer.data;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PresetParserTest {
    @Test
    public void parse_supportsCommentsBomWhitespaceAndOptionalDescriptions() throws Exception {
        String input = "\ufeff# comment\n"
                + "\n"
                + "  owner/first = First repository  \n"
                + "org/second\n"
                + "third/project = description = with equals\n";

        List<RepositoryItem> items = PresetParser.parse(input);

        assertEquals(3, items.size());
        assertEquals("owner/first", items.get(0).getRepository());
        assertEquals("First repository", items.get(0).getDescription());
        assertEquals(RepositoryItem.Source.PRESET, items.get(0).getSource());
        assertEquals("https://github.com/owner/first", items.get(0).getUrl());
        assertEquals("", items.get(1).getDescription());
        assertEquals("description = with equals", items.get(2).getDescription());
    }

    @Test
    public void parse_preservesConfiguredOrderAndDuplicates() throws Exception {
        List<RepositoryItem> items = PresetParser.parse(
                "owner/one\nowner/two\nowner/one = duplicate\n");

        assertEquals("owner/one", items.get(0).getRepository());
        assertEquals("owner/two", items.get(1).getRepository());
        assertEquals("owner/one", items.get(2).getRepository());
        assertEquals("duplicate", items.get(2).getDescription());
    }

    @Test
    public void parse_rejectsMalformedNonCommentLine() throws Exception {
        try {
            PresetParser.parse("valid/repository\nnot a repository\n");
            fail("Expected invalid preset to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("line 2"));
        }
    }

    @Test
    public void repositoryValidation_rejectsPathsAndAcceptsGitHubSlugs() {
        assertTrue(PresetParser.isValidRepository("F-Droid/F-Droid"));
        assertTrue(PresetParser.isValidRepository("owner/repo.name_2"));
        assertFalse(PresetParser.isValidRepository("https://github.com/owner/repo"));
        assertFalse(PresetParser.isValidRepository("owner/repo/extra"));
        assertFalse(PresetParser.isValidRepository("../repo"));
    }
}
