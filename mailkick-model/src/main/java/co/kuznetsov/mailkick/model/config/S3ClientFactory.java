package co.kuznetsov.mailkick.model.config;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Factory for creating S3 clients from environment variables.
 */
public final class S3ClientFactory {

    private S3ClientFactory() {
    }

    /**
     * Creates an S3Client using AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY,
     * and AWS_REGION environment variables. Defaults to us-east-2 if not set.
     */
    public static S3Client create() {
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        String region = System.getenv("AWS_REGION");
        if (region == null || region.isBlank()) {
            region = "us-east-2";
        }
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
