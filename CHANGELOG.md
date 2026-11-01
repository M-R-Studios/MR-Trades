# Changelog

## [Unreleased]

## [1.0] - 2026-11-01

The first release.

### Added
- **Trade requests:** `/trade <player>`, `/trade accept [player]`, `/trade deny [player]` and `/trade cancel`. Requesting a player who already requested you accepts their request. Requests expire after `requests.timeout-seconds` and respect a cooldown.
- **Sneak and right-click** a player to send a request, or to accept the one they sent you. NPCs (Citizens and similar) are ignored, and holding the button sends one request, not a stream. Works for players whose name is a `/trade` subcommand too. Turn it off with `requests.sneak-right-click: false`.
- **Clickable `[Accept]` / `[Deny]` buttons** for Java players. The typed commands are always shown too, so every client can answer.
- **`/trade toggle`** stops incoming requests. It's stored on the player, so it survives relogs and restarts.
- **Limits:** maximum distance (checked on a timer), cross-world trading, disabled worlds and blocked game modes (creative and spectator by default). Each has a bypass permission.
- **Shared trade window** with both players' heads and names, your offer on the left (up to 16 stacks) and theirs on the right. Click items in your inventory to offer them (left/shift-click: the whole stack, right-click: one) and click your offer to take them back.
- **Confirm and countdown:**
  - When both players confirm, a short countdown runs (`trade.countdown-seconds`) and then the swap happens in one go.
  - Any change to either offer, at any time, resets both confirmations and stops the countdown.
  - After the other player changes their offer, your confirm button is briefly locked (`trade.change-lock-ms`).
- **Atomic completion:**
  - Right before the swap, it checks again that both players have room for what they receive, enough money and enough levels.
  - If not, nothing moves, the trade stays open and both players are told why.
  - Both players are saved right after a completed trade.
- **XP levels in trades** with an editor: +/- buttons (`xp.buttons`), "Set to 0" and "Offer all". Needs `mrtrades.xp`.
- **Money in trades through Vault**, with the same editor (`money.buttons`, capped by `money.max-amount`). Needs `mrtrades.money`.
  - Amounts use your economy's own format and decimals.
  - Only the difference changes hands: if you offer 100 and they offer 30, you pay 70.
  - If the economy refuses or fails during the swap, the payment is undone and the trade stays open. If even undoing it fails, both players get a trade id to give staff and the console logs exactly who is owed what.
  - Without Vault, the money buttons are hidden and everything else works.
  - If the economy plugin stops during a trade, money offers are set to 0 and both players are told.
- **Type an amount instead of clicking.** The window closes and the next chat message is read: `all` offers everything, `input.cancel-word` cancels, and `input.chat-timeout-seconds` gives up. What you type is never shown in public chat, and the trade window comes back afterwards. The other player sees "typing an amount…" on your head meanwhile.
- **Bedrock support (Geyser + Floodgate):**
  - A pop-up with Accept / Deny for trade requests, a text field for money amounts and a slider for XP levels. `bedrock.request-form` and `bedrock.amount-form` turn them off.
  - Without Floodgate, Bedrock players use the same buttons, chat input and commands as Java players.
  - Switching between the trade window and the editors has a short pause on Bedrock, so the windows can't close each other.
- **While a trade window is open, right-clicking blocks and entities does nothing**, so a stray tap can't open a nearby chest over the trade.
- **Walking into each other or being pushed never cancels a trade;** only going past `limits.max-distance` does.
- **Every way a trade can end gives every offered item and all money back to its owner:**
  - Closing the window, the cancel button, `/trade cancel` and `/mrtrades cancel <player>`.
  - Leaving, being kicked or dying.
  - Changing world or to a blocked game mode, or walking past the distance limit.
  - Server stop or plugin disable.
- **Offered items are also recorded on the player**, so they come back after a server crash.
- **Items that don't fit** wait safely in a return queue (`returns.when-full: QUEUE`) and are given back on join, on respawn and with `/trade claim`. With `DROP` they drop at the owner's feet, only for them.
- **No stuck players:** after any trade ends, both players can open chests, use blocks and start new trades right away, without relogging.
- **Trade history:**
  - Every completed trade is recorded (SQLite by default, MySQL optional) with the exact items, money and XP levels of both sides.
  - `/trade history [player] [page]` shows them in a window with the partner's head, the date and a short summary; click a trade to see its items. Viewing another player's history needs `mrtrades.history.others`.
  - The log is written off the main thread and never delays or blocks a trade. If the database is unavailable, records are kept in `history-backlog.jsonl` and imported on the next start.
  - Old entries are deleted after `history.retention-days`.
- **`config.yml` and `messages.yml`** with every option commented and every text (GUI names and lore included) in MiniMessage format. Every sound can be changed or turned off.
  - An invalid value falls back to its default with a warning that names the key, the bad value and what's accepted.
  - Both files are upgraded automatically on plugin updates, with a backup of the old file.
- **`/mrtrades` (alias `/mrt`):**
  - `reload` says exactly which file failed and why (with the line number for YAML errors) and keeps the previous settings for that file.
  - `version` shows the economy plugin behind Vault, Floodgate, and whether the trade history storage is working.
  - `cancel <player>` ends a player's trade and gives everything back.
