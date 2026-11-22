# Zotify Discord Bot service

Server-side Discord bot bridge for Zotify Music. The Android app opens Discord OAuth2 using application/client ID `1573592442916307075`; it never receives a Discord password, bot token, or Discord user token.

## Render

- Runtime: Node
- Build: `cd discord-bot && npm install`
- Start: `cd discord-bot && npm start`
- Plan: Free

## Environment

- `DISCORD_BOT_TOKEN`: token from Discord Developer Portal → Application → Bot.
- `DISCORD_CLIENT_ID`: `1573592442916307075` (may use the default in code).
- `DISCORD_CLIENT_SECRET`: OAuth2 client secret from the Discord Developer Portal; keep only on Render.
- `DISCORD_SESSION_SECRET`: long random HMAC secret used to sign app sessions; keep only on Render.
- `PUBLIC_BASE_URL`: public service URL, for example `https://zotify-discord-bot.onrender.com`.
- `DISCORD_MOBILE_REDIRECT_URI`: `zotify://discord/oauth/callback`.

Register this exact Discord OAuth2 redirect URL in the Developer Portal:
`https://zotify-discord-bot.onrender.com/auth/discord/callback`

Never commit any of these secrets. `ZOTIFY_WEBHOOK_SECRET` is no longer used.

## API

- `GET /` and `GET /health`: service status.
- `GET /auth/discord?redirect_uri=zotify%3A%2F%2Fdiscord%2Foauth%2Fcallback`: starts Discord OAuth2.
- `POST /auth/discord/exchange`: exchanges the one-time mobile ticket for an app session.
- `POST /presence`: authenticated JSON `{"title":"Song","artist":"Artist","paused":false}`.
- `POST /clear`: clears the bot activity.

Presence and clear requests use `Authorization: Bearer <app-session-token>`, not a shared bridge secret. OAuth identifies and authorizes the user, while Discord correctly keeps the bot activity on the official bot account. Discord does not allow bots to impersonate user accounts or publish a user's personal Rich Presence.
