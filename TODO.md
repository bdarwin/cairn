# TODO

Found and not done, worst first.

1. **Large PUTs through the SDK run at a quarter of the bare server's speed.** 318 MiB/s for a 1 GiB
   PUT against 1,326 MiB/s streamed in probe 3. Chunk-signature checks, MD5, CRC32C and the
   request's checksum all run on the request thread. Not yet profiled.
2. **Nothing sweeps up after a crash.** A write killed part-way leaves a `.data-*` or
   `.meta-*.tmp` file in the object's directory. These are never read, but they use space, and they
   keep an otherwise empty directory from being removed.
3. **`STREAMING-UNSIGNED-PAYLOAD-TRAILER` has only been tested with hand-built bodies.** SDKs send
   it over HTTPS, and no real client was captured sending it, because cairn has no TLS.
4. **A durable small PUT costs 20-30 ms on macOS** (three `F_FULLFSYNC`s for a new key). No option
   trades durability for speed.
5. **Virtual-host-style addressing** (`bucket.host`) is not served; path style only.
