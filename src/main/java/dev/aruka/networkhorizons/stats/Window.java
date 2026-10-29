package dev.aruka.networkhorizons.stats;

/** Time span a stat is read over. */
public enum Window {

    LAST_60S("60s"),
    LAST_10M("10m"),
    TOTAL("total");

    public final String id;

    Window(String id) {
        this.id = id;
    }

    public static Window byId(String id) {
        for (Window w : values()) {
            if (w.id.equalsIgnoreCase(id)) return w;
        }
        return null;
    }
}
