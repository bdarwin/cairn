# Listing

`ListObjectsV2` (and the older `ListObjects`) is a walk of the bucket's directories. Nothing else
records which keys exist.

## Order

S3 returns keys sorted by their UTF-8 bytes. A depth-first walk of directories is not that order.
`-` (0x2D) sorts before `/` (0x2F), so key `a-c` comes before `a/b`, although the walk meets
directory `a` first. The walk therefore reads each directory whole and sorts it. A directory `d`
is compared as `d/` and the object stored in it as `d`, so the keys come out as `a`, `a-c`, `a/b`,
`a/b/c`, `a0`.

The comparison is by code point, which is UTF-8 order. Java's `String.compareTo` compares UTF-16
units and disagrees for characters above U+FFFF: it puts 😀 before U+FFFD, and S3 puts it after.

`ListingTest` checks the order exhaustively. It writes keys built from characters chosen to trip a
naive order (`-`, `/`, `.`, `%`, upper and lower case, U+E000, U+FFFD, 😀, and keys long enough to
overflow). Then it runs more than 600 combinations of prefix, delimiter and start position, paged at
random sizes. It compares each with the same query worked out on a plain sorted list. The run is
repeated with every directory served from the index below.

## Common prefixes

With a delimiter, keys roll up to the first delimiter after the prefix. When a whole subdirectory
rolls up to one prefix, as `readings/0421/` does under prefix `readings/` with delimiter `/`, it is
not walked. Finding any one object in it is enough to report the prefix, and that search stops at
the first one, in whatever order the filesystem returns entries.

## Continuation

`NextContinuationToken` is the last key or common prefix returned, base64-encoded. The next page
starts after it: a binary search in each directory on the way down, not a scan. Pages are
independent requests. The server holds no cursor.

## A million keys

`examples/.../ListingMillion.java` loads 1,000,000 keys in each of two shapes and lists them through
the HTTP API with the AWS SDK:

- **nested:** `readings/<group>/item-<n>`, 1,000 groups of 1,000;
- **flat:** `flat/item-<n>`, all in one directory.

The first version read and sorted each directory on every request:

| Query (1M keys) | First version | Now |
|---|---|---|
| nested, prefix `readings/`, delimiter `/` (1,000 prefixes) | 4,905 ms | 154 ms |
| nested, one group, delimiter `/` (1,000 keys) | 87 ms | 80 ms |
| flat, one page of 1,000 | 5,296-7,559 ms | 70 ms (7.7 s the first time) |
| flat, every key in pages of 1,000 | not run: ~1,000 pages at over 5 s each | 139 s |
| nested, every key in pages of 1,000 | 133 s | 135 s |

Two numbers decided the changes. The common-prefix query read every group directory whole just to
learn it was not empty; it now stops at the first object. The flat page re-read a directory of a
million names for every page. `ProbeDirectoryCost` measured that read at 1.5 s with a warm cache,
plus 0.3 s to sort, against 20 ms for the 1,000 metadata files the page needs.

### The index

That measurement is why cairn has an index, and what kind. Directories of 10,000 entries or more
keep their sorted entries in memory, tagged with the directory's modification time. Creating or
removing an entry changes that time, and the next listing reads the directory again. The index is
a cache of what is on disk: dropping it loses nothing, and a restart rebuilds it on first use, which
for the flat directory took 7.7 s. It holds at most 4,000,000 entries across all directories.

### Where a listing's time goes now

With the index warm, a full listing runs at about 7,400 keys a second, in both shapes, through HTTP
and the SDK. The store alone takes about 100 ms of each 135 ms page (`ProbeListingCost`). Under
JFR, almost all of that is in the kernel: opening and reading each object's `.meta` (about two
thirds of the samples), and opening each object's directory to look for keys below it (about a
third). S3 returns each key's size, ETag and date, and those are in each object's own file. MinIO
reads its `xl.meta` per object for the same reason.

A second index holding every object's metadata would remove most of that cost. It would also be a
second copy of each object's metadata, which every write and every crash would have to keep in step.
At 7,400 keys a second the measurement does not ask for that, so there is no such index. Skipping
the directory read for objects with nothing below them is the cheaper next step, and it is in
`TODO.md`.

All of these figures had a warm file cache. Cold-cache listing was not measured: macOS needs root
to drop the cache.
