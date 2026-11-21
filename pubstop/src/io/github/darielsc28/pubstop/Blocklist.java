package io.github.darielsc28.pubstop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

/** Ensemble de domaines bloqués. Accepte le format « hosts » ou un domaine par ligne. */
final class Blocklist {
    private final HashSet<String> domains;

    private Blocklist(HashSet<String> domains) {
        this.domains = domains;
    }

    static Blocklist load(InputStream in) throws IOException {
        HashSet<String> set = new HashSet<>(160000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String d = parseLine(line);
                if (d != null) set.add(d);
            }
        }
        return new Blocklist(set);
    }

    /** @return le domaine de la ligne, ou null (commentaire, ligne vide, entrée locale). */
    static String parseLine(String line) {
        int hash = line.indexOf('#');
        if (hash >= 0) line = line.substring(0, hash);
        line = line.trim();
        if (line.isEmpty()) return null;
        String[] parts = line.split("\\s+");
        String d;
        if (parts.length == 1) {
            d = parts[0];
        } else if (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1")) {
            d = parts[1];
        } else {
            return null;
        }
        d = d.toLowerCase(Locale.ROOT);
        if (d.indexOf('.') < 0 || d.equals("0.0.0.0") || d.startsWith("localhost")) return null;
        return d;
    }

    boolean isBlocked(String name) {
        return domains.contains(name);
    }

    int size() {
        return domains.size();
    }
}
