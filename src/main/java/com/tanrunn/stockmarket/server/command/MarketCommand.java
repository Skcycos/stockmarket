package com.tanrunn.stockmarket.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tanrunn.stockmarket.api.StockMarketApi;
import com.tanrunn.stockmarket.server.market.AccountService;
import com.tanrunn.stockmarket.server.market.MarketService;
import com.tanrunn.stockmarket.server.market.Stock;
import com.tanrunn.stockmarket.server.market.StockRegistry;
import com.tanrunn.stockmarket.server.market.TradeEngine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.text.DecimalFormat;

public final class MarketCommand {
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    private MarketCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("market")
                .executes(ctx -> openPanel(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> list(ctx)))
                .then(Commands.literal("account").executes(ctx -> account(ctx)))
                .then(Commands.literal("buy")
                        .then(Commands.argument("stock", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(stockIds(), builder))
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                        .executes(ctx -> trade(ctx, true)))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("stock", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(stockIds(), builder))
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                        .executes(ctx -> trade(ctx, false)))))
                .then(Commands.literal("order")
                        .then(Commands.literal("buy")
                                .then(Commands.argument("stock", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(stockIds(), builder))
                                        .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> placeOrder(ctx, true))))))
                        .then(Commands.literal("sell")
                                .then(Commands.argument("stock", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(stockIds(), builder))
                                        .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> placeOrder(ctx, false))))))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("orderId", LongArgumentType.longArg(1))
                                        .executes(ctx -> cancelOrder(ctx))))
                        .then(Commands.literal("list").executes(ctx -> myOrders(ctx))))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> reload(ctx)))
                .then(Commands.literal("reset")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> reset(ctx))))
                .then(Commands.literal("setprice")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("stock", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(stockIds(), builder))
                                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> setPrice(ctx))))));
    }

    private static java.util.List<String> stockIds() {
        return StockRegistry.get().definitions().stream().map(StockRegistry.Definition::id).toList();
    }

    private static int openPanel(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        StockMarketApi.openPanel(player);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§e=== 股市行情 ===");
        for (var info : MarketService.get().snapshot()) {
            double change = info.changePct();
            String color = change >= 0 ? "§a" : "§c";
            sb.append("\n§f").append(info.name()).append(" §7(").append(info.id()).append(")§r  ")
                    .append(MONEY.format(info.price()))
                    .append(color).append(" (").append(String.format("%+.2f%%", change)).append(")§r")
                    .append("  §7量 ").append(info.volume());
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int account(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var info = MarketService.get().accountInfo(player);
        StringBuilder sb = new StringBuilder("§e=== 我的账户 ===");
        sb.append("\n§6余银§r：").append(MONEY.format(info.cash()));
        sb.append("  §6家底§r：").append(MONEY.format(info.totalValue()));
        if (!info.holdings().isEmpty()) {
            sb.append("\n§7持股§r：");
            info.holdings().forEach((id, qty) -> {
                Stock stock = MarketService.get().stock(id);
                String name = stock != null ? stock.name() : id;
                sb.append("\n  ").append(name).append(" × ").append(qty);
            });
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int trade(CommandContext<CommandSourceStack> ctx, boolean buy) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String stockId = StringArgumentType.getString(ctx, "stock");
        int quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        TradeEngine.Result result = MarketService.get().trade(player, stockId, quantity, buy);
        if (result.success()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + result.message()), false);
            MarketService.get().sendSnapshot(player, false, result.message());
        } else {
            ctx.getSource().sendFailure(Component.literal(result.message()));
        }
        return result.success() ? 1 : 0;
    }

    private static int placeOrder(CommandContext<CommandSourceStack> ctx, boolean buy) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String stockId = StringArgumentType.getString(ctx, "stock");
        double price = DoubleArgumentType.getDouble(ctx, "price");
        int quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        TradeEngine.Result result = MarketService.get().placeOrder(player, stockId, buy, price, quantity);
        if (result.success()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + result.message()), false);
        } else {
            ctx.getSource().sendFailure(Component.literal(result.message()));
        }
        return result.success() ? 1 : 0;
    }

    private static int cancelOrder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long orderId = LongArgumentType.getLong(ctx, "orderId");
        TradeEngine.Result result = MarketService.get().cancelOrder(player, orderId);
        if (result.success()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + result.message()), false);
        } else {
            ctx.getSource().sendFailure(Component.literal(result.message()));
        }
        return result.success() ? 1 : 0;
    }

    private static int myOrders(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var orders = MarketService.get().accountInfo(player).orders();
        if (orders.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7暂无挂单"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§e=== 我的挂单 ===");
        for (var order : orders) {
            sb.append("\n§f#").append(order.orderId())
                    .append(" ").append(order.buy() ? "§a买" : "§c卖")
                    .append("§r ").append(order.stockId())
                    .append(" × ").append(order.quantity())
                    .append(" §7@" + MONEY.format(order.price()));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        com.tanrunn.stockmarket.server.registry.StockReloader.reload(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("股票定义已重载：" + StockRegistry.get().size() + " 只"), true);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        AccountService.reset(target);
        ctx.getSource().sendSuccess(() -> Component.literal("已重置 " + target.getName().getString() + " 的账户"), true);
        return 1;
    }

    private static int setPrice(CommandContext<CommandSourceStack> ctx) {
        String stockId = StringArgumentType.getString(ctx, "stock");
        double price = DoubleArgumentType.getDouble(ctx, "price");
        if (!MarketService.get().has(stockId)) {
            ctx.getSource().sendFailure(Component.literal("未知股票：" + stockId));
            return 0;
        }
        MarketService.get().setPrice(stockId, price, ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal(stockId + " 价格已设为 " + MONEY.format(price)), true);
        return 1;
    }
}
