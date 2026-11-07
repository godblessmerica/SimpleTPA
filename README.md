# SimpleTPA
A simple Paper 26.3 teleport plugin with TPA, TPAHere, and configurable settings.

> [!IMPORTANT]
> **SimpleTPA is developed almost entirely using AI.** It’s written using [OpenAI’s Codex](https://openai.com/codex/), and directed and tested by a human.
>
> Please keep discussions on the project rather than AI. If you disagree with the use of AI or don't trust it, feel free to not use this tool.

## Features
- Clickable accept, decline and cancel buttons.
- One outgoing request per player.
- Requests automatically expire, configurable.
- Teleport delay, default 3 seconds, configurable
- Damage cancellation, default on, configurable
- Movement cancelation, default off, configurable.
- Configurable messages, colors and button text.
- Tab completion for commands.

## Commands
| Command | Description |
|---|---|
| `/tpa <player>` | Request to teleport to a player. |
| `/tpahere <player>` | Ask a player to teleport to you. |
| `/tpa accept [player]` | Accept an incoming request. |
| `/tpa decline [player]` | Decline an incoming request. |
| `/tpa cancel` | Cancel your outgoing request. |
| `/tpa toggle` | Enable or disable incoming requests. |
| `/back` | Return to previous position before teleporting. |
| `/tpa reload` | Reload config. |

## Permissions
| Permission | Default | Description |
|---|---|---|
| `simpletpa.use` | Everyone | Use teleport requests and `/back`. |
| `simpletpa.reload` | Operators | Reload configuration and messages. |

## Configuration

Edit `plugins/SimpleTPA/config.yml`:

```yaml
teleport-delay-seconds: 3
request-expiry-seconds: 60
cancel-on-movement: false
cancel-on-damage: true
```

- **teleport-delay-seconds:** Delay after acceptance. Set to `0` for instant teleporting.
- **request-expiry-seconds:** How long a request stays available.
- **cancel-on-movement:** Cancel if the teleporting player moves during the delay.
- **cancel-on-damage:** Cancel if the teleporting player takes damage during the delay.

Players can move during the countdown by default. Taking damage cancels the teleport.

Messages, colors, and button text are also customizable in `config.yml`. Messages support MiniMessage formatting, such as `<green>text</green>`.

Run `/tpa reload` to apply changes without restarting the server.

## Requirements
- Paper 26.3 https://papermc.io/downloads/paper
- Java 25 or newer https://www.java.com/en/download/
