# Changelog

## Unreleased

### Added

- Maven project (`io.github.bdarwin:cairn`, Java 21, no runtime dependencies).
- Probes 1-5 with their measured results in `docs/probes.md`: `Expect: 100-continue` in the JDK
  `HttpServer`, the request bodies four S3 clients send on PUT, 5 GiB bodies through the JDK server,
  the cost of `fsync` on macOS, and the keys APFS cannot hold as they are. The probe programs and
  their output are in `examples/`.
