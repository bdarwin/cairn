package io.github.bdarwin.cairn.internal.store;

/**
 * Named points in a write where a test can make the process die at once, as a crash would. Set the
 * system property {@code cairn.test.crashAt} to a point's name; unset, this does nothing.
 */
final class CrashPoints {

    static final String DATA_WRITTEN = "data-written";
    static final String DATA_FORCED = "data-forced";
    static final String META_FORCED = "meta-forced";
    static final String RENAMED = "renamed";

    private static final String AT = System.getProperty("cairn.test.crashAt");

    private CrashPoints() {
    }

    static void reached(String point) {
        if (point.equals(AT)) Runtime.getRuntime().halt(99);
    }
}
