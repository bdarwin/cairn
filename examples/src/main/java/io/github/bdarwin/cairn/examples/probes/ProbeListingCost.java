package io.github.bdarwin.cairn.examples.probes;

import io.github.bdarwin.cairn.internal.store.ListPage;
import io.github.bdarwin.cairn.internal.store.ListQuery;
import io.github.bdarwin.cairn.internal.store.LocalObjectStore;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Probe for milestone 3: how much of a listing page is the store itself, without HTTP or the SDK's
 * XML parsing? Lists 200 pages of 1,000 straight from {@code LocalObjectStore} over the data that
 * {@code ListingMillion} loads. Run it under JFR to see where the time goes:
 * {@code java -XX:StartFlightRecording=filename=list.jfr,settings=profile ...}, then
 * {@code jfr print --events jdk.NativeMethodSample list.jfr}.
 */
public final class ProbeListingCost {

    public static void main(String[] a) throws Exception {
        LocalObjectStore st = new LocalObjectStore(Path.of(a[0]), Clock.systemUTC(), false);
        for (String b : new String[] {"nested", "flat"}) {
            for (int run = 0; run < 2; run++) {
                long t = System.nanoTime();
                String after = null;
                boolean afterIsPrefix = false;
                int pages = 0;
                while (pages < 200) {
                    ListPage p = st.list(b, new ListQuery("", null, after, afterIsPrefix, 1000));
                    pages++;
                    if (!p.truncated()) break;
                    ListPage.Entry last = p.entries().getLast();
                    after = last.key();
                    afterIsPrefix = last.isPrefix();
                }
                System.out.printf("%s run %d: %d pages, %.1f ms a page in the store alone%n", b, run, pages, (System.nanoTime() - t) / 1e6 / pages);
            }
        }
    }
}

/*
Output (2026-10-08, Apple M1 Max, internal SSD, APFS, JDK 21.0.11):

   nested run 0: 200 pages, 109.1 ms a page in the store alone
   nested run 1: 200 pages, 99.0 ms a page in the store alone
   flat run 0: 200 pages, 132.0 ms a page in the store alone
   flat run 1: 200 pages, 106.4 ms a page in the store alone

The same run under JFR: native method samples, top frame (count of 3,710 samples):

   1305  sun.nio.fs.UnixNativeDispatcher.open0         opening each object's .meta
   1156  sun.nio.ch.UnixFileDispatcherImpl.read0       reading it
    754  sun.nio.fs.UnixNativeDispatcher.opendir0      opening each object's directory, to look for keys below it
    337  sun.nio.fs.UnixNativeDispatcher.readdir0
     91  sun.nio.fs.UnixNativeDispatcher.stat0
   Java execution samples (137) were mostly TimSort and Utf8Order.compare, building the directory index.
*/
