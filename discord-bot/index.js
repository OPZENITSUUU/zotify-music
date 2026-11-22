const express = require("express");
const crypto = require("node:crypto");
const { Client, GatewayIntentBits, ActivityType } = require("discord.js");
const PORT = Number(process.env.PORT || 10000);
const BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;
const CLIENT_ID = process.env.DISCORD_CLIENT_ID || "1573592442916307075";
const CLIENT_SECRET = process.env.DISCORD_CLIENT_SECRET;
const SESSION_SECRET = process.env.DISCORD_SESSION_SECRET;
const PUBLIC_BASE_URL = process.env.PUBLIC_BASE_URL || "https://zotify-discord-bot.onrender.com";
const MOBILE_REDIRECT_URI = process.env.DISCORD_MOBILE_REDIRECT_URI || "zotify://discord/oauth/callback";
const CALLBACK_PATH = "/auth/discord/callback";
const SESSION_TTL = 30 * 24 * 60 * 60;
const TICKET_TTL = 60 * 1000;
const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));
const client = new Client({ intents: [GatewayIntentBits.Guilds] });
let ready = false;
const states = new Map();
const tickets = new Map();
function b64(value) { return Buffer.from(value).toString("base64url"); }
function sessionFor(user) {
  const payload = b64(JSON.stringify({ sub: user.id, username: user.username, global_name: user.global_name || user.username, avatar: user.avatar || "", exp: Math.floor(Date.now() / 1000) + SESSION_TTL }));
  const signature = crypto.createHmac("sha256", SESSION_SECRET).update(payload).digest("base64url");
  return `${payload}.${signature}`;
}
function verifySession(value) {
  if (!SESSION_SECRET || typeof value !== "string") return null;
  const [payload, signature] = value.split(".");
  if (!payload || !signature) return null;
  const expected = crypto.createHmac("sha256", SESSION_SECRET).update(payload).digest("base64url");
  const a = Buffer.from(signature); const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try { const user = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")); return user.exp > Math.floor(Date.now() / 1000) ? user : null; } catch (_) { return null; }
}
function authorized(req, res, next) {
  const token = (req.get("authorization") || "").replace(/^Bearer\s+/i, "");
  const user = verifySession(token);
  if (!user) return res.status(401).json({ error: "discord_session_required" });
  req.discordUser = user; next();
}
function cleanup() {
  const now = Date.now();
  for (const [key, value] of states) if (value < now) states.delete(key);
  for (const [key, value] of tickets) if (value.expiresAt < now) tickets.delete(key);
}
app.get("/", (_req, res) => res.json({ service: "Zotify Discord Bot", ready, oauth: true }));
app.get("/health", (_req, res) => res.status(ready ? 200 : 503).json({ ok: ready, oauth: true }));
app.get("/auth/discord", (req, res) => {
  if (!CLIENT_SECRET || !SESSION_SECRET) return res.status(503).json({ error: "oauth_not_configured" });
  if (String(req.query.redirect_uri || "") !== MOBILE_REDIRECT_URI) return res.status(400).json({ error: "invalid_redirect_uri" });
  cleanup();
  const state = crypto.randomBytes(24).toString("base64url"); states.set(state, Date.now() + TICKET_TTL);
  const params = new URLSearchParams({ client_id: CLIENT_ID, response_type: "code", redirect_uri: `${PUBLIC_BASE_URL}${CALLBACK_PATH}`, scope: "identify", state });
  res.redirect(`https://discord.com/oauth2/authorize?${params}`);
});
app.get(CALLBACK_PATH, async (req, res) => {
  cleanup();
  const state = String(req.query.state || "");
  if (!states.has(state) || req.query.error) return res.status(400).send("Discord authorization was cancelled or expired. You can close this tab.");
  states.delete(state);
  try {
    const body = new URLSearchParams({ client_id: CLIENT_ID, client_secret: CLIENT_SECRET, grant_type: "authorization_code", code: String(req.query.code || ""), redirect_uri: `${PUBLIC_BASE_URL}${CALLBACK_PATH}` });
    const tokenResponse = await fetch("https://discord.com/api/oauth2/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body });
    if (!tokenResponse.ok) throw new Error(`token exchange ${tokenResponse.status}`);
    const token = await tokenResponse.json();
    const profileResponse = await fetch("https://discord.com/api/users/@me", { headers: { Authorization: `Bearer ${token.access_token}` } });
    if (!profileResponse.ok) throw new Error(`profile lookup ${profileResponse.status}`);
    const user = await profileResponse.json();
    const ticket = crypto.randomBytes(32).toString("base64url");
    tickets.set(ticket, { user, expiresAt: Date.now() + TICKET_TTL });
    res.redirect(`${MOBILE_REDIRECT_URI}?ticket=${encodeURIComponent(ticket)}`);
  } catch (error) { console.error("Discord OAuth callback failed:", error); res.status(502).send("Discord authorization could not be completed. You can close this tab and try again."); }
});
app.post("/auth/discord/exchange", (req, res) => {
  cleanup();
  const ticket = String(req.body?.ticket || ""); const entry = tickets.get(ticket); tickets.delete(ticket);
  if (!entry) return res.status(401).json({ error: "invalid_or_expired_ticket" });
  res.json({ access_token: sessionFor(entry.user), user: { id: entry.user.id, username: entry.user.username, global_name: entry.user.global_name || entry.user.username, avatar: entry.user.avatar || "" } });
});
app.post("/presence", authorized, (req, res) => {
  if (!ready || !client.user) return res.status(503).json({ error: "bot_not_ready" });
  const { title, artist, paused = false, activityType = "listening", activityName = "" } = req.body || {};
  if (typeof title !== "string" || !title.trim()) return res.status(400).json({ error: "title_required" });
  const safeTitle = title.trim().slice(0, 90); const safeArtist = typeof artist === "string" ? artist.trim().slice(0, 70) : "";
  const customName = typeof activityName === "string" ? activityName.trim().slice(0, 32) : "";
  const track = safeArtist ? `${safeTitle} — ${safeArtist}` : safeTitle; const label = customName ? `${customName}: ${track}` : track;
  const types = { playing: ActivityType.Playing, listening: ActivityType.Listening, watching: ActivityType.Watching, competing: ActivityType.Competing };
  client.user.setPresence({ status: paused ? "idle" : "online", activities: [{ name: label, type: types[String(activityType).toLowerCase()] ?? ActivityType.Listening }] });
  res.json({ ok: true, activity: label, discord_user_id: req.discordUser.sub });
});
app.post("/clear", authorized, (_req, res) => { if (!ready || !client.user) return res.status(503).json({ error: "bot_not_ready" }); client.user.setPresence({ status: "online", activities: [] }); res.json({ ok: true }); });
app.listen(PORT, "0.0.0.0", () => console.log(`HTTP server listening on ${PORT}`));
if (!BOT_TOKEN) console.warn("DISCORD_BOT_TOKEN is not set yet.");
if (!CLIENT_SECRET || !SESSION_SECRET) console.warn("DISCORD_CLIENT_SECRET and DISCORD_SESSION_SECRET are required for OAuth.");
client.once("ready", () => { ready = true; console.log(`Discord bot logged in as ${client.user.tag}`); });
client.on("error", error => console.error("Discord client error:", error));
if (BOT_TOKEN) client.login(BOT_TOKEN).catch(error => console.error("Discord login failed:", error));
process.on("SIGTERM", () => { ready = false; client.destroy(); process.exit(0); });
