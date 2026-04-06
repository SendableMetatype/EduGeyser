# EduGeyser

A [Geyser](https://github.com/GeyserMC/Geyser) fork that enables **Minecraft Education Edition** clients to join **Java Edition** servers. Both Education and regular Bedrock players can connect to the same server simultaneously.

## Features

- **Opt-in education support** - Disabled by default (`tenancy-mode: off`), no impact on existing Geyser installations
- **Education client authentication** - Nonce-based verification via MESS (Minecraft Education Server Services) for official/hybrid modes
- **MESS server registration** - OAuth2 device code flow for automated server setup with Microsoft's education infrastructure
- **Multi-tenancy** - Multiple schools (M365 tenants) can share a single server with per-tenant token routing and newest-token selection
- **Standalone token acquisition** - `/geyser edu token` command for in-game device code flow with auto-refresh
- **Separate config files** - `edu_official.yml` and `edu_standalone.yml` with commented templates, keeping the main config clean
- **Education codec** - Custom StartGamePacket serializer appending the 3 education-specific fields required for Education Edition clients
- **Floodgate integration** - Deterministic UUID generation and username formatting for education players via [EduFloodgate](https://github.com/SendableMetatype/EduFloodgate)
- **Education skin support** - Education player skins are re-signed via the [EduGeyser Signing Relay](https://github.com/SendableMetatype/EduGeyser-Signing-Relay) and fed into the existing GeyserMC global API skin pipeline, making education skins visible to Java players
- **Operator tools** - `/geyser edu` command with status/players/token/reset subcommands and per-tenant health monitoring

## Downloads

Pre-built jars are available on the [Releases](https://github.com/SendableMetatype/EduGeyser/releases) page.

## Documentation

- **[Setup Guide](https://github.com/SendableMetatype/EduGeyser/blob/full/SETUP-GUIDE.md)** - How to install and configure EduGeyser
- **[MESS Tooling Reference](https://github.com/SendableMetatype/EduGeyser/blob/full/MESS-Tooling-Notebook-Reference.md)** - Technical reference for the Microsoft Education Server Services API

For technical details on the authentication flow, protocol differences, and MESS API, see the **[Master Documentation](https://github.com/SendableMetatype/EduGeyser/blob/full/edugeyser-master-documentation.md)**.

---

<img src="https://geysermc.org/img/geyser-1760-860.png" alt="Geyser" width="600"/>

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Discord](https://img.shields.io/discord/613163671870242838.svg?color=%237289da&label=discord)](https://discord.gg/geysermc)
[![Crowdin](https://badges.crowdin.net/e/51361b7f8a01644a238d0fe8f3bddc62/localized.svg)](https://translate.geysermc.org/)

Geyser is a bridge between Minecraft: Bedrock Edition and Minecraft: Java Edition, closing the gap from those wanting to play true cross-platform.

Geyser is an [Open Collaboration](https://opencollaboration.dev/) project.

## What is Geyser?
Geyser is a proxy, bridging the gap between Minecraft: Bedrock Edition and Minecraft: Java Edition servers.
The ultimate goal of this project is to allow Minecraft: Bedrock Edition users to join Minecraft: Java Edition servers as seamlessly as possible. However, due to the nature of Geyser translating packets over the network of two different games, *do not expect everything to work perfectly!*

Special thanks to the DragonProxy project for being a trailblazer in protocol translation and for all the team members who have joined us here!

## Supported Versions

| Edition | Supported Versions                                                                                   |
|---------|------------------------------------------------------------------------------------------------------|
| Bedrock | 1.21.130 - 1.21.132, 26.0, 26.1, 26.2, 26.3, 26.10                                                   |
| Java    | 1.21.11 (For older versions, [see this guide](https://geysermc.org/wiki/geyser/supported-versions/)) |

## Setting Up
Take a look [here](https://geysermc.org/wiki/geyser/setup/) for how to set up Geyser.

## Links:
- Website: https://geysermc.org
- Docs: https://geysermc.org/wiki/geyser/
- Download: https://geysermc.org/download
- Discord: https://discord.gg/geysermc
- Donate: https://opencollective.com/geysermc
- Test Server: `test.geysermc.org` port `25565` for Java and `19132` for Bedrock

## What's Left to be Added/Fixed
- Near-perfect movement (to the point where anticheat on large servers is unlikely to ban you)
- Some Entity Flags

## What can't be fixed
There are a few things Geyser is unable to support due to various differences between Minecraft Bedrock and Java. For a list of these limitations, see the [Current Limitations](https://geysermc.org/wiki/geyser/current-limitations/) page.

## Compiling
1. Clone the repo to your computer
2. Navigate to the Geyser root directory and run `git submodule update --init --recursive`. This command downloads all the needed submodules for Geyser and is a crucial step in this process.
3. Run `gradlew build` and locate to `bootstrap/build` folder.

## Contributing
Any contributions are appreciated. Please feel free to reach out to us on [Discord](https://discord.gg/geysermc) if
you're interested in helping out with Geyser.

## Libraries Used:
- [Adventure Text Library](https://github.com/KyoriPowered/adventure)
- [CloudburstMC Bedrock Protocol Library](https://github.com/CloudburstMC/Protocol)
- [GeyserMC's Java Protocol Library](https://github.com/GeyserMC/MCProtocolLib)
- [TerminalConsoleAppender](https://github.com/Minecrell/TerminalConsoleAppender)
- [Simple Logging Facade for Java (slf4j)](https://github.com/qos-ch/slf4j)
