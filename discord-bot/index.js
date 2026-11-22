const express = require("express");
const crypto = require("node:crypto");
const { Client, GatewayIntentBits, ActivityType } = require("discord.js");

const PORT = Number(process.env.PORT || 10000);
const BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;
const WEBHOOK_SECRET = process.env.ZOTIFY_WEBHOOK_SECRET;

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));

const client = new Client({ intents: [GatewayIntentBits.Guilds] });
let ready = false;

function authorized(req, res, next) {
  const supplied = req.get("authorization") || "";
  const expected = `Bearer ${WEBHOOK_SECRET || ""}`;
  const a = Buffer.from(supplied);
  const b = Buffer.from(expected);
  if (!WEBHOOK_SECRET || a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    return res.status(401).json({ error: "unauthorized" });
  }
  next();
}

app.get("/", (_req, res) => res.json({ service: "Zotify Discord Bot", ready }));
app.get("/health", (_req, res) => res.status(ready ? 200 : 503).json({ ok: ready }));

app.post("/presence", authorized, (req, res) => {
  if (!ready || !client.user) return res.status(503).json({ error: "bot_not_ready" });
  const { title, artist, paused = false } = req.body || {};
  if (typeof title !== "string" || !title.trim()) return res.status(400).json({ error: "title_required" });
  const safeTitle = title.trim().slice(0, 90);
  const safeArtist = typeof artist === "string" ? artist.trim().slice(0, 70) : "";
  const label = safeArtist ? `${safeTitle} — ${safeArtist}` : safeTitle;
  client.user.setPresence({
    status: paused ? "idle" : "online",
    activities: [{ name: label, type: ActivityType.Listening }]
  });
  return res.json({ ok: true, activity: label, status: paused ? "idle" : "online" });
});

app.post("/clear", authorized, (_req, res) => {
  if (!ready || !client.user) return res.status(503).json({ error: "bot_not_ready" });
  client.user.setPresence({ status: "online", activities: [] });
  return res.json({ ok: true });
});

app.listen(PORT, "0.0.0.0", () => console.log(`HTTP server listening on ${PORT}`));

if (!WEBHOOK_SECRET) console.warn("ZOTIFY_WEBHOOK_SECRET is not set; authenticated endpoints will reject requests.");
client.once("ready", () => {
  ready = true;
  console.log(`Discord bot logged in as ${client.user.tag}`);
});
client.on("error", error => console.error("Discord client error:", error));
if (BOT_TOKEN) {
  client.login(BOT_TOKEN).catch(error => {
    console.error("Discord login failed:", error);
  });
} else {
  console.warn("DISCORD_BOT_TOKEN is not set yet. Add it in the Render dashboard to connect the bot.");
}
process.on("SIGTERM", () => {
  ready = false;
  client.destroy();
  process.exit(0);
});
