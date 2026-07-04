<div align="center">

# GuardAC

**An AI-powered, free and open-source anti-cheat for Minecraft servers.**

[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x-brightgreen.svg)

English · Русский (in-game locale)

</div>

---

## What GuardAC is

GuardAC is an open-source anti-cheat plugin for Minecraft servers. It analyzes the
physics of a player's aim and sends short windows of that data to a neural-network
model, which returns the probability that the player is using an aim hack. The
plugin buffers those probabilities and, above a threshold, alerts your staff
and/or applies punishments.

## Important before you install

GuardAC's AI check uses the official **GuardAC API**. You need an API key:

1. Get a key at **https://guardac.net** (a free tier is available).
2. Put it in `config.yml` under `ai.api-key`, and set `ai.server` to
   `https://guardac.net`.

If you don't have API access yet, disable the AI check for now
(`ai.enabled: false` in `config.yml`).

## Requirements

- **Java 17 or newer** to run the plugin
- **JDK 21 or newer** if you want to build from source
- A **Spigot / Paper / Folia**-based server (1.21.x)
- A configured **GuardAC API key** if the AI check is enabled

## Installation

1. Download the latest release from **GitHub Releases**.
2. Place `GuardAC-<version>.jar` in the server's `plugins/` directory.
3. Start the server once so GuardAC can generate its configuration files.
4. Open `plugins/GuardAC/config.yml` and set your `ai.server` and `ai.api-key`.
5. If **WorldGuard** is installed, specific regions can be excluded from the AI check.
6. Restart the server or run `/guard reload`.

Bedrock players (via Geyser) are automatically excluded from the AI check.

## Configuration files

- `config.yml` - AI connection, alerts, combat handling, cross-server reputation,
  anti-relog, packet handling
- `monitor.yml` - formatting for `/guard monitor`
- `hologram.yml` - suspect hologram display
- `punishments.yml` - punishment rules and animations
- `messages/messages_en.yml` - English messages
- `messages/messages_ru.yml` - Russian messages

## Main commands

| Command | Purpose |
|---|---|
| `/guard monitor <player>` | Watch AI data for one player in real time |
| `/guard profile <player>` | Open a player's live profile |
| `/guard suspicious` | Review currently suspicious online players |
| `/guard scan <player> [windows]` | Run an on-demand deep scan of a player |
| `/guard alerts` | Toggle violation alerts for yourself |
| `/guard punish <player>` | Manually apply the top punishment |
| `/guard history <player> [page]` | View a player's stored violation history |
| `/guard log [page]` | View recent violations |
| `/guard stats` | View server-side anti-cheat stats |
| `/guard exempt <player>` | Exempt a player from checks |
| `/guard menu` | Open the suspects menu |
| `/guard reload` | Reload GuardAC configuration |
| `/guarddc <start\|stop>` | Manage labeled data-collection sessions |

For the full command list, use `/guard help` in game. Each subcommand has its own
permission node `guardac.command.<sub>` (or `guardac.admin` for everything).

## Building from source

```bash
git clone https://github.com/PalassCQ/GuardAC.git
cd GuardAC
./gradlew build
```

The plugin jar will be written to:

```
build/libs/GuardAC-<version>.jar
```

## Help, bugs, and discussion

- **Bug reports:** GitHub Issues
- **Community / support:** https://guardac.net

A good issue report includes:

- server version
- Java version
- plugin version
- relevant config values
- logs, stack traces, and steps to reproduce

That makes problems easier to reproduce and fix.

## Credits

GuardAC has its own, independently developed codebase. Its detection is built
around a Temporal Convolutional Network (TCN) trained on real gameplay data.
Thanks to the wider open-source Minecraft anti-cheat community, whose projects
were valuable as references while designing GuardAC.

## License

GuardAC is distributed under the terms of the **GNU General Public License v3.0**.
See [LICENSE](LICENSE).
