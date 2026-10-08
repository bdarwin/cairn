#!/usr/bin/env bash
# Probe 2: capture what each S3 client really sends, by pointing it at ProbeCaptureServer on port 9000.
# Needs java 21, mvn, aws (CLI v2), mc and duckdb on the PATH. Writes examples/captures/<client>.log.
set -euo pipefail
cd "$(dirname "$0")/.."
out=captures
work=$(mktemp -d)
trap 'pkill -f ProbeCaptureServer || true; rm -rf "$work"' EXIT
mkdir -p "$out"
mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile="$work/cp.txt" > /dev/null
cp="target/classes:$(cat "$work/cp.txt")"

start() {
  pkill -f ProbeCaptureServer || true
  sleep 1
  rm -f "$out/$1.log"
  java -cp "$cp" io.github.bdarwin.cairn.examples.probes.ProbeCaptureServer 9000 "$out/$1.log" > /dev/null 2>&1 &
  sleep 1.5
}

head -c 10240 /dev/urandom > "$work/small.bin"
head -c $((20 * 1024 * 1024)) /dev/urandom > "$work/big20m.bin"

echo "== AWS SDK for Java v2"
start aws-sdk-java-v2
java -cp "$cp" io.github.bdarwin.cairn.examples.probes.ProbeSdkRequests

echo "== aws CLI: $(aws --version)"
start aws-cli
export AWS_ACCESS_KEY_ID=probekey AWS_SECRET_ACCESS_KEY=probesecret AWS_DEFAULT_REGION=us-east-1 AWS_EC2_METADATA_DISABLED=true
aws="aws --endpoint-url http://localhost:9000"
$aws s3 cp --no-progress "$work/small.bin" s3://probe/cli/small.bin
$aws s3api put-object --bucket probe --key cli/api-small.bin --body "$work/small.bin" > /dev/null
$aws s3 cp --no-progress "$work/big20m.bin" s3://probe/cli/big20m.bin
$aws s3 cp --no-progress s3://probe/cli/small.bin "$work/small.back"
cmp "$work/small.bin" "$work/small.back" && echo "aws CLI read back byte for byte"

echo "== mc: $(mc --version | head -1)"
start mc
mcx() { mc --config-dir "$work/mc" --quiet --no-color "$@"; }
mcx alias set probe http://localhost:9000 probekey probesecret --api s3v4 > /dev/null
mcx cp "$work/small.bin" probe/probe/mc/small.bin > /dev/null
mcx cp "$work/big20m.bin" probe/probe/mc/big20m.bin > /dev/null
mcx cat probe/probe/mc/small.bin | cmp - "$work/small.bin" && echo "mc read back byte for byte"

echo "== duckdb $(duckdb --version)"
start duckdb
duckdb -c "
INSTALL httpfs; LOAD httpfs;
CREATE SECRET (TYPE s3, KEY_ID 'probekey', SECRET 'probesecret', ENDPOINT 'localhost:9000', URL_STYLE 'path', USE_SSL false, REGION 'us-east-1');
COPY (SELECT range AS n, range * 2 AS m FROM range(1000000)) TO 's3://probe/duck/x.parquet';
SELECT count(*), sum(n) FROM read_parquet('s3://probe/duck/*.parquet');
COPY (SELECT range AS n, hash(range) AS h, md5(range::VARCHAR) AS s FROM range(3000000)) TO 's3://probe/duck/big.parquet';
SELECT count(*) FROM read_parquet('s3://probe/duck/big.parquet');
"
for f in "$out"/*.log; do echo "$f: $(grep -c '^===' "$f") requests"; done

# Output (2026-10-08). The request logs are in examples/captures/.
# == AWS SDK for Java v2
# AWS SDK for Java v2 2.55.12
# done
# == aws CLI: aws-cli/2.37.10 Python/3.14.8 Darwin/25.5.0 source/arm64
# upload: ../../../../../../var/folders/p2/3wph7cw515d_ch9lsq65trmh0000gn/T/tmp.g42pO5bizv/small.bin to s3://probe/cli/small.bin
# upload: ../../../../../../var/folders/p2/3wph7cw515d_ch9lsq65trmh0000gn/T/tmp.g42pO5bizv/big20m.bin to s3://probe/cli/big20m.bin
# download: s3://probe/cli/small.bin to ../../../../../../var/folders/p2/3wph7cw515d_ch9lsq65trmh0000gn/T/tmp.g42pO5bizv/small.back
# aws CLI read back byte for byte
# == mc: mc version RELEASE.2025-08-13T08-35-41Z (commit-id=7394ce0dd2a80935aded936b09fa12cbb3cb8096)
# mc read back byte for byte
# == duckdb v1.5.6 (Variegata) 069cc9f9b5
# ┌─────────┐
# │ Success │
# │ boolean │
# ├─────────┤
# │ true    │
# └─────────┘
# ┌──────────────┬──────────────┐
# │ count_star() │    sum(n)    │
# │    int64     │    int128    │
# ├──────────────┼──────────────┤
# │      1000000 │ 499999500000 │
# └──────────────┴──────────────┘
# ┌──────────────┐
# │ count_star() │
# │    int64     │
# ├──────────────┤
# │      3000000 │
# └──────────────┘
# captures/aws-cli.log: 9 requests
# captures/aws-sdk-java-v2.log: 8 requests
# captures/duckdb.log: 18 requests
# captures/mc.log: 16 requests
