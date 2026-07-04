<div align="center">

# 🛡️ GuardAC

### AI anti-cheat for Minecraft that catches aim hacks by how the mouse *moves*.

[![License](https://img.shields.io/badge/license-GPLv3-22d3ee.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-17%2B-3b82f6.svg)](#-build-from-source)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.x-brightgreen.svg)](#-requirements)
[![Platform](https://img.shields.io/badge/spigot%20%7C%20paper%20%7C%20folia-lightgrey.svg)](#-requirements)

**Free · Open-source · Neural-network powered**

</div>

> GuardAC studies the *physics* of a player's aim in real time and asks a neural
> network one question — "is this a human or an aimbot?" No static reach/CPS
> rules, no guesswork: just the model, a confidence buffer, and your call on
> what to do about it.

---

## ✨ Features

- 🧠 **Neural-network aim detection** — a TCN model scores short windows of aim behavior.
- 🕒 **Real-time & on-demand** — passive checking plus a `/guard scan` deep dive.
- 🔒 **Safe by design** — `only-alert` mode lets you watch before you ban.
- 🧩 **Fingerprint check** — flags a player who switches on a cheat mid-session.
- 🌐 **Cross-server reputation** — a detection on one server is visible to others.
- 🎭 **Rich response** — clickable alerts, suspect menu, holograms, punishment animations.
- ⚙️ **Plays nice** — skips Bedrock (Geyser) players and excluded WorldGuard regions.

---

## 📋 Requirements

| Need | Version |
|------|---------|
| Run the plugin | **Java 17+** |
| Build from source | **JDK 21+** |
| Server | **Spigot / Paper / Folia**, MC **1.21.x** |
| AI check (optional) | a **GuardAC API key** |

---

## 🚀 Quick start

```text
1. Grab the latest jar from GitHub Releases.
2. Drop GuardAC-<version>.jar into your server's plugins/ folder.
3. Start the server once — GuardAC writes its config files.
4. Set your API key (see below), then /guard reload.
```

> [!IMPORTANT]
> The AI check talks to the official **GuardAC API**. Get a key (free tier
> available) at **https://guardac.net**, then in `plugins/GuardAC/config.yml`:
> ```yaml
> ai:
>   server: "https://guardac.net"
>   api-key: "YOUR-KEY-HERE"
> ```
> No key yet? Set `ai.enabled: false` to run the rest of the plugin without it.

---

## ⚙️ Configuration

| File | Controls |
|------|----------|
| `config.yml` | AI connection, alerts, combat handling, cross-server reputation, packets |
| `monitor.yml` | layout of `/guard monitor` |
| `hologram.yml` | suspect hologram display |
| `punishments.yml` | punishment ladder & animations |
| `messages/messages_en.yml` | English text |
| `messages/messages_ru.yml` | Russian text |

---

## 🎮 Commands

<details open>
<summary><b>Everyday staff commands</b></summary>

| Command | What it does |
|---------|--------------|
| `/guard monitor <player>` | live AI readout for one player |
| `/guard profile <player>` | a player's current profile |
| `/guard suspicious` | list currently suspicious players |
| `/guard scan <player> [windows]` | on-demand deep scan |
| `/guard alerts` | toggle alerts for yourself |
| `/guard menu` | open the suspects menu |

</details>

<details>
<summary><b>Moderation & data</b></summary>

| Command | What it does |
|---------|--------------|
| `/guard punish <player>` | apply the top punishment manually |
| `/guard history <player> [page]` | stored violation history |
| `/guard log [page]` | recent violations |
| `/guard stats` | server-side anti-cheat stats |
| `/guard exempt <player>` | exempt a player from checks |
| `/guard reload` | reload configuration |
| `/guarddc <start\|stop>` | labeled data-collection sessions |

</details>

Full list in game: **`/guard help`**. Each subcommand has its own permission node
`guardac.command.<sub>` — or grant `guardac.admin` for everything.

---

## 🛠️ Build from source

```bash
git clone https://github.com/PalassCQ/GuardAC.git
cd GuardAC
./gradlew build
# → build/libs/GuardAC-<version>.jar
```

---

## 💬 Support & bug reports

- 🐛 **Bugs:** open a [GitHub Issue](https://github.com/PalassCQ/GuardAC/issues)
- 💡 **Help & community:** https://guardac.net

<details>
<summary>What to include in a bug report</summary>

- server version · Java version · plugin version
- the relevant `config.yml` values
- logs / stack traces and steps to reproduce

</details>

---

## 🙌 Credits & license

GuardAC is an independently developed codebase — its detection is built around a
Temporal Convolutional Network (TCN) trained on real gameplay. Thanks to the wider
open-source Minecraft anti-cheat community for ideas and inspiration.

Released under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).
