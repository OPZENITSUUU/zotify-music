# Zotify Discord Bot service

Server-side Discord bot bridge for Zotify Music. It uses the official bot account and Discord OAuth identify flow; it never asks for or stores a Discord user token.

## Render

- Runtime: Node
- Build: `cd discord-bot && npm install`
- Start: `cd discord-bot && npm start`
- Plan: Free

## Environment

- `DISCORD_BOT_TOKEN`: token from Discord Developer Portal > Application > Bot.
- `DISCORD_CLIENT_ID` (or `DISCORD_APPLICATION_ID`): Discord application ID.
- `DISCORD_CLIENT_SECRET`: OAuth client secret.
- `DISCORD_SESSION_SECRET`: long random HMAC signing secret.
- `PUBLIC_BASE_URL`: public HTTPS service URL.
- `DISCORD_REDIRECT_URI`: optional; defaults to `PUBLIC_BASE_URL/auth/discord/callback`.

Never commit secrets.

## API

- `GET /` and `GET /health`: service status.
- `GET /auth/discord`: starts the app's OAuth + PKCE flow.
- `POST /auth/discord/exchange`: exchanges a short-lived PKCE ticket for a signed app session.
- `POST /presence`: publishes the current track and selected activity text.
- `POST /clear`: clears the activity only when called by the current playback owner.

Authenticated requests use `Authorization: Bearer <signed Zotify session>`.

## Important Discord platform limits

This is a Mochy-inspired **bot-profile activity**, not user-account Rich Presence. Discord exposes one gateway presence for a bot account, so the bot can show only one connected listener's track at a time. A short playback lease prevents another connected listener from silently replacing it; competing requests receive HTTP 409. The current owner can clear its own activity.

The bot API supports text activity fields and timestamps. It does not provide the same personal-account Rich Presence assets, clickable buttons, or Spotify-verified badge. The Spotify-style toggle changes the bot activity label to "Spotify"; it is a visual label, not a real Spotify integration or verified Spotify status. Personal-profile Rich Presence requires a supported user-side Discord client integration and cannot be implemented by sending a bot token to Discord.
