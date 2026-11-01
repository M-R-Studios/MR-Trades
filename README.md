# MR-Trades

Safe player-to-player trading for Paper servers, for Java and Bedrock players alike. Nothing is ever lost or duplicated.

By **MR Studios**. Work in progress.

## Commands

| Command | Aliases | Permission | What it does |
|---|---|---|---|
| `/trade <player>` | | `mrtrades.trade` | Sends a trade request. If that player already sent you one, accepts it instead. |
| `/trade accept [player]` | | `mrtrades.trade` | Accepts a pending request. Without a name: the most recent one. |
| `/trade deny [player]` | | `mrtrades.trade` | Denies a pending request. Without a name: the most recent one. |
| `/trade cancel` | | `mrtrades.trade` | Withdraws your outgoing request, or cancels the trade you're in. |
| `/trade toggle` | | `mrtrades.toggle` | Turns receiving trade requests off or on. |
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

`plugins/MR-Trades/config.yml` documents every option in its comments; `messages.yml` holds every player-facing text in [MiniMessage](https://docs.advntr.dev/minimessage/format) format. After editing either, run `/mrtrades reload`. An invalid value falls back to its default with a warning naming the key; a file with a YAML error keeps its previous settings and the error names the line. Storage settings need a restart.

## Trade history

Every completed trade is recorded: who traded, when, where, the exact items each side gave (enchantments, names and shulker contents included), money and XP levels. `/trade history` shows them newest first, 45 per page; click a trade to see its items. Nothing can be taken out of these windows, and they work the same on Bedrock.

- **Storage:** SQLite by default (`plugins/MR-Trades/history.db`, nothing to set up). For a network, set `storage.type: MYSQL` and the `storage.mysql` connection; several servers can share one database (`history.server-name` tells them apart). Changing the storage needs a restart, and entries aren't copied between storages.
- **Never in the way of trading:** the log is written on a background thread. If the database is down, trades still work and their records are kept in `history-backlog.jsonl`, which is imported automatically on the next start.
- **Retention:** `history.retention-days` deletes older entries at startup and once a day (0 keeps everything). `history.enabled: false` turns the log and `/trade history` off.

## Reporting bugs

Open an issue using the bug report form: [new issue](https://github.com/M-R-Studios/MR-Trades/issues/new/choose). For questions and setup help, join the [MR Studios Discord](https://discord.gg/hTsrHsGcTg).

## Building

```bash
mvn -B clean package
```

The jar is written to `target/`.
