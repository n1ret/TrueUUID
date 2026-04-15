package cn.alini.trueuuid.server;

import java.util.List;

import cn.alini.trueuuid.Trueuuid;
import cn.alini.trueuuid.config.TrueuuidConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Refresh the skin after login, and notify the player when "offline fallback" occurs;
 * also display a screen title indicating the current mode.
 */
@Mod.EventBusSubscriber(modid = Trueuuid.MODID)
public class SkinRefreshHandler {
    private static final int SUBTITLE_MAX_CHARS = 64; // Protection: truncate if too long to avoid overflow

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        var server = sp.getServer();
        if (server == null) return;

        // 1) Refresh skin one tick after login (force client to re-fetch skin)
        server.execute(() -> {
            var list = server.getPlayerList();
            list.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(sp.getUUID())));
            list.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(sp)));
        });

        // 2) Check if offline fallback is active, and send chat notification + screen title (subtitle uses short text)
        var netConn = sp.connection.connection;
        var fallbackOpt = AuthState.consume(netConn);

        if (fallbackOpt.isPresent()) {
            // Title: red "Offline Mode", subtitle short text (yellow)
            var title = Component.literal("Offline Mode").withStyle(ChatFormatting.RED);
            String shortSubtitle = TrueuuidConfig.offlineShortSubtitle();
            var subtitle = Component.literal(clamp(shortSubtitle, SUBTITLE_MAX_CHARS)).withStyle(ChatFormatting.YELLOW);

            sp.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 10));
            sp.connection.send(new ClientboundSetTitleTextPacket(title));
            sp.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        } else {
            // Premium mode: green title, subtitle short text (gray)
            var title = Component.literal("Premium Mode").withStyle(ChatFormatting.GREEN);
            String shortSubtitle = TrueuuidConfig.onlineShortSubtitle();
            var subtitle = Component.literal(clamp(shortSubtitle, SUBTITLE_MAX_CHARS)).withStyle(ChatFormatting.GRAY);

            sp.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 10));
            sp.connection.send(new ClientboundSetTitleTextPacket(title));
            sp.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}