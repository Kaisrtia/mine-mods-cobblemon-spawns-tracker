# Cobblemon Notification Tracker

A server-side Fabric Minecraft mod for **Cobblemon 1.7.3+ (Minecraft 1.21.1, Java 21)** that notifies players via Discord Webhook (and optional in-game chat) when tracked, Shiny, or Legendary Pokémon spawn near them.

---

## 🌟 Features

* **Personalized Player Tracking**: Each player configures their own watchlist, search radius, and preferences independently.
* **Discord Webhook Alerts**:
  * Rich embeds with Showdown sprite thumbnails, coordinates (`X, Y, Z`), and dimension names.
  * Direct Discord user mention (`<@ID>`) for players who link their Discord User ID.
  * Zero server tick-rate impact (100% asynchronous HTTP POST via Java 21 `HttpClient`).
* **Strict Wild Pokémon Validation**:
  * Hooks into both natural spawns (`CobblemonEvents.POKEMON_ENTITY_SPAWN`) and command/item spawns (`ServerEntityEvents.ENTITY_LOAD` e.g., `/pokespawn`).
  * Filters chunk disk loads (`CobblemonEvents.POKEMON_ENTITY_LOAD`) to avoid duplicate or false alerts.
  * Filters out player-owned Pokémon, Party/PC Pokémon, and battle clones.
* **Smart Cooldown & Spam Protection**: Per-species cooldown to avoid notification spam during horde spawns.
* **Role-Based Permission Security**:
  * Administrative commands (`/track webhook`, `/track reload`) are strictly limited to operators (`OP level >= 2`).
  * Normal players only see and execute personal tracking commands.

---

## 📋 Commands

### Player Commands (Available to all players)

| Command | Description |
| :--- | :--- |
| `/track add <pokemon>` | Add a Pokémon species to your personal watchlist |
| `/track remove <pokemon>` | Remove a Pokémon species from your watchlist |
| `/track list` | View your current tracking settings and species list |
| `/track clear` | Clear your entire personal watchlist |
| `/track radius <blocks>` | Set detection radius in blocks (`-1` for unlimited) |
| `/track shiny [true/false]` | Toggle notifications for nearby Shiny Pokémon |
| `/track legendary [true/false]` | Toggle notifications for nearby Legendary Pokémon |
| `/track toggle` | Toggle your notifications on or off |
| `/track discord <user_id>` | Link your Discord User ID to receive `@mention` pings |
| `/track discord clear` | Unlink your Discord User ID |
| `/track help` | View available commands |

### Admin Commands (Requires OP Level >= 2)

| Command | Description |
| :--- | :--- |
| `/track webhook <url>` | Set the Discord Webhook URL for server notifications |
| `/track reload` | Reload configuration from `config.json` |

---

## ⚙️ Configuration

Server configuration is stored at `config/cobblemonnotificationtracker/config.json`:

```json
{
  "webhookUrl": "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL",
  "sendToDiscord": true,
  "sendToInGameChat": false,
  "cooldownSeconds": 15
}
```

Player watchlists are stored asynchronously as individual JSON files in:
`config/cobblemonnotificationtracker/players/<player-uuid>.json`

---

## 🛠️ Building from Source

### Requirements
* Java 21 JDK
* Gradle (provided via wrapper)

### Build Command
```bash
./gradlew clean build
```

The compiled mod jar will be generated at:
`build/libs/cobblemonnotificationtracker-1.0.0.jar`

---

## 📄 License
This project is licensed under the [CC0 1.0 Universal License](LICENSE).
