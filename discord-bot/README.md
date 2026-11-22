# Zotify Discord OAuth bridge

The Render service hosts Zotify's Discord OAuth callback and token-refresh endpoints. The Android app uses the resulting user-authorized Discord access token to connect to Discord's Social Layer Gateway and publish Rich Presence on the **connected user's own profile**, following Mochy's client-side model.

The Discord bot account remains online for the existing bot integration, but it is not the account that displays the listener's music activity.

## Render

- Runtime: Node
- Build: `cd discord-bot && npm install`
- Start: `cd discord-bot && npm start`
- Plan: Free

## Environment

- `DISCORD_BOT_TOKEN`: existing official Zotify bot token.
- `DISCORD_CLIENT_ID` (or `DISCORD_APPLICATION_ID`): Discord application ID.
- `DISCORD_CLIENT_SECRET`: OAuth client secret.
- `DISCORD_SESSION_SECRET`: HMAC secret for short-lived PKCE tickets and legacy bridge sessions.
- `PUBLIC_BASE_URL`: public HTTPS Render service URL.
- `DISCORD_REDIRECT_URI`: optional; defaults to `PUBLIC_BASE_URL/auth/discord/callback`.

Keep all secrets in Render environment variables; never commit them.

## OAuth endpoints

- `GET /auth/discord`: starts Discord OAuth with PKCE and requests `openid identify sdk.social_layer_presence`.
- `GET /auth/discord/callback`: handles Discord's HTTPS callback and redirects a short-lived, PKCE-bound ticket to the Android app.
- `POST /auth/discord/exchange`: exchanges that ticket plus the app-held verifier for Discord access/refresh tokens and the user's profile.
- `POST /auth/discord/refresh`: rotates Discord OAuth tokens.

The app stores access and refresh tokens in Android encrypted storage. Playback activity is sent directly from the app to Discord's Social Layer Gateway; the Render service does not receive track titles or playback events.

## Discord platform setup

The Discord application must have Social SDK / `sdk.social_layer_presence` authorization enabled. The registered HTTPS redirect must match `DISCORD_REDIRECT_URI`.

The Spotify-style mode changes the activity name and type to Spotify-like wording. It is not a Spotify integration and cannot grant the Spotify-verified badge.
