package dev.codei18n.idea;

/** How translations are shown. Neither mode modifies documents: both use only inlays and fold regions. */
public enum DisplayMode {
    /** Original comment stays visible; the translation is a block inlay below it. */
    BELOW("Below the comment (inlay)"),
    /** The comment is folded away and the translation is painted in its place until the caret enters it. */
    REPLACE("Replace the comment (fold)");

    private final String label;

    DisplayMode(String label) { this.label = label; }

    public String label() { return label; }

    public DisplayMode toggled() { return this == BELOW ? REPLACE : BELOW; }

    /** Parses a persisted name; unknown or blank values fall back to {@link #BELOW}. */
    public static DisplayMode parse(String name) {
        if (name != null) {
            for (DisplayMode mode : values()) if (mode.name().equalsIgnoreCase(name.trim())) return mode;
        }
        return BELOW;
    }

    @Override public String toString() { return label; }
}
