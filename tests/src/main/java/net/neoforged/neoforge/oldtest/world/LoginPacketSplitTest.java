/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.oldtest.world;

import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.GsonHelper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A test mod used to test splitting the {@link net.minecraft.network.protocol.game.ClientboundLoginPacket}. <br>
 * In order to test this works, first {@link #ENABLED enable} the packet.
 * Start a local server and client. In the server console you should see how big the
 * registryaccess in the packet would be, and how much {@code %} of the packet limit is represents. <br>
 * Connect to the server from the client, and if you successfully connect and the {@code /big_data} command
 * reports 50000 entries then the packet has been successfully split. <br> <br>
 * To test if the packet is too large simply remove the login packet from the {@link net.neoforged.neoforge.network.filters.GenericPacketSplitter}
 * and try connecting again. You should see the connection fail.
 */

@Mod(LoginPacketSplitTest.MOD_ID)
public class LoginPacketSplitTest {
    public static final Logger LOG = LogUtils.getLogger();
    public static final String MOD_ID = "login_packet_split_test";
    public static final boolean ENABLED = true;
    private static final Gson GSON = new Gson();
    public static final ResourceKey<Registry<BigData>> BIG_DATA = ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(MOD_ID, "big_data"));

    public LoginPacketSplitTest(IEventBus bus) {
        bus.addListener((final DataPackRegistryEvent.NewRegistry event) -> event.dataPackRegistry(BIG_DATA, BigData.CODEC, BigData.CODEC));
        if (ENABLED) {
            bus.addListener((final AddPackFindersEvent event) -> {
                if (event.getPackType() == PackType.SERVER_DATA) {
                    // This pack info has no KnownPack, so it is actually synced
                    PackLocationInfo info = new PackLocationInfo("virtual_bigdata", Component.literal("Pack containing big datapack registries"), PackSource.BUILT_IN, Optional.empty());
                    final InMemoryResourcePack pack = new InMemoryResourcePack(info);
                    generateEntries(pack);
                    event.addRepositorySource(packs -> packs.accept(Pack.readMetaAndCreate(
                            pack.location(),
                            BuiltInPackSource.fixedResources(pack),
                            PackType.SERVER_DATA,
                            new PackSelectionConfig(true, Pack.Position.TOP, false))));
                }
            });

            if (FMLLoader.getDist().isClient()) {
                NeoForge.EVENT_BUS.addListener((final RegisterClientCommandsEvent event) -> event.getDispatcher().register(Commands.literal("big_data")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal("Registry has " + context.getSource().registryAccess().registryOrThrow(BIG_DATA).holders().count() + " entries."), true);
                            return Command.SINGLE_SUCCESS;
                        })));
            }
        }
    }

    private void generateEntries(InMemoryResourcePack pack) {
        final Stopwatch stopwatch = Stopwatch.createUnstarted();
        final Registry<BigData> dummyRegistry = new MappedRegistry<>(BIG_DATA, Lifecycle.stable(), false);
        final Random random = new Random();

        stopwatch.start();
        for (int i = 0; i < 50_000; i++) {
            final BigData bigData = new BigData(randomString(random, 30 + random.nextInt(10)).repeat(15), random.nextInt(Integer.MAX_VALUE));
            final JsonObject json = new JsonObject();
            json.addProperty("text", bigData.text);
            json.addProperty("number", bigData.number);
            pack.putData(ResourceLocation.fromNamespaceAndPath(MOD_ID, MOD_ID + "/big_data/entry_" + i + ".json"), json);
            Registry.register(dummyRegistry, ResourceLocation.fromNamespaceAndPath(MOD_ID, MOD_ID + "/big_data/entry_" + i), bigData);
        }
        stopwatch.stop();
        LOG.warn("Setting up big data registry took " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " miliseconds.");

        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        dummyRegistry.holders().forEach(ref -> {
            buf.writeUtf(ref.getRegisteredName());
            buf.writeJsonWithCodec(BigData.CODEC, ref.value());
        });

        final int size = buf.writerIndex();
        LOG.warn("Dummy big registry size: " + size + ", or " + ((double) size / CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH * 100) + "% of the maximum packet size.");
    }

    private String randomString(Random random, int length) {
        return random.ints(97, 122 + 1) // letter 'a' to letter 'z'
                .limit(length)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    public record BigData(String text, int number) {
        public static final Codec<BigData> CODEC = RecordCodecBuilder.create(in -> in.group(
                Codec.STRING.fieldOf("text").forGetter(BigData::text),
                Codec.INT.fieldOf("number").forGetter(BigData::number)).apply(in, BigData::new));
    }

    public static final class InMemoryResourcePack implements PackResources {
        private final Map<ResourceLocation, Supplier<byte[]>> data = new ConcurrentHashMap<>();
        private final Map<String, Supplier<byte[]>> root = new ConcurrentHashMap<>();

        private final PackLocationInfo info;

        public InMemoryResourcePack(PackLocationInfo info) {
            this.info = info;

            final JsonObject mcmeta = new JsonObject();
            final JsonObject packJson = new JsonObject();
            packJson.addProperty("description", "A virtual resource pack.");
            packJson.addProperty("pack_format", SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
            mcmeta.add("pack", packJson);

            putRoot("pack.mcmeta", mcmeta);
        }

        @Nullable
        @Override
        public IoSupplier<InputStream> getRootResource(String... loc) {
            return openResource(this.root, String.join("/", loc));
        }

        @Nullable
        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation loc) {
            if (type != PackType.SERVER_DATA) return null;
            return openResource(data, loc);
        }

        private <T> @Nullable IoSupplier<InputStream> openResource(Map<T, Supplier<byte[]>> map, T key) {
            final Supplier<byte[]> supplier = map.get(key);
            if (supplier == null) {
                return null;
            }
            final byte[] bytes = supplier.get();
            if (bytes == null) {
                return null;
            }
            return () -> new ByteArrayInputStream(bytes);
        }

        @Override
        public void listResources(PackType type, String namespace, String startingPath, ResourceOutput out) {
            if (type != PackType.SERVER_DATA) return;
            data.forEach((key, data) -> {
                if (key.getNamespace().equals(namespace) && key.getPath().startsWith(startingPath + "/")) {
                    final byte[] bytes = data.get();
                    if (bytes != null) {
                        out.accept(key, () -> new ByteArrayInputStream(bytes));
                    }
                }
            });
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES ? Set.of()
                    : data.keySet().stream().map(ResourceLocation::getNamespace)
                            .collect(Collectors.toUnmodifiableSet());
        }

        @Nullable
        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> section) throws IOException {
            final JsonObject json = GsonHelper.parse(new String(root.get("pack.mcmeta").get()));
            if (!json.has(section.getMetadataSectionName())) {
                return null;
            } else {
                return section.fromJson(GsonHelper.getAsJsonObject(json, section.getMetadataSectionName()));
            }
        }

        @Override
        public PackLocationInfo location() {
            return info;
        }

        @Override
        public void close() {}

        public void putRoot(String path, JsonObject json) {
            final byte[] bytes = fromJson(json);
            putRoot(path, () -> bytes);
        }

        public void putRoot(String path, Supplier<byte[]> data) {
            root.put(path, data);
        }

        public void putData(ResourceLocation path, JsonObject json) {
            final byte[] bytes = fromJson(json);
            putData(path, () -> bytes);
        }

        public void putData(ResourceLocation path, Supplier<byte[]> data) {
            this.data.put(path, data);
        }

        public static byte[] fromJson(JsonElement json) {
            return GsonHelper.toStableString(json).getBytes(StandardCharsets.UTF_8);
        }
    }

    public <T> void writeJsonWithCodec(FriendlyByteBuf buf, Codec<T> codec, T instance) {
        DataResult<JsonElement> dataresult = codec.encodeStart(JsonOps.INSTANCE, instance);
        final String s = GSON.toJson(dataresult.getOrThrow(error -> new EncoderException("Failed to encode: " + error + " " + instance)));
        buf.writeVarInt(s.length());
        buf.writeUtf(s, s.length());
    }
}
