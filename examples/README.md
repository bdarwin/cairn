# Examples and probes

Runnable programs. Each ends with its real output pasted in a comment, and every figure in the docs
comes from one of them. This is a separate Maven project, not part of the cairn build.

```
cd examples
mvn -q compile
java -cp target/classes io.github.bdarwin.cairn.examples.probes.ProbeExpectContinue
```

## Probes (milestone 1, written up in `docs/probes.md`)

| Probe | What it answers |
|---|---|
| `ProbeExpectContinue` | When the JDK `HttpServer` answers `Expect: 100-continue` |
| `ProbeCaptureServer`, `ProbeSdkRequests`, `scripts/capture-clients.sh` | What the AWS SDK v2, `aws` CLI, `mc` and DuckDB send; logs in `captures/` |
| `ProbeLargeBody` | 5 GiB PUT and GET through the JDK server: heap and throughput |
| `ProbeFsync`, `native/fsync_probe.c` | The cost of making a file durable on macOS |
| `ProbeKeyNames` | Which S3 keys APFS cannot hold as they are |

`ProbeLargeBody` and `ProbeFsync` take a directory to write in. `ProbeLargeBody` needs room for
three copies of the file.
