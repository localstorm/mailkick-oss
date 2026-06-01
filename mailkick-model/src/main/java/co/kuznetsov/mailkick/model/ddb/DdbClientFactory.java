package co.kuznetsov.mailkick.model.ddb;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Factory for creating DynamoDB clients from environment variables.
 *
 * <p>Reads {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}, and
 * {@code AWS_REGION} from the process environment. Falls back to {@code us-east-2}
 * when {@code AWS_REGION} is absent or blank.</p>
 */
public final class DdbClientFactory {

    private DdbClientFactory() {
    }

    /**
     * Creates a {@link DynamoDbClient} using {@code AWS_ACCESS_KEY_ID},
     * {@code AWS_SECRET_ACCESS_KEY}, and {@code AWS_REGION} environment variables.
     * Defaults to {@code us-east-2} if {@code AWS_REGION} is not set.
     *
     * @return a configured {@link DynamoDbClient}
     */
    public static DynamoDbClient create() {
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        String region = System.getenv("AWS_REGION");
        if (region == null || region.isBlank()) {
            region = "us-east-2";
        }
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
