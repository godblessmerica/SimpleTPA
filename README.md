<img src="assets/icon.png" width="128" alt="SimpleTPA icon">

# SimpleTPA
A simple Paper 26.3 teleport plugin with TPA, TPAHere, and configurable settings.

[Download SimpleTPA](../../releases/latest)

> [!IMPORTANT]
> **SimpleTPA is developed almost entirely using AI.** It’s written using [OpenAI’s Codex](https://openai.com/codex/), and directed and tested by a human.
>
> Please keep discussions on the project rather than AI. If you disagree with the use of AI or don't trust it, feel free to not use this tool.

## Features
- Clickable accept, decline and cancel buttons.
- One outgoing request per player.
- Requests automatically expire, configurable.
- Teleport delay, default 3 seconds, configurable
- Teleport countdowns for `/tpa`, `/tpahere` and `/back`.
- Damage cancellation, default on, configurable
- Movement cancelation, default off, configurable.
- Configurable messages, colors and button text.
- Tab completion for commands.
- Tab completion for setting names and boolean values.
- Edit settings in game, excluding messages.
- Help command lists permitted commands with short descriptions.
- Updated version notice when operator joins, with a clickable download link.
- Config toggle for update checks.

## Commands
| Command | Description |
|---|---|
| `/tpa <player>` | Request to teleport to a player. |
| `/tpahere <player>` | Ask a player to teleport to you. |
| `/tpaccept [player]` | Accept an incoming request. |
| `/tpdeny [player]` | Decline an incoming request. |
| `/tpcancel` | Cancel your outgoing request. |
| `/tptoggle` | Enable or disable incoming requests. |
| `/back` | Return to previous position before teleporting. |
| `/tpa help` | List permitted commands and subcommands with short descriptions. |
| `/tpa version` | Display version of plugin. |
| `/tpa reload` | Reload config. |
| `/tpa config` | List editable settings and current values. |
| `/tpa config <setting>` | Show current value of a setting. |
| `/tpa config <setting> <value>` | Edit and save a setting in game. |

## Permissions
| Permission | Default | Description |
|---|---|---|
| `tpa.tpa` | Everyone | Use `/tpa <player>`. |
| `tpa.tphere` | Everyone | Use `/tpahere <player>`. |
| `tpa.toggle` | Everyone | Use `/tptoggle`. |
| `tpa.back` | Everyone | Use `/back`. |
| `tpa.help` | Everyone | Use `/tpa help`. |
| `tpa.config` | Operators | Use `/tpa config`. |
| `tpa.reload` | Operators | Reload configuration and messages. |
| `tpa.version` | Operators | Use `/tpa version`. |

`/tpaccept`, `/tpdeny`, `/tpcancel` come with either `tpa.tpa` or `tpa.tphere`.

## Configuration

Edit `plugins/SimpleTPA/config.yml`:

```yaml
teleport-delay-seconds: 3
request-expiry-seconds: 60
cancel-on-movement: false
cancel-on-damage: true
check-for-updates: true
```

- **teleport-delay-seconds:** Delay after acceptance. Set to `0` for instant teleporting.
- **request-expiry-seconds:** How long a request stays available.
- **cancel-on-movement:** Cancel if the teleporting player moves during the delay.
- **cancel-on-damage:** Cancel if the teleporting player takes damage during the delay.
- **check-for-updates:** Check for updated versions and notify operators when they join.

Players can move during the countdown by default. Taking damage cancels the teleport.

Use `/tpa config <setting> <value>` to edit settings in game. Changes save and apply immediately. Invalid values leave settings unchanged. Messages cannot be edited in game.

Messages, colors, and button text are also customizable in `config.yml`. Messages support [MiniMessage formatting](https://docs.advntr.dev/minimessage/format.html), such as `<green>text</green>`.

Run `/tpa reload` to apply changes without restarting the server.

## Requirements
- [Paper 26.3](https://papermc.io/downloads/paper)
- [Java 25 or newer](https://adoptium.net/temurin/releases/?version=25)
