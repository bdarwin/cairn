package io.github.bdarwin.cairn.internal.store;

/**
 * One page of a listing.
 *
 * @param prefix        only keys starting with this
 * @param delimiter     roll keys up to the first delimiter after the prefix (null: no rolling up)
 * @param after         only keys after this, in UTF-8 order (null: from the start)
 * @param afterIsPrefix {@code after} is a common prefix already returned: skip every key under it too
 * @param maxKeys       at most this many keys and common prefixes together
 */
public record ListQuery(String prefix, String delimiter, String after, boolean afterIsPrefix, int maxKeys) {
}
