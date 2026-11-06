# EasyTrading

EasyTrading is a Minecraft server plugin for player-to-player trading and an in-game economy. It provides a GUI market, player trade requests, purchase offers, and a configurable server bank.

## Features

- Browse, search, and filter player listings through an inventory GUI.
- List held items for sale, manage listings, and create purchase offers.
- Trade items directly with another player using a request and confirmation flow.
- Transfer money between players and view transaction history.
- Buy and sell configured resources through a server bank with adjustable prices, stock, taxes, and limits.
- Show a balance HUD and optionally hide it.
- Integrate with Vault when Vault is installed. Towny is also detected as an optional dependency.
- Build universal, Folia, or Bukkit-targeted plugin jars.

## Requirements

- Java 21
- A Minecraft 1.21.x server
- Paper is the primary target. Folia builds are also supported.
- Vault is optional; install it if you want EasyTrading balances exposed through the Vault economy API.

## Installation

1. Download a compatible EasyTrading jar from the project's releases, or build one using the instructions below.
2. Place the jar in the server's `plugins` directory.
3. Optionally install Vault (and an economy consumer such as Towny) for Vault integrations.
4. Restart the server. EasyTrading creates its data directory and default configuration files on startup.

## Commands

### Player commands

| Command | Description |
| --- | --- |
| `/market` | Open the market. |
| `/market my` | View your own listings. |
| `/market seller <player>` | Filter listings by seller. |
| `/market search <item>` | Search listings by item. |
| `/market sell <price>` | List the item in your hand. |
| `/market purchase` or `/market pur` | Open the purchase-offer menu. |
| `/market relist <listingId> [price]` | Relist one of your items. |
| `/market cancelall` | Remove all of your listings. |
| `/market sellto <amount>` | Sell items to the server bank. |
| `/market buyfrom <resource> <amount>` | Buy resources from the server bank. |
| `/market send <player> <amount>` | Send money to another player. |
| `/market trade <player>` | Send a direct trade request. |
| `/market trade accept` / `/market trade decline` | Respond to a trade request. |
| `/market team [name]` | View a team balance. |
| `/market balance` | View your balance. |
| `/market history` | View transaction history. |
| `/market limits` | View remaining bank limits. |
| `/market hide` / `/market show` | Hide or show the balance HUD. |
| `/market help` | Show in-game help. |

### Administrator commands

These commands require the `easytrading.admin` permission, which defaults to server operators.

| Command | Description |
| --- | --- |
| `/checkbalance <player>` | Check a player's balance. |
| `/market bankreload` | Reload bank rates and configuration. |
| `/market clearlimits` | Reset bank limits. |
| `/market change <percent>` | Set the bank tax percentage. |
| `/market ads <limit>` | Set the promoted-listing limit. |
| `/market add <player> <amount>` | Add money to a player's balance. |
| `/market take <player> <amount>` | Remove money from a player's balance. |

## Configuration and data

On first startup, EasyTrading creates its files in `plugins/EasyTrading/`. Important configuration files include:

- `market-config.json` — listing limits, listing fees, allowed price range, and promotion settings.
- `bank-config.json` — bank resources, prices, stock, tax, and limits.

The plugin also stores economy balances, market listings, trade history, and other runtime data in this directory. Back up the directory before making configuration changes or upgrading the plugin.

## Build from source

The project uses the Gradle wrapper and requires a JDK 21 installation.

On Windows:

```powershell
.\gradlew.bat clean build
```

On Linux or macOS:

```sh
./gradlew clean build
```

The main plugin jar is written to `build/libs/`. The build also includes the companion Russian localization add-on.

By default, the build targets the universal plugin and forms the version from the Minecraft version, the current month and year, and release iteration `01`. For example, to build the `11.26.01` release explicitly:

```powershell
.\gradlew.bat clean build -Pmc_version=1.21.x -Prelease_period=11.26 -Prelease_iteration=01
```

Choose a platform target with `-Pplatform_target=universal`, `-Pplatform_target=folia`, or `-Pplatform_target=bukkit`.

Run the test suite with:

```powershell
.\gradlew.bat test
```

## License

This project is distributed under the MIT License. See [LICENCE.md](LICENCE.md).
