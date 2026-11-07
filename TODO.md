# TODO

Found and not done, worst first.

1. **A client that stops reading holds a thread for ever.** Nothing times out a stalled response
   write or limits connections. On JDK 21-23 each stalled download holds a platform thread (the fix
   for pinning, `docs/duckdb.md`); on JDK 24+ a virtual thread, which is cheaper but still never let
   go. Enough of them exhaust memory. Wants a write timeout or an idle limit per connection. Whether
   the JDK server's `sun.net.httpserver.maxRspTime` property gives one is untested.
2. **The pinning bug only shows on JDK 21-23,** and `mvn test` runs on whatever JDK Maven finds (25
   on this machine). `SlowReadersTest` failed before its fix only under
   `JAVA_HOME=.../openjdk@21`. CI should run the tests on 21.
3. **Large PUTs through the SDK run at a quarter of the bare server's speed.** 318 MiB/s for a 1 GiB
   PUT against 1,326 MiB/s streamed in probe 3. Chunk-signature checks, MD5, CRC32C and the
   request's checksum all run on the request thread. Not yet profiled.
4. **Nothing sweeps up after a crash.** A write killed part-way leaves a `.data-*` or
   `.meta-*.tmp` file in the object's directory; a CompleteMultipartUpload killed before its commit
   leaves hard links there; abandoned multipart uploads stay under `.cairn/uploads` until aborted
   (S3 offers a lifecycle rule for that; cairn has none). These are never read, but they use space, and they
   keep an otherwise empty directory from being removed.
5. **`STREAMING-UNSIGNED-PAYLOAD-TRAILER` has only been tested with hand-built bodies.** SDKs send
   it over HTTPS, and no real client was captured sending it, because cairn has no TLS.
6. **A durable small PUT costs 20-30 ms on macOS** (three `F_FULLFSYNC`s for a new key). No option
   trades durability for speed.
7. **A full listing reads every object's directory to look for keys below it** (about a third of a
   listing's time under JFR, `ProbeListingCost`). Most objects have nothing below them; a cheaper
   way to know would take listing from about 7,400 towards 10,000 keys a second.
8. **Cold-cache listing is unmeasured** (macOS needs root to drop the file cache). The first listing
   of a 1,000,000-entry directory after a restart took 7.7 s to build its index.
9. **GET with `?partNumber=` and UploadPartCopy are refused** (501). Some downloaders fetch
   multipart objects part by part; none of the four clients tested here did.
10. **Virtual-host-style addressing** (`bucket.host`) is not served; path style only.
11. **DeleteObjects does not insist on `Content-MD5` or a checksum header,** as S3 does. It checks
   one when sent (DuckDB sends `Content-MD5`). A `VersionId` in the request is ignored, since
   cairn has no versioning.

What quackjvm would need to use cairn well (S3 configuration that is not SQL text, URL prefixes in
its hardening allow-list) is quackjvm's to do, and is written up in `docs/quackjvm.md`.
