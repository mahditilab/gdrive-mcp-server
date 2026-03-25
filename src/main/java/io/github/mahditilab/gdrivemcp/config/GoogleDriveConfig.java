package io.github.mahditilab.gdrivemcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import io.github.mahditilab.gdrivemcp.tools.GoogleDriveTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
public class GoogleDriveConfig {

    @Value("${google.drive.tokens-path}")
    private String tokensPath;

    @Value("${google.drive.application-name}")
    private String applicationName;

    @Bean
    public Drive googleDrive() throws GeneralSecurityException, IOException {
        var credentials = loadCredentialsFromTokensFile(Path.of(tokensPath));

        var scopedCredentials = credentials.createScoped(List.of(DriveScopes.DRIVE));

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(scopedCredentials))
                .setApplicationName(applicationName)
                .build();
    }

    @Bean
    public ToolCallbackProvider googleDriveToolCallbackProvider(GoogleDriveTools googleDriveTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(googleDriveTools)
                .build();
    }

    /**
     * Reads credentials from a tokens.json file produced by Google OAuth flow.
     * Expected fields: client_id, client_secret, refresh_token.
     */
    private UserCredentials loadCredentialsFromTokensFile(Path path) throws IOException {
        var mapper = new ObjectMapper();
        JsonNode tokens = mapper.readTree(Files.readString(path));

        String clientId     = tokens.get("client_id").asText();
        String clientSecret = tokens.get("client_secret").asText();
        String refreshToken = tokens.get("refresh_token").asText();

        return UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build();
    }
}
