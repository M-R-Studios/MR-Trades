# Changelog

## [Unreleased]

### Added
- Project skeleton.
- `config.yml` and `messages.yml`, with every option commented. Invalid values fall back to their default with a warning that names the key, the bad value and what's accepted.
- `/mrtrades` (alias `/mrt`) with `reload` and `version`. Reload says exactly which file failed and why (with the line number for YAML errors) and keeps the previous settings for that file.
- Automatic upgrade of `config.yml` and `messages.yml` on plugin updates, with a backup of the old file.
- All commands and permissions listed in `plugin.yml` and the README.
- Trade core (not reachable in game yet): offered items are held in escrow, any change to either offer resets both confirmations and briefly locks the other player's confirm button, and completion first checks both players still have room, money and levels.
- Trade requests: `/trade <player>`, `/trade accept [player]`, `/trade deny [player]`, `/trade cancel` and `/trade toggle`. Requests expire after `requests.timeout-seconds`, respect the cooldown, distance, cross-world, disabled-world and game-mode limits (each with its bypass permission), and are checked again when accepted. Requesting a player who already requested you accepts their request. The request message always shows the typed commands, plus clickable [Accept] / [Deny] buttons for Java players. Accepting doesn't open a trade window yet.
- `/trade toggle` is stored on the player, so it survives relogs and restarts.
- Trade window: accepting a request opens a shared window with both players' heads and names. Click items in your inventory to offer them (left/shift-click: the whole stack, right-click: one) and click your offer to take them back. Both confirm buttons work; completing a trade arrives in the next update.
- Every way a trade can end early gives every offered item back to its owner: closing the window, the cancel button, `/trade cancel`, `/mrtrades cancel <player>`, leaving, being kicked, dying, changing world, switching to a blocked game mode, walking past `limits.max-distance`, and server stop or plugin disable. Both players can use chests, blocks and new trades again right away.
- Offered items are also recorded on the player, so they come back after a server crash.
- `/trade claim` and the return queue: items that don't fit in your inventory wait for you (or drop at your feet, only for you, with `returns.when-full: DROP`) and are given back on join, on respawn and with `/trade claim`.
- `/mrtrades cancel <player>` ends a player's trade and returns everything.
