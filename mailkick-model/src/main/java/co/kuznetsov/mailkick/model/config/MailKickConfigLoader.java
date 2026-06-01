package co.kuznetsov.mailkick.model.config;

import co.kuznetsov.mailkick.model.MailKickConfig;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Loads a {@link MailKickConfig} from an S3 object.
 */
public final class MailKickConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(
        MailKickConfigLoader.class
    );

    private final S3Client s3Client;
    private final String bucket;
    private final String key;

    /**
     * Constructs a {@link MailKickConfigLoader} with the given S3 client, bucket, and key.
     *
     * @param s3Client the S3 client to use for loading
     * @param bucket   the S3 bucket containing the config object
     * @param key      the S3 object key of the config file
     */
    public MailKickConfigLoader(S3Client s3Client, String bucket, String key) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.key = key;
    }

    /**
     * Creates a {@link MailKickConfigLoader} by reading {@code CONFIG_S3_BUCKET} and
     * {@code CONFIG_S3_KEY} from environment variables.
     *
     * @param s3Client the S3 client to use for loading
     * @return a configured {@link MailKickConfigLoader}
     * @throws IllegalStateException if either environment variable is not set or is blank
     */
    public static MailKickConfigLoader fromEnv(S3Client s3Client) {
        String bucket = System.getenv("CONFIG_S3_BUCKET");
        String key = System.getenv("CONFIG_S3_KEY");
        if (
            bucket == null || bucket.isBlank() || key == null || key.isBlank()
        ) {
            throw new IllegalStateException(
                "CONFIG_S3_BUCKET and CONFIG_S3_KEY environment variables must be set"
            );
        }
        return new MailKickConfigLoader(s3Client, bucket, key);
    }

    /**
     * Loads and returns a {@link MailKickConfig} from the configured S3 object.
     *
     * @return the loaded {@link MailKickConfig}
     * @throws IOException if the S3 object cannot be read or the XML cannot be parsed
     */
    public MailKickConfig load() throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
        ResponseBytes<GetObjectResponse> responseBytes =
            s3Client.getObjectAsBytes(request);
        String content = responseBytes.asUtf8String();
        MailKickConfig config = MailKickConfigXml.fromXml(content);
        LOG.info("Loaded MailKickConfig from s3://{}/{}", bucket, key);
        return config;
    }
}
