# GuardAC

A modern, AI-assisted anti-cheat for Minecraft, focused on detecting aim
assistance and aimbots. Free and open-source, licensed under the GPLv3.

[![License](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.x-brightgreen.svg)](#requirements)
[![Platform](https://img.shields.io/badge/platform-spigot%20%7C%20paper%20%7C%20folia-lightgrey.svg)](#requirements)

## Overview

GuardAC is a server-side plugin whose aim check is powered by the GuardAC API.
The plugin observes gameplay, requests a verdict from the service, and handles
alerts, violation tracking and punishments on its own. It is built to stay
conservative: an alert-only mode lets you evaluate accuracy on your own server
before enabling any automatic action.

Additional capabilities include cross-server reputation sharing, configurable
punishments with optional animations, a live suspect view, and clean handling
of edge cases such as lag, teleports and reconnects.

## Requirements

- Java 17 or newer to run the plugin
- JDK 21 or newer to build from source
- Spigot, Paper or Folia — Minecraft 1.21.x
- A GuardAC API key if the aim check is enabled

## Installation

1. Download the latest release from the Releases page.
2. Place `GuardAC-<version>.jar` in your server's `plugins/` directory.
3. Start the server once to generate the configuration files.
4. In `plugins/GuardAC/config.yml`, set your service address and key:

   ```yaml
   ai:
     server: "https://guardac.net"
     api-key: "your-key-here"
   ```

   Keys are available at https://guardac.net. If you do not have access yet,
   set `ai.enabled: false` to run the plugin without the aim check.
5. Restart the server, or run `/guard reload`.

Bedrock players connecting through Geyser are excluded automatically, and
specific WorldGuard regions can be excluded via the configuration.

## Configuration

| File | Purpose |
| --- | --- |
| `config.yml` | Service connection, alerts, combat handling, cross-server reputation |
| `monitor.yml` | Layout of the live monitor output |
| `hologram.yml` | Suspect hologram display |
| `punishments.yml` | Punishment ladder and animations |
| `messages/messages_en.yml` | English messages |
| `messages/messages_ru.yml` | Russian messages |

## Commands

| Command | Description |
| --- | --- |
| `/guard monitor <player>` | Follow a player's live check output |
| `/guard profile <player>` | Show a player's current profile |
| `/guard suspicious` | List currently suspicious players |
| `/guard scan <player> [windows]` | Run an on-demand deep scan |
| `/guard punish <player>` | Apply the top punishment manually |
| `/guard history <player> [page]` | Show a player's violation history |
| `/guard log [page]` | Show recent violations |
| `/guard stats` | Show server-side statistics |
| `/guard exempt <player>` | Exempt a player from checks |
| `/guard alerts` | Toggle alerts for yourself |
| `/guard reload` | Reload the configuration |

The complete list is available in game via `/guard help`. Every subcommand has
its own permission node, `guardac.command.<name>`; `guardac.admin` grants all of
them.

## Building

```bash
git clone https://github.com/PalassCQ/GuardAC.git
cd GuardAC
./gradlew build
```

The compiled plugin is written to `build/libs/GuardAC-<version>.jar`.

## Support

- Bug reports: [GitHub Issues](https://github.com/PalassCQ/GuardAC/issues)
- Community and help: https://guardac.net

When reporting a bug, please include your server and Java versions, the plugin
version, the relevant configuration values, and any logs or stack traces.

## License

GuardAC is distributed under the terms of the GNU General Public License v3.0.
See the [LICENSE](LICENSE) file for details.
