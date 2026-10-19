package com.velocitypowered.crossstitch.mixin.command;

import com.google.common.base.Suppliers;
import com.mojang.brigadier.arguments.ArgumentType;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntObjectPair;
import net.minecraft.command.argument.serialize.ArgumentSerializer;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

@Mixin(targets = "net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket$ArgumentNode")
public class CommandTreeSerializationMixin {
    @Unique
    private static final Set<String> BUILT_IN_REGISTRY_KEYS = Set.of("minecraft", "brigadier");
    @Unique
    private static final int MOD_ARGUMENT_INDICATOR = -256;

    @Unique
    private static final Supplier<OptionalInt> FIRST_MOD_ARGUMENT_ID = Suppliers.memoize(() ->
            Registries.COMMAND_ARGUMENT_TYPE.stream()
                .map(sr -> IntObjectPair.of(Registries.COMMAND_ARGUMENT_TYPE.getRawId(sr), sr))
                .sorted(Comparator.comparingInt(IntObjectPair::firstInt))
                .filter(p -> Registries.COMMAND_ARGUMENT_TYPE.getKey(p.second()).filter(k -> !BUILT_IN_REGISTRY_KEYS.contains(k.getValue().getNamespace())).isPresent())
                .mapToInt(IntObjectPair::firstInt)
                .findFirst()
        );

    @Inject(method = "write(Lnet/minecraft/network/PacketByteBuf;Lnet/minecraft/command/argument/serialize/ArgumentSerializer;Lnet/minecraft/command/argument/serialize/ArgumentSerializer$ArgumentTypeProperties;)V",
            at = @At("HEAD"), cancellable = true)
    private static <A extends ArgumentType<?>, T extends ArgumentSerializer.ArgumentTypeProperties<A>> void writeNode$wrapInVelocityModArgument(PacketByteBuf buf, ArgumentSerializer<A, T> serializer, ArgumentSerializer.ArgumentTypeProperties<A> properties, CallbackInfo ci) {
        OptionalInt firstModArgument = FIRST_MOD_ARGUMENT_ID.get();
        boolean isModArgument = firstModArgument.isPresent() && Registries.COMMAND_ARGUMENT_TYPE.getRawId(serializer) >= firstModArgument.getAsInt();

        if (isModArgument) {
            // Not a standard Minecraft argument type - so we need to wrap it
            ci.cancel();
            serializeWrappedArgumentType(buf, serializer, properties);
        }
    }

    @Unique
    private static <A extends ArgumentType<?>, T extends ArgumentSerializer.ArgumentTypeProperties<A>> void serializeWrappedArgumentType(PacketByteBuf packetByteBuf, ArgumentSerializer<A, T> serializer, ArgumentSerializer.ArgumentTypeProperties<A> properties) {
        packetByteBuf.writeVarInt(MOD_ARGUMENT_INDICATOR);
        packetByteBuf.writeVarInt(Registries.COMMAND_ARGUMENT_TYPE.getRawId(serializer));

        PacketByteBuf extraData = new PacketByteBuf(Unpooled.buffer());
        serializer.writePacket((T) properties, extraData);

        packetByteBuf.writeVarInt(extraData.readableBytes());
        packetByteBuf.writeBytes(extraData);
    }
}
