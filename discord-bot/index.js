const express = require("express");
const crypto = require("node:crypto");
const { Client, GatewayIntentBits, ActivityType } = require("discord.js");

const PORT = Number(process.env.PORT || 10000);
const BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;
const WEBHOOK_SECRET = process.env.ZOTIFY_WEBHOOK_SECRET;
const CLIENT_ID = process.env.DISCORD_CLIENT_ID || "1573592442916307075";
const CLIENT_SECRET = process.env.DISCORD_CLIENT_SECRET;
const SESSION_SECRET = process.env.DISCORD_SESSION_SECRET;
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || "https://zotify-discord-bot.onrender.com").replace(/\/+$/, "");
const MOBILE_REDIRECT_URI = process.env.DISCORD_MOBILE_REDIRECT_URI || "zotify://discord/oauth/callback";
const CALLBACK_PATH = "/auth/discord/callback";
const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;
const TICKET_TTL_MS = 60 * 1000;
const MAX_PENDING_AUTH = 2048;

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));

const client = new Client({ intents: [GatewayIntentBits.Guilds] });
let ready = false;
const states = new Map();
const tickets = new Map();

function base64url(value) {
  return Buffer.from(value).toString("base64url");
}

function sessionFor(user) {
  const payload = base64url(JSON.stringify({
    sub: user.id,
    username: user.username,
    global_name: user.global_name || user.username,
    avatar: user.avatar || "",
    exp: Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS
  }));
  const signature = crypto.createHmac("sha256", SESSION_SECRET).update(payload).digest("base64url");
  return payload + "." + signature;
}

function verifySession(value) {
  if (!SESSION_SECRET || typeof value !== "string") return null;
  const parts = value.split(".");
  if (parts.length !== 2 || !parts[0] || !parts[1]) return null;

  const expected = crypto.createHmac("sha256", SESSION_SECRET).update(parts[0]).digest("base64url");
  const suppliedBytes = Buffer.from(parts[1]);
  const expectedBytes = Buffer.from(expected);
  if (suppliedBytes.length !== expectedBytes.length || !crypto.timingSafeEqual(suppliedBytes, expectedBytes)) return null;

  try {
    const user = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
    return user && typeof user.sub === "string" && user.exp > Math.floor(Date.now() / 1000) ? user : null;
  } catch (_) {
    return null;
  }
}

function authorized(req, res, next) {
  const supplied = req.get("authorization") || "";
  const match = supplied.match(/^Bearer\s+(.+)$/i);
  const sessionUser = match ? verifySession(match[1]) : null;
  if (sessionUser) {
    req.discordUser = sessionUser;
    return next();
  }

  // Backward compatibility for app builds that still use the original bridge secret.
  const expected = "Bearer " + (WEBHOOK_SECRET || "");
  const a = Buffer.from(supplied);
  const b = Buffer.from(expected);
  if (!WEBHOOK_SECRET || a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    return res.status(401).json({ error: "discord_session_required" });
  }
  return next();
}

function cleanupExpiredAuth() {
  const now = Date.now();
  for (const [key, expiresAt] of states) if (expiresAt <= now) states.delete(key);
  for (const [key, entry] of tickets) if (entry.expiresAt <= now) tickets.delete(key);
}

function oauthConfigured() {
  return Boolean(CLIENT_ID && CLIENT_SECRET && SESSION_SECRET && PUBLIC_BASE_URL && MOBILE_REDIRECT_URI);
}

app.get("/", (_req, res) => res.json({ service: "Zotify Discord Bot", ready, oauth: oauthConfigured() }));
app.get("/health", (_req, res) => res.status(ready ? 200 : 503).json({ ok: ready, oauth: oauthConfigured() }));

app.get("/auth/discord", (req, res) => {
  if (!oauthConfigured()) return res.status(503).json({ error: "oauth_not_configured" });
  if (String(req.query.redirect_uri || "") !== MOBILE_REDIRECT_URI) {
    return res.status(400).json({ error: "invalid_redirect_uri" });
  }

  cleanupExpiredAuth();
  if (states.size >= MAX_PENDING_AUTH) return res.status(503).json({ error: "authorization_busy" });

  const state = crypto.randomBytes(24).toString("base64url");
  states.set(state, Date.now() + TICKET_TTL_MS);
  const callbackUrl = PUBLIC_BASE_URL + CALLBACK_PATH;
  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    response_type: "code",
    redirect_uri: callbackUrl,
    scope: "identify",
    state
  });
  return res.redirect(302, "https://discord.com/oauth2/authorize?" + params.toString());
});

app.get(CALLBACK_PATH, async (req, res) => {
  cleanupExpiredAuth();
  const state = typeof req.query.state === "string" ? req.query.state : "";
  const expiresAt = states.get(state);
  if (!state || !expiresAt || expiresAt <= Date.now() || req.query.error) {
    states.delete(state);
    return res.status(400).send("Discord authorization was cancelled or expired. You can close this tab.");
  }

  states.delete(state);
  const authorizationCode = typeof req.query.code === "string" ? req.query.code : "";
  if (!authorizationCode) return res.status(400).send("Discord authorization did not return a code. You can close this tab.");

  try {
    const body = new URLSearchParams({
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      grant_type: "authorization_code",
      code: authorizationCode,
      redirect_uri: PUBLIC_BASE_URL + CALLBACK_PATH
    });

    const tokenResponse = await fetch("https://discord.com/api/oauth2/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body
    });
    if (!tokenResponse.ok) throw new Error("Discord token exchange returned HTTP " + tokenResponse.status);

    const tokenData = await tokenResponse.json();
    if (typeof tokenData.access_token !== "string" || !tokenData.access_token) {
      throw new Error("Discord token exchange returned no access token");
    }

    const profileResponse = await fetch("https://discord.com/api/users/@me", {
      headers: { Authorization: "Bearer " + tokenData.access_token }
    });
    if (!profileResponse.ok) throw new Error("Discord profile lookup returned HTTP " + profileResponse.status);

    const user = await profileResponse.json();
    if (typeof user.id !== "string" || typeof user.username !== "string") {
      throw new Error("Discord returned an invalid profile");
    }

    const ticket = crypto.randomBytes(32).toString("base64url");
    tickets.set(ticket, { user, expiresAt: Date.now() + TICKET_TTL_MS });
    return res.redirect(302, MOBILE_REDIRECT_URI + "?ticket=" + encodeURIComponent(ticket));
  } catch (error) {
    console.error("Discord OAuth callback failed:", error.message);
    return res.status(502).send("Discord authorization could not be completed. You can close this tab and try again.");
  }
});

app.post("/auth/discord/exchange", (req, res) => {
  cleanupExpiredAuth();
  const ticket = typeof req.body?.ticket === "string" ? req.body.ticket : "";
  const entry = tickets.get(ticket);
  tickets.delete(ticket);
  if (!entry || entry.expiresAt <= Date.now()) {
    return res.status(401).json({ error: "invalid_or_expired_ticket" });
  }

  return res.json({
    access_token: sessionFor(entry.user),
    user: {
      id: entry.user.id,
      username: entry.user.username,
      global_name: entry.user.global_name || entry.user.username,
      avatar: entry.user.avatar || ""
    }
  });
});

app.post("/presence", authorized, (req, res) => {
  if (!ready || !client.user) return res.status(503).json({ error: "bot_not_ready" });
  const { title, artist, paused = false, activityType = "listening", activityName = "" } = req.body || {};
  if (typeof title !== "string" || !title.trim()) return res.status(400).json({ error: "title_required" });

  const safeTitle = title.trim().slice(0, 90);
  const safeArtist = typeof artist === "string" ? artist.trim().slice(0, 70) : "";
  const customName = typeof activityName === "string" ? activityName.trim().slice(0, 32) : "";
  const trackLabel = safeArtist ? safeTitle + " — " + safeArtist : safeTitle;
  const label = customName ? customName + ": " + trackLabel : trackLabel;
  const types = {
    playing: ActivityType.Playing,
    listening: ActivityType.Listening,
    watching: ActivityType.Watching,
    competing: ActivityType.Competing
  };
  const type = types[String(activityType).toLowerCase()] ?? ActivityType.Listening;
  const statuses = ["online", "idle", "dnd", "invisible"];
  const status = paused ? "idle" : (statuses.includes(req.body?.status) ? req.body.status : "online");
  client.user.setPresence({ status, activities: [{ name: label, type }] });
  return res.json({ ok: true, activity: label, status, activityType: String(activityType).toLowerCase() });
});

app.post("/clear", authorized, (_req, res) => {
  if (!ready || !client.user) return res.status(503).json({ error: "bot_not_ready" });
  client.user.setPresence({ status: "online", activities: [] });
  return res.json({ ok: true });
});

app.listen(PORT, "0.0.0.0", () => console.log("HTTP server listening on " + PORT));

if (!WEBHOOK_SECRET) console.warn("ZOTIFY_WEBHOOK_SECRET is not set; legacy authenticated requests will be rejected.");
if (!oauthConfigured()) console.warn("Discord OAuth is not fully configured; verify the Render OAuth environment variable names.");
client.once("ready", () => {
  ready = true;
  console.log("Discord bot logged in as " + client.user.tag);
});
client.on("error", error => console.error("Discord client error:", error));
if (BOT_TOKEN) {
  client.login(BOT_TOKEN).catch(error => console.error("Discord login failed:", error.message));
} else {
  console.warn("DISCORD_BOT_TOKEN is not set; presence updates will remain unavailable.");
}
process.on("SIGTERM", () => {
  ready = false;
  client.destroy();
  process.exit(0);
});
