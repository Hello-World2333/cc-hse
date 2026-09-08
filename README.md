# CC: HSE

为 [CC: Tweaked](https://tweaked.cc/) 提供**独立于游戏刻的均匀时钟信号**。

`cc_hse` 方块是一个“HSE 时钟”外设：**每个运行中的时钟都有自己的定时器线程**，按可配置的
频率（`0.1 ~ 2000 Hz`）逐个产生 tick，而不是在每个游戏刻里成批推送，因此节拍均匀且可以远高于
Minecraft 的 20 Hz 游戏刻速率。程序既可以监听 `hse_tick` 事件，也可以调用**没有队列**的
`waitNextTick()` 与时钟同步，并知道自己在两次调用之间经过（错过）了多少拍。

## 功能特性

- **均匀时钟**：每个时钟一个独立线程、按绝对时间排程，与游戏刻无关。实测 100 Hz 平均
  100.0 Hz；频率越高抖动越明显（1000 Hz 实测约 969 Hz，见[文档](docs/api.zh_cn.md)）。
- **可调频率**：`0.1 ~ 2000.0` Hz，可以高于也可以低于 20 Hz；只在有电脑连接时才运行。
- **开关控制**：`setOn` / `getOn` / `toggle`，开关状态随方块保存。
- **系统事件推送开关**：`setPushEvents` / `getPushEvents`，可以关闭自动推送，
  只使用 `waitNextTick()` 按需等待 tick。
- **无队列等待**：`waitNextTick()` 等待下一个 HSE tick，并返回两次调用之间经过的 HSE tick 数，
  不会在事件队列中堆积旧 tick；即使关闭了事件推送也依然可用。
- **完整的中文文档**：[`docs/api.zh_cn.md`](docs/api.zh_cn.md)。

## 环境要求

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| CC: Tweaked | 1.120.0 及以上 |
| Java | 21 |

## 合成

在工作台中按下图摆放（S = 石头，R = 红石，C = 时钟）：

```
S S S
S R S
S C S
```

## 快速上手

把 HSE 时钟放在电脑旁边（或通过有线调制解调器连接），然后：

```lua
local hse = peripheral.find("cc_hse")
if not hse then error("没有找到 HSE 时钟", 0) end

hse.setFrequency(100) -- 每秒 100 次
hse.setOn(true)

while true do
    os.pullEvent("hse_tick")
    -- 每 10 毫秒左右执行一次（由定时器线程驱动，不受游戏刻限制）
end
```

不使用事件、只按需等待 tick：

```lua
local hse = peripheral.find("cc_hse")
hse.setFrequency(100) -- 每秒 100 拍
hse.setOn(true)
hse.setPushEvents(false) -- 不需要事件流

while true do
    local ticks = hse.waitNextTick() -- 首次为 0，之后通常为 1
    if ticks > 1 then
        print("跟不上时钟，错过了 " .. (ticks - 1) .. " 拍")
    end
end
```

## API 概览

| 方法 | 返回值 | 说明 |
| --- | --- | --- |
| `setFrequency(frequency)` | 无 | 设置频率（Hz），范围 `0.1 ~ 2000.0` |
| `getFrequency()` | `number` | 获取当前频率（Hz） |
| `setOn(on)` | 无 | 打开/关闭时钟 |
| `getOn()` | `boolean` | 获取开关状态 |
| `toggle()` | `boolean` | 切换开关状态 |
| `setPushEvents(pushEvents)` | 无 | 设置是否自动推送 `hse_tick` 事件 |
| `getPushEvents()` | `boolean` | 获取是否自动推送 `hse_tick` 事件 |
| `waitNextTick()` | `number` | 等待下一个 HSE tick，返回两次调用之间经过的 HSE tick 数 |

事件：`hse_tick`（无参数）。

完整的参数说明、错误信息、注意事项和更多示例请见
[**docs/api.zh_cn.md**](docs/api.zh_cn.md)。

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/cc-hse-<版本>.jar`。推送形如 `v1.2.3` 的标签时，
GitHub Actions 会自动构建并发布 Release。

## 目录结构

```
src/main/java/com/example/cc_hse/
├── CcHseMod.java                 模组入口、方块与方块实体注册
└── block/
    ├── CcHseBlock.java           方块定义
    ├── CcHseBlockEntity.java     时钟逻辑（频率、tick 计数、等待唤醒）
    └── CcHsePeripheral.java      Lua 外设 API
docs/api.zh_cn.md                 中文 API 文档
```

## 许可证

请参阅仓库根目录下的 [LICENSE](LICENSE) 文件。
