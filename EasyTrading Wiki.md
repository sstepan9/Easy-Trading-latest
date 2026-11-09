# EasyTrading Wiki

**Valid for EasyTrading `1.21.x-11.26.02`.**

## Contents

- [Overview](#overview)
- [Installation](#installation)
- [Player Guide](#player-guide)
- [Player Market](#player-market)
- [Purchase Orders](#purchase-orders)
- [Server Bank](#server-bank)
- [Direct Player Trading](#direct-player-trading)
- [Money, Teams, and History](#money-teams-and-history)
- [Commands](#commands)
- [Configuration](#configuration)
- [Data, Logs, and Backups](#data-logs-and-backups)
- [Permissions](#permissions)
- [FAQ](#faq)

## Overview

EasyTrading provides an in-game economy and several ways for players to exchange items and money:

- A GUI-based player market for item listings.
- Purchase orders for any item type.
- A server bank with dynamic prices, stock, taxes, and daily limits.
- Direct player-to-player item and money trades.
- Money transfers, team balances, transaction history, and a balance HUD.
- Optional Vault economy support.

The balance HUD is shown as a yellow boss bar and refreshes automatically. Players can hide it with `/market hide` and show it again with `/market show`.

## Installation

1. Install Java 21 and a compatible Minecraft `1.21.x` server.
2. Place the EasyTrading JAR in the server `plugins/` directory.
3. Optionally install Vault if other plugins need to use the EasyTrading balance through Vault.
4. Start the server once to generate the plugin folder and default files.
5. Stop the server before manually editing JSON data or configuration files.
6. Configure the market and bank, then start the server again.

Paper is the primary platform. The universal build also supports Folia, but direct player trading is unavailable on Folia.

## Player Guide

Useful commands for new players:

```text
/market
/market balance
/market sell <price>
/market purchase
/market sellto <amount>
/market buyfrom <resource> <amount>
/market limits
/market send <player> <amount>
/market history
```

## Player Market

Open the market with:

```text
/market
```

The market GUI has two modes:

- **Selling** — player item listings.
- **Purchasing** — player purchase orders.

Use the navigation row to change pages and sorting, show only your own offers, clear filters, search by item, filter by seller or buyer, and switch modes. The page information item also shows the current server-bank buy and sell prices.

### Listing an Item

Hold the item in your main hand and run:

```text
/market sell <price>
```

The price must be within the configured market price range. Confirm the listing in the confirmation GUI.

Important rules:

- Resources accepted by the server bank must be sold with `/market sellto`, not listed on the player market.
- A listing fee may apply. The fee is charged only after a successful listing is created.
- If the item in your hand changes before confirmation, the listing is cancelled.
- Items marked as handled in Creative mode cannot be listed when `market.block-creative-items` is enabled.

### Buying and Managing Listings

Click another player's listing to open a purchase confirmation. The plugin checks your balance and available inventory space before completing the purchase.

Clicking your own listing removes it from sale and returns the item.

```text
/market my
/market relist <listingId> [newPrice]
/market cancelall
```

`/market cancelall` removes your sale listings only. It does not cancel purchase orders. Returned items are delivered to your inventory; any remainder is queued for delivery if necessary.

### Listing Fees and Promotion

Market fees are controlled by `market-config.json`.

- Listings within `free_listing_slots` have no normal listing fee.
- `base_fee_percent` applies after the free slots are used.
- `progressive_fee_percent` increases the fee after `soft_listing_cap`.
- The final fee is constrained by `fee_min` and `fee_max`.
- The total number of sale listings and purchase orders is limited by `hard_listing_cap`.

When enabled by the confirmation screen, a listing may be promoted for an additional configured fee. The number of promoted listings per seller is limited by `promoted_listing_limit`.

## Purchase Orders

Create a purchase order with:

```text
/market purchase
```

or:

```text
/market pur
```

Purchase orders use a chat-first flow and can be created for **any item type**. After running the command, enter an item name or Minecraft ID in chat (for example, `diamond_sword`). The results screen includes matching items and tolerates ordinary spelling mistakes. Select an item, enter the quantity in chat, then enter the **total price for the entire order**—not a price per item.

When an order is confirmed, its total price is reserved from the buyer's balance. Another player can open the Purchasing market mode and fulfill the complete order.

Important rules:

- Orders are fulfilled in full; partial fulfillment is not supported.
- The seller must have the complete requested amount of matching items.
- Quantities follow the item's vanilla stack size: up to 1 for non-stackable items, 16 for 16-stack items, and 64 for regular items.
- Cancelling your own order returns the reserved money.
- If the buyer is offline or lacks inventory space when an order is completed, delivery is queued safely for later.

While choosing an item, amount, or price, type `back` to return to the previous step or `cancel` to stop. `/market purchase back` and `/market purchase cancel` provide the same controls.

## Server Bank

The server bank buys and sells configured resources. Its prices are dynamic and are influenced by stock, target stock, tax, and configured limits.

### Sell to the Bank

Hold a supported resource in your main hand:

```text
/market sellto <amount>
```

The accepted amount may be reduced by the stack size in your hand or your remaining daily sell limit. Confirm the transaction in the GUI.

Items marked as handled in Creative mode are rejected when `market.block-creative-items` is enabled.

### Buy from the Bank

```text
/market buyfrom <resource> <amount>
```

The bank checks its stock, your daily buy limit, your balance, and free inventory space. The final amount may be lower than requested if one of those limits applies.

Use `/market limits` to see your remaining daily buy and sell limits.

### Default Resources

The default bank configuration contains:

- Coal
- Redstone
- Lapis Lazuli
- Copper Ingots
- Iron Ingots
- Gold Ingots
- Diamonds
- Emeralds
- Netherite Ingots

### Dynamic Prices

Selling increases a resource's stock; buying reduces it. The bank periodically moves stock toward its target level, and prices adjust within the configured minimum, maximum, and step limits.

The displayed bank buy price includes tax. The sell price is the amount paid to the player after tax.

## Direct Player Trading

```text
/market trade <player>
/market trade accept
/market trade decline
```

Direct trades use a shared GUI with separate item areas for both players and optional money offers.

- Trade requests expire after 60 seconds.
- Each player has 16 item offer slots.
- Money offer buttons change the offered amount in steps of 10.
- Both players must confirm, followed by a three-second countdown.
- Any item or money change resets both confirmations.
- Closing the window, dying, disconnecting, or changing worlds cancels the trade and returns the offered items.

For additional protection, `trade.block-creative-and-spectator` can prevent Creative and Spectator players from starting or completing a direct trade.

## Money, Teams, and History

### Send Money

```text
/market send <player> <amount>
```

The recipient must be online. You cannot transfer money to yourself, and you must have enough balance.

### Team Balance

```text
/market team
```

Displays the combined balance of online members of your current scoreboard team.

Administrators can inspect a named team:

```text
/market team <team>
```

### History

```text
/market history
```

Shows your recent transactions, including market purchases and sales, bank activity, transfers, fees, and trades.

## Commands

### Player Commands

| Command | Description |
| --- | --- |
| `/market` | Open the player market. |
| `/market help` | Show in-game command help. |
| `/market my` | Show your own sale listings. |
| `/market seller <player>` | Filter sale listings by seller. |
| `/market search <item>` | Filter listings by item name. |
| `/market sell <price>` | List the item in your main hand. |
| `/market purchase` or `/market pur` | Open the purchase-order setup. |
| `/market relist <listingId> [newPrice]` | Refresh one of your sale listings. |
| `/market cancelall` | Remove all of your sale listings. |
| `/market sellto <amount>` | Sell the held bank resource to the server bank. |
| `/market buyfrom <resource> <amount>` | Buy a resource from the server bank. |
| `/market limits` | Show your remaining bank limits. |
| `/market send <player> <amount>` | Transfer money to an online player. |
| `/market trade <player>` | Send a direct trade request. |
| `/market trade accept` | Accept a pending direct trade request. |
| `/market trade decline` | Decline a pending direct trade request. |
| `/market team` | Show your online team's combined balance. |
| `/market history` | Show recent transactions. |
| `/market balance` | Show your balance. |
| `/market hide` / `/market show` | Hide or show the balance HUD. |

### Administrator Commands

These commands require `easytrading.admin`.

| Command | Description |
| --- | --- |
| `/checkbalance <player>` | Check an online player's balance. |
| `/market bankreload` | Reload the bank configuration. |
| `/market clearlimits` | Reset bank limits for the current day. |
| `/market change <0-100>` | Set the bank tax percentage. |
| `/market ads <limit>` | Set the promoted-listing limit. |
| `/market add <player> <amount>` | Add money to an online player. |
| `/market take <player> <amount>` | Remove money from an online player. |
| `/market team <team>` | Show an online scoreboard team's total balance. |

## Configuration

EasyTrading creates its files in `plugins/EasyTrading/`.

### `config.yml`

```yml
trade:
  block-creative-and-spectator: true

market:
  block-creative-items: true
```

- `trade.block-creative-and-spectator` prevents Creative and Spectator players from using direct player trades.
- `market.block-creative-items` marks items handled in Creative and blocks them from `/market sell` and `/market sellto`.

Creative-origin tracking starts after the updated plugin is installed. Items created before the update cannot be reliably identified retroactively.

### `market-config.json`

Controls player-market limits, prices, normal listing fees, and promotion settings.

| Key | Purpose |
| --- | --- |
| `free_listing_slots` | Number of sale listings without a normal listing fee. |
| `soft_listing_cap` | Listing count at which progressive fees begin. |
| `hard_listing_cap` | Maximum combined number of sale listings and purchase orders per player. |
| `base_fee_percent` | Base listing fee percentage. |
| `progressive_fee_percent` | Additional progressive fee percentage. |
| `fee_min` / `fee_max` | Lower and upper fee limits. |
| `min_price` / `max_price` | Allowed total price range. |
| `promotion_fee_percent` | Fee percentage for promoted listings. |
| `promoted_listing_limit` | Maximum promoted listings per seller. |

### `bank-config.json`

Controls bank resources and dynamic pricing.

Global keys include:

- `tax_percent`
- `price_elasticity`
- `min_factor`
- `max_factor`
- `max_step_percent`
- `daily_stock_recovery_percent`
- `buy_limit_multiplier`

Each resource has its own `limit`, `base`, `target`, `min`, `max`, `stock`, and `price` values.

Edit live state values such as `stock`, `price`, and `last_rebalance_date` only while the server is stopped.

## Data, Logs, and Backups

Important files in `plugins/EasyTrading/`:

| File | Purpose |
| --- | --- |
| `economy.json` | Player balances. |
| `market.json` | Sale listings and purchase orders. |
| `market-config.json` | Player-market settings. |
| `bank-config.json` | Bank settings and live resource state. |
| `bank-state.json` | Daily bank buy and sell counters. |
| `history.json` | Transaction history. |
| `market-notify.json` | New-market-offer notification state. |
| `market-deliveries.json` | Queued item deliveries. |
| `logs/easytrading-bank.log` | Bank transaction log. |
| `logs/easytrading-transfers.log` | Player money-transfer log. |
| `logs/easytrading-trades.log` | Direct-trade audit log. |

If `economy.json` or `market.json` is critically damaged, EasyTrading records the error and preserves a copy with a `.corrupted-<timestamp>` suffix before a later save can replace it.

Always back up the `plugins/EasyTrading/` folder before upgrades or manual data changes.

## Permissions

| Permission | Description | Default |
| --- | --- | --- |
| `easytrading.admin` | Allows administrator commands. | OP |
| `easytrading.use` | Declared basic-use permission. | Everyone |

## FAQ

### Why can’t I list a bank resource on the player market?

Configured bank resources must be sold through `/market sellto` so the bank can apply its stock, tax, and daily-limit rules.

### Why was my Creative item rejected?

With `market.block-creative-items: true`, items handled in Creative are blocked from player-market listings and bank sales. This protects the server economy from Creative-generated resources.

### Why was my requested bank amount reduced?

The bank limits the transaction to the amount in your hand, your remaining daily limit, available stock, available balance, and available inventory space as applicable.

### Why did my purchase order reserve money immediately?

The money is reserved to guarantee that a seller will be paid when the order is fulfilled. Cancelling the order returns the reservation.

### Why can’t I partially fulfill a purchase order?

Purchase orders require the full requested quantity in this version.

### Why do I not see direct trading on Folia?

Direct player-to-player trading is disabled in the Folia build.
