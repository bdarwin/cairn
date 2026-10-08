package io.github.bdarwin.cairn.examples.probes;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.util.VersionInfo;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Random;

/**
 * Probe 2, the AWS SDK for Java v2 side: drives the requests whose wire form cairn must accept,
 * against {@link ProbeCaptureServer} on port 9000. The capture server's log is the result.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=io.github.bdarwin.cairn.examples.probes.ProbeSdkRequests}
 */
public final class ProbeSdkRequests {

    public static void main(String[] args) {
        System.out.println("AWS SDK for Java v2 " + VersionInfo.SDK_VERSION);
        byte[] small = random(10 * 1024);
        byte[] mib = random(1024 * 1024);
        try (S3Client s3 = client(RequestChecksumCalculation.WHEN_SUPPORTED);
             S3Client legacy = client(RequestChecksumCalculation.WHEN_REQUIRED)) {
            s3.putObject(b -> b.bucket("probe").key("sdk/bytes-10k"), RequestBody.fromBytes(small));
            s3.putObject(b -> b.bucket("probe").key("sdk/bytes-1m"), RequestBody.fromBytes(mib));
            s3.putObject(b -> b.bucket("probe").key("sdk/stream-1m"),
                    RequestBody.fromInputStream(new ByteArrayInputStream(mib), mib.length));
            legacy.putObject(b -> b.bucket("probe").key("sdk/when-required-10k"), RequestBody.fromBytes(small));
            s3.getObjectAsBytes(b -> b.bucket("probe").key("sdk/bytes-10k"));

            String id = s3.createMultipartUpload(b -> b.bucket("probe").key("sdk/multipart")).uploadId();
            String etag = s3.uploadPart(b -> b.bucket("probe").key("sdk/multipart").uploadId(id).partNumber(1),
                    RequestBody.fromBytes(mib)).eTag();
            s3.completeMultipartUpload(b -> b.bucket("probe").key("sdk/multipart").uploadId(id)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(CompletedPart.builder().partNumber(1).eTag(etag).build()).build()));
        }
        System.out.println("done");
    }

    static S3Client client(RequestChecksumCalculation checksums) {
        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:9000"))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .requestChecksumCalculation(checksums)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("probekey", "probesecret")))
                .build();
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }
}

/*
Output (2026-10-08, JDK 21.0.11), and what ProbeCaptureServer logged for it:

   AWS SDK for Java v2 2.55.12
   done
   
   captures/aws-sdk-java-v2.log, request #1 (all eight are in that file):
   === #1 PUT /probe/sdk/bytes-10k
     Amz-sdk-invocation-id: 60fad86d-f7bf-023d-c62c-2acaba712c9e
     Amz-sdk-request: attempt=1; max=4
     Authorization: AWS4-HMAC-SHA256 Credential=probekey/20261008/us-east-1/s3/aws4_request, SignedHeaders=amz-sdk-invocation-id;amz-sdk-request;content-encoding;content-length;content-type;host;x-amz-content-sha256;x-amz-date;x-amz-decoded-content-length;x-amz-sdk-checksum-algorithm;x-amz-trailer, Signature=8bd1fac1f06d4af5e1907f8bae53c84a4dac1a3efa7f30727c89c3137d171968
     Connection: keep-alive
     Content-encoding: aws-chunked
     Content-length: 10536
     Content-type: application/octet-stream
     Host: localhost:9000
     User-agent: aws-sdk-java/2.55.12 md/io#sync md/http#Apache5 ua/2.1 api/S3#2.55.x os/Mac_OS_X#26.5.2 lang/java#21.0.11 md/OpenJDK_64-Bit_Server_VM#21.0.11 md/vendor#Homebrew md/en_SG md/rb#b m/D,AJ,N,N,Z,b,U,e
     X-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER
     X-amz-date: 20261008T133146Z
     X-amz-decoded-content-length: 10240
     X-amz-sdk-checksum-algorithm: CRC32
     X-amz-trailer: x-amz-checksum-crc32
     -- body: 10536 bytes on the wire
       chunk header: 2800;chunk-signature=4ddfec755925b3b42985721b335b0a798d33815eacc61b880a2e3b846cdba9f0   (10240 bytes)
       chunk header: 0;chunk-signature=feec2aded949d2e4346f56d51ee60e2d741ec2d33353d2246917ae974d48f010   (0 bytes)
       trailer: x-amz-checksum-crc32:9+yNBQ==
       trailer: x-amz-trailer-signature:dd6c98f9dfdfdaf99b9615988a6ae653c8a3daa6e5d2dff5fb6272a4c24eef5d
       <empty line: end of trailers>
     -- decoded payload: 10240 bytes
*/
