package com.example.cc_hse.block;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import dan200.computercraft.api.peripheral.IComputerAccess;
import com.example.cc_hse.CcHseMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class CcHseBlockEntity extends BlockEntity {
    /** Lowest supported frequency, in Hz. */
    public static final double MIN_FREQUENCY = 0.1;
    /** Highest supported frequency, in Hz. */
    public static final double MAX_FREQUENCY = 2000.0;

    /** 时钟产生的事件名。 */
    public static final String EVENT = "hse_tick";

    /**
     * 内部事件名，仅用于唤醒阻塞在 {@code waitNextTick} 中的电脑。
     *
     * <p>该事件只发给正在等待的电脑，因此不会在电脑的事件队列中堆积。</p>
     */
    public static final String WAIT_EVENT = "hse_tick_wait";

    /**
     * 超过这段时间没有游戏刻，就认为世界已暂停或区块已卸载，时钟随之暂停。
     *
     * <p>这样单人游戏暂停、区块卸载时不会继续产生事件，也不会让电脑在游戏暂停时继续运行。</p>
     */
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** 每个时钟线程的栈大小，避免方块很多时占用过多内存。 */
    private static final long TIMER_STACK_SIZE = 256 * 1024;

    private final AttachedComputerSet computers = new AttachedComputerSet();
    private volatile double frequency = 1.0;
    private volatile boolean on = false;
    private volatile boolean pushEvents = true;
    /** 方块实体是否已被移除（移除后必须停表）。 */
    private volatile boolean removed = false;

    /**
     * 自时钟开始计时以来产生的 HSE tick 数（每个 tick 加一）。
     *
     * <p>由时钟线程递增，被电脑线程读取，因此声明为 {@code volatile}。</p>
     */
    private volatile long hseTickCounter = 0;

    /** 最近一次游戏刻的时间戳，用来判断世界是否还在正常运行。 */
    private volatile long lastGameTickNanos = System.nanoTime();

    /**
     * 当前运行中的时钟线程；未运行时为 {@code null}。
     *
     * <p>每个时钟有**自己的**线程：时钟之间互不干扰，也不会因为共享线程被占用而抖动。
     * 线程只在时钟真正运行（已打开且有电脑连接）时存在，停止时即退出。</p>
     */
    private Thread timerThread;
    /** 时钟线程代号：每次启动/停止都递增，旧线程发现代号不符就退出。 */
    private final AtomicLong timerGeneration = new AtomicLong();

    /**
     * 每台电脑上一次调用 {@code waitNextTick} 时的 {@link #hseTickCounter} 快照。
     * 键为电脑 ID，只由该电脑自己的线程读写。
     */
    private final Map<Integer, Long> waitNextTickSnapshots = new ConcurrentHashMap<>();
    /** 正在 {@code waitNextTick} 中等待的调用，键为唤醒事件的唯一编号。 */
    private final Map<Long, Waiter> waiters = new ConcurrentHashMap<>();
    /** 下一次等待所用的唯一编号，用于丢弃过期的唤醒事件。 */
    private final AtomicLong nextWaitToken = new AtomicLong();
    /** 用于给每个方块实体分配一个全局唯一的编号。 */
    private static final AtomicLong NEXT_ENTITY_ID = new AtomicLong();
    /**
     * 本方块实体的唯一负编号，在 {@link #detach(IComputerAccess)} 时作为唤醒事件发出，
     * 让等待中的程序收到错误而不是永久挂起。
     */
    private final long detachToken = -NEXT_ENTITY_ID.incrementAndGet();

    /** 一次 {@code waitNextTick} 等待的登记信息。 */
    private record Waiter(int computerId, long token, long registeredAtHseTick, long missedHseTicks) {
    }

    public CcHseBlockEntity(BlockPos pos, BlockState state) {
        super(CcHseMod.CC_HSE_BLOCK_ENTITY.get(), pos, state);
    }

    public CcHsePeripheral createPeripheral() {
        return new CcHsePeripheral(this);
    }

    /** @return 本方块实体用于通知"外设已断开"的负编号 */
    public long detachToken() {
        return detachToken;
    }

    public void attach(IComputerAccess computer) {
        computers.add(computer);
        updateTimer();
    }

    public void detach(IComputerAccess computer) {
        int id = computer.getID();
        // 外设被移除（方块被破坏或电脑断开）：唤醒所有等待中的调用，
        // 让它们抛出错误而不是永久挂起。
        for (var iterator = waiters.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (entry.getValue().computerId() != id) continue;
            iterator.remove();
            computer.queueEvent(WAIT_EVENT, detachToken, 0L);
        }
        computers.remove(computer);
        waitNextTickSnapshots.remove(id);
        updateTimer();
    }

    /**
     * 由方块实体 ticker 每个游戏刻调用一次：记录世界仍在运行，并核对时钟线程状态。
     *
     * <p>时钟本身不依赖游戏刻（它由自己的线程驱动），这里只是用来兜底启动/停止，
     * 以及在暂停、卸载后恢复。</p>
     */
    public static void tick(Level level, BlockPos pos, BlockState state, CcHseBlockEntity be) {
        if (level.isClientSide) return;
        be.lastGameTickNanos = System.nanoTime();
        be.updateTimer();
    }

    /** @return 当前频率下两个 HSE tick 之间的间隔（纳秒） */
    private long periodNanos() {
        return (long) (1_000_000_000.0 / frequency);
    }

    /** 根据开关和连接状态启动或停止时钟线程。 */
    private synchronized void updateTimer() {
        boolean shouldRun = on && !removed && computers.hasComputers();
        if (shouldRun == (timerThread != null)) return;

        if (shouldRun) {
            startTimerThread();
        } else {
            stopTimerThread();
        }
    }

    /** 频率变化后按新周期重启时钟线程。 */
    private synchronized void restartTimer() {
        if (timerThread == null) return;
        stopTimerThread();
        startTimerThread();
    }

    /** 停止时钟线程（例如世界暂停、区块卸载、方块被移除）。 */
    private synchronized void stopTimer() {
        if (timerThread == null) return;
        stopTimerThread();
    }

    private void startTimerThread() {
        long generation = timerGeneration.incrementAndGet();
        var thread = new Thread(null, () -> timerLoop(generation), "cc-hse-timer", TIMER_STACK_SIZE);
        thread.setDaemon(true);
        timerThread = thread;
        thread.start();
    }

    private void stopTimerThread() {
        var thread = timerThread;
        timerThread = null;
        timerGeneration.incrementAndGet();
        if (thread != null) thread.interrupt();
    }

    /**
     * 时钟线程的主循环：按绝对时间表产生 tick，与游戏刻无关。
     *
     * @param generation 线程代号，停止/重启后旧线程直接退出
     */
    private void timerLoop(long generation) {
        long period = periodNanos();
        long next = System.nanoTime() + period;

        while (generation == timerGeneration.get()) {
            long now = System.nanoTime();
            if (next > now) {
                // 睡到下一拍；parkNanos 可能提前返回，所以用循环重新对齐。
                LockSupport.parkNanos(next - now);
                continue;
            }

            if (!isWorldRunning(now)) {
                // 世界暂停或区块已卸载：停表，等游戏刻恢复后由 updateTimer() 重新启动。
                stopTimer();
                return;
            }

            try {
                emitTick();
            } catch (Throwable t) {
                // 时钟线程上不能抛异常，否则这个时钟会静默停摆。
                CcHseMod.LOGGER.error("HSE clock tick failed", t);
            }

            // 按绝对时间表推进：正常情况下间隔就是 period；如果被延迟了超过一个周期，
            // 就跳过落后的那些拍（而不是补发一串突发事件）。
            next += period;
            if (next <= now) next = now + period;
            period = periodNanos(); // 频率可能在运行中被修改
        }
    }

    /** @return 世界是否还在正常运行（未暂停、未冻结，且最近有游戏刻） */
    private boolean isWorldRunning(long now) {
        if (now - lastGameTickNanos > STALL_NANOS) return false;

        Level level = getLevel();
        // 没有 level 时以游戏刻为准（例如方块实体正在加载/卸载，此时也不会有电脑连接）。
        if (level == null) return true;
        MinecraftServer server = level.getServer();
        return server == null || (!server.isPaused() && !server.tickRateManager().isFrozen());
    }

    /** 产生一个 HSE tick：计数、推送事件（可选）、唤醒等待者。 */
    private void emitTick() {
        hseTickCounter++;
        if (pushEvents) {
            computers.queueEvent(EVENT);
        }
        wakeWaiters();
    }

    public void setFrequency(double frequency) throws LuaException {
        if (!Double.isFinite(frequency) || frequency < MIN_FREQUENCY || frequency > MAX_FREQUENCY) {
            throw new LuaException("frequency must be between %s and %s Hz".formatted(MIN_FREQUENCY, MAX_FREQUENCY));
        }
        this.frequency = frequency;
        setChanged();
        restartTimer();
    }

    public double getFrequency() {
        return frequency;
    }

    public void setOn(boolean on) {
        if (this.on != on) {
            this.on = on;
            setChanged();
            updateTimer();
        }
    }

    public boolean getOn() {
        return on;
    }

    public boolean toggle() {
        setOn(!on);
        return on;
    }

    public void setPushEvents(boolean pushEvents) {
        if (this.pushEvents != pushEvents) {
            this.pushEvents = pushEvents;
            setChanged();
        }
    }

    public boolean getPushEvents() {
        return pushEvents;
    }

    /**
     * 登记一次 {@code waitNextTick} 调用，返回本次等待的唯一编号。
     *
     * <p>本方法由电脑线程调用，因此只操作线程安全的字段。返回的编号会随唤醒事件一起发回，
     * 用于识别属于本次调用的事件，并丢弃上一次调用残留的过期事件。</p>
     *
     * @param computer 发起调用的电脑
     * @return 本次等待的唯一编号
     */
    public long beginWaitNextTick(IComputerAccess computer) {
        int id = computer.getID();
        long now = hseTickCounter;

        // 返回值是"两次调用之间经过的 HSE tick 数"：跟得上时钟时每次为 1，
        // 首次调用为 0。
        Long previous = waitNextTickSnapshots.get(id);
        long missed = previous == null ? 0 : Math.max(0, now - previous);

        long token = nextWaitToken.incrementAndGet();
        waiters.put(token, new Waiter(id, token, now, missed));
        waitNextTickSnapshots.put(id, now);
        return token;
    }

    /**
     * 给所有正在 {@code waitNextTick} 中等待的调用发送唤醒事件。
     *
     * <p>只在时钟线程调用。每次等待只投递一次：事件被电脑消费后就结束了。
     * 如果这次投递恰好被电脑的事件队列上限（256）丢弃，这次等待会一直挂起，
     * 需要程序自己终止（Ctrl+T）。</p>
     */
    private void wakeWaiters() {
        if (waiters.isEmpty()) return;

        long tick = hseTickCounter;
        computers.forEach(computer -> {
            int id = computer.getID();
            for (var entry : waiters.entrySet()) {
                Waiter waiter = entry.getValue();
                // 只有在本轮 HSE tick 之前登记的等待才唤醒，保证至少等待到一个新的 HSE tick。
                if (waiter.computerId() != id || waiter.registeredAtHseTick() >= tick) continue;

                if (waiters.remove(entry.getKey(), waiter)) {
                    computer.queueEvent(WAIT_EVENT, waiter.token(), waiter.missedHseTicks());
                }
            }
        });
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        removed = true;
        stopTimer();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        removed = false;
        updateTimer();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("frequency", frequency);
        tag.putBoolean("on", on);
        tag.putBoolean("pushEvents", pushEvents);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        frequency = tag.contains("frequency")
            ? Math.clamp(tag.getDouble("frequency"), MIN_FREQUENCY, MAX_FREQUENCY)
            : 1.0;
        on = tag.getBoolean("on");
        pushEvents = !tag.contains("pushEvents") || tag.getBoolean("pushEvents");
        hseTickCounter = 0;
        lastGameTickNanos = System.nanoTime();
        waitNextTickSnapshots.clear();
        waiters.clear();
        updateTimer();
    }
}
