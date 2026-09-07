package com.example.cc_hse;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import com.example.cc_hse.block.CcHseBlock;
import com.example.cc_hse.block.CcHseBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CcHseMod.MOD_ID)
public final class CcHseMod {
    public static final String MOD_ID = "cc_hse";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);

    public static final DeferredBlock<CcHseBlock> CC_HSE_BLOCK =
        BLOCKS.register("cc_hse", () -> new CcHseBlock());
    public static final DeferredItem<BlockItem> CC_HSE_ITEM =
        ITEMS.registerSimpleBlockItem("cc_hse", CC_HSE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CcHseBlockEntity>> CC_HSE_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("cc_hse", () ->
            BlockEntityType.Builder.of(CcHseBlockEntity::new, CC_HSE_BLOCK.get()).build(null));

    public CcHseMod(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);

        modBus.addListener(this::addCreative);
        modBus.addListener(this::registerCapabilities);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), CC_HSE_BLOCK_ENTITY.get(), (be, side) -> be.createPeripheral());
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(CC_HSE_ITEM);
        }
    }
}
