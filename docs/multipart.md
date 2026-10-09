# Multipart upload

All six calls are served: CreateMultipartUpload, UploadPart, CompleteMultipartUpload,
AbortMultipartUpload, ListParts and ListMultipartUploads. UploadPartCopy is not.

## An upload in progress

```
data/.cairn/uploads/items/<upload id>/
  upload.json            key, content type, metadata, declared checksum
  part-00001.json        part 1: size, MD5, CRC32C, checksum, data file name
  data-00001-<uuid>      part 1's bytes
```

A part is written the same way as an object. The bytes go to a new file, which is forced. Then the
part's JSON is written to a temporary file, forced, and renamed into place. Uploading a part number
again replaces it, and the old data file is deleted after the rename.

## Completing: a part list, not a copy

The brief asked for both ways of completing to be measured and one picked.
`ProbeMultipartComplete` completed 5 GiB uploaded in 8 MiB parts, the `aws` CLI's size:

| | Complete | Extra disk while completing | Reading the object back |
|---|---|---|---|
| Copy the parts into one file | 16.8 s | 5 GiB | 4,320 MiB/s |
| Keep the parts, list them in `.meta` | 0.22 s | none (hard links) | 4,304 MiB/s |

Reading across 640 files costs nothing measurable. Copying costs 16.8 s while the client waits, and
doubles the space for the duration. So a completed object keeps its parts. CompleteMultipartUpload:

1. checks the list: ascending part numbers, each part present with the ETag the client gives, and
   every part but the last at least 5 MiB;
2. hard-links each part's data file into the object's directory as `.data-<uuid>-<n>`;
3. commits the object's `.meta`, naming those files, with the same rename that commits a PUT;
4. removes the upload's directory.

A crash before step 3 leaves the upload untouched. The client can complete it again, and the
stray links are removed if the server is still running, or left (see `TODO.md`) if not. A crash
after step 3 leaves the whole object. `CrashTest` kills the server at both points.

The object's `.meta` lists its parts, each with size, MD5 and CRC32C, and the data files in the same
order. A ranged GET finds the parts that hold the range.

## ETag and checksums

The ETag is S3's: the MD5 of the parts' binary MD5s, then `-` and the part count. For example,
`"65ecf8b8ec06d2ead0ce19579f7f637b-640"`.

When CreateMultipartUpload declares `x-amz-checksum-algorithm`, every part is checked against its
checksum, or the server computes one when the part does not send it. On completion the object gets
a checksum the way S3 gives one:

- **Full object** (the default for CRC64NVME, and asked for with `x-amz-checksum-type: FULL_OBJECT`
  for CRC32 and CRC32C): the CRC of every byte. It is combined from the parts' CRCs without reading
  the bytes again, with the method of zlib's `crc32_combine`. Clients check it against what they
  download. `MultipartSdkTest` compares it with a CRC computed over the whole object.
- **Composite** (the default for the others): the checksum of the parts' checksums, then `-N`.
  Clients do not check it against the bytes.

## 5 GiB through unmodified clients

`examples/.../MultipartBigFile.java`, server durable, one 5,120 MiB file of random bytes:

| Client | Parts | Upload | Download | Read back |
|---|---|---|---|---|
| `aws` CLI 2.37.10 | 640 x 8 MiB | 16.4 s (312 MiB/s) | 10.6 s (481 MiB/s) | identical (`cmp`) |
| `mc` RELEASE.2025-08-13 | 320 x 16 MiB | 9.8 s (522 MiB/s) | 5.1 s (1,009 MiB/s) | identical (`cmp`) |

No upload was left in progress afterwards, and nothing was left under `.cairn/uploads`.
