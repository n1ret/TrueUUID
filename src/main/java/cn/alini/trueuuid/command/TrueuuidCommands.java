package cn.alini.trueuuid.command;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import cn.alini.trueuuid.Trueuuid;
import cn.alini.trueuuid.config.TrueuuidConfig;
import cn.alini.trueuuid.server.NameRegistry;
import cn.alini.trueuuid.server.TrueuuidRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod.EventBusSubscriber(modid = Trueuuid.MODID)
public class TrueuuidCommands {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("trueuuid")
                .requires(src -> src.hasPermission(3))
            // Added: /trueuuid mojang status
                .then(Commands.literal("config")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.literal("nomojang")
                                .then(Commands.literal("status")
                                        .executes(ctx -> cmdNomojangStatus(ctx.getSource()))
                                )
                                .then(Commands.literal("on")
                                        .executes(ctx -> cmdNomojangSet(ctx.getSource(), true))
                                )
                                .then(Commands.literal("off")
                                        .executes(ctx -> cmdNomojangSet(ctx.getSource(), false))
                                )
                                .then(Commands.literal("toggle")
                                        .executes(ctx -> cmdNomojangToggle(ctx.getSource()))
                                )
                        )
                )
                .then(Commands.literal("mojang")
                        .then(Commands.literal("status")
                                .executes(ctx -> mojangStatus(ctx.getSource()))
                        )
                )
                .then(Commands.literal("link")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        true, true, true, true, true)))
                        .then(Commands.literal("run")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                false, true, true, true, true))))
                        .then(Commands.literal("dryrun")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                true, true, true, true, true))))
                )
                .then(Commands.literal("reload")
                        .executes(ctx -> cmdConfigReload(ctx.getSource()))
                )
        );



    }

    // Added method: reload config from disk at runtime and write into TrueuuidConfig.COMMON.
    private static int cmdConfigReload(CommandSourceStack src) {
        try {
            Path cfgPath = FMLPaths.CONFIGDIR.get().resolve("trueuuid-common.toml");
            CommentedFileConfig cfg = CommentedFileConfig.builder(cfgPath)
                    .sync() // Keep synchronized with disk
                    .autosave()
                    .build();
            cfg.load();

                // Helper lookup: prefer auth.xxx, then try key without auth prefix for compatibility.
            java.util.function.BiFunction<String, String, Object> getVal = (authKey, altKey) -> {
                if (cfg.contains(authKey)) return cfg.get(authKey);
                if (altKey != null && cfg.contains(altKey)) return cfg.get(altKey);
                return null;
            };

                // Boolean fields
            Object v;
            v = getVal.apply("auth.nomojang.enabled", "nomojang.enabled");
            if (v instanceof Boolean) TrueuuidConfig.COMMON.nomojangEnabled.set((Boolean) v);

            v = getVal.apply("auth.debug", "debug");
            if (v instanceof Boolean) TrueuuidConfig.COMMON.debug.set((Boolean) v);

            v = getVal.apply("auth.recentIpGrace.enabled", "recentIpGrace.enabled");
            if (v instanceof Boolean) TrueuuidConfig.COMMON.recentIpGraceEnabled.set((Boolean) v);

            v = getVal.apply("auth.knownPremiumDenyOffline", "knownPremiumDenyOffline");
            if (v instanceof Boolean) TrueuuidConfig.COMMON.knownPremiumDenyOffline.set((Boolean) v);

            v = getVal.apply("auth.allowOfflineForUnknownOnly", "allowOfflineForUnknownOnly");
            if (v instanceof Boolean) TrueuuidConfig.COMMON.allowOfflineForUnknownOnly.set((Boolean) v);

            v = getVal.apply("auth.allowOfflineOnTimeout", "allowOfflineOnTimeout");
            if (v instanceof Boolean) TrueuuidConfig.COMMON.allowOfflineOnTimeout.set((Boolean) v);

            v = getVal.apply("auth.allowOfflineOnFailure", "allowOfflineOnFailure");
            if (v instanceof Boolean) TrueuuidConfig.COMMON.allowOfflineOnFailure.set((Boolean) v);

            // Numeric fields
            v = getVal.apply("auth.timeoutMs", "timeoutMs");
            if (v instanceof Number) TrueuuidConfig.COMMON.timeoutMs.set(((Number) v).longValue());

            v = getVal.apply("auth.recentIpGrace.ttlSeconds", "recentIpGrace.ttlSeconds");
            if (v instanceof Number) TrueuuidConfig.COMMON.recentIpGraceTtlSeconds.set(((Number) v).intValue());

            // String fields
            v = getVal.apply("auth.timeoutKickMessage", "timeoutKickMessage");
            if (v != null) TrueuuidConfig.COMMON.timeoutKickMessage.set(String.valueOf(v));

            v = getVal.apply("auth.offlineFallbackMessage", "offlineFallbackMessage");
            if (v != null) TrueuuidConfig.COMMON.offlineFallbackMessage.set(String.valueOf(v));

            v = getVal.apply("auth.offlineShortSubtitle", "offlineShortSubtitle");
            if (v != null) TrueuuidConfig.COMMON.offlineShortSubtitle.set(String.valueOf(v));

            v = getVal.apply("auth.onlineShortSubtitle", "onlineShortSubtitle");
            if (v != null) TrueuuidConfig.COMMON.onlineShortSubtitle.set(String.valueOf(v));

            // Completion feedback
            src.sendSuccess(() -> Component.literal("[TrueUUID] Config reloaded from disk").withStyle(net.minecraft.ChatFormatting.GREEN), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("[TrueUUID] Failed to reload config: " + ex.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
            Trueuuid.debug(ex, "Failed to reload trueuuid-common.toml");
            return 0;
        }
    }

    // The following methods belong to `TrueuuidCommands` (same file).
    private static int cmdNomojangStatus(CommandSourceStack src) {
        boolean enabled = TrueuuidConfig.nomojangEnabled();
        if (enabled) {
            src.sendSuccess(() -> Component.literal("[TrueUUID] NoMojang: enabled").withStyle(net.minecraft.ChatFormatting.GREEN), false);
        } else {
            src.sendSuccess(() -> Component.literal("[TrueUUID] NoMojang: disabled").withStyle(net.minecraft.ChatFormatting.RED), false);
        }
        return 1;
    }

    private static int cmdNomojangSet(CommandSourceStack src, boolean value) {
        try {
            TrueuuidConfig.COMMON.nomojangEnabled.set(value);
            // Runtime update feedback
            src.sendSuccess(() -> Component.literal("[TrueUUID] NoMojang " + (value ? "enabled" : "disabled"))
                    .withStyle(value ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED), false);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("[TrueUUID] Unable to set NoMojang: " + t.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
            Trueuuid.debug(t, "Failed to set NoMojang");
            return 0;
        }
    }

    private static int cmdNomojangToggle(CommandSourceStack src) {
        boolean current = TrueuuidConfig.nomojangEnabled();
        return cmdNomojangSet(src, !current);
    }

    private static int mojangStatus(CommandSourceStack src) {
        try {
            String testUrl = TrueuuidConfig.COMMON.mojangReverseProxy.get()+"/session/minecraft/hasJoined?username=Mojang&serverId=test";
            java.net.URL url = new java.net.URL(testUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 204 || responseCode == 403) {
                src.sendSuccess(() -> Component.literal("[TrueUUID] Mojang session server is reachable, status code: " + responseCode)
                        .withStyle(net.minecraft.ChatFormatting.GREEN), false);
            } else {
                src.sendFailure(Component.literal("[TrueUUID] Unexpected Mojang session server response, status code: " + responseCode)
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[TrueUUID] Unable to connect to Mojang session server: " + e.getMessage())
                    .withStyle(net.minecraft.ChatFormatting.RED));
            Trueuuid.debug(e, "Mojang session server connectivity check failed");
            return 0;
        }
    }

    private static int run(CommandSourceStack src, String name,
                           boolean dryRun, boolean backup,
                           boolean mergeInv, boolean mergeEnder, boolean mergeStats) {
        MinecraftServer server = src.getServer();

        Optional<NameRegistry.Entry> reg = getEntry(name);
        if (reg.isEmpty()) {
            src.sendFailure(Component.literal("No premium record found in registry for name: " + name));
            return 0;
        }
        UUID premium = reg.get().premiumUuid;
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path adv = worldRoot.resolve("advancements");
        Path stats = worldRoot.resolve("stats");

        Path premDat = playerData.resolve(premium + ".dat");
        Path offDat = playerData.resolve(offline + ".dat");
        Path premAdv = adv.resolve(premium + ".json");
        Path offAdv = adv.resolve(offline + ".json");
        Path premStats = stats.resolve(premium + ".json");
        Path offStats = stats.resolve(offline + ".json");

        src.sendSuccess(() -> Component.literal(
                "[TrueUUID] link " + (dryRun ? "(dry-run)" : "(run)") + " name=" + name +
                        "\n premium=" + premium + "\n offline=" + offline +
                        "\n files:\n  " + offDat + " -> " + premDat +
                        "\n  " + offAdv + " -> " + premAdv +
                        "\n  " + offStats + " -> " + premStats
        ), false);

        if (dryRun) return 1;

        try {
            if (backup) {
                String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
                Path backupDir = worldRoot.resolve("backups/trueuuid/" + ts + "/" + name.toLowerCase(Locale.ROOT));
                Files.createDirectories(backupDir);
                copyIfExists(premDat, backupDir.resolve("premium.dat"));
                copyIfExists(offDat, backupDir.resolve("offline.dat"));
                copyIfExists(premAdv, backupDir.resolve("premium.adv.json"));
                copyIfExists(offAdv, backupDir.resolve("offline.adv.json"));
                copyIfExists(premStats, backupDir.resolve("premium.stats.json"));
                copyIfExists(offStats, backupDir.resolve("offline.stats.json"));
            }

            // ==== NBT merge implementation ====
            if (Files.exists(offDat)) {
                if (!Files.exists(premDat)) {
                    Files.move(offDat, premDat, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // Merge NBT (inventory / ender chest)
                    mergePlayerDatNBT(premDat, offDat, mergeInv, mergeEnder);
                }
            }
            // ==== advancements merge implementation ====
            if (Files.exists(offAdv)) {
                if (!Files.exists(premAdv)) {
                    Files.move(offAdv, premAdv, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    mergeAdvancementsJson(premAdv, offAdv);
                }
            }
            // ==== stats merge implementation ====
            if (Files.exists(offStats)) {
                if (!Files.exists(premStats)) {
                    Files.move(offStats, premStats, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    mergeStatsJson(premStats, offStats);
                }
            }

            src.sendSuccess(() -> Component.literal("Done. We recommend the player log in with a premium account next time to verify data."), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Failed: " + ex.getMessage()));
            Trueuuid.error(ex, "trueuuid link execution failed, name={}", name);
            return 0;
        }
    }

    private static Optional<NameRegistry.Entry> getEntry(String name) {
        // Used only for command display of premium UUID; can be replaced with getPremiumUuid directly.
        try {
            var f = NameRegistry.class.getDeclaredField("map");
            f.setAccessible(true);
        } catch (Throwable t) {
            Trueuuid.debug(t, "Failed to read NameRegistry.map via reflection (safe to ignore)");
        }
        // Simplified: reuse getPremiumUuid and build an Entry.
        return TrueuuidRuntime.NAME_REGISTRY.getPremiumUuid(name).map(u -> {
            NameRegistry.Entry e = new NameRegistry.Entry();
            e.premiumUuid = u;
            return e;
        });
    }

    private static void copyIfExists(Path from, Path to) throws Exception {
        if (Files.exists(from)) {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- NBT merge: inventory / ender chest ---
    private static void mergePlayerDatNBT(Path premDat, Path offDat, boolean mergeInv, boolean mergeEnder) throws Exception {
        CompoundTag prem, off;
        try (InputStream is = Files.newInputStream(premDat)) {
            prem = NbtIo.readCompressed(is);
        }
        try (InputStream is = Files.newInputStream(offDat)) {
            off = NbtIo.readCompressed(is);
        }

        boolean changed = false;
        if (mergeInv) {
            changed |= mergeItemListTag(prem, off, "Inventory");
        }
        if (mergeEnder) {
            changed |= mergeItemListTag(prem, off, "EnderItems");
        }

        if (changed) {
            try (OutputStream os = Files.newOutputStream(premDat, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                NbtIo.writeCompressed(prem, os);
            }
        }
    }

    // Merge rule: premium takes precedence; append offline-only items without overwriting slots.
    private static boolean mergeItemListTag(CompoundTag prem, CompoundTag off, String key) {
        if (!prem.contains(key) || !off.contains(key)) return false;
        ListTag premList = prem.getList(key, 10); // 10: CompoundTag
        ListTag offList = off.getList(key, 10);
        // Use slot number as the key.
        Set<Integer> premSlots = new HashSet<>();
        for (int i = 0; i < premList.size(); ++i) {
            CompoundTag tag = premList.getCompound(i);
            if (tag.contains("Slot")) premSlots.add((int) tag.getByte("Slot"));
        }
        boolean changed = false;
        for (int i = 0; i < offList.size(); ++i) {
            CompoundTag tag = offList.getCompound(i);
            if (tag.contains("Slot")) {
                int slot = tag.getByte("Slot");
                if (!premSlots.contains(slot)) {
                    premList.add(tag.copy());
                    changed = true;
                }
            }
        }
        if (changed) {
            prem.put(key, premList);
        }
        return changed;
    }

    // --- advancements merge: union ---
    private static void mergeAdvancementsJson(Path premAdv, Path offAdv) throws Exception {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject prem, off;
        try (Reader r = Files.newBufferedReader(premAdv)) {
            prem = gson.fromJson(r, JsonObject.class);
        }
        try (Reader r = Files.newBufferedReader(offAdv)) {
            off = gson.fromJson(r, JsonObject.class);
        }
        boolean changed = false;
        for (String key : off.keySet()) {
            if (!prem.has(key)) {
                prem.add(key, off.get(key));
                changed = true;
            }
        }
        if (changed) {
            try (Writer w = Files.newBufferedWriter(premAdv, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                gson.toJson(prem, w);
            }
        }
    }

    // More robust mergeStatsJson implementation to handle JsonElement type differences and avoid ClassCastException.
    private static void mergeStatsJson(Path premStats, Path offStats) throws Exception {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject prem, off;
        try (Reader r = Files.newBufferedReader(premStats)) {
            prem = gson.fromJson(r, JsonObject.class);
        }
        try (Reader r = Files.newBufferedReader(offStats)) {
            off = gson.fromJson(r, JsonObject.class);
        }
        boolean changed = false;

        for (String cat : off.keySet()) {
            JsonElement offElem = off.get(cat);
            // If premium does not contain this category, copy the whole element directly.
            if (!prem.has(cat)) {
                prem.add(cat, offElem);
                changed = true;
                continue;
            }

            JsonElement premElem = prem.get(cat);

            // Both are objects -> merge per key (sum numeric values, keep premium for non-numeric).
            if (offElem.isJsonObject() && premElem.isJsonObject()) {
                JsonObject offCat = offElem.getAsJsonObject();
                JsonObject premCat = premElem.getAsJsonObject();
                for (String key : offCat.keySet()) {
                    JsonElement offVal = offCat.get(key);
                    if (!premCat.has(key)) {
                        premCat.add(key, offVal);
                        changed = true;
                    } else {
                        JsonElement premVal = premCat.get(key);
                        // Try to sum primitive numeric values.
                        if (premVal.isJsonPrimitive() && offVal.isJsonPrimitive()) {
                            JsonPrimitive pPri = premVal.getAsJsonPrimitive();
                            JsonPrimitive oPri = offVal.getAsJsonPrimitive();
                            if (pPri.isNumber() && oPri.isNumber()) {
                                try {
                                    long a = pPri.getAsLong();
                                    long b = oPri.getAsLong();
                                    premCat.addProperty(key, a + b);
                                    changed = true;
                                } catch (Exception ignored) {
                                    // If long addition fails, keep premium value unchanged.
                                }
                            }
                        }
                        // For other types (array/object/non-numeric primitive), keep premium value.
                    }
                }
                prem.add(cat, premCat);
            } else {
                // Types differ or neither is an object:
                // if both are numeric primitives, try to sum (for uncommon numeric stats).
                if (premElem.isJsonPrimitive() && offElem.isJsonPrimitive()) {
                    JsonPrimitive pPri = premElem.getAsJsonPrimitive();
                    JsonPrimitive oPri = offElem.getAsJsonPrimitive();
                    if (pPri.isNumber() && oPri.isNumber()) {
                        try {
                            long a = pPri.getAsLong();
                            long b = oPri.getAsLong();
                            prem.addProperty(cat, a + b);
                            changed = true;
                        } catch (Exception ignored) {
                            // Keep premium if not addable.
                        }
                    }
                }
                // Otherwise, keep existing premium value when types mismatch.
            }
        }

        if (changed) {
            try (Writer w = Files.newBufferedWriter(premStats, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                gson.toJson(prem, w);
            }
        }

    }
}
