# Changelog

## [Unreleased]

### Added
- Project skeleton.
- `config.yml` and `messages.yml`, with every option commented. Invalid values fall back to their default with a warning that names the key, the bad value and what's accepted.
- `/mrtrades` (alias `/mrt`) with `reload` and `version`. Reload says exactly which file failed and why (with the line number for YAML errors) and keeps the previous settings for that file.
- Automatic upgrade of `config.yml` and `messages.yml` on plugin updates, with a backup of the old file.
- All commands and permissions listed in `plugin.yml` and the README.
