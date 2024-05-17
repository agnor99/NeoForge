/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.network.handlers;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;
import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.ConfigSync;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import net.neoforged.neoforge.network.payload.AdvancedContainerSetDataPayload;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;
import net.neoforged.neoforge.network.payload.AuxiliaryLightDataPayload;
import net.neoforged.neoforge.network.payload.ConfigFilePayload;
import net.neoforged.neoforge.network.payload.ExtensibleEnumDataPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistryPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistrySyncCompletedPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistrySyncStartPayload;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ClientPayloadHandler {
    private static final Set<ResourceLocation> toSynchronize = Sets.newConcurrentHashSet();
    private static final Map<ResourceLocation, RegistrySnapshot> synchronizedRegistries = Maps.newConcurrentMap();

    private ClientPayloadHandler() {}

    public static void handle(FrozenRegistryPayload payload, IPayloadContext context) {
        synchronizedRegistries.put(payload.registryName(), payload.snapshot());
        toSynchronize.remove(payload.registryName());
    }

    public static void handle(FrozenRegistrySyncStartPayload payload, IPayloadContext context) {
        toSynchronize.addAll(payload.toAccess());
        synchronizedRegistries.clear();
    }

    public static void handle(FrozenRegistrySyncCompletedPayload payload, IPayloadContext context) {
        if (!toSynchronize.isEmpty()) {
            context.disconnect(Component.translatable("neoforge.network.registries.sync.missing", toSynchronize.stream().map(Object::toString).collect(Collectors.joining(", "))));
            return;
        }

        try {
            //This method normally returns missing entries, but we just accept what the server send us and ignore the rest.
            Set<ResourceKey<?>> keysUnknownToClient = RegistryManager.applySnapshot(synchronizedRegistries, false, false);
            if (!keysUnknownToClient.isEmpty()) {
                context.disconnect(Component.translatable("neoforge.network.registries.sync.server-with-unknown-keys", keysUnknownToClient.stream().map(Object::toString).collect(Collectors.joining(", "))));
                return;
            }

            toSynchronize.clear();
            synchronizedRegistries.clear();
            context.reply(FrozenRegistrySyncCompletedPayload.INSTANCE);
        } catch (Throwable t) {
            context.disconnect(Component.translatable("neoforge.network.registries.sync.failed", t.getMessage()));
        }
    }

    public static void handle(ConfigFilePayload payload, IPayloadContext context) {
        ConfigSync.INSTANCE.receiveSyncedConfig(payload.contents(), payload.fileName());
    }

    public static void handle(AdvancedAddEntityPayload advancedAddEntityPayload, IPayloadContext context) {
        try {
            Entity entity = context.player().level().getEntity(advancedAddEntityPayload.entityId());
            if (entity instanceof IEntityWithComplexSpawn entityAdditionalSpawnData) {
                final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(advancedAddEntityPayload.customPayload()), entity.registryAccess());
                try {
                    entityAdditionalSpawnData.readSpawnData(buf);
                } finally {
                    buf.release();
                }
            }
        } catch (Throwable t) {
            context.disconnect(Component.translatable("neoforge.network.advanced_add_entity.failed", t.getMessage()));
        }
    }

    public static void handle(AdvancedOpenScreenPayload msg, IPayloadContext context) {
        Minecraft mc = Minecraft.getInstance();
        RegistryAccess registryAccess = mc.player.registryAccess();
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(msg.additionalData()), registryAccess);
        try {
            createMenuScreen(msg.name(), msg.menuType(), msg.windowId(), buf);
        } catch (Throwable t) {
            context.disconnect(Component.translatable("neoforge.network.advanced_open_screen.failed", t.getMessage()));
        } finally {
            buf.release();
        }
    }

    private static <T extends AbstractContainerMenu> void createMenuScreen(Component name, MenuType<T> menuType, int windowId, RegistryFriendlyByteBuf buf) {
        Minecraft mc = Minecraft.getInstance();
        MenuScreens.getScreenFactory(menuType).ifPresent(f -> {
            Screen s = f.create(menuType.create(windowId, mc.player.getInventory(), buf), mc.player.getInventory(), name);
            mc.player.containerMenu = ((MenuAccess<?>) s).getMenu();
            mc.setScreen(s);
        });
    }

    public static void handle(AuxiliaryLightDataPayload msg, IPayloadContext context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            AuxiliaryLightManager lightManager = mc.level.getAuxLightManager(msg.pos());
            if (lightManager instanceof LevelChunkAuxiliaryLightManager manager) {
                manager.handleLightDataSync(msg.entries());
            }
        } catch (Throwable t) {
            context.disconnect(Component.translatable("neoforge.network.aux_light_data.failed", msg.pos().toString(), t.getMessage()));
        }
    }

    public static void handle(AdvancedContainerSetDataPayload msg, IPayloadContext context) {
        context.handle(msg.toVanillaPacket());
    }

    public static void handle(ExtensibleEnumDataPayload<?> msg, IPayloadContext context) {
        enum MissMatchReason {
            MISSING_ON_CLIENT,
            ADDED_ONLY_ON_CLIENT,
            ID_MISMATCH;

            private void buildError(StringBuilder builder, List<String> errors) {
                String msg = switch (this) {
                    case ID_MISMATCH -> "idmissmatch";
                    case ADDED_ONLY_ON_CLIENT -> "clientOnly";
                    case MISSING_ON_CLIENT -> "serverOnly";
                };
                builder.append(msg).append('[').append(String.join(",", errors)).append(']');
            }
        }

        Map<Class<?>, Map<MissMatchReason, List<String>>> allErrors = new HashMap<>();
        msg.enums().forEach((enumClass, enumData) -> {
            List<String> missingOnClient = enumData.enumValues().stream().filter(value -> Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).noneMatch(value::equals)).toList();
            List<String> addedOnlyOnClient = Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).filter(value -> enumData.enumValues().stream().noneMatch(value::equals)).toList();
            List<String> idMismatch = List.of();
            if (enumData.verifyOrder()) {
                idMismatch = Arrays.stream(enumClass.getEnumConstants())
                        .map(enumValue -> new Tuple<>(enumValue, enumData.enumValues().indexOf(enumValue.name())))
                        .filter(tuple -> tuple.getA().ordinal() != tuple.getB())
                        .filter(tuple -> tuple.getB() != -1)
                        .map(tuple -> tuple.getA().name() + "[" + tuple.getB() + "->" + tuple.getA().ordinal() + "]")
                        .toList();
            }
            if (!missingOnClient.isEmpty() || !addedOnlyOnClient.isEmpty() || !idMismatch.isEmpty()) {
                Map<MissMatchReason, List<String>> errors = new HashMap<>();
                allErrors.put(enumClass, errors);
                if (!missingOnClient.isEmpty()) {
                    errors.put(MissMatchReason.MISSING_ON_CLIENT, missingOnClient);
                }
                if (!addedOnlyOnClient.isEmpty()) {
                    errors.put(MissMatchReason.ADDED_ONLY_ON_CLIENT, addedOnlyOnClient);
                }
                if (!idMismatch.isEmpty()) {
                    errors.put(MissMatchReason.ID_MISMATCH, idMismatch);
                }
            }
        });
        if (allErrors.isEmpty()) {
            return;
        }
        StringBuilder strBuilder = new StringBuilder();
        allErrors.forEach((enumClazz, errors) -> {
            strBuilder.append(enumClazz.getName());
            strBuilder.append('[');
            errors.forEach((reason, innerErrors) -> reason.buildError(strBuilder, innerErrors));
            strBuilder.append("],");
        });
        strBuilder.deleteCharAt(strBuilder.length() - 1); //remove last ','
        context.disconnect(Component.translatable("neoforge.network.extensible_enum_data.failed", strBuilder.toString()));
    }
}
