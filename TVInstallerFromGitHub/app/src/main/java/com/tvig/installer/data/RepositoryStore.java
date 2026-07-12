package com.tvig.installer.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Owns the on-device preset file and the user's JSON-encoded favorites. */
public final class RepositoryStore {
    public static final int MAX_PRESET_BYTES = 1024 * 1024;

    private static final String TAG = "RepositoryStore";
    private static final String PRESET_ASSET = "preset.txt";
    private static final String PRESET_FILE = "preset.txt";
    private static final String PREFERENCES = "repository_store";
    private static final String KEY_FAVORITES = "favorites_json";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final Context context;
    private final SharedPreferences preferences;
    private final File presetFile;
    private final AtomicFile atomicPreset;

    public RepositoryStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext != null ? applicationContext : context;
        this.preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        this.presetFile = new File(this.context.getFilesDir(), PRESET_FILE);
        this.atomicPreset = new AtomicFile(presetFile);
        try {
            ensurePresetExists();
        } catch (IOException error) {
            Log.e(TAG, "Unable to initialize preset file", error);
        }
    }

    /** Returns presets in file order, then favorites in reverse addition order. */
    public synchronized List<RepositoryItem> getRepositories() {
        List<RepositoryItem> presets = readPresetsWithAssetFallback();
        List<RepositoryItem> merged = new ArrayList<>(presets.size() + 8);
        merged.addAll(presets);

        Map<String, String> presetDescriptions = new LinkedHashMap<>();
        for (RepositoryItem item : presets) {
            String key = normalizeKey(item.getRepository());
            if (!presetDescriptions.containsKey(key)) {
                presetDescriptions.put(key, item.getDescription());
            }
        }

        List<String> favorites = readFavorites();
        for (int index = favorites.size() - 1; index >= 0; index--) {
            String repository = favorites.get(index);
            String description = presetDescriptions.get(normalizeKey(repository));
            merged.add(new RepositoryItem(
                    repository,
                    description == null ? "" : description,
                    RepositoryItem.Source.FAVORITE));
        }
        return merged;
    }

    /** Adds a missing favorite or removes an existing one, returning its new state. */
    public synchronized boolean toggleFavorite(String repository) {
        String normalized = requireValidRepository(repository);
        List<String> favorites = readFavorites();
        int existing = indexOfRepository(favorites, normalized);
        boolean nowFavorite;
        if (existing >= 0) {
            favorites.remove(existing);
            nowFavorite = false;
        } else {
            favorites.add(normalized);
            nowFavorite = true;
        }
        writeFavorites(favorites);
        return nowFavorite;
    }

    public synchronized boolean isFavorite(String repository) {
        if (!PresetParser.isValidRepository(repository)) {
            return false;
        }
        return indexOfRepository(readFavorites(), repository.trim()) >= 0;
    }

    /** Validates the entire UTF-8 payload before atomically replacing files/preset.txt. */
    public synchronized void replacePresetAtomically(byte[] presetBytes) throws IOException {
        validatePreset(presetBytes);
        writePresetAtomically(presetBytes);
    }

    private void ensurePresetExists() throws IOException {
        File backup = new File(presetFile.getPath() + ".bak");
        if (presetFile.isFile() || backup.isFile()) {
            return;
        }
        byte[] bundled = readBundledPreset();
        validatePreset(bundled);
        writePresetAtomically(bundled);
    }

    private List<RepositoryItem> readPresetsWithAssetFallback() {
        try {
            ensurePresetExists();
            byte[] saved = readAtomicPreset();
            return validatePreset(saved);
        } catch (Exception savedError) {
            Log.w(TAG, "Saved preset is unavailable; using bundled fallback", savedError);
            try {
                byte[] bundled = readBundledPreset();
                List<RepositoryItem> parsed = validatePreset(bundled);
                try {
                    writePresetAtomically(bundled);
                } catch (IOException repairError) {
                    Log.w(TAG, "Unable to repair saved preset", repairError);
                }
                return parsed;
            } catch (Exception assetError) {
                Log.e(TAG, "Bundled preset is invalid", assetError);
                return new ArrayList<>();
            }
        }
    }

    private byte[] readBundledPreset() throws IOException {
        InputStream input = context.getAssets().open(PRESET_ASSET);
        try {
            return readLimited(input);
        } finally {
            input.close();
        }
    }

    private byte[] readAtomicPreset() throws IOException {
        FileInputStream input = atomicPreset.openRead();
        try {
            return readLimited(input);
        } finally {
            input.close();
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_PRESET_BYTES) {
                throw new IOException("Preset exceeds " + MAX_PRESET_BYTES + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private List<RepositoryItem> validatePreset(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Preset is empty");
        }
        if (bytes.length > MAX_PRESET_BYTES) {
            throw new IOException("Preset exceeds " + MAX_PRESET_BYTES + " bytes");
        }

        String text = decodeUtf8Strictly(bytes);
        List<RepositoryItem> parsed;
        try {
            parsed = PresetParser.parse(text);
        } catch (IllegalArgumentException formatError) {
            throw new IOException("Preset format is invalid", formatError);
        }
        if (parsed.isEmpty()) {
            throw new IOException("Preset contains no repositories");
        }
        return parsed;
    }

    private String decodeUtf8Strictly(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private void writePresetAtomically(byte[] bytes) throws IOException {
        FileOutputStream output = null;
        try {
            output = atomicPreset.startWrite();
            output.write(bytes);
            atomicPreset.finishWrite(output);
            output = null;
        } catch (IOException error) {
            if (output != null) {
                atomicPreset.failWrite(output);
            }
            throw error;
        } catch (RuntimeException error) {
            if (output != null) {
                atomicPreset.failWrite(output);
            }
            throw error;
        }
    }

    private List<String> readFavorites() {
        String json = preferences.getString(KEY_FAVORITES, "[]");
        List<String> favorites = new ArrayList<>();
        try {
            JSONArray array = parseFavoritesArray(json);
            for (int index = 0; index < array.length(); index++) {
                Object value = array.opt(index);
                if (!(value instanceof String)) {
                    continue;
                }
                String repository = ((String) value).trim();
                if (PresetParser.isValidRepository(repository)
                        && indexOfRepository(favorites, repository) < 0) {
                    favorites.add(repository);
                }
            }
        } catch (JSONException error) {
            Log.w(TAG, "Ignoring malformed favorites JSON", error);
        }
        return favorites;
    }

    private void writeFavorites(List<String> favorites) {
        JSONArray array = new JSONArray();
        for (String repository : favorites) {
            array.put(repository);
        }
        try {
            JSONObject root = new JSONObject();
            root.put("favorites", array);
            if (!preferences.edit().putString(KEY_FAVORITES, root.toString()).commit()) {
                throw new IllegalStateException("Unable to persist favorites");
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to encode favorites", error);
        }
    }

    private JSONArray parseFavoritesArray(String json) throws JSONException {
        String saved = json == null ? "[]" : json.trim();
        if (saved.startsWith("[")) {
            return new JSONArray(saved);
        }
        JSONObject root = new JSONObject(saved);
        JSONArray favorites = root.optJSONArray("favorites");
        return favorites == null ? new JSONArray() : favorites;
    }

    private String requireValidRepository(String repository) {
        if (!PresetParser.isValidRepository(repository)) {
            throw new IllegalArgumentException("Invalid GitHub repository: " + repository);
        }
        return repository.trim();
    }

    private int indexOfRepository(List<String> repositories, String target) {
        for (int index = 0; index < repositories.size(); index++) {
            if (repositories.get(index).equalsIgnoreCase(target)) {
                return index;
            }
        }
        return -1;
    }

    private String normalizeKey(String repository) {
        return repository.toLowerCase(Locale.US);
    }
}
