# Zotify Discord Bot service

Server-side Discord bot bridge for Zotify Music. Android opens Discord OAuth2 using application/client ID `1573592442916307075`; the app never receives a Discord password, bot token, client secret, or Discord OAuth access token.

## Render deployment

- Runtime: Node 20 or newer
- Root directory: `discord-bot`
- Build command: `npm install`
- Start command: `npm start`
- Public service URL: `https://zotify-discord-bot.onrender.com`

Deploy the `main` branch after merging the backend OAuth route fix.

## Required Render environment variables

- `DISCORD_BOT_TOKEN`: token from Discord Developer Portal → Application → Bot.
- `DISCORD_CLIENT_ID`: `1573592442916307075` (the code has this default).
- `DISCORD_CLIENT_SECRET`: OAuth2 client secret from Discord Developer Portal → OAuth2. Keep it only in Render.
- `DISCORD_SESSION_SECRET`: long random secret used to sign Zotify app sessions. Keep it only in Render.
- `PUBLIC_BASE_URL`: `https://zotify-discord-bot.onrender.com`.
- `DISCORD_MOBILE_REDIRECT_URI`: `zotify://discord/oauth/callback`.

Generate `DISCORD_SESSION_SECRET` as a random value with at least 32 bytes of entropy. Never paste it into chat or commit it to GitHub.

## Discord Developer Portal

Under OAuth2 → Redirects, register this exact callback URL:

`https://zotify-discord-bot.onrender.com/auth/discord/callback`

The mobile redirect URI is fixed to `zotify://discord/oauth/callback`. The backend rejects other redirect URIs.

## OAuth flow

1. Android opens `GET /auth/discord?redirect_uri=zotify%3A%2F%2Fdiscord%2Foauth%2Fcallback`.
2. The backend validates the mobile redirect, creates a short-lived state, and redirects to Discord.
3. Discord returns to the registered HTTPS callback. The backend exchanges the authorization code and reads the `identify` profile scope server-side.
4. The backend redirects to Zotify with a one-time ticket. Android exchanges it through `POST /auth/discord/exchange`.
5. The backend returns a signed, expiring Zotify session token. Discord OAuth access tokens and the client secret never leave the server.

OAuth states expire after 10 minutes, exchange tickets expire after 60 seconds and are single-use, and app sessions expire after 30 days.

## API

- `GET /`: service readiness and OAuth configuration status.
- `GET /health`: bot readiness and OAuth configuration status.
- `GET /auth/discord?redirect_uri=...`: starts Discord OAuth2.
- `GET /auth/discord/callback`: registered Discord OAuth callback.
- `POST /auth/discord/exchange`: exchanges a one-time ticket for a signed app session.
- `POST /presence`: authenticated presence update.
- `POST /clear`: clears the official bot activity.

Presence and clear requests use `Authorization: Bearer <zotify-session-token>`. The backend verifies the token signature and expiry. It does not use a shared webhook secret or a personal Discord user token.

## Troubleshooting

- `Cannot GET /auth/discord`: Render is still running an old deployment. Confirm the service deploys this repository's `main` branch and that its root directory is `discord-bot`.
- `oauth_not_configured`: add `DISCORD_CLIENT_SECRET` and `DISCORD_SESSION_SECRET` to the Render environment, then redeploy.
- Discord reports a redirect mismatch: the Discord Developer Portal callback must exactly match the HTTPS URL above.
- `bot_not_ready`: check `DISCORD_BOT_TOKEN` and the bot login logs in Render.

Discord bots publish activity on the official bot account; Discord does not allow a bot to impersonate a user's personal account or publish that user's personal Rich Presence card.
