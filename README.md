# MR-Trades

Safe player-to-player trading for Paper servers, for Java and Bedrock players alike. Two players put items, money and XP levels into a shared window, both confirm, and everything swaps in one go. Nothing is ever lost or duplicated, and nobody is left stuck afterwards.

By **MR Studios**.

## Features

- **Trade requests:** `/trade <player>`, or sneak and right-click a player. Requests expire, have a cooldown, and can be turned off with `/trade toggle`. Java players get clickable `[Accept]` / `[Deny]` buttons; Bedrock players get a pop-up. The typed commands always work too.
- **Shared trade window:** your offer on the left (up to 16 stacks), theirs on the right, both heads and names at the top. Click items in your inventory to offer them (left/shift-click: the whole stack, right-click: one) and click your offer to take them back.
- **Confirm and countdown:** when both confirm, a short countdown runs. Any change to either offer, at any time, resets both confirmations. After the other player changes their offer, your confirm button is locked for a moment, so a last-second swap can't be confirmed by accident.
- **Atomic completion:** right before the swap, it checks again that both players still have everything, enough room and enough money and levels. Then everything moves in one tick, or nothing moves.
- **Money** (with Vault and an economy plugin) and **XP levels:** +/- buttons, "Offer all", or type an amount (in chat, or in a pop-up form on Bedrock). Only the difference changes hands, and a failed payment is rolled back.
- **Every way a trade can end gives everything back:** closing the window, cancelling, leaving, being kicked, dying, changing world or game mode, walking too far apart, server stop, plugin disable, even a server crash. Items that don't fit go to a safe return queue (`/trade claim`) or drop at the owner's feet, only for them.
- **No stuck players:** after any trade, both players can open chests, place blocks and trade again right away.
- **Bumping never cancels:** walking into each other or being pushed doesn't end a trade. Only going past the distance limit does.
- **Trade history:** every completed trade is recorded (SQLite or MySQL). `/trade history` shows past trades with the exact items; staff can look up anyone's.
- **Limits:** max distance, cross-world, disabled worlds, blocked game modes, each with a bypass permission.
- **Fully configurable:** every option is commented in `config.yml`, every text (including GUI names and lore) is in `messages.yml` with [MiniMessage](https://docs.advntr.dev/minimessage/format) formatting, and every sound can be changed or turned off. `/mrtrades reload` says exactly what failed and why.

## Requirements

- **Paper 1.21.11** (or a fork) and **Java 21**.
- Optional: **Vault** plus an economy plugin, for money in trades.
- Optional: **Geyser + Floodgate**, for Bedrock pop-up forms. Bedrock players can trade without Floodgate too, using buttons, chat and commands.

Drop `MR-Trades-<version>.jar` into `plugins/` and restart. No database setup is needed.

## Commands

| Command | Aliases | Permission | What it does |
|---|---|---|---|
| `/trade <player>` | | `mrtrades.trade` | Sends a trade request. If that player already sent you one, accepts it instead. |
| `/trade accept [player]` | | `mrtrades.trade` | Accepts a pending request. Without a name: the most recent one. |
| `/trade deny [player]` | | `mrtrades.trade` | Denies a pending request. Without a name: the most recent one. |
| `/trade cancel` | | `mrtrades.trade` | Withdraws your outgoing request, or cancels the trade you're in. |
| `/trade toggle` | | `mrtrades.toggle` | Turns receiving trade requests off or on. Remembered across restarts. |
| `/trade claim` | | none | Gives you back items that couldn't be returned because your inventory was full. Needs no permission, so nobody can be locked out of their own items. |
| `/trade history [player] [page]` | | `mrtrades.history` (another player: `mrtrades.history.others`) | Opens your trade history, or another player's. `/trade history 2` opens your second page. |
| `/trade` | | `mrtrades.trade` | Shows help. |
| `/mrtrades` | `/mrt` | `mrtrades.admin` | Shows the admin commands. |
| `/mrtrades reload` | | `mrtrades.admin` | Reloads `config.yml` and `messages.yml`. Tells you exactly what failed and why; a file that fails to load keeps its previous settings. |
| `/mrtrades version` | | `mrtrades.admin` | Shows the plugin and server version, active hooks (Vault, Floodgate) and the trade history storage and whether it's working. |
| `/mrtrades cancel <player>` | | `mrtrades.admin` | Ends that player's trade and gives everything back to both players. |

`accept`, `deny`, `cancel`, `toggle`, `claim` and `history` are reserved words: a player with one of those names can't be requested with `/trade <name>`, but can still be requested by sneak-right-clicking them.

## Permissions

| Permission | Default | Meaning |
|---|---|---|
| `mrtrades.*` | op | All of the below. |
| `mrtrades.trade` | everyone | Send, accept and deny requests, and trade. |
| `mrtrades.trade.sneak` | everyone | Request or accept by sneak-right-clicking a player. |
| `mrtrades.money` | everyone | Put money into a trade. |
| `mrtrades.xp` | everyone | Put XP levels into a trade. |
| `mrtrades.toggle` | everyone | Use `/trade toggle`. |
| `mrtrades.history` | everyone | View your own trade history. |
| `mrtrades.history.others` | op | View other players' trade history. |
| `mrtrades.bypass.cooldown` | op | Ignore the request cooldown. |
| `mrtrades.bypass.distance` | op | Ignore `limits.max-distance` and `limits.allow-cross-world`. Applies if either player has it. |
| `mrtrades.bypass.world` | op | Trade in worlds listed in `limits.disabled-worlds`. |
| `mrtrades.bypass.toggle` | op | Send requests to players who turned requests off. |
| `mrtrades.admin` | op | `/mrtrades` and its subcommands. |

`/trade claim` has no permission on purpose.

## Configuration

`plugins/MR-Trades/config.yml` documents every option in its comments. The sections:

| Section | What it controls |
|---|---|
| `requests` | Request timeout, cooldown, sneak-right-click, clickable buttons. |
| `limits` | Max distance and how often it's checked, cross-world trading, disabled worlds, blocked game modes (creative and spectator by default). |
| `trade` | Countdown length and the confirm lock after an offer change. |
| `money` / `xp` | Turn them on or off, the +/- button amounts, a maximum money offer. |
| `input` | Chat amount timeout and the cancel word. |
| `bedrock` | Floodgate detection, the request pop-up and the amount pop-up. |
| `returns` | What happens to returned items that don't fit: `QUEUE` (kept safe for `/trade claim`) or `DROP`. |
| `history` / `storage` | The trade log, retention, and SQLite or MySQL (see below). |
| `sounds` | Each sound, its volume and pitch, or off. |

`messages.yml` holds every player-facing text in MiniMessage format. After editing either file, run `/mrtrades reload`. An invalid value falls back to its default with a warning naming the key; a file with a YAML error keeps its previous settings and the error names the line. Storage settings need a restart. Both files are upgraded automatically when the plugin updates, with a backup of the old file.

## Trade history

Every completed trade is recorded: who traded, when, where, the exact items each side gave (enchantments, names and shulker contents included), money and XP levels. `/trade history` shows them newest first, 45 per page; click a trade to see its items. Nothing can be taken out of these windows, and they work the same on Bedrock.

- **Storage:** SQLite by default (`plugins/MR-Trades/history.db`, nothing to set up). For a network, set `storage.type: MYSQL` and the `storage.mysql` connection; several servers can share one database (`history.server-name` tells them apart). Changing the storage needs a restart, and entries aren't copied between storages.
- **Never in the way of trading:** the log is written on a background thread. If the database is down, trades still work and their records are kept in `history-backlog.jsonl`, which is imported automatically on the next start.
- **Retention:** `history.retention-days` deletes older entries at startup and once a day (0 keeps everything). `history.enabled: false` turns the log and `/trade history` off.

## Bedrock players

Everything works through Geyser: every action is a plain tap on a slot, a command or a chat message, so nothing needs a Java-only gesture.

- With **Floodgate**, Bedrock players get a pop-up to accept or deny requests, a text field for money and a slider for XP levels. `bedrock.request-form` and `bedrock.amount-form` turn them off.
- Without Floodgate, Bedrock players use the same buttons, chat input and commands as Java players.
- Switching between the trade window and the money/XP editors has a short pause on Bedrock, so the windows can't close each other.
- While a trade window is open, right-clicking blocks and entities does nothing, so a stray tap can't open a nearby chest over the trade.
- Bedrock clients don't show pop-up forms while the player is in a menu or in chat; the chat message and `/trade accept` still work then.

## How items are kept safe

Offered items leave your inventory and are held by the trade, and a copy of the offer is saved on your player data. So at every moment each item is in exactly one place: your inventory, the trade, or (after completion) the other player. After a server crash, anything that was in a trade comes back when you join.

**One known limit:** Minecraft saves each player to their own file. Right after a trade completes, both players are saved immediately, but if the server process dies in the few milliseconds between those two saves, one player could be rolled back to before the trade while the other isn't. No plugin can make two separate player files save as one, and every other crash moment is safe.

## Reporting bugs

Open an issue using the bug report form: [new issue](https://github.com/M-R-Studios/MR-Trades/issues/new/choose). For questions and setup help, join the [MR Studios Discord](https://discord.gg/hTsrHsGcTg).

## Building

```bash
mvn -B clean package
```

The jar is written to `target/`.
