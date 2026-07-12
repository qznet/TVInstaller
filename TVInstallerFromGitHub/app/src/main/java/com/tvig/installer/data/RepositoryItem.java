package com.tvig.installer.data;

/** Immutable repository row shown by the TV UI. */
public final class RepositoryItem {
    public enum Source {
        PRESET,
        FAVORITE
    }

    private static final String GITHUB_BASE_URL = "https://github.com/";

    private final String repository;
    private final String description;
    private final Source source;
    private final String url;

    public RepositoryItem(String repository, String description, Source source) {
        if (repository == null || repository.trim().isEmpty()) {
            throw new IllegalArgumentException("repository must not be empty");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.repository = repository.trim();
        this.description = description == null ? "" : description.trim();
        this.source = source;
        this.url = GITHUB_BASE_URL + this.repository;
    }

    public String getRepository() {
        return repository;
    }

    public String getDescription() {
        return description;
    }

    public Source getSource() {
        return source;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepositoryItem)) {
            return false;
        }
        RepositoryItem that = (RepositoryItem) other;
        return repository.equals(that.repository)
                && description.equals(that.description)
                && source == that.source;
    }

    @Override
    public int hashCode() {
        int result = repository.hashCode();
        result = 31 * result + description.hashCode();
        result = 31 * result + source.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "RepositoryItem{" + repository + ", source=" + source + '}';
    }
}
