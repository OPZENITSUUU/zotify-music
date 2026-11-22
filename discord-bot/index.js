const express = require("express");
const crypto = require("node:crypto");
const { Client, GatewayIntentBits, ActivityType } = require("discord.js");

const PORT = Number(process.env.PORT || 10000);
const BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;
const CLIENT_ID = process.env.DISCORD_CLIENT_ID;
const CLIENT_SECRET = process.env.DISCORD_CLIENT_SECRET;
const SESSION_SECRET = process.env.DISCORD_SESSION_SECRET;
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || "https://zotify-discord-bot.onrender.com").replace(/\/$/, "");
const DISCORD_REDIRECT_URI = process.env.DISCORD_REDIRECT_URI || `${PUBLIC_BASE_URL}/auth/discord/callback`;

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));

const client = new Client({ intents: [GatewayIntentBits.Guilds] });
let ready = false;

// OAuth state/tickets are short-lived and single-use. The resulting session is
// stateless HMAC-signed so a Render restart does not invalidate connected apps.
const oauthStates = new Map();
const oauthTickets = new Map();

function randomToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString("base64url");
}
function sha256Base64Url(value) {
  return crypto.createHash("sha256").update(value, "ascii").digest("base64url");
}
function signSession(user) {
  const payload = Buffer.from(JSON.stringify({
    sub: user.id,
    username: user.username,
    global_name: user.global_name || user.username,
    exp: Math.floor(Date.now() / 1000) + 60 * 60 * 24 * 30,
  })).toString("base64url");
  const signature = crypto.createHmac("sha256", SESSION_SECRET).update(payload).digest("base64url");
  return `${payload}.${signature}`;
}
function verifySession(token) {
  if (!SESSION_SECRET || typeof token !== "string") return null;
  const [payload, signature, extra] = token.split(".");
  if (!payload || !signature || extra) return null;
  const expected = crypto.createHmac("sha256", SESSION_SECRET).update(payload).digest();
  let supplied;
  try { supplied = Buffer.from(signature, "base64url"); } catch { return null; }
  if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) return null;
  try {
    const data = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    if (!data.sub || !data.exp || data.exp <= Math.floor(Date.now() / 1000)) return null;
    return data;
  } catch { return null; }
}
function requireSession(req, res, next) {
  const token = (req.get("authorization") || "").replace(/^Bearer\s+/i, "");
  const session = verifySession(token);
  if (!session) return res.status(401).json({ error: "invalid_or_expired_session" });
  req.discordSession = session;
  next();
}
function clean(value, max) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}
function prune(map) {
  const now = Date.now();
  for (const [key, value] of map) if (value.expiresAt <= now) map.delete(key);
}

app.get("/", (_req, res) => res.json({ service: "Zotify Discord Bot", ready }));
app.get("/health", (_req, res) => res.status(ready ? 200 : 503).json({ ok: ready }));

// Start the official Discord OAuth authorization-code flow. The custom app
// redirect is never sent to Discord; Discord redirects only to our HTTPS URL.
app.get("/auth/discord", (req, res) => {
  if (!CLIENT_ID || !CLIENT_SECRET || !SESSION_SECRET) {
    return res.status(503).send("Discord OAuth is not configured on the service.");
  }
  const appRedirect = req.query.redirect_uri;
  const challenge = req.query.code_challenge;
  if (appRedirect !== "zotify://discord/oauth/callback" ||
      typeof challenge !== "string" || !/^[A-Za-z0-9_-]{43,128}$/.test(challenge) ||
      req.query.code_challenge_method !== "S256") {
    return res.status(400).send("Invalid Zotify authorization request.");
  }
  prune(oauthStates);
  const state = randomToken(24);
  oauthStates.set(state, {
    appRedirect,
    challenge,
    expiresAt: Date.now() + 10 * 60 * 1000,
  });
  const authorize = new URL("https://discord.com/oauth2/authorize");
  authorize.searchParams.set("client_id", CLIENT_ID);
  authorize.searchParams.set("response_type", "code");
  authorize.searchParams.set("redirect_uri", DISCORD_REDIRECT_URI);
  authorize.searchParams.set("scope", "identify");
  authorize.searchParams.set("state", state);
  res.redirect(302, authorize.toString());
});

app.get("/auth/discord/callback", async (req, res) => {
  prune(oauthStates);
  const state = oauthStates.get(req.query.state);
  oauthStates.delete(req.query.state);
  if (!state || state.expiresAt <= Date.now()) {
    return res.status(400).send("Discord authorization expired. Return to Zotify and connect again.");
  }
  if (req.query.error || typeof req.query.code !== "string") {
    const target = new URL(state.appRedirect);
    target.searchParams.set("error", "access_denied");
    return res.redirect(302, target.toString());
  }
  try {
    const tokenResponse = await fetch("https://discord.com/api/oauth2/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: CLIENT_ID,
        client_secret: CLIENT_SECRET,
        grant_type: "authorization_code",
        code: req.query.code,
        redirect_uri: DISCORD_REDIRECT_URI,
      }),
    });
    const tokenData = await tokenResponse.json();
    if (!tokenResponse.ok || !tokenData.access_token) {
      console.error("Discord OAuth token exchange failed:", tokenData.error || tokenResponse.status);
      return res.status(502).send("Discord could not complete authorization. Please retry from Zotify.");
    }
    const userResponse = await fetch("https://discord.com/api/users/@me", {
      headers: { Authorization: `Bearer ${tokenData.access_token}` },
    });
    const user = await userResponse.json();
    if (!userResponse.ok || !user.id) {
      return res.status(502).send("Discord account lookup failed. Please retry from Zotify.");
    }
    prune(oauthTickets);
    const ticket = randomToken(32);
    oauthTickets.set(ticket, {
      user: { id: user.id, username: user.username, global_name: user.global_name || user.username },
      challenge: state.challenge,
      expiresAt: Date.now() + 2 * 60 * 1000,
    });
    const target = new URL(state.appRedirect);
    target.searchParams.set("ticket", ticket);
    return res.redirect(302, target.toString());
  } catch (error) {
    console.error("Discord OAuth callback failed:", error.message);
    return res.status(502).send("Discord authorization failed. Please retry from Zotify.");
  }
});

app.post("/auth/discord/exchange", (req, res) => {
  prune(oauthTickets);
  const ticket = req.body && req.body.ticket;
  const verifier = req.body && req.body.code_verifier;
  const entry = oauthTickets.get(ticket);
  oauthTickets.delete(ticket);
  if (!entry || entry.expiresAt <= Date.now() ||
      typeof verifier !== "string" || verifier.length < 43 || verifier.length > 128 ||
      sha256Base64Url(verifier) !== entry.challenge) {
    return res.status(400).json({ error: "invalid_or_expired_ticket" });
  }
  return res.json({
    access_token: signSession(entry.user),
    token_type: "Bearer",
    expires_in: 60 * 60 * 24 * 30,
    user: entry.user,
  });
});

app.post("/presence", requireSession, (req, res) => {
  if (!ready || !client.user) return res.status(503).json({ error: "bot_not_ready" });
  const body = req.body || {};
  const title = clean(body.title, 90);
  const artist = clean(body.artist, 70);
  if (!title) return res.status(400).json({ error: "title_required" });

  const paused = body.paused === true;
  const spotifySpoof = body.spotifySpoof === true;
  const activityName = spotifySpoof ? "Spotify" : (clean(body.activityName, 64) || "Zotify Music");
  const activityTypeMap = {
    listening: ActivityType.Listening,
    playing: ActivityType.Playing,
    watching: ActivityType.Watching,
    competing: ActivityType.Competing,
  };
  const activityType = spotifySpoof
    ? ActivityType.Listening
    : (activityTypeMap[clean(body.activityType, 20).toLowerCase()] ?? ActivityType.Listening);
  const details = clean(body.details, 128) || title;
  const state = clean(body.state, 128) || artist;
  const positionMs = Number.isFinite(Number(body.positionMs)) ? Math.max(0, Number(body.positionMs)) : 0;
  const durationMs = Number.isFinite(Number(body.durationMs)) ? Math.max(0, Number(body.durationMs)) : 0;
  const activity = { name: activityName, type: activityType, details, state };
  if (!paused && durationMs > 0) {
    const start = Date.now() - positionMs;
    activity.timestamps = { start, end: start + durationMs };
  }
  client.user.setPresence({
    status: ["online", "idle", "dnd"].includes(body.status) ? body.status : (paused ? "idle" : "online"),
    activities: [activity],
  });
  return res.json({
    ok: true,
    account: req.discordSession.username,
    activity: { name: activityName, details, state, type: activityType },
    note: "Discord bot presence is shared by the bot account; it does not change a user's personal profile.",
  });
});

app.post("/clear", requireSession, (_req, res) => {
  if (!ready || !client.user) return res.status(503).json({ error: "bot_not_ready" });
  client.user.setPresence({ status: "online", activities: [] });
  return res.json({ ok: true });
});

app.listen(PORT, "0.0.0.0", () => console.log(`HTTP server listening on ${PORT}`));
if (!CLIENT_ID || !CLIENT_SECRET || !SESSION_SECRET) {
  console.warn("Discord OAuth requires DISCORD_CLIENT_ID, DISCORD_CLIENT_SECRET and DISCORD_SESSION_SECRET.");
}
client.once("ready", () => {
  ready = true;
  console.log(`Discord bot logged in as ${client.user.tag}`);
});
client.on("error", error => console.error("Discord client error:", error));
if (BOT_TOKEN) {
  client.login(BOT_TOKEN).catch(error => console.error("Discord login failed:", error));
} else {
  console.warn("DISCORD_BOT_TOKEN is not set yet. Add it in the Render dashboard to connect the bot.");
}
process.on("SIGTERM", () => {
  ready = false;
  client.destroy();
  process.exit(0);
});
