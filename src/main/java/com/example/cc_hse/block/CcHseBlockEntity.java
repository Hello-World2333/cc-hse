package com.example.cc_hse.block;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import dan200.computercraft.api.peripheral.IComputerAccess;
import com.example.cc_hse.CcHseMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CcHseBlockEntity extends BlockEntity {
    /** Lowest supported frequency, in Hz. */
    public static final double MIN_FREQUENCY = 0.1;
    /** Highest supported frequency, in Hz. At 20 TPS this means at most 100 events per tick. */
    public static final double MAX_FREQUENCY = 2000.0;
    /** Hard cap of events queued per tick, to protect the server. */
    private static final long MAX_EVENTS_PER_TICK = 100;

    public static final String EVENT = "hse_tick";

    private final AttachedComputerSet computers = new AttachedComputerSet();
    private double frequency = 1.0;
    private boolean on = false;
    /** Game time of the last tick processed, or -1 when unknown. */
    private long lastTick = -1;
    /** Fractional events accumulated since the last whole event. */
    private double accumulator = 0.0;

    public CcHseBlockEntity(BlockPos pos, BlockState state) {
        super(CcHseMod.CC_HSE_BLOCK_ENTITY.get(), pos, state);
    }

    public CcHsePeripheral createPeripheral() {
        return new CcHsePeripheral(this);
    }

    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, CcHseBlockEntity be) {
        if (level.isClientSide || !be.on || !be.computers.hasComputers()) {
            return;
        }

        long now = level.getGameTime();
        if (be.lastTick < 0) {
            be.lastTick = now;
            return;
        }

        long elapsed = now - be.lastTick;
        be.lastTick = now;
        if (elapsed <= 0) {
            return;
        }

        // Minecraft runs at 20 ticks per second, so 1 tick == 1/20 s.
        be.accumulator += elapsed * be.frequency / 20.0;
        long events = (long) be.accumulator;
        if (events <= 0) {
            return;
        }
        be.accumulator -= events;
        if (events > MAX_EVENTS_PER_TICK) {
            // The computer cannot keep up; drop the excess rather than lagging the server.
            events = MAX_EVENTS_PER_TICK;
        }

        for (long i = 0; i < events; i++) {
            be.computers.queueEvent(EVENT);
        }
    }

    public void setFrequency(double frequency) throws LuaException {
        if (!Double.isFinite(frequency) || frequency < MIN_FREQUENCY || frequency > MAX_FREQUENCY) {
            throw new LuaException("frequency must be between %s and %s Hz".formatted(MIN_FREQUENCY, MAX_FREQUENCY));
        }
        this.frequency = frequency;
        setChanged();
    }

    public double getFrequency() {
        return frequency;
    }

    public void setOn(boolean on) {
        if (this.on != on) {
            this.on = on;
            // Restart timing when switched on, so we don't fire a burst of accumulated events.
            this.lastTick = -1;
            this.accumulator = 0.0;
            setChanged();
        }
    }

    public boolean getOn() {
        return on;
    }

    public boolean toggle() {
        setOn(!on);
        return on;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("frequency", frequency);
        tag.putBoolean("on", on);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        frequency = tag.contains("frequency")
            ? Math.clamp(tag.getDouble("frequency"), MIN_FREQUENCY, MAX_FREQUENCY)
            : 1.0;
        on = tag.getBoolean("on");
        lastTick = -1;
        accumulator = 0.0;
    }
}
