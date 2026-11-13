/*
 * GitSync compatibility adapter for the public JGit AttributesNode API.
 * Authored for GitSync: supports standard C-quoted .gitattributes patterns.
 * The build omits the upstream parser class, retaining the remaining JGit jar.
 */
package org.eclipse.jgit.attributes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.jgit.util.QuotedString;

/** Keeps the JGit 5.x public API while parsing Git's quoted path grammar. */
public class AttributesNode {
    private final List<AttributesRule> rules;

    public AttributesNode() { this(new ArrayList<>()); }
    public AttributesNode(List<AttributesRule> rules) { this.rules = rules; }
    public List<AttributesRule> getRules() { return Collections.unmodifiableList(rules); }

    /** The caller owns the input stream, as required by the JGit API. */
    public void parse(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') continue;
            int end;
            String pattern;
            if (line.charAt(0) == '"') {
                boolean escaped = false;
                end = 1;
                for (; end < line.length(); end++) {
                    char current = line.charAt(end);
                    if (escaped) { escaped = false; continue; }
                    if (current == '\\') { escaped = true; continue; }
                    if (current == '"') break;
                }
                if (end == line.length()) throw new IOException("Unterminated quoted .gitattributes pattern");
                end++;
                pattern = QuotedString.GIT_PATH.dequote(line.substring(0, end));
                if (pattern.startsWith("!")) continue;
            } else {
                end = 0;
                while (end < line.length() && line.charAt(end) != ' ' && line.charAt(end) != '\t') end++;
                pattern = line.substring(0, end);
            }
            if (end == line.length() || (line.charAt(end) != ' ' && line.charAt(end) != '\t')) continue;
            String attributes = line.substring(end).trim();
            if (!pattern.isEmpty() && !attributes.isEmpty()) rules.add(new AttributesRule(pattern, attributes));
        }
    }
}
