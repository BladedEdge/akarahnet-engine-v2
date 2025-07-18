package dev.akarah.cdata.registry.item.value;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;

public record CustomData(Dynamic<?> value) {
    public static Codec<CustomData> CODEC = Codec.PASSTHROUGH.xmap(CustomData::new, CustomData::value);
}
