"use strict";

const crypto = require("node:crypto");
const express = require("express");
const { Client, GatewayIntentBits, ActivityType } = require("discord.js");

const PORT = Number(process.env.PORT || 10000);
const BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;
const CLIENT_ID = process.env.DISCORD_CLIENT_ID || "1573592442916307075";
const CLIENT_SECRET = process.env.DISCORD_CLIENT_SECRET;
const SESSION_SECRET = process.env.DISCORD_SESSION_SECRET;
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || "https://zotify-discord-bot.onrender.com").replace(/\/+$/, "");
const MOBILE_REDIRECT_URI = process.env.DISCORD_MOBILE_REDIRECT_URI || "zotify://discord/oauth/callback";
const CALLBACK_PATH = "/auth/discord/callback";
const CALLBACK_URL = \`\${PUBLIC_BASE_URL}\${CALLBACK_PATH}\`;
const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;
const STATE_TTL_MS = 10 * 60 * 1000;
const TICKET_TTL_MS = 60 * 1000;
const DISCORD_TIMEOUT_MS = 15_000;

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));

const client = new Client({ intents: [GatewayIntentBits.Guilds] });
const states = new Map();
const tickets = new Map();
let ready = false;

function base64Url(value) {
  return Buffer.from(value).toString("base64url");
}

function createSession(user) {
  if (!SESSION_SECRET) throw new Error("Discord session signing is not configured");

  const payload = base64Url(JSON.stringify({
    sub: user.id,
    username: user.username,
    global_name: user.global_name || user.username,
    avatar: user.avatar || "",
    exp: Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS,
  }));
  const signature = crypto
    .createHmac("sha256", SESSION_SECRET)
    .update(payload)
    .digest("base64url");

  return \`\${payload}.\${signature}\`;
}

function verifySession(token) {
  if (!SESSION_SECRET || typeof token !== "string") return null;

  const parts = token.split(".");
  if (parts.length !== 2 || !parts[0] || !parts[1]) return null;

  const [payload, suppliedSignature] = parts;
  const expectedSignature = crypto
    .createHmac("sha256", SESSION_SECRET)
    .update(payload)
    .digest("base64url");

  const supplied = Buffer.from(suppliedSignature);
  const expected = Buffer.from(expectedSignature);
  if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) {
    return null;
  }

  try {
    const user = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    if (typeof user.sub !== "string" || !Number.isFinite(user.exp)) return null;
    return user.exp > Math.floor(Date.now() / 1000) ? user : null;
  } catch {
    return null;
  }
}

function requireDiscordSession(req, res, next) {
  const authorization = req.get("authorization") || "";
  const match = /^Bearer\s+(.+)$/i.exec(authorization);
  const user = match ? verifySession(match[1]) : null;

  if (!user) {
    return res.status(401).json({ error: "discord_session_required" });
  }

  req.discordUser = user;
  return next();
}

function noStore(_req, res, next) {
  res.set("Cache-Control", "no-store, max-age=0");
  res.set("Pragma", "no-cache");
  return next();
}

function cleanupExpiredOAuthData() {
  const now = Date.now();

  for (const [state, expiresAt] of states) {
    if (expiresAt <= now) states.delete(state);
  }

  for (const [ticket, entry] of tickets) {
    if (entry.expiresAt <= now) tickets.delete(ticket);
  }
}

function oauthConfigured() {
  return Boolean(CLIENT_ID && CLIENT_SECRET && SESSION_SECRET);
}

async function fetchDiscord(url, options) {
  return fetch(url, {
    ...options,
    signal: AbortSignal.timeout(DISCORD_TIMEOUT_MS),
  });
}

app.get("/", (_req, res) => {
  res.json({
    service: "Zotify Discord Bot",
    ready,
    oauth_configured: oauthConfigured(),
  });
});

app.get("/health", (_req, res) => {
  res.status(ready ? 200 : 503).json({
    ok: ready,
    oauth_configured: oauthConfigured(),
  });
});

/**
 * Starts Discord OAuth2. The only accepted application redirect is the
 * Zotify custom scheme, preventing this endpoint from becoming an open redirect.
 */
app.get("/auth/discord", noStore, (req, res) => {
  if (!oauthConfigured()) {
    return res.status(503).json({ error: "oauth_not_configured" });
  }

  if (String(req.query.redirect_uri || "") !== MOBILE_REDIRECT_URI) {
    return res.status(400).json({ error: "invalid_redirect_uri" });
  }

  cleanupExpiredOAuthData();

  const state = crypto.randomBytes(24).toString("base64url");
  states.set(state, Date.now() + STATE_TTL_MS);

  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    response_type: "code",
    redirect_uri: CALLBACK_URL,
    scope: "identify",
    state,
  });

  return res.redirect(302, \`https://discord.com/oauth2/authorize?\${params.toString()}\`);
});

/**
 * Handles Discord's registered HTTPS callback, exchanges the code server-side,
 * then redirects to the app with a short-lived, one-use ticket.
 */
app.get(CALLBACK_PATH, noStore, async (req, res) => {
  cleanupExpiredOAuthData();

  const state = String(req.query.state || "");
  const stateExpiry = states.get(state);

  if (!state || !stateExpiry || stateExpiry <= Date.now()) {
    if (state) states.delete(state);
    return res.status(400).send("Discord authorization expired. Close this page and try again.");
  }

  // Consume state before making any external request to prevent callback replay.
  states.delete(state);

  if (req.query.error) {
    return res.status(400).send("Discord authorization was cancelled. You can close this tab.");
  }

  const code = String(req.query.code || "");
  if (!code) {
    return res.status(400).send("Discord did not return an authorization code.");
  }

  try {
    const tokenBody = new URLSearchParams({
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      grant_type: "authorization_code",
      code,
      redirect_uri: CALLBACK_URL,
    });

    const tokenResponse = await fetchDiscord("https://discord.com/api/oauth2/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: tokenBody,
    });

    if (!tokenResponse.ok) {
      throw new Error(\`Discord token exchange failed with HTTP \${tokenResponse.status}\`);
    }

    const token = await tokenResponse.json();
    if (typeof token.access_token !== "string" || !token.access_token) {
      throw new Error("Discord token response did not contain an access token");
    }

    const profileResponse = await fetchDiscord("https://discord.com/api/users/@me", {
      headers: { Authorization: \`Bearer \${token.access_token}\` },
    });

    if (!profileResponse.ok) {
      throw new Error(\`Discord profile lookup failed with HTTP \${profileResponse.status}\`);
    }

    const user = await profileResponse.json();
    if (typeof user.id !== "string" || typeof user.username !== "string") {
      throw new Error("Discord profile response was incomplete");
    }

    const ticket = crypto.randomBytes(32).toString("base64url");
    tickets.set(ticket, {
      user: {
        id: user.id,
        username: user.username,
        global_name: user.global_name || user.username,
        avatar: user.avatar || "",
      },
      expiresAt: Date.now() + TICKET_TTL_MS,
    });

    return res.redirect(302, \`\${MOBILE_REDIRECT_URI}?ticket=\${encodeURIComponent(ticket)}\`);
  } catch (error) {
    // Do not log authorization codes, access tokens, or client secrets.
    console.error("Discord OAuth callback failed:", error.message);
    return res.status(502).send("Discord authorization could not be completed. Close this tab and try again.");
  }
});

/**
 * Exchanges the one-use mobile ticket for a signed Zotify session. The Discord
 * OAuth access token itself is never returned to or stored by the Android app.
 */
app.post("/auth/discord/exchange", noStore, (req, res) => {
  cleanupExpiredOAuthData();

  if (!oauthConfigured()) {
    return res.status(503).json({ error: "oauth_not_configured" });
  }

  const ticket = typeof req.body?.ticket === "string" ? req.body.ticket : "";
  const entry = tickets.get(ticket);
  tickets.delete(ticket);

  if (!ticket || !entry || entry.expiresAt <= Date.now()) {
    return res.status(401).json({ error: "invalid_or_expired_ticket" });
  }

  try {
    return res.json({
      access_token: createSession(entry.user),
      user: entry.user,
    });
  } catch {
    return res.status(503).json({ error: "session_signing_unavailable" });
  }
});

app.post("/presence", requireDiscordSession, (req, res) => {
  if (!ready || !client.user) {
    return res.status(503).json({ error: "bot_not_ready" });
  }

  const {
    title,
    artist,
    paused = false,
    activityType = "listening",
    activityName = "",
    status = "online",
  } = req.body || {};

  if (typeof title !== "string" || !title.trim()) {
    return res.status(400).json({ error: "title_required" });
  }

  const safeTitle = title.trim().slice(0, 90);
  const safeArtist = typeof artist === "string" ? artist.trim().slice(0, 70) : "";
  const safeActivityName = typeof activityName === "string" ? activityName.trim().slice(0, 32) : "";
  const track = safeArtist ? \`\${safeTitle} — \${safeArtist}\` : safeTitle;
  const label = safeActivityName ? \`\${safeActivityName}: \${track}\` : track;

  const activityTypes = {
    playing: ActivityType.Playing,
    listening: ActivityType.Listening,
    watching: ActivityType.Watching,
    competing: ActivityType.Competing,
  };
  const selectedType = activityTypes[String(activityType).toLowerCase()] ?? ActivityType.Listening;
  const allowedStatuses = new Set(["online", "idle", "dnd", "invisible"]);
  const selectedStatus = allowedStatuses.has(String(status).toLowerCase())
    ? String(status).toLowerCase()
    : (paused ? "idle" : "online");

  client.user.setPresence({
    status: selectedStatus,
    activities: [{ name: label, type: selectedType }],
  });

  return res.json({
    ok: true,
    activity: label,
    discord_user_id: req.discordUser.sub,
  });
});

app.post("/clear", requireDiscordSession, (_req, res) => {
  if (!ready || !client.user) {
    return res.status(503).json({ error: "bot_not_ready" });
  }

  client.user.setPresence({ status: "online", activities: [] });
  return res.json({ ok: true });
});

const server = app.listen(PORT, "0.0.0.0", () => {
  console.log(\`Zotify Discord Bot HTTP server listening on port \${PORT}\`);
});

if (!BOT_TOKEN) {
  console.warn("DISCORD_BOT_TOKEN is not configured.");
}
if (!CLIENT_SECRET || !SESSION_SECRET) {
  console.warn("Discord OAuth is disabled until DISCORD_CLIENT_SECRET and DISCORD_SESSION_SECRET are configured.");
}

client.once("ready", () => {
  ready = true;
  console.log(\`Discord bot logged in as \${client.user.tag}\`);
});

client.on("error", (error) => {
  console.error("Discord client error:", error);
});

if (BOT_TOKEN) {
  client.login(BOT_TOKEN).catch((error) => {
    console.error("Discord login failed:", error);
  });
}

function shutdown() {
  ready = false;
  server.close(() => {
    client.destroy();
    process.exit(0);
  });
}

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
