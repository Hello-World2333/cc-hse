package com.example.cc_hse.block;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import com.example.cc_hse.CcHseMod;

/**
 * The CC: Tweaked peripheral exposed by a {@link CcHseBlockEntity}.
 */
public final class CcHsePeripheral implements IPeripheral {
    private final CcHseBlockEntity blockEntity;

    public CcHsePeripheral(CcHseBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public String getType() {
        return CcHseMod.MOD_ID;
    }

    @LuaFunction(mainThread = true)
    public void setFrequency(double frequency) throws LuaException {
        blockEntity.setFrequency(frequency);
    }

    @LuaFunction(mainThread = true)
    public double getFrequency() {
        return blockEntity.getFrequency();
    }

    @LuaFunction(mainThread = true)
    public void setOn(boolean on) {
        blockEntity.setOn(on);
    }

    @LuaFunction(mainThread = true)
    public boolean getOn() {
        return blockEntity.getOn();
    }

    @LuaFunction(mainThread = true)
    public boolean toggle() {
        return blockEntity.toggle();
    }

    @Override
    public void attach(IComputerAccess computer) {
        blockEntity.attach(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        blockEntity.detach(computer);
    }

    @Override
    public Object getTarget() {
        return blockEntity;
    }

    @Override
    public boolean equals(IPeripheral other) {
        return this == other || other instanceof CcHsePeripheral peripheral && peripheral.blockEntity == blockEntity;
    }
}
