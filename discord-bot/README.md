# Zotify Discord Bot service

Server-side Discord bot bridge for Zotify Music. Uses a real Discord bot token, never a user token.

## Render
- Runtime: Node
- Build: `cd discord-bot && npm install`
- Start: `cd discord-bot && npm start`
- Plan: Free

## Environment
- `DISCORD_BOT_TOKEN`: token from Discord Developer Portal > Application > Bot.
- `ZOTIFY_WEBHOOK_SECRET`: long random secret shared with the Zotify app bridge.

Never commit either secret.

## API
- `GET /` and `GET /health`: service status.
- `POST /presence`: JSON `{"title":"Song","artist":"Artist","paused":false}`
- `POST /clear`: clears activity.

Authenticated requests include `Authorization: Bearer <ZOTIFY_WEBHOOK_SECRET>`.

The bot publishes a standard bot activity such as "Listening to Song — Artist". Bot accounts do not receive the full personal-account Rich Presence card controls.
