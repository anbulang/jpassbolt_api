package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTOs for GPG authentication requests and responses.
 */
public class AuthDto {

    @Data
    public static class LoginRequest {
        private DataWrapper data;
    }

    @Data
    public static class DataWrapper {
        @JsonProperty("gpg_auth")
        private GpgAuth gpgAuth;
    }

    @Data
    public static class GpgAuth {
        /**
         * The user's GPG key fingerprint (40 chars) or key_id (up to 16 chars)
         */
        private String keyid;

        /**
         * Stage 0: Encrypted token from client for server to decrypt (server
         * verification)
         */
        @JsonProperty("server_verify_token")
        private String serverVerifyToken;

        /**
         * Stage 2: Decrypted token from user (authentication completion)
         */
        @JsonProperty("user_token_result")
        private String userTokenResult;
    }

    @Data
    public static class LoginResponse {
        private String header;
        private Body body;

        public LoginResponse(String serverVerifyToken) {
            this.header = null;
            this.body = new Body(serverVerifyToken);
        }

        @Data
        public static class Body {
            @JsonProperty("server_verify_token")
            private String serverVerifyToken;

            public Body(String token) {
                this.serverVerifyToken = token;
            }
        }
    }
}
