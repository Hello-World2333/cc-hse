package com.example.cc_hse.block;

import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import com.example.cc_hse.CcHseMod;


/**
 * HSE 时钟外设，通过 CC: Tweaked 暴露给 Lua 程序。
 *
 * <p>时钟是一个**独立于游戏刻的均匀信号源**：它由专门的定时器线程按 {@code 1 / 频率}
 * 的间隔产生 tick，而不是在每个游戏刻里成批推送，因此频率可以高于（也可以低于）
 * 每秒 20 个游戏刻。</p>
 *
 * <p>该外设提供以下功能：</p>
 * <ul>
 *     <li>控制时钟的开关状态和频率</li>
 *     <li>控制是否自动推送 {@code hse_tick} 系统事件</li>
 *     <li>提供 {@link #waitNextTick(IComputerAccess)} 方法等待下一个 tick</li>
 * </ul>
 *
 * @see CcHseBlockEntity
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

    /**
     * 设置时钟的频率。
     *
     * <p>频率就是时钟每秒产生的 tick 数，也是 {@code hse_tick} 事件的实际间隔倒数：
     * 1.0 Hz 表示每 1 秒一拍，20 Hz 表示每 50 毫秒一拍，100 Hz 表示每 10 毫秒一拍。
     * 时钟由独立定时器线程驱动，因此不受游戏刻（每秒 20 次）的限制。</p>
     *
     * <p>受操作系统/JVM 定时器精度限制，频率越高，单拍间隔的抖动越明显：
     * 100 Hz 以下非常均匀；1000 Hz 以上平均频率仍然接近设定值，但个别间隔可能明显偏大。
     * 修改频率会立即按新周期重新排程。</p>
     *
     * @param frequency 频率值，单位为 Hz（赫兹），范围为 0.1 ~ 2000.0
     * @throws LuaException 如果频率值不合法（超出范围或不是有限数）
     */
    @LuaFunction(mainThread = true)
    public void setFrequency(double frequency) throws LuaException {
        blockEntity.setFrequency(frequency);
    }

    /**
     * 获取当前时钟的频率。
     *
     * @return 当前频率值，单位为 Hz（赫兹）
     */
    @LuaFunction(mainThread = true)
    public double getFrequency() {
        return blockEntity.getFrequency();
    }

    /**
     * 设置时钟的开关状态。
     *
     * <p>当开关打开（{@code true}）时，时钟开始按设定频率产生 tick：推送 {@code hse_tick}
     * 事件、并唤醒正在 {@code waitNextTick()} 中等待的程序。当开关关闭（{@code false}）时，
     * 时钟停止，tick 计数也停止增长。</p>
     *
     * <p>时钟只在有电脑连接时才会真正运行（没有消费者时不会空转）。
     * 另外，世界暂停（单人游戏按 Esc、{@code /tick freeze}）或方块所在区块卸载时，
     * 时钟会跟着暂停，恢复后继续。</p>
     *
     * <p>注意：开关状态会持久化保存，重启服务器后依然生效。</p>
     *
     * @param on {@code true} 打开时钟，{@code false} 关闭时钟
     */
    @LuaFunction(mainThread = true)
    public void setOn(boolean on) {
        blockEntity.setOn(on);
    }

    /**
     * 获取时钟的当前开关状态。
     *
     * @return {@code true} 表示时钟已打开，{@code false} 表示时钟已关闭
     */
    @LuaFunction(mainThread = true)
    public boolean getOn() {
        return blockEntity.getOn();
    }

    /**
     * 切换时钟的开关状态。
     *
     * <p>等同于调用 {@code setOn(not getOn())}。</p>
     *
     * @return 切换后的新状态，{@code true} 表示已打开，{@code false} 表示已关闭
     */
    @LuaFunction(mainThread = true)
    public boolean toggle() {
        return blockEntity.toggle();
    }

    /**
     * 设置是否自动推送系统事件。
     *
     * <p>当此选项打开（{@code true}，默认值）时，时钟会按照设定的频率自动向所有连接的电脑
     * 推送 {@code hse_tick} 系统事件。当此选项关闭（{@code false}）时，时钟不再自动推送事件。</p>
     *
     * <p>此设置不影响 {@link #waitNextTick(IComputerAccess)} 的工作。即使关闭了事件推送，
     * {@code waitNextTick()} 仍然可以正常等待下一个 tick。</p>
     *
     * @param pushEvents {@code true} 开启自动推送，{@code false} 关闭自动推送
     */
    @LuaFunction(mainThread = true)
    public void setPushEvents(boolean pushEvents) {
        blockEntity.setPushEvents(pushEvents);
    }

    /**
     * 获取当前是否启用了自动推送系统事件。
     *
     * @return {@code true} 表示自动推送已开启，{@code false} 表示已关闭
     */
    @LuaFunction(mainThread = true)
    public boolean getPushEvents() {
        return blockEntity.getPushEvents();
    }

    /**
     * 等待时钟的下一个 HSE tick（{@code hse_tick}），并返回两次调用之间经过的 HSE tick 数。
     *
     * <p>调用后当前协程会被挂起，直到时钟产生新的 HSE tick；此时返回自上一次调用
     * {@code waitNextTick()} 到本次调用之间经过的 **HSE tick 数**（即这段时间内时钟走了几拍，
     * 不是游戏刻数）。时钟由独立定时器线程按 {@link #setFrequency(double) 频率} 产生 tick，
     * 所以跟得上时钟时每次调用返回 {@code 1}（100 Hz 就是每 10 毫秒一次）；
     * 如果程序处理得慢，返回的值会更大，差值就是错过的拍数。</p>
     *
     * <p><b>特点：</b></p>
     * <ul>
     *     <li>没有队列：方块不会为 tick 建缓冲区，只有调用正在等待时才发送一个唤醒事件，
     *         因此不会像 {@code hse_tick} 那样堆积、也不会读到积压的旧 tick</li>
     *     <li>唤醒事件只投递一次：电脑的事件队列有上限（CC: Tweaked 为 256），队列满时
     *         {@code queueEvent} 会静默丢弃事件；若这次唤醒恰好被丢弃，本次调用会一直挂起，
     *         需要用 Ctrl+T 终止程序</li>
     *     <li>与 {@link #setPushEvents(boolean)} 无关：即使关闭了系统事件推送，本方法依然可用</li>
     *     <li>需要时钟处于开启状态：{@link #setOn(boolean)} 为 {@code false} 时没有 HSE tick，
     *         本方法会一直等待，直到时钟重新开启并产生下一个 HSE tick（世界暂停或区块卸载时
     *         时钟同样会暂停）</li>
     *     <li>首次调用返回 {@code 0}，因为此时还没有"上一次调用"</li>
     *     <li>唤醒延迟约为一拍（{@code 1 / frequency} 秒），不受游戏刻限制</li>
     *     <li>等待期间的其他事件：在顶层（主协程）调用时，和 {@code sleep()} 一样，
     *         不匹配的事件会被丢弃；在 {@code parallel} 的子协程里调用时，事件由 parallel
     *         统一取出并按过滤条件广播给所有匹配的子协程，因此其它子协程（例如监听 key 的）
     *         不会漏掉事件。终止事件（terminate）在两种情况下都能中断等待</li>
     * </ul>
     *
     * <p><b>用法示例（Lua）：</b></p>
     * <pre>{@code
     * local hse = peripheral.find("cc_hse")
     * hse.setFrequency(100) -- 每秒 100 拍
     * hse.setOn(true)
     * while true do
     *     local ticks = hse.waitNextTick() -- 首次为 0，之后通常为 1
     *     if ticks > 1 then
     *         print("跟不上时钟，错过了 " .. (ticks - 1) .. " 拍")
     *     end
     * end
     * }</pre>
     *
     * @param computer 调用本方法的电脑，由 CC: Tweaked 自动传入
     * @return 自上一次调用 {@code waitNextTick()} 以来经过的 HSE tick 数（非负整数，首次为 0）
     * @throws LuaException 如果等待期间该外设被移除
     */
    @LuaFunction
    public MethodResult waitNextTick(IComputerAccess computer) throws LuaException {
        long token = blockEntity.beginWaitNextTick(computer);
        return MethodResult.pullEvent(
            CcHseBlockEntity.WAIT_EVENT,
            new WakeUpCallback(blockEntity.detachToken(), token));
    }

    /**
     * {@link #waitNextTick(IComputerAccess)} 的恢复回调。
     *
     * <p>注意参数布局与 CC 的约定一致：{@code args[0]} 是事件名，
     * 事件参数从 {@code args[1]} 开始（参考 CC 的 {@code TaskCallback}）。</p>
     *
     * <p>唤醒事件带有一个唯一编号：编号与本外设的"断开编号"相同时说明外设已被移除；
     * 编号与本次等待的编号相同时正常返回；其余情况是上一次调用残留的过期事件，
     * 继续等待下一个 tick。</p>
     *
     * @param detachToken 本外设的"断开编号"，收到它表示外设已被移除
     * @param token       本次等待的编号
     */
    private record WakeUpCallback(long detachToken, long token) implements ILuaCallback {
        @Override
        public MethodResult resume(Object[] args) throws LuaException {
            // args = [事件名, 本次等待的编号, 经过的 tick 数]
            if (args.length >= 2 && args[1] instanceof Number received) {
                long value = received.longValue();
                if (value == detachToken) {
                    throw new LuaException("The cc_hse peripheral was detached while waiting for a tick");
                }
                if (value == token) {
                    long missed = args.length >= 3 && args[2] instanceof Number count ? count.longValue() : 0;
                    return MethodResult.of(missed);
                }
            }
            return MethodResult.pullEvent(CcHseBlockEntity.WAIT_EVENT, this);
        }
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
