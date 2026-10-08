# Getting started

## Run it

cairn needs Java 21 or later and nothing else.

```
mvn -q package -DskipTests
CAIRN_ACCESS_KEY=example-key CAIRN_SECRET_KEY=example-secret \
  java -cp target/cairn-0.1.0-SNAPSHOT.jar io.github.bdarwin.cairn.Main --data /srv/cairn --port 9000
```

```
cairn listening on http://localhost:9000, data in /srv/cairn
```

Options, on the command line or in a properties file given with `--config`:

| Option | Default | |
|---|---|---|
| `data` | required | The data directory. Its path must be at most 200 bytes long (see [Storage](storage.md#keys-and-paths)). |
| `port` | `9000` | `0` picks a free port. |
| `bind` | `0.0.0.0` | Address to listen on. |
| `region` | `us-east-1` | Reported to clients by `GetBucketLocation`. Requests signed for any region are accepted. |
| `credentials` | | `KEY:SECRET,KEY2:SECRET2`. `CAIRN_ACCESS_KEY` and `CAIRN_SECRET_KEY` add one more. |

cairn speaks plain HTTP. For TLS, put it behind a proxy.

## Embed it

```java
try (CairnServer server = CairnServer.builder(Path.of("/srv/cairn"))
        .port(9000)
        .credentials("example-key", "example-secret")
        .start()) {
    System.out.println(server.endpoint());
}
```

## Point clients at it

cairn serves path-style requests (`http://host:9000/bucket/key`).

AWS SDK for Java v2:

```java
S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:9000"))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("example-key", "example-secret")))
        .build();
```

`aws` CLI:

```
AWS_ACCESS_KEY_ID=example-key AWS_SECRET_ACCESS_KEY=example-secret AWS_DEFAULT_REGION=us-east-1 \
  aws --endpoint-url http://localhost:9000 s3 cp reading.bin s3://items/reading.bin
```

## What works so far

| Area | State |
|---|---|
| Buckets | create, delete (empty only), head, list |
| Objects | PUT, GET (with `Range`), HEAD, DELETE |
| Listing | ListObjectsV2 and ListObjects: prefix, delimiter, max-keys, continuation token, start-after, marker, `encoding-type=url` |
| Auth | SigV4 in the header; all three `aws-chunked` body forms |
| Integrity | `Content-MD5`; `x-amz-checksum-*` CRC32, CRC32C, CRC64NVME, SHA-1, SHA-256, as headers or trailers |
| Not yet | multipart upload, presigned URLs, conditional requests, CopyObject, DeleteObjects |
