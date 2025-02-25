/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client.model.generators.loaders;

import com.google.common.base.Preconditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.generators.CustomLoaderBuilder;
import net.neoforged.neoforge.client.model.generators.ModelBuilder;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class CompositeModelBuilder<T extends ModelBuilder<T>> extends CustomLoaderBuilder<T> {
    public static <T extends ModelBuilder<T>> CompositeModelBuilder<T> begin(T parent, ExistingFileHelper existingFileHelper) {
        return new CompositeModelBuilder<>(parent, existingFileHelper);
    }

    private final Map<String, T> childModels = new LinkedHashMap<>();
    private final List<String> itemRenderOrder = new ArrayList<>();

    protected CompositeModelBuilder(T parent, ExistingFileHelper existingFileHelper) {
        super(ResourceLocation.fromNamespaceAndPath("neoforge", "composite"), parent, existingFileHelper, false);
    }

    public CompositeModelBuilder<T> child(String name, T modelBuilder) {
        Preconditions.checkNotNull(name, "name must not be null");
        Preconditions.checkNotNull(modelBuilder, "modelBuilder must not be null");
        childModels.put(name, modelBuilder);
        itemRenderOrder.add(name);
        return this;
    }

    public CompositeModelBuilder<T> itemRenderOrder(String... names) {
        Preconditions.checkNotNull(names, "names must not be null");
        Preconditions.checkArgument(names.length > 0, "names must contain at least one element");
        for (String name : names)
            if (!childModels.containsKey(name))
                throw new IllegalArgumentException("names contains \"" + name + "\", which is not a child of this model");
        itemRenderOrder.clear();
        itemRenderOrder.addAll(Arrays.asList(names));
        return this;
    }

    @Override
    public JsonObject toJson(JsonObject json) {
        json = super.toJson(json);

        JsonObject children = new JsonObject();
        for (Map.Entry<String, T> entry : childModels.entrySet()) {
            children.add(entry.getKey(), entry.getValue().toJson());
        }
        json.add("children", children);

        JsonArray itemRenderOrder = new JsonArray();
        for (String name : this.itemRenderOrder) {
            itemRenderOrder.add(name);
        }
        json.add("item_render_order", itemRenderOrder);

        return json;
    }
}
