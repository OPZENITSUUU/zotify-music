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
const STATE_TTL_SECONDS = 10 * 60;
const TICKET_TTL_SECONDS = 60;

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));
app.use((_req, res, next) => {
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  next();
});

const client = new Client({ intents: [GatewayIntentBits.Guilds] });
let ready = false;

function signPayload(payload) {
  if (!SESSION_SECRET) throw new Error("Discord session signing is not configured");
  const encoded = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const signature = crypto.createHmac("sha256", SESSION_SECRET).update(encoded).digest("base64url");
  return encoded + "." + signature;
}

function verifyPayload(token) {
  if (!SESSION_SECRET || typeof token !== "string") return null;
  const parts = token.split(".");
  if (parts.length !== 2 || !parts[0] || !parts[1]) return null;
  const expected = crypto.createHmac("sha256", SESSION_SECRET).update(parts[0]).digest("base64url");
  const actual = Buffer.from(parts[1]);
  const wanted = Buffer.from(expected);
  if (actual.length !== wanted.length || !crypto.timingSafeEqual(actual, wanted)) return null;
  try {
    const payload = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
    return payload && Number.isInteger(payload.exp) && payload.exp > Math.floor(Date.now() / 1000)
      ? payload : null;
  } catch (_) {
    return null;
  }
}

function sessionFor(user) {
  return signPayload({
    sub: user.id,
    username: user.username,
    global_name: user.global_name || user.username,
    avatar: user.avatar || "",
    exp: Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS
  });
}

function authorized(req, res, next) {
  const supplied = req.get("authorization") || "";
  const match = supplied.match(/^Bearer\s+(.+)$/i);
  const sessionUser = match ? verifyPayload(match[1]) : null;
  if (sessionUser && typeof sessionUser.sub === "string") {
    req.discordUser = sessionUser;
    return next();
  }

  // Backward compatibility for older app builds using the bridge secret.
  const expected = "Bearer " + (WEBHOOK_SECRET || "");
  const a = Buffer.from(supplied);
  const b = Buffer.from(expected);
  if (!WEBHOOK_SECRET || a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    return res.status(401).json({ error: "discord_session_required" });
  }
  return next();
}

function oauthConfigured() {
  return Boolean(CLIENT_ID && CLIENT_SECRET && SESSION_SECRET && PUBLIC_BASE_URL && MOBILE_REDIRECT_URI);
}

function failToApp(res, reason) {
  return res.redirect(302, MOBILE_REDIRECT_URI + "?error=" + encodeURIComponent(reason));
}

app.get("/", (_req, res) => res.json({ service: "Zotify Discord Bot", ready, oauth: oauthConfigured() }));
app.get("/health", (_req, res) => res.status(ready ? 200 : 503).json({ ok: ready, oauth: oauthConfigured() }));

app.get("/auth/discord", (req, res) => {
  if (!oauthConfigured()) return res.status(503).json({ error: "oauth_not_configured" });
  if (String(req.query.redirect_uri || "") !== MOBILE_REDIRECT_URI) {
    return res.status(400).json({ error: "invalid_redirect_uri" });
  }

  const challenge = typeof req.query.code_challenge === "string" ? req.query.code_challenge : "";
  const method = typeof req.query.code_challenge_method === "string" ? req.query.code_challenge_method : "";
  if (method !== "S256" || !/^[A-Za-z0-9_-]{43}$/.test(challenge)) {
    return res.redirect(302, "zotify://discord/oauth/start");
  }

  // Signed, short-lived state is stateless: Render restarts or instance changes
  // cannot erase it between the authorize request and Discord's callback.
  const state = signPayload({
    challenge,
    exp: Math.floor(Date.now() / 1000) + STATE_TTL_SECONDS,
    nonce: crypto.randomBytes(16).toString("base64url")
  });
  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    response_type: "code",
    redirect_uri: PUBLIC_BASE_URL + CALLBACK_PATH,
    scope: "identify",
    state
  });
  return res.redirect(302, "https://discord.com/oauth2/authorize?" + params.toString());
});

app.get(CALLBACK_PATH, async (req, res) => {
  const state = typeof req.query.state === "string" ? req.query.state : "";
  const authState = verifyPayload(state);
  if (!authState || typeof authState.challenge !== "string" ||
      !/^[A-Za-z0-9_-]{43}$/.test(authState.challenge)) {
    console.warn("Discord OAuth callback rejected: invalid or expired signed state");
    if (req.query.error) return failToApp(res, "access_denied");
    return res.status(400).send("This Discord sign-in link expired. Return to Zotify and tap Connect again.");
  }
  if (req.query.error) return failToApp(res, "access_denied");

  const code = typeof req.query.code === "string" ? req.query.code : "";
  if (!code) return res.status(400).send("Discord did not return an authorization code. Return to Zotify and try again.");

  try {
    const body = new URLSearchParams({
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      grant_type: "authorization_code",
      code,
      redirect_uri: PUBLIC_BASE_URL + CALLBACK_PATH
    });
    const tokenResponse = await fetch("https://discord.com/api/oauth2/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body
    });
    if (!tokenResponse.ok) {
      const detail = await tokenResponse.text();
      console.error("Discord OAuth token exchange failed:", tokenResponse.status, detail.slice(0, 300));
      return failToApp(res, "token_exchange_failed");
    }

    const tokenData = await tokenResponse.json();
    if (typeof tokenData.access_token !== "string" || !tokenData.access_token) {
      console.error("Discord OAuth token response contained no access token");
      return failToApp(res, "token_exchange_failed");
    }

    const profileResponse = await fetch("https://discord.com/api/users/@me", {
      headers: { Authorization: "Bearer " + tokenData.access_token }
    });
    if (!profileResponse.ok) {
      console.error("Discord profile lookup failed:", profileResponse.status);
      return failToApp(res, "profile_lookup_failed");
    }
    const user = await profileResponse.json();
    if (typeof user.id !== "string" || typeof user.username !== "string") {
      return failToApp(res, "invalid_profile");
    }

    // The signed ticket carries its PKCE challenge and survives a Render restart.
    // The one-time property is enforced by the app's locally-held verifier and
    // the 60-second expiry; the ticket itself contains no Discord OAuth token.
    const ticket = signPayload({
      kind: "discord_ticket",
      user: {
        id: user.id,
        username: user.username,
        global_name: user.global_name || user.username,
        avatar: user.avatar || ""
      },
      challenge: authState.challenge,
      exp: Math.floor(Date.now() / 1000) + TICKET_TTL_SECONDS,
      nonce: crypto.randomBytes(16).toString("base64url")
    });
    return res.redirect(302, MOBILE_REDIRECT_URI + "?ticket=" + encodeURIComponent(ticket));
  } catch (error) {
    console.error("Discord OAuth callback failed:", error.message);
    return failToApp(res, "oauth_callback_failed");
  }
});

app.post("/auth/discord/exchange", (req, res) => {
  const ticket = typeof req.body?.ticket === "string" ? req.body.ticket : "";
  const entry = verifyPayload(ticket);
  if (!entry || entry.kind !== "discord_ticket" || !entry.user ||
      typeof entry.user.id !== "string" || typeof entry.challenge !== "string") {
    return res.status(401).json({ error: "invalid_or_expired_ticket" });
  }

  const verifier = typeof req.body?.code_verifier === "string" ? req.body.code_verifier : "";
  if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) {
    return res.status(401).json({ error: "invalid_pkce_verifier" });
  }
  const challenge = crypto.createHash("sha256").update(verifier).digest("base64url");
  const actual = Buffer.from(challenge);
  const expected = Buffer.from(entry.challenge);
  if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) {
    return res.status(401).json({ error: "invalid_pkce_verifier" });
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
  const {
    title, artist, paused = false, activityType = "listening", activityName = "",
    useDetails = false, audioQuality = ""
  } = req.body || {};
  if (typeof title !== "string" || !title.trim()) return res.status(400).json({ error: "title_required" });

  const safeTitle = title.trim().slice(0, 70);
  const safeArtist = typeof artist === "string" ? artist.trim().slice(0, 60) : "";
  const customName = typeof activityName === "string" ? activityName.trim().slice(0, 32) : "";
  const safeQuality = typeof audioQuality === "string" ? audioQuality.trim().slice(0, 32) : "";
  const trackLabel = safeArtist
    ? (useDetails ? safeTitle + " — " + safeArtist : safeArtist + " — " + safeTitle)
    : safeTitle;
  const label = customName ? customName + ": " + trackLabel : trackLabel;
  const safeActivity = (safeQuality ? label + " · " + safeQuality : label).slice(0, 128);
  const types = {
    playing: ActivityType.Playing,
    listening: ActivityType.Listening,
    watching: ActivityType.Watching,
    competing: ActivityType.Competing
  };
  const type = types[String(activityType).toLowerCase()] ?? ActivityType.Listening;
  const statuses = ["online", "idle", "dnd", "invisible"];
  const status = paused ? "idle" : (statuses.includes(req.body?.status) ? req.body.status : "online");
  client.user.setPresence({ status, activities: [{ name: safeActivity, type }] });
  return res.json({ ok: true, activity: safeActivity, status, activityType: String(activityType).toLowerCase() });
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
