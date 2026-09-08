# CC: HSE 外设 API 参考

本文档描述 `cc_hse` 方块通过 [CC: Tweaked](https://tweaked.cc/) 暴露给 Lua 程序的外设 API。

- **外设类型名**：`cc_hse`
- **获取方式**：`peripheral.find("cc_hse")`
- **相关事件**：`hse_tick`

> 文档中的“tick”指 Minecraft 的游戏刻（每秒 20 刻）。

## 目录

1. [快速上手](#1-快速上手)
2. [方法一览](#2-方法一览)
3. [时钟开关与频率](#3-时钟开关与频率)
4. [系统事件推送](#4-系统事件推送)
5. [等待下一个 HSE tick：waitNextTick](#5-等待下一个-hse-tickwaitnexttick)
6. [事件：hse_tick](#6-事件hse_tick)
7. [完整示例](#7-完整示例)
8. [注意事项与常见问题](#8-注意事项与常见问题)

## 1. 快速上手

```lua
local hse = peripheral.find("cc_hse")
if not hse then error("没有找到 HSE 时钟", 0) end

hse.setOn(true)      -- 打开时钟
hse.setFrequency(20) -- 每秒推送 20 次 hse_tick

while true do
    local event = os.pullEvent("hse_tick")
    -- 每次收到事件都会执行一次
end
```

如果不想使用事件，也可以直接等待时钟的 tick：

```lua
local hse = peripheral.find("cc_hse")
hse.setOn(true)

while true do
    local ticks = hse.waitNextTick() -- 首次为 0，之后通常为 1
    print("经过的 HSE tick 数：" .. ticks)
end
```

## 2. 方法一览

| 方法 | 返回值 | 说明 |
| --- | --- | --- |
| `setFrequency(frequency)` | 无 | 设置时钟频率（Hz），范围 `0.1 ~ 2000.0` |
| `getFrequency()` | `number` | 获取当前频率（Hz） |
| `setOn(on)` | 无 | 打开/关闭时钟 |
| `getOn()` | `boolean` | 获取时钟开关状态 |
| `toggle()` | `boolean` | 切换开关状态，返回切换后的状态 |
| `setPushEvents(pushEvents)` | 无 | 设置是否自动推送 `hse_tick` 系统事件 |
| `getPushEvents()` | `boolean` | 获取是否自动推送 `hse_tick` 系统事件 |
| `waitNextTick()` | `number` | 等待下一个 HSE tick，返回两次调用之间经过的 HSE tick 数 |

## 3. 时钟开关与频率

### `setFrequency(frequency)`

设置时钟频率，单位为 Hz（赫兹）。频率就是时钟每秒产生的 tick 数，也就是 `hse_tick` 事件的实际间隔倒数：

- `1.0` Hz：每 1 秒一拍；
- `20.0` Hz：每 50 毫秒一拍（与 Minecraft 的游戏刻速率相同）；
- `100.0` Hz：每 10 毫秒一拍；
- `2000.0` Hz：每 0.5 毫秒一拍（上限）。

**时钟由独立定时器线程驱动，与游戏刻无关**——这正是本模组的意义所在：服务器主线程每秒只跑
20 个游戏刻，在游戏刻里推事件只能得到“每刻一批”的突发信号；本模组为**每个运行中的时钟**
开一个独立线程，按绝对时间表逐个产生 tick，因此频率可以高于（也可以低于）20 Hz，且节拍均匀。

**精度**：受操作系统/JVM 定时器精度限制（通常是 0.1 ~ 1 毫秒），频率越高，单拍间隔的抖动越明显。
空闲 JVM 上实测：

| 设定频率 | 实测平均频率 | 相邻间隔范围 |
| --- | --- | --- |
| 20 Hz | 20.0 Hz | 46 ~ 54 ms |
| 100 Hz | 100.0 Hz | 4 ~ 16 ms |
| 500 Hz | 488 Hz | 0.1 ~ 7.5 ms |
| 1000 Hz | 969 Hz | 0.01 ~ 4.8 ms |
| 2000 Hz | 1882 Hz | 0.02 ~ 5.3 ms |

也就是说：**平均频率基本准确**，但 100 Hz 以上个别间隔会明显偏大（服务器负载高、GC 时更明显）；
1000 Hz 以上周期本身已经接近操作系统定时器精度，抖动不可避免。**对等间隔要求严格时建议 ≤ 100 Hz**。

修改频率会立即按新周期重新排程。

**参数**

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `frequency` | `number` | 频率，单位 Hz，必须在 `0.1 ~ 2000.0` 之间 |

**返回值**：无。

**错误**：当参数不是有限数或超出 `0.1 ~ 2000.0` 范围时，抛出 Lua 错误：

```text
frequency must be between 0.1 and 2000.0 Hz
```

```lua
hse.setFrequency(100)  -- 每秒 100 次 hse_tick
```

### `getFrequency()`

返回当前频率，单位为 Hz。

```lua
print(hse.getFrequency()) --> 100.0
```

### `setOn(on)`

打开或关闭时钟。

- `on = true`：时钟开始按设定频率产生 tick——推送 `hse_tick`，并唤醒 `waitNextTick()` 中等待的程序；
- `on = false`：时钟停止，tick 计数也不再增长，`waitNextTick()` 会一直等待。

时钟只在**有电脑连接**时才会真正运行（没有消费者时不空转）。另外，**世界暂停**（单人游戏按 Esc、
`/tick freeze`）或方块所在区块**卸载**时，时钟会跟着暂停，恢复后继续。

**参数**

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `on` | `boolean` | `true` 打开，`false` 关闭 |

**返回值**：无。

**持久化**：开关状态会随方块保存，服务器重启后依然生效。

### `getOn()`

返回时钟是否已打开。

```lua
if not hse.getOn() then hse.setOn(true) end
```

### `toggle()`

切换时钟开关状态，等同于 `setOn(not getOn())`。

**返回值**：`boolean`，切换后的新状态（`true` 表示已打开）。

```lua
local nowOn = hse.toggle()
print(nowOn and "时钟已打开" or "时钟已关闭")
```

## 4. 系统事件推送

### `setPushEvents(pushEvents)`

设置是否自动推送系统事件。

- `pushEvents = true`（默认值）：时钟按设定频率自动向所有连接的电脑推送 `hse_tick` 事件；
- `pushEvents = false`：时钟不再自动推送 `hse_tick` 事件，事件队列不会被高频事件填满。

关闭自动推送**不会**影响 `waitNextTick()`：该方法的内部计时与事件推送互相独立，
因此可以“只按需等待 tick，不接收事件流”。

**参数**

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `pushEvents` | `boolean` | `true` 开启自动推送，`false` 关闭自动推送 |

**返回值**：无。

**持久化**：该设置会随方块保存，服务器重启后依然生效；旧存档中不存在该字段时默认为 `true`。

```lua
hse.setPushEvents(false) -- 只使用 waitNextTick()，不接收 hse_tick 事件
```

### `getPushEvents()`

返回当前是否启用自动推送系统事件。

```lua
print(hse.getPushEvents()) --> true 或 false
```

## 5. 等待下一个 HSE tick：`waitNextTick`

### `waitNextTick()`

等待时钟的下一个 **HSE tick**，并返回**两次调用之间经过的 HSE tick 数**。

HSE tick 就是 `hse_tick` 事件所代表的"拍"，由独立定时器线程按 `frequency` 产生，
间隔是 `1 / frequency` 秒。返回值为"自上一次调用 `waitNextTick()` 到本次调用之间经过的
HSE tick 数"（**不是游戏刻数**）：

- 首次调用返回 `0`（此时还没有"上一次调用"）；
- 跟得上时钟时，之后每次返回 `1`（100 Hz 下就是每 10 毫秒返回一次）；
- 如果程序处理得慢，返回值会更大。例如 100 Hz 下返回 `4`，表示两次调用之间经过了 4 拍，
  也就是错过了 3 拍。

唤醒延迟约为一拍（`1 / frequency` 秒），不受游戏刻限制。

**参数**：无。

**返回值**：`number`，经过的 HSE tick 数（非负整数）。

**错误**：

- 等待期间外设被移除（方块被破坏、电脑断开）时：
  `The cc_hse peripheral was detached while waiting for a tick`。

**特点**

- **没有队列**：方块不会为 tick 建缓冲区。只有调用正在等待时才会发送一个唤醒事件，
  等待期间不推进也不累积，因此程序不会读到积压的旧 tick；
- **与事件推送相互独立**：返回的拍数只由时钟自己的定时器决定，不受 `hse_tick` 事件
  是否被丢弃、电脑队列是否已满的影响；
- **唤醒事件被丢弃也不会卡死**：CC: Tweaked 的事件队列有上限（256 个），队列满时
  `queueEvent` 会静默丢弃事件。**唤醒事件只投递一次**：如果这次投递恰好被丢弃，本次调用会
  一直挂起，需要 Ctrl+T 终止程序（所以高频 + 程序跟不上时，建议降低频率或关闭 `pushEvents`）；
- **与 `setPushEvents` 无关**：即使关闭了系统事件推送，本方法依然可用；
- **需要时钟处于开启状态**：`setOn(false)` 时没有 HSE tick，方法会一直等待，直到时钟
  重新开启并产生下一个 HSE tick 为止（频率越低，等待越久：0.1 Hz 时约 10 秒一拍）；
  世界暂停或区块卸载时时钟同样暂停；
- **等待期间的其他事件**：
  - 在**顶层（主协程）**调用时，和 `sleep()` 一样，不匹配的事件会被丢弃；
  - 在 **`parallel` 的子协程**里调用时**不会丢事件**：`parallel` 自己用 `os.pullEventRaw()`
    取出事件，再按每个子协程的过滤条件广播给所有匹配的子协程，所以监听 `key` 之类的
    子协程照常收到事件；
  - `terminate` 在两种情况下都能中断等待。

```lua
local hse = peripheral.find("cc_hse")
hse.setFrequency(100) -- 每秒 100 拍，即每 10 毫秒一拍
hse.setOn(true)

while true do
    local ticks = hse.waitNextTick() -- 首次为 0，之后通常为 1
    if ticks > 1 then
        print("跟不上时钟，错过了 " .. (ticks - 1) .. " 拍")
    end
end
```

## 6. 事件：`hse_tick`

当 `setOn(true)` 且 `setPushEvents(true)` 时，时钟会按设定频率向所有连接的电脑推送
`hse_tick` 事件。该事件**没有参数**，第一个返回值就是事件名本身。

```lua
while true do
    local event = os.pullEvent("hse_tick")
    -- event 的值就是字符串 "hse_tick"
end
```

推送规则：

- 每个 tick 单独推送一个 `hse_tick` 事件，间隔就是 `1 / frequency` 秒（由定时器线程驱动）；
- 只有时钟打开、且至少有一台电脑连接时才会推送；
- 电脑自己的事件队列上限是 256 个，电脑处理不过来时新事件会被 CC: Tweaked 静默丢弃。
  如果程序跟不上高频事件，请降低频率，或改用 `setPushEvents(false)` + `waitNextTick()`；
- `waitNextTick()` 使用一个内部事件（`hse_tick_wait`）来唤醒等待中的程序，该事件会被
  方法自身消费，程序不应依赖它。

## 7. 完整示例

### 7.1 使用事件驱动（推荐用于高频节拍）

```lua
local hse = peripheral.find("cc_hse")
if not hse then error("没有找到 HSE 时钟", 0) end

hse.setFrequency(100) -- 每秒 100 次
hse.setOn(true)

while true do
    os.pullEvent("hse_tick")
    -- 每 10 毫秒左右执行一次（由定时器线程驱动）
end
```

### 7.2 使用 `waitNextTick()` 计算帧间隔

```lua
local hse = peripheral.find("cc_hse")
hse.setFrequency(100)    -- 每秒 100 拍
hse.setOn(true)
hse.setPushEvents(false) -- 不需要事件流

while true do
    local ticks = hse.waitNextTick()
    -- ticks == 1 表示跟上了时钟
    if ticks > 1 then
        print("警告：错过了 " .. (ticks - 1) .. " 拍")
    end
end
```

### 7.3 运行时切换事件推送

```lua
local hse = peripheral.find("cc_hse")
hse.setFrequency(100) -- 每秒 100 拍
hse.setOn(true)

-- 安静模式：不接收事件，只在需要时等待
hse.setPushEvents(false)
for i = 1, 10 do
    local ticks = hse.waitNextTick()
    print("第 " .. i .. " 次等待，经过 " .. ticks .. " 拍")
end

-- 恢复事件推送
hse.setPushEvents(true)
while true do
    os.pullEvent("hse_tick")
end
```

### 7.4 同时处理 tick 和按键（推荐写法）

`waitNextTick()` 在顶层调用时会丢弃等待期间的其他事件；放进 `parallel` 的子协程里就不会——
事件会广播给所有匹配的子协程。

```lua
local hse = peripheral.find("cc_hse")
hse.setFrequency(100)    -- 每秒 100 拍
hse.setOn(true)
hse.setPushEvents(false) -- 不需要事件流，队列不会被 hse_tick 塞满

parallel.waitForAny(
    function() -- 主循环：每拍跑一次
        while true do
            local ticks = hse.waitNextTick()
            if ticks > 1 then
                print("跟不上时钟，错过了 " .. (ticks - 1) .. " 拍")
            end
        end
    end,
    function() -- 事件循环：等待 tick 期间来的按键照常收到
        while true do
            local _, key = os.pullEvent("key")
            if key == keys.q then return end
            print("按下按键 " .. key)
        end
    end
)
```

## 8. 注意事项与常见问题

**设置会保存吗？**

`frequency`、`on`、`pushEvents` 都会写入方块的 NBT 数据，服务器重启后依然生效。
tick 计数器与 `waitNextTick()` 的计时快照不会保存：服务器重启、方块被重新加载或电脑
断开重连后，下一次 `waitNextTick()` 调用会重新返回 `0`。

**为什么 `waitNextTick()` 一直没有返回？**

请检查：

1. 时钟是否已打开（`hse.setOn(true)`）；
2. 方块所在的区块是否被加载（区块卸载时时钟会暂停）；
3. 是否有一台电脑连接在方块旁边（没有消费者时时钟不运行）；
4. 世界是否暂停（单人游戏按 Esc、`/tick freeze` 时时钟暂停）；
5. 频率是否很低——本方法等的是"下一个 HSE tick"，0.1 Hz 时一拍要 10 秒。

如果外设在等待期间被移除，方法会抛出错误而不是一直挂起。

**`waitNextTick()` 会不会因为电脑事件队列满而丢失唤醒？**

不会永久丢失。CC: Tweaked 用 `queueEvent` 投递事件，队列上限为 256 个，满了以后新事件会被
**静默丢弃**。`waitNextTick()` 的唤醒事件也走这条通道（这是 CC 唯一能唤醒协程的机制），
所以确实可能被丢弃一次；为此方块会重复投递同一个唤醒事件（两次之间至少间隔 50 毫秒，
最多 5 次），直到电脑确认收到。因此即使队列之前被 `hse_tick` 塞满，等待也只会稍微晚一点
返回，不会永久卡住。

另外两点值得注意：

- 方块本身不会为 tick 建队列：**只有调用正在等待时**才会产生唤醒事件，所以 `waitNextTick()`
  自己不会造成积压；
- 等待期间事件队列也不会继续堆积：顶层等待时机器会丢弃不匹配的事件，`parallel` 等待时
  事件被取出并广播/分发。真正会积压的只有 `hse_tick` 事件流，不想要它就
  `setPushEvents(false)`。

**`waitNextTick()` 和 `os.pullEvent("hse_tick")` 有什么区别？**

- `os.pullEvent("hse_tick")` 从电脑的事件队列里取出事件：如果电脑处理得慢，事件会堆积，
  程序会依次处理“旧”的 tick；频率越高，队列压力越大。
- `waitNextTick()` 没有队列：它总是等待“下一个” tick，并告诉你两次调用之间经过了多少
  tick（也就是错过了多少），因此更适合需要与实时节拍同步的程序；即使唤醒事件因为队列满
  被丢弃，这次等待就会一直挂起（不会重试）。

两者共享同一个时钟，但**只有 `hse_tick` 会把事件留在队列里**：程序没在 `waitNextTick()`
里等待时，方块不会为它产生任何事件。

**高频事件会不会拖慢服务器？**

每个 tick 只推送一个事件，且只有时钟打开、至少有一台电脑连接时才推送。若电脑处理不过来，
新事件会被 CC 的事件队列上限（256）丢弃，而不是让服务器卡顿。真正的高频信号请用
`waitNextTick()`（它只在有人等待时才产生一个内部事件，每个 tick 最多一次）。

**多个 HSE 时钟会不会互相影响？**

每个**运行中**的时钟都有自己的定时器线程（停止时线程退出），彼此不共享、互不干扰，
所以多个时钟同时运行不会互相拉低精度。代价是每个运行中的时钟占一个线程（栈 256 KB），
大量方块同时高频运行时线程数会比较多；空闲/关闭的时钟不占线程。

**等待期间会丢事件吗？**

要看在哪里调用：

- **顶层调用**（主协程直接 `hse.waitNextTick()`）：会丢。和 `sleep()` 一样，等待期间到达的
  其他事件（`key`、`timer`、`hse_tick`…）不匹配过滤条件，会被 CC 丢弃。需要同时处理输入时，
  请把 `waitNextTick()` 放进 `parallel`（见下面的示例）。
- **在 `parallel` 的子协程里调用**：不会丢。`parallel` 用 `os.pullEventRaw()` 取事件，
  然后按每个子协程的过滤条件把同一个事件**广播**给所有匹配的子协程——不存在多个消费者
  抢同一个事件的情况。所以一个子协程在 `waitNextTick()` 里等待时，另一个子协程
  `os.pullEvent("key")` 依然能收到按键。完整示例见
  [7.4 同时处理 tick 和按键](#74-同时处理-tick-和按键推荐写法)。
