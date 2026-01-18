# Developer Documentation

## OAuth2 Account Linking & Multi-Channel Support

### Overview
The application supports linking a Google/YouTube account to an existing AFK Live user account without overwriting the user's identity ("Account Takeover"). This allows a user to manage multiple YouTube channels from different Google accounts under a single profile.

### Implementation Details

1.  **Custom Authorization Request Resolver** (`CustomOAuth2AuthorizationRequestResolver`):
    *   Intercepts requests to `/oauth2/authorization/google`.
    *   If the query parameter `action=connect_youtube` is present:
        *   Adds specific YouTube scopes (`youtube.upload`, etc.).
        *   Stores the *current* authenticated user's email in the **Session** attribute `LINKING_USER`.
        *   This marks the flow as a "Link" operation rather than a "Login" operation.

2.  **Success Handler** (`OAuth2LoginSuccessHandler`):
    *   On callback, checks for the `LINKING_USER` session attribute.
    *   If found:
        *   Extracts the OAuth2 `token.getName()` (Principal Name / Google ID).
        *   Calls `ChannelService.syncChannelFromGoogle(credentialId, targetUsername)`.
        *   **Crucial:** Restores the `SecurityContext` to the `targetUsername` (original user) and persists it using `HttpSessionSecurityContextRepository`. This prevents the user from being logged out or swapped to the Google identity.
    *   If not found:
        *   Proceeds with standard Login/Registration logic.

3.  **Multi-Channel Credential Storage**:
    *   Credentials (`OAuth2AuthorizedClient`) are stored by Spring Security using the `Principal Name` (Google ID) as the key.
    *   The `SocialChannel` entity stores this key in the `credentialId` field.
    *   Services (`YouTubeService`, `VideoSchedulerService`) retrieve credentials using this `credentialId` instead of the user's email, enabling support for channels belonging to different Google identities.

## FFmpeg Streaming Logic

### "Preparing Stream" Fix
YouTube Live requires an audio track. If a video source has no audio and the user mutes the original audio (`muteVideoAudio=true`) without providing background music, the stream would previously hang.
*   **Fix:** `FFmpegCommandBuilder` detects this condition and injects a silent audio source:
    *   `-f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100`
    *   Maps it to the output audio stream.

### Preview Visibility
*   The preview player uses `position: absolute` and `z-index: 10` within a `relative` container to ensure it sits above placeholders.
*   CSS `background: black` was removed from the player to prevent obscuring content if rendering fails.
*   `app.js` enforces `visibility: visible` and logs video metadata dimensions for debugging.
