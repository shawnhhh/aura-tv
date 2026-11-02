/*
 * Source: FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222
 * Original author: FongMi. GPL-3.0; see android-tv/LICENSE-GPL-3.0.md.
 * Imported 2026-11-01. See docs/m467/player-engine.md for host adaptation notes.
 */
// Host adaptation: persistence belongs to Herrin; this is the upstream track value model.
package com.fongmi.android.tv.bean;





public class Track {

    public static final int ROLE_PRIMARY = 0;
    public static final int ROLE_SECONDARY = 1;

    private int id;
    private int type;
    private int role;
    private int ordinal;
    private String key;
    private String name;
    private String format;
    private String label;
    private String language;
    private String mimeType;
    private boolean selected;

    public Track(int type, String name, String format) {
        this.type = type;
        this.name = name;
        this.format = format;
        this.role = ROLE_PRIMARY;
        this.ordinal = -1;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getRole() {
        return role;
    }

    public void setRole(int role) {
        this.role = role;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public Track key(String key) {
        setKey(key);
        return this;
    }

    public boolean isSecondary() {
        return role == ROLE_SECONDARY;
    }

    public Track toggle() {
        setSelected(!isSelected());
        return this;
    }

}
