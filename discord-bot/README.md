# Zotify Discord Bot service

Server-side Discord bot bridge for Zotify Music. It authenticates to Discord with the official bot token only; the Android app never signs in to Discord and never reads a Discord user token.

## Render
- Runtime: Node
- Build: `cd discord-bot && npm install`
- Start: `cd discord-bot && npm start`
- Plan: Free

## Environment
- `DISCORD_BOT_TOKEN`: token from Discord Developer Portal → Application → Bot.
- `ZOTIFY_WEBHOOK_SECRET`: long random secret shared with the Zotify app bridge.

Never commit either secret. Enter the webhook secret into the Android Discord settings; do not enter the Discord bot token in the app.

## API
- `GET /` and `GET /health`: service status.
- `POST /presence`: authenticated JSON body with `title`, `artist`, optional `paused`, `activityType`, `activityName`, and `status`.
- `POST /clear`: clears the bot activity.

Authenticated requests include `Authorization: Bearer <ZOTIFY_WEBHOOK_SECRET>`.

The Android playback service sends play, track-change, pause/resume, and stop updates automatically. Setting the activity name to `Spotify` gives a Spotify-style text activity such as “Listening to Spotify: Song — Artist”. Discord bot accounts cannot publish Spotify's personal-account Rich Presence card, album artwork, playback progress bars, or buttons.
